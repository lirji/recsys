package com.recsys.offline;

import java.util.HashMap;
import java.util.Map;

/**
 * As-of(时间点)特征构造器:消除离线训练样本的数据穿越。
 *
 * <p><b>问题</b>:在线特征(Redis feat:*)是「截至当前」的全量聚合,在线打分用它本就正确。
 * 但离线生成训练样本时,若也用全量聚合,某条 (user,item,ts) 样本的特征就包含了「ts 之后」乃至
 * 「目标交互本身」的信息 —— 这是数据穿越,会让离线 AUC 虚高、上线后崩塌。
 *
 * <p><b>做法</b>:按事件时间升序流式累加聚合。为某条样本取特征时,只反映<strong>此前已 apply 的事件</strong>。
 * 调用方在 apply 目标事件<em>之前</em> snapshot,即得到「截至 ts(不含本次)」的特征,等价 point-in-time join。
 *
 * <p>聚合维度与 {@link com.recsys.rank.FeatureAssembler} 期望的特征键一致:
 * <ul>
 *   <li>user:{@code user_act_norm}(log 归一活跃度)、{@code user_avg_rating}(原始均分,assembler 再 /5);</li>
 *   <li>user×category:{@code catavg:<cat>}(用户对该类目历史均分,原始);</li>
 *   <li>item:{@code item_pop_norm}(log 归一热度)、{@code item_avg_rating}(原始均分)。</li>
 * </ul>
 *
 * <p><b>已知简化</b>:归一化分母 {@code lnMaxUser/lnMaxItem} 用<strong>全量</strong>最大计数常数
 * (纯尺度因子,所有样本同一常数,不携带单样本的未来信息)。严格 as-of 连分母也该随时间动,
 * 但那对树模型/双塔几乎无影响,故此处固定,javadoc 标注。
 */
final class AsOfFeatureBuilder {

    private final double lnMaxUser;
    private final double lnMaxItem;

    // [cnt, sum, sumSq](sumSq 供 item 评分离散度 item_rating_std;user/userCat 不用 sumSq)
    private final Map<Long, double[]> userAgg = new HashMap<>();
    private final Map<Long, double[]> itemAgg = new HashMap<>();
    // user -> (category -> [cnt, sum, sumSq])
    private final Map<Long, Map<String, double[]>> userCatAgg = new HashMap<>();

    AsOfFeatureBuilder(double lnMaxUser, double lnMaxItem) {
        this.lnMaxUser = lnMaxUser <= 0 ? 1.0 : lnMaxUser;
        this.lnMaxItem = lnMaxItem <= 0 ? 1.0 : lnMaxItem;
    }

    /** 把一条评分事件并入聚合(调用方应在 snapshot 之后、按 ts 升序 apply)。 */
    void apply(long userId, long itemId, double value, String category) {
        accumulate(userAgg.computeIfAbsent(userId, k -> new double[3]), value);
        accumulate(itemAgg.computeIfAbsent(itemId, k -> new double[3]), value);
        if (category != null) {
            Map<String, double[]> byCat = userCatAgg.computeIfAbsent(userId, k -> new HashMap<>());
            accumulate(byCat.computeIfAbsent(category, k -> new double[3]), value);
        }
    }

    /** 截至当前已 apply 事件的用户特征(无历史 → 空 map,交 FeatureAssembler 用中性默认)。 */
    Map<String, Double> snapshotUser(long userId) {
        double[] agg = userAgg.get(userId);
        Map<String, Double> f = new HashMap<>();
        if (agg == null || agg[0] == 0) {
            return f;
        }
        double userCnt = agg[0];
        f.put("user_act_norm", round(Math.log1p(userCnt) / lnMaxUser));
        f.put("user_avg_rating", round(agg[1] / userCnt));
        Map<String, double[]> byCat = userCatAgg.get(userId);
        if (byCat != null) {
            for (var e : byCat.entrySet()) {
                double[] c = e.getValue();
                if (c[0] > 0) {
                    f.put("catavg:" + e.getKey(), round(c[1] / c[0]));
                    // 扩充特征(S2):类目参与深度(log 归一)+ 类目占比(与 build-features 同源)
                    f.put("catcnt_norm:" + e.getKey(), round(Math.log1p(c[0]) / lnMaxUser));
                    f.put("catratio:" + e.getKey(), round(c[0] / userCnt));
                }
            }
        }
        return f;
    }

    /** 截至当前已 apply 事件的物品特征(无历史 → 空 map)。 */
    Map<String, Double> snapshotItem(long itemId) {
        double[] agg = itemAgg.get(itemId);
        Map<String, Double> f = new HashMap<>();
        if (agg == null || agg[0] == 0) {
            return f;
        }
        double cnt = agg[0];
        double mean = agg[1] / cnt;
        f.put("item_pop_norm", round(Math.log1p(cnt) / lnMaxItem));
        f.put("item_avg_rating", round(mean));
        // 扩充特征(S2):评分离散度(总体标准差),与 build-features 的 stddev_pop 同源
        double var = agg[2] / cnt - mean * mean;
        f.put("item_rating_std", round(Math.sqrt(Math.max(0.0, var))));
        return f;
    }

    private static void accumulate(double[] agg, double value) {
        agg[0] += 1;
        agg[1] += value;
        agg[2] += value * value;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
