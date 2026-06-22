package com.recsys.ad;

import java.util.List;

/**
 * DCO 动态创意优化的**纯函数**多臂老虎机(docs/05 §7 M7):在一个广告的多套创意间用 UCB1 择优。
 * 无 Spring、无 IO,便于单测——同 {@code DelayModel}/{@code ListwiseExternality}/{@code QualityScore} 的角色。
 *
 * <p><b>解决什么</b>:一个广告多套创意(标题/图/落地页),固定用一套会浪费高潜创意;但新创意无历史 CTR
 * (冷启动)。把"广告级"EE({@link ExplorationService})下沉到"创意级":每个创意是一条臂,曝光/点击是
 * 反馈,选 UCB 最高的创意展示——既利用高 CTR 创意,又给新创意探索曝光。
 *
 * <pre>UCB(c) = 经验CTR(c) + coef·sqrt( ln(adTotalImpr + e) / impr(c) )</pre>
 * 未曝光过的创意(impr=0)给 {@code +∞} 强制先探索一轮;加成随曝光增长衰减,最终回归纯 CTR 利用。
 * UCB 而非 Thompson Sampling:确定性、可单测、与现有 EE 一致(TS 为等效替代,见 docs)。
 *
 * <p>退化:无创意 → 返回 {@code null}(调用方退广告默认创意);仅一个创意 → 直接返回它。
 */
final class CreativeBandit {

    private CreativeBandit() {
    }

    /**
     * 一条创意臂的统计。
     *
     * @param creativeId  创意 ID
     * @param impressions 累计曝光
     * @param clicks      累计点击
     */
    record Arm(long creativeId, long impressions, long clicks) {
        double ctr() {
            return impressions > 0 ? (double) clicks / impressions : 0.0;
        }
    }

    /**
     * 用 UCB1 选一条创意臂。
     *
     * @param arms 候选创意(非空);全部 impr=0(全新)→ 选第一条(此时各臂 UCB 同为 +∞,取稳定序首条)
     * @param coef UCB 探索系数(越大越激进)
     * @return 选中的 creativeId;arms 为空 → null
     */
    static Long select(List<Arm> arms, double coef) {
        if (arms == null || arms.isEmpty()) {
            return null;
        }
        long totalImpr = 0;
        for (Arm a : arms) {
            totalImpr += a.impressions();
        }
        double logTotal = Math.log(totalImpr + Math.E);

        Long best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Arm a : arms) {
            double score = a.impressions() == 0
                    ? Double.POSITIVE_INFINITY                              // 未探索 → 强制先试
                    : a.ctr() + coef * Math.sqrt(logTotal / a.impressions());
            if (score > bestScore) {
                bestScore = score;
                best = a.creativeId();
            }
        }
        return best;
    }
}
