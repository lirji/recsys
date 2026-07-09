package com.recsys.rank;

import java.util.HashMap;
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
 * <p><b>特征集(S2 特征扩充,向后兼容)</b>:
 * <ul>
 *   <li>{@link #FEATURE_ORDER}(5 维,默认/旧模型):OnnxRankService、gen-samples 默认、
 *       所有已训练模型都用它,行为与扩充前逐位一致 —— 扩充零风险。</li>
 *   <li>{@link #EXTENDED_ORDER}(8 维,重训后经 schema {@code dense_order} 启用):在 5 维基础上追加
 *       {@code user_cat_cnt_norm}(用户对该类目的参与深度,log 归一)、{@code user_cat_ratio}
 *       (该类目占用户全部行为的比例)、{@code item_rating_std}(物品评分离散度=共识/争议,/5)。
 *       三者均由 build-features(在线)与 AsOfFeatureBuilder(离线 as-of)<strong>同源产出</strong>,读同名字段。</li>
 * </ul>
 * 深度模型经 {@code SparseFeatureEncoder.denseOrder()} 拿到自己训练时的 dense_order 顺序装配;
 * 缺 dense_order(旧 schema)默认回 {@link #FEATURE_ORDER},故旧模型完全不受影响。
 */
public final class FeatureAssembler {

    private FeatureAssembler() {
    }

    /** 基础特征顺序(默认/向后兼容;与旧 ONNX 模型输入列严格对齐)。 */
    public static final List<String> FEATURE_ORDER = List.of(
            "item_pop_norm",      // 物品热度(log 归一,0~1)
            "item_avg_rating",    // 物品平均分(/5)
            "user_act_norm",      // 用户活跃度(log 归一,0~1)
            "user_avg_rating",    // 用户平均打分(/5,评分偏置)
            "user_cat_affinity"   // 交叉:用户对该物品类目的历史平均分(/5,最有用)
    );

    /** 扩充特征顺序(S2,重训后启用):基础 5 维 + 3 个新交叉/离散度特征。 */
    public static final List<String> EXTENDED_ORDER = List.of(
            "item_pop_norm",
            "item_avg_rating",
            "user_act_norm",
            "user_avg_rating",
            "user_cat_affinity",
            "user_cat_cnt_norm",  // 交叉:用户对该类目的参与次数(log 归一)—— 深度 vs 均分
            "user_cat_ratio",     // 交叉:该类目占用户全部行为的比例(0~1)
            "item_rating_std"     // 物品评分离散度(/5):高=争议,低=共识
    );

    /** 基础特征维度(默认路径;OnnxRankService 用)。 */
    public static int dim() {
        return FEATURE_ORDER.size();
    }

    private static final double DEFAULT_ITEM_AVG = 3.5;
    private static final double DEFAULT_USER_AVG = 3.5;

    /**
     * 计算全部特征到一个 map(单一真源;各 assemble 从中按 order 取)。
     * 缺失值给中性默认,保证新用户/新物品不崩。
     */
    private static Map<String, Double> computeAll(Map<String, Double> userFeat,
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

        // 扩充特征(缺失给中性默认;online build-features / offline as-of 同源产出同名字段)
        double catCntNorm = itemCategory == null ? 0.0 : u.getOrDefault("catcnt_norm:" + itemCategory, 0.0);
        double catRatio = itemCategory == null ? 0.0 : u.getOrDefault("catratio:" + itemCategory, 0.0);
        double itemStd = it.getOrDefault("item_rating_std", 0.0) / 5.0;

        Map<String, Double> m = new HashMap<>();
        m.put("item_pop_norm", itemPop);
        m.put("item_avg_rating", itemAvg);
        m.put("user_act_norm", userAct);
        m.put("user_avg_rating", userAvg);
        m.put("user_cat_affinity", catAff);
        m.put("user_cat_cnt_norm", catCntNorm);
        m.put("user_cat_ratio", catRatio);
        m.put("item_rating_std", itemStd);
        return m;
    }

    /**
     * 装配单条 (user, item) 特征向量,顺序 = {@link #FEATURE_ORDER}(基础 5 维,向后兼容)。
     *
     * @param userFeat     用户在线特征(feat:user:{id} 的数值字段,含 catavg/catcnt_norm/catratio:&lt;genre&gt;)
     * @param itemFeat     物品在线特征(feat:item:{id})
     * @param itemCategory 物品主类目(取交叉特征用;为空则交叉回退到用户均分)
     */
    public static double[] assemble(Map<String, Double> userFeat,
                                    Map<String, Double> itemFeat,
                                    String itemCategory) {
        return assemble(userFeat, itemFeat, itemCategory, FEATURE_ORDER);
    }

    /** 按给定顺序装配(供深度模型用自己训练时的 dense_order;支持扩充特征)。 */
    public static double[] assemble(Map<String, Double> userFeat,
                                    Map<String, Double> itemFeat,
                                    String itemCategory,
                                    List<String> order) {
        Map<String, Double> all = computeAll(userFeat, itemFeat, itemCategory);
        double[] out = new double[order.size()];
        for (int i = 0; i < order.size(); i++) {
            out[i] = all.getOrDefault(order.get(i), 0.0);
        }
        return out;
    }

    /** 把特征向量转成带名快照(随 RankedItem 返回,便于调试与训练样本回流核对),名用 {@link #FEATURE_ORDER}。 */
    public static java.util.Map<String, Double> snapshot(double[] feats) {
        return snapshot(feats, FEATURE_ORDER);
    }

    /** 按给定顺序命名快照(与扩充 dense_order 对齐)。 */
    public static java.util.Map<String, Double> snapshot(double[] feats, List<String> order) {
        java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < order.size() && i < feats.length; i++) {
            m.put(order.get(i), feats[i]);
        }
        return m;
    }
}
