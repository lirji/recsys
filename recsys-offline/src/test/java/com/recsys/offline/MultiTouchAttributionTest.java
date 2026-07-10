package com.recsys.offline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MultiTouchAttribution} 单测(A5)——锁死归因权重的数值正确性:每次转化恰好分出 1.0 信用
 * (不放大/不丢失),各模型的形状(均分 / U 形 / 时间衰减)符合定义。
 */
class MultiTouchAttributionTest {

    private static final double EPS = 1e-9;

    private static double sum(double[] a) {
        double s = 0;
        for (double x : a) {
            s += x;
        }
        return s;
    }

    @Test
    void linear_equalAndSumsToOne() {
        double[] w = MultiTouchAttribution.linearWeights(4);
        assertArrayEquals(new double[]{0.25, 0.25, 0.25, 0.25}, w, EPS);
        assertEquals(1.0, sum(w), EPS);
        assertEquals(0, MultiTouchAttribution.linearWeights(0).length, "n<=0 → 空");
        assertArrayEquals(new double[]{1.0}, MultiTouchAttribution.linearWeights(1), EPS);
    }

    @Test
    void position_uShape_sumsToOne() {
        // n=3, 40/20/40:首末重、中间轻
        double[] w = MultiTouchAttribution.positionBasedWeights(3, 0.4, 0.4);
        assertArrayEquals(new double[]{0.4, 0.2, 0.4}, w, EPS);
        assertEquals(1.0, sum(w), EPS);
        // 首末 > 中间
        assertTrue(w[0] > w[1] && w[2] > w[1]);
    }

    @Test
    void position_edgeCases() {
        assertArrayEquals(new double[]{1.0}, MultiTouchAttribution.positionBasedWeights(1, 0.4, 0.4), EPS);
        // n=2:无中间,首末按 first:last 归一(相等 → 0.5/0.5)
        double[] w2 = MultiTouchAttribution.positionBasedWeights(2, 0.4, 0.4);
        assertArrayEquals(new double[]{0.5, 0.5}, w2, EPS);
        // first+last>1 也被归一化,和仍为 1
        double[] w = MultiTouchAttribution.positionBasedWeights(4, 0.6, 0.6);
        assertEquals(1.0, sum(w), EPS);
        assertEquals(0, MultiTouchAttribution.positionBasedWeights(0, 0.4, 0.4).length);
    }

    @Test
    void position_longPath_middleSplitEqually() {
        // n=5, first=last=0.4 → 中间 3 个均分 0.2 → 各 0.0667
        double[] w = MultiTouchAttribution.positionBasedWeights(5, 0.4, 0.4);
        assertEquals(1.0, sum(w), EPS);
        assertEquals(0.4, w[0], EPS);
        assertEquals(0.4, w[4], EPS);
        assertEquals(w[1], w[2], EPS);
        assertEquals(w[2], w[3], EPS);
        assertEquals(0.2 / 3.0, w[1], EPS);
    }

    @Test
    void timeDecay_recentTouchWeightsMore() {
        // ages(天):[6, 3, 0] 升序=越早的触点 age 越大;half-life=3 → 权重 2^(-age/3):[0.25,0.5,1] 归一
        double[] w = MultiTouchAttribution.timeDecayWeights(new double[]{6, 3, 0}, 3.0);
        assertEquals(1.0, sum(w), EPS);
        assertTrue(w[2] > w[1] && w[1] > w[0], "越近的触点权重越大");
        // 半衰期语义:age=3 相对 age=0 权重减半 → w[1]/w[2] ≈ 0.5
        assertEquals(0.5, w[1] / w[2], 1e-6);
        assertEquals(0.25, w[0] / w[2], 1e-6);
    }

    @Test
    void timeDecay_edgeCasesAndGuard() {
        assertEquals(0, MultiTouchAttribution.timeDecayWeights(new double[0], 3.0).length);
        assertArrayEquals(new double[]{1.0}, MultiTouchAttribution.timeDecayWeights(new double[]{2.5}, 3.0), EPS);
        // half-life 非正 → 抛异常(防静默错误权重)
        try {
            MultiTouchAttribution.timeDecayWeights(new double[]{1, 2}, 0);
            assertTrue(false, "应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void normalize_degenerateInputFallsBackToEqual() {
        // 全 0 → 退化均分(下游总能拿到合法权重)
        double[] w = MultiTouchAttribution.normalize(new double[]{0, 0, 0});
        assertArrayEquals(new double[]{1 / 3.0, 1 / 3.0, 1 / 3.0}, w, EPS);
    }
}
