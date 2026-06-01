package com.recsys.rank;

import com.recsys.common.feature.FeatureService;
import com.recsys.common.rank.RankService;
import com.recsys.common.rank.RankedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1 规则排序:score = w_pop * 归一化热度 + w_profile * 画像类目匹配。
 * 特征来自 FeatureService(Redis);为空时分数趋于 0,由上层用召回分兜底。
 *
 * 特征快照随结果返回(RankedItem.featureSnapshot),便于调试与未来训练样本对齐。
 * strategy=onnx 预留:加载 ONNX 模型打分(见 OnnxRankService,本期 TODO)。
 */
@Service
@EnableConfigurationProperties(RankProperties.class)
public class RuleRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(RuleRankService.class);

    private final FeatureService featureService;
    private final RankProperties props;

    public RuleRankService(FeatureService featureService, RankProperties props) {
        this.featureService = featureService;
        this.props = props;
    }

    @Override
    public List<RankedItem> rank(long userId, List<Long> candidateItemIds, String scene) {
        if (candidateItemIds == null || candidateItemIds.isEmpty()) {
            return List.of();
        }
        var w = props.getWeights();
        Map<String, Double> userFeat = featureService.userFeatures(userId);

        List<RankedItem> ranked = new ArrayList<>(candidateItemIds.size());
        for (long itemId : candidateItemIds) {
            Map<String, Double> itemFeat = featureService.itemFeatures(itemId);

            double popularity = itemFeat.getOrDefault("popularity_norm",
                    itemFeat.getOrDefault("popularity", 0.0));
            double profileMatch = profileMatch(userFeat, itemFeat);

            double score = w.getPopularity() * popularity + w.getProfileMatch() * profileMatch;

            Map<String, Double> snapshot = new HashMap<>();
            snapshot.put("popularity", popularity);
            snapshot.put("profileMatch", profileMatch);
            ranked.add(new RankedItem(itemId, score, snapshot));
        }
        ranked.sort(Comparator.comparingDouble(RankedItem::score).reversed());
        return ranked;
    }

    /**
     * 画像匹配:用户对该物品类目的历史点击率特征(交叉特征,最有用)。
     * 约定离线写入 user 特征 "cat_ctr:{category}",item 特征含 "category_id"。
     * 简化:若 item 特征里有 "user_cat_affinity" 直接用。
     */
    private double profileMatch(Map<String, Double> userFeat, Map<String, Double> itemFeat) {
        return itemFeat.getOrDefault("user_cat_affinity", 0.0);
    }
}
