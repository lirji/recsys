package com.recsys.offline;

/**
 * 质量度精细化的**纯函数**(docs/05 §7 M7):贝叶斯收缩 + 归一 + 加权融合。无 IO,数学收敛于此便于单测——
 * 同 {@code DelayModel}/{@code ListwiseExternality} 的角色。{@link QualityScoreJob} 从 {@code ad_event} 取计数后
 * 调本类算最终质量度。质量度直接进 eCPM 排序与计费,把公式独立可测是审计要求(守 §8 #3 红线)。
 */
final class QualityScore {

    private QualityScore() {
    }

    /**
     * 贝叶斯收缩:经验率向大盘先验回归,低量样本不被极端值带偏。
     * {@code (successes + prior·popRate) / (trials + prior)}。trials→∞ 时 → 经验率;trials→0 时 → popRate。
     *
     * @param successes 成功数(点击数 / 转化数)
     * @param trials    试验数(曝光数 / 点击数)
     * @param popRate   大盘率(收缩目标)
     * @param prior     先验强度(等效的虚拟试验次数)
     */
    static double shrink(double successes, double trials, double popRate, double prior) {
        return (successes + prior * popRate) / (trials + prior);
    }

    /**
     * 归一为围绕 1.0 的乘子:{@code clamp(value / base, 1-clamp, 1+clamp)}。
     * 大盘基准 {@code base <= 0}(信号不可用)→ 返回中性 1.0(该因子不影响最终质量度)。
     */
    static double norm(double value, double base, double clamp) {
        if (base <= 0) {
            return 1.0;
        }
        return clamp(value / base, 1.0 - clamp, 1.0 + clamp);
    }

    /**
     * 加权融合三因子归一值并 clamp 到 [qMin,qMax]。权重之和用于归一(不必预先和为 1)。
     * 三因子均为 1.0(平均广告)→ 质量度 1.0(中性,等同旧随机基线中位)。
     */
    static double fuse(double relN, double ctrN, double cvrN,
                       double wRel, double wCtr, double wCvr,
                       double qMin, double qMax) {
        double wSum = wRel + wCtr + wCvr;
        double fused = (wRel * relN + wCtr * ctrN + wCvr * cvrN) / wSum;
        return clamp(fused, qMin, qMax);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
