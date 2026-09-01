package com.recsys.offline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void cuped_perfectCorrelation_recoversThetaAndRemovesVariance() {
        List<AbStats.CupedObservation> rows = List.of(
                new AbStats.CupedObservation(1, 10, -1.0),
                new AbStats.CupedObservation(2, 10, 0.0),
                new AbStats.CupedObservation(3, 10, 1.0));

        AbStats.CupedModel model = AbStats.fitCuped(rows);
        AbStats.CupedSummary summary = AbStats.summarizeCuped(rows, model);

        assertTrue(model.applied());
        assertEquals(1.0, model.theta(), 1e-12);
        assertEquals(1.0, model.varianceReduction(), 1e-12);
        assertEquals(0.2, summary.rawMean(), 1e-12);
        assertEquals(summary.rawMean(), summary.adjustedMean(), 1e-12,
                "pooled CUPED 调整不改变总体均值");
        assertEquals(0.0, summary.standardError(), 1e-12);
    }

    @Test
    void cuped_weightedRawMean_matchesRatioOfSums() {
        List<AbStats.CupedObservation> rows = List.of(
                new AbStats.CupedObservation(1, 10, 0.2),
                new AbStats.CupedObservation(15, 30, 0.4));
        AbStats.CupedSummary summary = AbStats.summarizeCuped(rows, AbStats.fitCuped(rows));
        assertEquals(16.0 / 40.0, summary.rawMean(), 1e-12);
    }

    @Test
    void cuped_constantCovariate_degradesToRawWithoutNan() {
        List<AbStats.CupedObservation> rows = List.of(
                new AbStats.CupedObservation(1, 5, 0.2),
                new AbStats.CupedObservation(3, 8, 0.2));
        AbStats.CupedModel model = AbStats.fitCuped(rows);
        AbStats.CupedSummary summary = AbStats.summarizeCuped(rows, model);

        assertFalse(model.applied());
        assertEquals(0.0, model.theta(), 1e-12);
        assertEquals(summary.rawMean(), summary.adjustedMean(), 1e-12);
        assertTrue(Double.isFinite(summary.standardError()));
    }

    @Test
    void cuped_missingPreHistory_keepsUserAndReportsCoverage() {
        List<AbStats.CupedObservation> rows = List.of(
                new AbStats.CupedObservation(1, 10, -1.0),
                new AbStats.CupedObservation(3, 10, 1.0),
                new AbStats.CupedObservation(16, 20, null));
        AbStats.CupedModel model = AbStats.fitCuped(rows);
        AbStats.CupedSummary summary = AbStats.summarizeCuped(rows, model);

        assertEquals(3, summary.units());
        assertEquals(2, summary.covariateUnits());
        assertEquals(0.5, summary.covariateCoverage(), 1e-12);
        assertEquals(0.5, summary.rawMean(), 1e-12);
        assertTrue(Double.isFinite(summary.adjustedMean()));
    }

    @Test
    void cupedDifferenceZ_zeroStandardError_isUnavailable() {
        AbStats.CupedSummary treatment = new AbStats.CupedSummary(0.2, 0.2, 0, 0, 2, 2, 4, 20, 1);
        AbStats.CupedSummary baseline = new AbStats.CupedSummary(0.1, 0.1, 0, 0, 2, 2, 2, 20, 1);

        assertTrue(Double.isNaN(AbStats.cupedDifferenceZ(treatment, baseline)));
    }

    @Test
    void cuped_ratioAdjustment_doesNotWeightPreCovariateByPostExposure() {
        List<AbStats.CupedObservation> rows = List.of(
                new AbStats.CupedObservation(1, 10, 1.0),
                new AbStats.CupedObservation(15, 30, null));
        AbStats.CupedModel fixedModel = new AbStats.CupedModel(2.0, 0.0, 0.0, 2, 40, true);

        AbStats.CupedSummary summary = AbStats.summarizeCuped(rows, fixedModel);

        assertEquals(0.4, summary.rawMean(), 1e-12);
        assertEquals(14.0 / 40.0, summary.adjustedMean(), 1e-12,
                "control correction is θ·X_pre once per randomized user, not postImpressions·θ·X_pre");
    }
}
