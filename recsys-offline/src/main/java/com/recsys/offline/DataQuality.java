package com.recsys.offline;

/**
 * 数据质量/漂移度量纯函数(E6)——无副作用、无依赖,便于单测(DataQualityTest),被 {@link DataQualityJob} 调用。
 *
 * <ul>
 *   <li><b>PSI</b>(Population Stability Index):度量两个分布(基线 vs 当前)的偏移。
 *       PSI = Σ (a_i − e_i)·ln(a_i/e_i)。经验阈值:&lt;0.1 稳定、0.1~0.25 中度漂移、&gt;0.25 显著漂移。</li>
 *   <li><b>ECE</b>(Expected Calibration Error):预估概率(pCTR)与实际率(CTR)的加权校准偏差。
 *       ECE = Σ (n_i/N)·|mean_pred_i − actual_i|;越小越校准。</li>
 * </ul>
 */
final class DataQuality {

    private static final double EPS = 1e-6;

    private DataQuality() {
    }

    /**
     * PSI(两分布计数 → 比例后计算)。长度须相等且 &gt;0;各桶加 EPS 平滑避免 ln(0)。
     */
    static double psi(long[] expected, long[] actual) {
        if (expected.length != actual.length || expected.length == 0) {
            throw new IllegalArgumentException("PSI 两分布桶数须相等且非空");
        }
        double totE = 0, totA = 0;
        for (long e : expected) {
            totE += e;
        }
        for (long a : actual) {
            totA += a;
        }
        if (totE == 0 || totA == 0) {
            return 0.0;
        }
        double psi = 0;
        for (int i = 0; i < expected.length; i++) {
            double e = Math.max(expected[i] / totE, EPS);
            double a = Math.max(actual[i] / totA, EPS);
            psi += (a - e) * Math.log(a / e);
        }
        return psi;
    }

    /** PSI 分级标签(供报表/告警可读)。 */
    static String psiLevel(double psi) {
        if (psi < 0.1) {
            return "stable";
        }
        if (psi < 0.25) {
            return "moderate";
        }
        return "significant";
    }

    /**
     * ECE:给定每个校准桶的(样本数、平均预估概率、实际正例率),算加权绝对校准偏差。
     * 三数组等长;总样本为 0 返回 0。
     */
    static double expectedCalibrationError(long[] binCount, double[] meanPred, double[] actualRate) {
        int k = binCount.length;
        if (meanPred.length != k || actualRate.length != k) {
            throw new IllegalArgumentException("ECE 三数组须等长");
        }
        long total = 0;
        for (long c : binCount) {
            total += c;
        }
        if (total == 0) {
            return 0.0;
        }
        double ece = 0;
        for (int i = 0; i < k; i++) {
            if (binCount[i] == 0) {
                continue;
            }
            ece += (binCount[i] / (double) total) * Math.abs(meanPred[i] - actualRate[i]);
        }
        return ece;
    }

    /** 覆盖率 = 有值 / 总数(总数为 0 → 1.0,视作无缺口)。 */
    static double coverage(long withValue, long total) {
        return total <= 0 ? 1.0 : (double) withValue / total;
    }
}
