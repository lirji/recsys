package com.recsys.offline;

import java.util.Arrays;

/**
 * 多触点归因(MTA)的**纯函数**(无状态、无 IO):把一次转化的 1.0 信用按不同模型分配到转化路径上的
 * 各个触点(广告曝光/点击)。这是 {@link AttributionJob} 的**唯一数学真相**——把权重公式收敛在这里
 * (而非散落在 SQL/Job)避免口径漂移,同 {@link DelayModel}/{@code FeatureAssembler} 的角色。
 *
 * <p><b>为什么要 MTA(A5)</b>:现有归因是 last-touch(转化只记在最后那个广告头上),会系统性高估
 * "临门一脚"渠道、低估前期种草的触点。MTA 把信用摊到路径上所有触点,给广告主更公平的渠道价值度量。
 * 三种经典模型:
 * <ul>
 *   <li><b>linear</b>(均分):路径上每个触点等权;</li>
 *   <li><b>position</b>(U 形/位置):首触点(拉新)与末触点(转化)各占大头,中间触点均分剩余;</li>
 *   <li><b>time-decay</b>(时间衰减):越靠近转化的触点权重越大,权重 ∝ 2^(−age/halfLife)。</li>
 * </ul>
 * 三者均返回和为 1 的权重向量(把恰好 1 次转化拆开,不放大也不丢失),便于与 last-touch 对账。
 */
final class MultiTouchAttribution {

    private MultiTouchAttribution() {
    }

    /** 线性(均分):每个触点等权,和为 1。{@code nTouches<=0} → 空数组。 */
    static double[] linearWeights(int nTouches) {
        if (nTouches <= 0) {
            return new double[0];
        }
        double[] w = new double[nTouches];
        Arrays.fill(w, 1.0 / nTouches);
        return w;
    }

    /**
     * 位置(U 形):触点按<b>时间升序</b>(index 0 = 首触点、末位 = 转化前最后触点)。
     * 首触点占 {@code first}、末触点占 {@code last}、中间触点均分剩余 {@code max(0,1-first-last)};
     * 最终归一化保证和为 1(容错 first+last>1 的入参)。
     * 退化:{@code n==1} → [1];{@code n==2} → 首末按 first:last 归一(无中间)。
     */
    static double[] positionBasedWeights(int nTouches, double first, double last) {
        if (nTouches <= 0) {
            return new double[0];
        }
        if (nTouches == 1) {
            return new double[]{1.0};
        }
        double[] w = new double[nTouches];
        if (nTouches == 2) {
            w[0] = first;
            w[1] = last;
        } else {
            double mid = Math.max(0.0, 1.0 - first - last);
            double per = mid / (nTouches - 2);
            w[0] = first;
            w[nTouches - 1] = last;
            for (int i = 1; i < nTouches - 1; i++) {
                w[i] = per;
            }
        }
        return normalize(w);
    }

    /**
     * 时间衰减:权重 ∝ 2^(−age/halfLife),越靠近转化(age 越小)的触点权重越大;归一化到和为 1。
     *
     * @param ageDays      每个触点到转化的天数(&ge;0;负值按 0 处理)
     * @param halfLifeDays 半衰期(天):经过它权重减半,必须 &gt; 0
     * @throws IllegalArgumentException halfLifeDays &le; 0 或非有限
     */
    static double[] timeDecayWeights(double[] ageDays, double halfLifeDays) {
        int n = ageDays == null ? 0 : ageDays.length;
        if (n == 0) {
            return new double[0];
        }
        if (!(halfLifeDays > 0) || !Double.isFinite(halfLifeDays)) {
            throw new IllegalArgumentException("half-life 必须为正且有限: " + halfLifeDays);
        }
        double lambda = Math.log(2.0) / halfLifeDays;
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            double age = Math.max(0.0, ageDays[i]);
            w[i] = Math.exp(-lambda * age);
        }
        return normalize(w);
    }

    /** 归一到和为 1;总和 &le;0(退化输入)→ 回退均分,保证下游拿到合法权重。 */
    static double[] normalize(double[] w) {
        double s = 0;
        for (double x : w) {
            s += x;
        }
        if (!(s > 0)) {
            return linearWeights(w.length);
        }
        double[] out = new double[w.length];
        for (int i = 0; i < w.length; i++) {
            out[i] = w[i] / s;
        }
        return out;
    }
}
