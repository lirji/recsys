package com.recsys.offline;

/**
 * 延迟转化模型的**纯函数**(无状态、无 IO):指数延迟分布 + 转化完成曲线 + Horvitz–Thompson 权重。
 *
 * <p>这是延迟转化纠偏的**唯一数学真相**:{@link DelayModelJob} 用 {@link #lambdaFromMeanDays} 拟合 λ、
 * 用 {@link #completion} 打印完成曲线;{@link OcpcCalibrateJob} 用 {@link #htWeight} 给每个已到达转化加权
 * 补回在途转化。把公式收敛到这里(而非散落在 SQL/各 Job)避免离线纠偏与拟合口径漂移——同 {@code FeatureAssembler}
 * 之于在线/离线特征一致性的角色。
 *
 * <p><b>模型</b>:转化延迟 {@code d}(点击到转化的天数,条件于"终将转化")~ 指数分布 Exp(λ)。
 * 完成曲线 {@code c(e) = P(d ≤ e) = 1 − e^(−λe)} = 一次 elapsed=e 的点击若终将转化、到现在已转化的概率。
 * 故一个"已观测(已到达)转化"代表 {@code 1/c(elapsed)} 个终值转化(Horvitz–Thompson 逆概率加权)。
 */
final class DelayModel {

    private DelayModel() {
    }

    /**
     * 指数分布 MLE:λ = 1 / 平均延迟(天)。
     *
     * @param meanDays 已到达转化的平均延迟(天),必须 &gt; 0
     * @throws IllegalArgumentException meanDays &le; 0 或非有限
     */
    static double lambdaFromMeanDays(double meanDays) {
        if (!(meanDays > 0) || !Double.isFinite(meanDays)) {
            throw new IllegalArgumentException("平均延迟必须为正且有限: " + meanDays);
        }
        return 1.0 / meanDays;
    }

    /**
     * 转化完成曲线 {@code c(e) = 1 − e^(−λ·e)}:elapsed 天内已转化的概率,随 elapsed 单调递增,e=0 时为 0。
     *
     * @param lambda      延迟速率 λ(1/天),&gt; 0
     * @param elapsedDays 点击到现在的时长(天),&ge; 0
     */
    static double completion(double lambda, double elapsedDays) {
        if (elapsedDays <= 0) {
            return 0.0;
        }
        return 1.0 - Math.exp(-lambda * elapsedDays);
    }

    /**
     * Horvitz–Thompson 权重 {@code = 1 / max(c(elapsed), minCompletion)}:一个已到达转化代表的终值转化数。
     * {@code minCompletion} 给完成度设下限(权重上限 {@code 1/minCompletion}),防极近点击 c(e)→0 时权重爆炸。
     *
     * @param lambda        延迟速率 λ(1/天)
     * @param elapsedDays   点击到现在的时长(天)
     * @param minCompletion 完成度下限,(0,1],权重上限即 1/minCompletion
     */
    static double htWeight(double lambda, double elapsedDays, double minCompletion) {
        double c = Math.max(completion(lambda, elapsedDays), minCompletion);
        return 1.0 / c;
    }
}
