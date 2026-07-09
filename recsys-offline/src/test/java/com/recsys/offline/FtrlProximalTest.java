package com.recsys.offline;

import com.recsys.common.feature.FtrlHashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FtrlProximal} 单元测试 —— 近线增量学习 FTRL-Proximal LR 的算法契约。
 * 覆盖:初始无信息=0.5、正例训练后概率上升、L1 稀疏权重、状态 warm-start 一致(近线续训依赖)。
 */
class FtrlProximalTest {

    @Test
    void learnsPositivePreference() {
        FtrlProximal m = new FtrlProximal(0.1, 1.0, 1.0, 1.0);
        int[] feats = FtrlHashing.features(1, 100);
        double before = m.predict(feats);
        assertEquals(0.5, before, 1e-9, "初始无信息 → 0.5");
        for (int i = 0; i < 50; i++) {
            m.update(feats, 1.0);                              // 正例
            m.update(FtrlHashing.features(1, 1000 + i), 0.0);  // 负例
        }
        double after = m.predict(feats);
        assertTrue(after > before, "正例训练后概率应上升: " + after);
        assertTrue(after > 0.5);
    }

    @Test
    void l1ProducesSparseWeights() {
        FtrlProximal m = new FtrlProximal(0.1, 1.0, 1.0, 1.0);
        int[] feats = FtrlHashing.features(1, 100);
        for (int i = 0; i < 30; i++) {
            m.update(feats, 1.0);
        }
        assertFalse(m.serveWeights().isEmpty(), "训练过应有非零权重");
        assertTrue(m.serveWeights().size() <= feats.length, "稀疏:非零权重数 ≤ 命中坐标数");
    }

    @Test
    void stateRoundtrip_predictsIdentically() {
        FtrlProximal a = new FtrlProximal(0.1, 1.0, 1.0, 1.0);
        int[] feats = FtrlHashing.features(7, 42);
        for (int i = 0; i < 20; i++) {
            a.update(feats, 1.0);
            a.update(FtrlHashing.features(7, 900 + i), 0.0);
        }
        FtrlProximal b = new FtrlProximal(0.1, 1.0, 1.0, 1.0);
        b.loadState(a.biasState(), a.stateCoords());   // warm-start(近线增量续训)
        assertEquals(a.predict(feats), b.predict(feats), 1e-12, "warm-start 后预测应一致");
    }
}
