package com.recsys.rank;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FeatureAssembler} 契约测试 —— 在线/离线特征一致性的第一道防线(docs/03 §4「推荐第一大坑」)。
 *
 * <p>核心保证:<b>默认 5 维输出逐位不变(回归护栏)</b>——任何改动若动了默认路径的数值,此测试即红,
 * 防止在线(OnnxRankService/深度服务)与离线(gen-samples/train_*.py DENSE_COLS)悄悄漂移导致上线崩。
 * 并覆盖 S2 扩充路径(8 维追加正确)与缺失值中性默认。
 */
class FeatureAssemblerTest {

    private static final double EPS = 1e-9;

    private Map<String, Double> userFeat() {
        Map<String, Double> u = new HashMap<>();
        u.put("user_act_norm", 0.5);
        u.put("user_avg_rating", 4.0);
        u.put("catavg:Action", 4.5);
        u.put("catcnt_norm:Action", 0.3);
        u.put("catratio:Action", 0.6);
        return u;
    }

    private Map<String, Double> itemFeat() {
        Map<String, Double> it = new HashMap<>();
        it.put("item_pop_norm", 0.7);
        it.put("item_avg_rating", 3.8);
        it.put("item_rating_std", 1.0);
        return it;
    }

    @Test
    void defaultAssemble_exactValues_regressionGuard() {
        double[] f = FeatureAssembler.assemble(userFeat(), itemFeat(), "Action");
        // [item_pop_norm, item_avg/5, user_act, user_avg/5, catavg/5]
        assertArrayEquals(new double[]{0.7, 0.76, 0.5, 0.8, 0.9}, f, EPS);
        assertEquals(5, FeatureAssembler.dim());
    }

    @Test
    void extendedAssemble_appendsThreeFeatures() {
        double[] f = FeatureAssembler.assemble(userFeat(), itemFeat(), "Action", FeatureAssembler.EXTENDED_ORDER);
        assertArrayEquals(new double[]{0.7, 0.76, 0.5, 0.8, 0.9, 0.3, 0.6, 0.2}, f, EPS);
        assertEquals(8, FeatureAssembler.EXTENDED_ORDER.size());
    }

    @Test
    void extendedIsSupersetOfDefault_first5Identical() {
        double[] base = FeatureAssembler.assemble(userFeat(), itemFeat(), "Action");
        double[] ext = FeatureAssembler.assemble(userFeat(), itemFeat(), "Action", FeatureAssembler.EXTENDED_ORDER);
        for (int i = 0; i < base.length; i++) {
            assertEquals(base[i], ext[i], EPS, "扩展前 5 维必须与默认逐位一致,下标 " + i);
        }
    }

    @Test
    void missingFeatures_neutralDefaults() {
        // 空特征 + 无类目 → 中性默认(新用户/新物品不崩)
        double[] f = FeatureAssembler.assemble(Map.of(), Map.of(), null);
        // item_pop=0, item_avg=3.5/5=0.7, user_act=0, user_avg=3.5/5=0.7, catAff=userAvg(3.5)/5=0.7
        assertArrayEquals(new double[]{0.0, 0.7, 0.0, 0.7, 0.7}, f, EPS);
    }

    @Test
    void nullMaps_doNotThrow() {
        double[] f = FeatureAssembler.assemble(null, null, null);
        assertArrayEquals(new double[]{0.0, 0.7, 0.0, 0.7, 0.7}, f, EPS);
    }

    @Test
    void catAffinity_fallsBackToUserAvgWhenNoCatRecord() {
        Map<String, Double> u = new HashMap<>();
        u.put("user_avg_rating", 4.0);
        // 类目 Drama 无 catavg 记录 → 交叉特征回退用户整体均分 4.0/5=0.8
        double[] f = FeatureAssembler.assemble(u, Map.of(), "Drama");
        assertEquals(0.8, f[FeatureAssembler.FEATURE_ORDER.indexOf("user_cat_affinity")], EPS);
    }

    @Test
    void snapshot_namesByOrder() {
        double[] f = FeatureAssembler.assemble(userFeat(), itemFeat(), "Action", FeatureAssembler.EXTENDED_ORDER);
        Map<String, Double> snap = FeatureAssembler.snapshot(f, FeatureAssembler.EXTENDED_ORDER);
        assertEquals(0.3, snap.get("user_cat_cnt_norm"), EPS);
        assertEquals(0.2, snap.get("item_rating_std"), EPS);
    }
}
