package com.recsys.offline;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsOfFeatureBuilder} 契约测试 —— 离线样本特征的 <b>as-of(时间点)无穿越</b> 保证,
 * 以及与在线 {@code build-features} 同源的特征数值(含 S2 扩充特征 catcnt_norm/catratio/item_rating_std)。
 */
class AsOfFeatureBuilderTest {

    private static final double D = 1e-4;   // snapshot round() 到 4 位

    @Test
    void noLeakage_snapshotReflectsOnlyPriorEvents() {
        AsOfFeatureBuilder b = new AsOfFeatureBuilder(Math.log1p(10), Math.log1p(10));
        // apply 之前:无历史 → 空(调用方在 apply 目标事件之前 snapshot,即无穿越)
        assertTrue(b.snapshotUser(1).isEmpty(), "apply 前用户无特征");
        assertTrue(b.snapshotItem(100).isEmpty(), "apply 前物品无特征");
        b.apply(1, 100, 5.0, "Action");
        assertEquals(5.0, b.snapshotUser(1).get("user_avg_rating"), D);
        assertEquals(5.0, b.snapshotItem(100).get("item_avg_rating"), D);
    }

    @Test
    void userFeatures_avgAndCatCross() {
        AsOfFeatureBuilder b = new AsOfFeatureBuilder(Math.log1p(10), Math.log1p(10));
        b.apply(1, 100, 5.0, "Action");
        b.apply(1, 200, 3.0, "Action");
        b.apply(1, 300, 4.0, "Drama");
        Map<String, Double> u = b.snapshotUser(1);
        assertEquals(4.0, u.get("user_avg_rating"), D, "整体均分 (5+3+4)/3");
        assertEquals(4.0, u.get("catavg:Action"), D, "Action 均分 (5+3)/2");
        // S2 扩充:类目占比(与 build-features 同源)
        assertEquals(2.0 / 3, u.get("catratio:Action"), D);
        assertEquals(1.0 / 3, u.get("catratio:Drama"), D);
        // S2 扩充:类目参与深度 log1p(cnt)/lnMaxUser
        assertEquals(Math.log1p(2) / Math.log1p(10), u.get("catcnt_norm:Action"), D);
    }

    @Test
    void itemRatingStd_populationStd() {
        AsOfFeatureBuilder b = new AsOfFeatureBuilder(Math.log1p(10), Math.log1p(10));
        b.apply(1, 100, 5.0, "Action");
        b.apply(2, 100, 1.0, "Action");   // item 100 评分 [5,1] → mean 3, var 4, std 2
        assertEquals(2.0, b.snapshotItem(100).get("item_rating_std"), D);
        b.apply(3, 200, 4.0, "Drama");    // 单评分 → std 0
        assertEquals(0.0, b.snapshotItem(200).get("item_rating_std"), D);
    }
}
