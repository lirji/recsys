package com.recsys.rank;

import java.util.List;
import java.util.Map;

/**
 * 排序特征装配器(Track C/D 一致性地基)。
 *
 * <p><b>这是在线/离线特征一致性的唯一来源</b>:在线 {@link OnnxRankService} 打分、
 * 离线 gen-samples 构造训练样本,都必须经此装配,保证「同样的特征名、同样的计算逻辑」,
 * 杜绝特征穿越/不一致(docs/03 §4 推荐系统第一大坑)。
 *
 * <p>纯函数、无状态、无 Spring 依赖:输入已取好的 user/item 特征 Map(来自 Redis feat:*,
 * 由离线 build-features 物化)+ 物品主类目,输出固定顺序的特征向量。
 *
 * <p>特征顺序即 {@link #FEATURE_ORDER},训练侧导出的 feature_order.json 必须与之逐位对齐。
 * 评分归一到约 [0,1] 量纲(评分类除以 5)。缺失值给中性默认,保证新用户/新物品不崩。
 */
public final class FeatureAssembler {

    private FeatureAssembler() {
    }

    /** 特征顺序(与 ONNX 模型输入列严格对齐,改动需同步重训)。 */
    public static final List<String> FEATURE_ORDER = List.of(
            "item_pop_norm",      // 物品热度(log 归一,0~1)
            "item_avg_rating",    // 物品平均分(/5)
            "user_act_norm",      // 用户活跃度(log 归一,0~1)
            "user_avg_rating",    // 用户平均打分(/5,评分偏置)
            "user_cat_affinity"   // 交叉:用户对该物品类目的历史平均分(/5,最有用)
    );

    public static int dim() {
        return FEATURE_ORDER.size();
    }

    private static final double DEFAULT_ITEM_AVG = 3.5;
    private static final double DEFAULT_USER_AVG = 3.5;

    /**
     * 装配单条 (user, item) 特征向量。
     *
     * @param userFeat     用户在线特征(feat:user:{id} 的数值字段,含 catavg:&lt;genre&gt;)
     * @param itemFeat     物品在线特征(feat:item:{id})
     * @param itemCategory 物品主类目(取交叉特征用;为空则交叉回退到用户均分)
     */
    public static double[] assemble(Map<String, Double> userFeat,
                                    Map<String, Double> itemFeat,
                                    String itemCategory) {
        Map<String, Double> u = userFeat == null ? Map.of() : userFeat;
        Map<String, Double> it = itemFeat == null ? Map.of() : itemFeat;

        double itemPop = it.getOrDefault("item_pop_norm", 0.0);
        double itemAvg = it.getOrDefault("item_avg_rating", DEFAULT_ITEM_AVG) / 5.0;
        double userAct = u.getOrDefault("user_act_norm", 0.0);
        double userAvgRaw = u.getOrDefault("user_avg_rating", DEFAULT_USER_AVG);
        double userAvg = userAvgRaw / 5.0;

        // 交叉特征:用户对该类目的历史均分;无该类目记录则回退到用户整体均分
        double catAff = userAvgRaw;
        if (itemCategory != null) {
            Double v = u.get("catavg:" + itemCategory);
            if (v != null) {
                catAff = v;
            }
        }
        catAff = catAff / 5.0;

        return new double[]{itemPop, itemAvg, userAct, userAvg, catAff};
    }

    /** 把特征向量转成带名快照(随 RankedItem 返回,便于调试与训练样本回流核对)。 */
    public static java.util.Map<String, Double> snapshot(double[] feats) {
        java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < FEATURE_ORDER.size() && i < feats.length; i++) {
            m.put(FEATURE_ORDER.get(i), feats[i]);
        }
        return m;
    }
}
