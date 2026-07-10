package com.recsys.offline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbStats} 单测(P2)——锁死 A/B 显著性推断的数值正确性,防止"把噪声当显著"或反之。
 */
class AbStatsTest {

    @Test
    void normalCdf_knownPoints() {
        assertEquals(0.5, AbStats.normalCdf(0), 1e-6);
        assertEquals(0.977249868, AbStats.normalCdf(2.0), 1e-4);  // Φ(2)≈0.9772
        assertEquals(0.841344746, AbStats.normalCdf(1.0), 1e-4);
    }

    @Test
    void inverseNormalCdf_isInverseOfCdf() {
        assertEquals(1.959964, AbStats.inverseNormalCdf(0.975), 1e-3);  // 95% 双侧临界 z
        assertEquals(0.841621, AbStats.inverseNormalCdf(0.8), 1e-3);    // power=0.8 的 z_β
        // 互逆
        assertEquals(1.2816, AbStats.inverseNormalCdf(AbStats.normalCdf(1.2816)), 1e-3);
    }

    @Test
    void twoSidedP_largeZ_isSignificant_smallZ_isNot() {
        assertTrue(AbStats.twoSidedPValue(3.0) < 0.05, "z=3 应显著");
        assertTrue(AbStats.twoSidedPValue(0.5) > 0.05, "z=0.5 不应显著");
        assertEquals(0.05, AbStats.twoSidedPValue(1.959964), 1e-3, "z=1.96 → p≈0.05");
    }

    @Test
    void twoProportionZ_hugeDifference_hasLargeZ() {
        // 1000 曝光,基线 100 点击(10%)vs 处理 200 点击(20%)→ 明显显著
        double z = AbStats.twoProportionZ(200, 1000, 100, 1000);
        assertTrue(z > 5, "10% vs 20% @ n=1000 应有很大 z,实得 " + z);
        assertTrue(AbStats.twoSidedPValue(z) < 0.001);
    }

    @Test
    void twoProportionZ_identicalRates_nearZero() {
        // 同 CTR 的两桶(AA 期望)→ z≈0、p≈1(不显著)
        double z = AbStats.twoProportionZ(100, 1000, 100, 1000);
        assertEquals(0.0, z, 1e-9);
        assertEquals(1.0, AbStats.twoSidedPValue(z), 1e-9);
    }

    @Test
    void wilson_intervalBracketsPointEstimate() {
        double[] ci = AbStats.wilson(100, 1000, 1.96);  // p̂=0.1
        assertTrue(ci[0] < 0.1 && 0.1 < ci[1], "CI 应包住点估计 0.1");
        assertTrue(ci[0] > 0.08 && ci[1] < 0.125, "n=1000 时 95%CI 约 [0.083,0.120]");
        // 边界:零试验 → {0,0}
        assertEquals(0.0, AbStats.wilson(0, 0, 1.96)[1], 1e-9);
    }

    @Test
    void minSamplePerArm_smallerEffectNeedsMoreSamples() {
        long nBig = AbStats.minSamplePerArm(0.12, 0.10, 0.05, 0.8);   // 2pp 提升
        long nSmall = AbStats.minSamplePerArm(0.101, 0.10, 0.05, 0.8); // 0.1pp 提升
        assertTrue(nSmall > nBig, "更小的效应需要更多样本");
        assertEquals(Long.MAX_VALUE, AbStats.minSamplePerArm(0.1, 0.1, 0.05, 0.8), "零差异 → 无穷");
    }
}
