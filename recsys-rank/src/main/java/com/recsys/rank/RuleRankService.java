package com.recsys.rank;

import com.recsys.common.feature.FeatureService;
import com.recsys.common.rank.RankService;
import com.recsys.common.rank.RankedItem;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * v1 规则排序:score = w_pop·item_pop_norm + w_profile·user_cat_affinity。
 *
 * <p>特征经共享 {@link FeatureAssembler} 装配(与 ONNX 路径同一套特征,口径一致),
 * 来自离线 build-features 物化的 Redis feat:*;无数据时分趋于 0,由上层用召回分兜底。
 * 特征快照随结果返回,便于调试与训练样本对齐。
 */
@Service
@EnableConfigurationProperties(RankProperties.class)
public class RuleRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(RuleRankService.class);

    private final FeatureService featureService;
    private final ContentService contentService;
    private final RankProperties props;

    public RuleRankService(FeatureService featureService, ContentService contentService,
                           RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.props = props;
    }

    @Override
    public List<RankedItem> rank(long userId, List<Long> candidateItemIds, String scene) {
        if (candidateItemIds == null || candidateItemIds.isEmpty()) {
            return List.of();
        }
        var w = props.getWeights();
        Map<String, Double> userFeat = featureService.userFeatures(userId);
        Map<Long, Item> items = contentService.findByIds(candidateItemIds);

        // 特征下标(与 FeatureAssembler.FEATURE_ORDER 对齐)
        int idxPop = FeatureAssembler.FEATURE_ORDER.indexOf("item_pop_norm");
        int idxAff = FeatureAssembler.FEATURE_ORDER.indexOf("user_cat_affinity");

        List<RankedItem> ranked = new ArrayList<>(candidateItemIds.size());
        for (long itemId : candidateItemIds) {
            Item it = items.get(itemId);
            String cat = it == null ? null : it.category();
            double[] f = FeatureAssembler.assemble(userFeat, featureService.itemFeatures(itemId), cat);

            double score = w.getPopularity() * f[idxPop] + w.getProfileMatch() * f[idxAff];
            ranked.add(new RankedItem(itemId, score, FeatureAssembler.snapshot(f)));
        }
        ranked.sort(Comparator.comparingDouble(RankedItem::score).reversed());
        return ranked;
    }
}
