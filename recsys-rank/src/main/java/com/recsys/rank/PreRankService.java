package com.recsys.rank;

import com.recsys.common.feature.FeatureService;
import com.recsys.common.rank.TowerScorer;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 粗排(pre-rank):精排前的轻量打分 + 截断,补齐 <b>召回 → 粗排 → 精排</b> 漏斗。
 *
 * <p><b>动机</b>:多路召回合并去重后候选可达数百甚至上千,若全量喂精排(DeepFM/DIN 深度 ONNX),
 * 成本随候选量线性增长。粗排用一个只吃廉价特征的线性打分把候选砍到 top-K,精排只对这 K 个
 * 跑重模型 —— 用极小的召回损失换精排算力的数量级下降,是规模化的关键一环。
 *
 * <p><b>打分</b> = {@code recallWeight·归一化召回分 + popWeight·item_pop_norm + affinityWeight·user_cat_affinity}。
 * 特征经 {@link FeatureService#itemFeatures(java.util.Collection)} 一次批量读(pipeline),
 * 并复用共享 {@link FeatureAssembler}(与精排/离线同源,零特征穿越)。
 *
 * <p><b>安全</b>:候选数 ≤ limit 时直接放行(无需粗排);任何异常回退全量候选交精排,绝不阻断请求。
 * 与精排的关系类比推荐漏斗:粗排求「快而广地砍」,精排求「准而重地排」。
 */
@Component
@EnableConfigurationProperties(RankProperties.class)
public class PreRankService {

    private static final Logger log = LoggerFactory.getLogger(PreRankService.class);

    private final FeatureService featureService;
    private final ContentService contentService;
    private final ObjectProvider<TowerScorer> towerScorerProvider;   // R6:双塔粗排打分(可选,缺则线性)
    private final RankProperties props;

    private final int idxPop = FeatureAssembler.FEATURE_ORDER.indexOf("item_pop_norm");
    private final int idxAff = FeatureAssembler.FEATURE_ORDER.indexOf("user_cat_affinity");

    public PreRankService(FeatureService featureService, ContentService contentService,
                          ObjectProvider<TowerScorer> towerScorerProvider, RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.towerScorerProvider = towerScorerProvider;
        this.props = props;
    }

    /**
     * 粗排截断:返回按粗排分降序的前 limit 个候选 id。
     * 候选 ≤ limit 或未启用粗排时原样返回(不改变行为)。
     *
     * @param recallScores itemId → 跨路归一化召回分(编排层已算好,直接复用不重算)
     */
    public List<Long> preRank(long userId, List<Long> candidateIds,
                              Map<Long, Double> recallScores, String scene) {
        RankProperties.PreRank cfg = props.getPreRank();
        if (!cfg.isEnabled() || candidateIds == null || candidateIds.size() <= cfg.getLimit()) {
            return candidateIds;   // 无需粗排:候选本就不多,全量进精排
        }
        try {
            Map<String, Double> userFeat = featureService.userFeatures(userId);
            Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(candidateIds);
            Map<Long, Item> items = contentService.findByIds(candidateIds);

            double maxRecall = recallScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            final double denom = maxRecall <= 0 ? 1.0 : maxRecall;

            // R6:双塔学习分(mode=two-tower + 塔就绪时)。归一化后叠加进粗排分;缺向量的候选自然退线性。
            Map<Long, Double> towerScores = towerScores(userId, candidateIds, cfg);
            double towerMax = towerScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            final double towerDenom = towerMax <= 0 ? 1.0 : towerMax;

            List<Scored> scored = new ArrayList<>(candidateIds.size());
            for (Long id : candidateIds) {
                Item it = items.get(id);
                String cat = it == null ? null : it.category();
                double[] f = FeatureAssembler.assemble(userFeat, itemFeats.getOrDefault(id, Map.of()), cat);
                double rNorm = recallScores.getOrDefault(id, 0.0) / denom;
                double s = cfg.getRecallWeight() * rNorm
                        + cfg.getPopWeight() * f[idxPop]
                        + cfg.getAffinityWeight() * f[idxAff];
                Double tw = towerScores.get(id);
                if (tw != null) {
                    s += cfg.getTowerWeight() * (tw / towerDenom);   // 学习型双塔信号(归一化)
                }
                scored.add(new Scored(id, s));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());

            List<Long> out = new ArrayList<>(cfg.getLimit());
            for (int i = 0; i < cfg.getLimit() && i < scored.size(); i++) {
                out.add(scored.get(i).id());
            }
            log.debug("粗排 user={} 候选 {}→{}", userId, candidateIds.size(), out.size());
            return out;
        } catch (Exception e) {
            // 粗排是优化项,失败绝不阻断:退回全量候选交给精排
            log.warn("粗排失败,回退全量候选 user={}: {}", userId, e.getMessage());
            return candidateIds;
        }
    }

    /** 双塔粗排分:仅在 mode=two-tower 且塔就绪时返回,否则空 map(退纯线性)。 */
    private Map<Long, Double> towerScores(long userId, List<Long> candidateIds, RankProperties.PreRank cfg) {
        if (!"two-tower".equalsIgnoreCase(cfg.getMode())) {
            return Map.of();
        }
        TowerScorer scorer = towerScorerProvider.getIfAvailable();
        if (scorer == null || !scorer.isReady()) {
            return Map.of();   // 双塔未就绪 → 退纯线性(优雅降级)
        }
        try {
            return scorer.score(userId, candidateIds);
        } catch (Exception e) {
            log.debug("双塔粗排打分异常,退线性 user={}: {}", userId, e.getMessage());
            return Map.of();
        }
    }

    private record Scored(long id, double score) {
    }
}
