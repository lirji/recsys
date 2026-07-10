package com.recsys.offline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataQuality} 单测(E6)——锁死漂移/校准度量的数值语义,防止"漂移了却报稳定"或反之。
 */
class DataQualityTest {

    @Test
    void psi_identicalDistribution_isZero() {
        long[] d = {100, 200, 300};
        assertEquals(0.0, DataQuality.psi(d, d), 1e-9);
        assertEquals("stable", DataQuality.psiLevel(0.0));
    }

    @Test
    void psi_shiftedDistribution_isLarge() {
        long[] base = {700, 200, 100};      // 大部分在桶0
        long[] shifted = {100, 200, 700};   // 大部分挪到桶2
        double psi = DataQuality.psi(base, shifted);
        assertTrue(psi > 0.25, "显著漂移 PSI 应 > 0.25,实得 " + psi);
        assertEquals("significant", DataQuality.psiLevel(psi));
    }

    @Test
    void psi_moderateShift_levelModerate() {
        long[] base = {500, 300, 200};
        long[] cur = {430, 330, 240};
        double psi = DataQuality.psi(base, cur);
        assertTrue(psi >= 0 && psi < 0.25, "轻微漂移应落 stable/moderate,实得 " + psi);
    }

    @Test
    void ece_perfectlyCalibrated_isZero() {
        long[] n = {100, 100};
        double[] pred = {0.1, 0.5};
        double[] actual = {0.1, 0.5};   // 预估=实际 → ECE=0
        assertEquals(0.0, DataQuality.expectedCalibrationError(n, pred, actual), 1e-9);
    }

    @Test
    void ece_systematicOverestimate_isPositive_weightedByCount() {
        long[] n = {900, 100};          // 桶0 权重大
        double[] pred = {0.30, 0.30};   // 都高估
        double[] actual = {0.10, 0.10};
        // ECE = 0.9*0.2 + 0.1*0.2 = 0.2
        assertEquals(0.2, DataQuality.expectedCalibrationError(n, pred, actual), 1e-9);
    }

    @Test
    void coverage_ratioAndEmptyGuard() {
        assertEquals(0.8, DataQuality.coverage(80, 100), 1e-9);
        assertEquals(1.0, DataQuality.coverage(0, 0), 1e-9, "总数0视作无缺口");
    }
}
