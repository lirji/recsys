package com.recsys.ad;

/** A7 两个潜在结果及其差值；delta 是每次广告机会的增量转化概率。 */
public record UpliftEstimate(double mu0, double mu1, double delta, String modelVersion) {
    public UpliftEstimate {
        if (!Double.isFinite(mu0) || !Double.isFinite(mu1) || !Double.isFinite(delta)) {
            throw new IllegalArgumentException("uplift estimate 必须为有限值");
        }
    }
}
