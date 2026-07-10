package com.recsys.offline;

/**
 * A/B 显著性统计纯函数(P2)——把"桶间 CTR 差异"从点估计升级为可判定的推断:
 * Wilson 置信区间、两比例 z 检验 + 双侧 p 值、检测既定提升所需的最小样本量。
 *
 * <p>全部无副作用、无依赖,便于单测(见 AbStatsTest);被 {@link AbReportJob} 调用。
 */
final class AbStats {

    private AbStats() {
    }

    /**
     * 比例的 Wilson score 置信区间(比正态近似在小样本/极端比例下更稳)。
     *
     * @param successes 成功数(点击);@param trials 试验数(曝光);@param z 置信水平对应的 z(95%→1.96)
     * @return {low, high},trials=0 时退 {0,0}
     */
    static double[] wilson(long successes, long trials, double z) {
        if (trials <= 0) {
            return new double[]{0.0, 0.0};
        }
        double n = trials;
        double phat = successes / n;
        double z2 = z * z;
        double denom = 1.0 + z2 / n;
        double center = (phat + z2 / (2 * n)) / denom;
        double half = z * Math.sqrt(phat * (1 - phat) / n + z2 / (4 * n * n)) / denom;
        return new double[]{Math.max(0.0, center - half), Math.min(1.0, center + half)};
    }

    /**
     * 两比例 z 检验统计量(合并方差):z = (p̂1 − p̂2) / sqrt(p̄(1−p̄)(1/n1 + 1/n2))。
     * 返回 0(无法判定)当任一样本为空或合并方差为 0。
     */
    static double twoProportionZ(long c1, long n1, long c2, long n2) {
        if (n1 <= 0 || n2 <= 0) {
            return 0.0;
        }
        double p1 = (double) c1 / n1;
        double p2 = (double) c2 / n2;
        double pool = (double) (c1 + c2) / (n1 + n2);
        double se = Math.sqrt(pool * (1 - pool) * (1.0 / n1 + 1.0 / n2));
        if (se == 0.0) {
            return 0.0;
        }
        return (p1 - p2) / se;
    }

    /** 双侧 p 值:2·(1 − Φ(|z|))。 */
    static double twoSidedPValue(double z) {
        return 2.0 * (1.0 - normalCdf(Math.abs(z)));
    }

    /**
     * 检测两比例差异所需的每臂最小样本量(双侧 α、power=1−β):
     * n ≈ (z_{α/2}+z_β)² · (p1(1−p1)+p2(1−p2)) / (p1−p2)²。差为 0 时返回 {@link Long#MAX_VALUE}。
     */
    static long minSamplePerArm(double p1, double p2, double alpha, double power) {
        double diff = p1 - p2;
        if (diff == 0.0) {
            return Long.MAX_VALUE;
        }
        double zA = inverseNormalCdf(1.0 - alpha / 2.0);
        double zB = inverseNormalCdf(power);
        double num = Math.pow(zA + zB, 2) * (p1 * (1 - p1) + p2 * (1 - p2));
        return (long) Math.ceil(num / (diff * diff));
    }

    /** 标准正态 CDF Φ(x)(Abramowitz-Stegun 7.1.26 erf 近似,|误差|<1.5e-7)。 */
    static double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-x * x);
        return Math.signum(x) * y;
    }

    /** 标准正态分位数 Φ⁻¹(p)(Acklam 有理逼近);p∈(0,1),越界钳到边界。 */
    static double inverseNormalCdf(double p) {
        if (p <= 0) {
            return -Double.MAX_VALUE;
        }
        if (p >= 1) {
            return Double.MAX_VALUE;
        }
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        double plow = 0.02425;
        double phigh = 1 - plow;
        if (p < plow) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p <= phigh) {
            double q = p - 0.5;
            double r = q * q;
            return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        }
        double q = Math.sqrt(-2 * Math.log(1 - p));
        return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
    }
}
