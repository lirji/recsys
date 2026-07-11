package com.recsys.common.dto;

import java.util.List;
import java.util.Map;

/**
 * 推荐链路 explain(仅在请求带 {@code explain=true} 时由编排层填充,否则整体为 null)。
 *
 * <p>把 {@code RecommendOrchestrator} 内部本已算出、平时丢弃的中间量真实暴露出来,替代前端从最终列表
 * 反推的伪造漏斗计数:逐阶段候选 in/out、去重前每路原始召回数、去重后每路对候选池的贡献数、每条打分分解。
 *
 * @param stages              有序阶段计数(recall→filter→preRank→rank→fusion→rerank)
 * @param channelRecall       去重前每路原始召回数(来自 MultiChannelRecallService 的 perChannel 真值)
 * @param channelContribution 去重后每路对候选池的贡献数(同一 item 命中多路则各路都计一次)
 * @param scores              itemId → 打分分解(与最终 items 对齐)
 */
public record RecommendExplain(
        List<Stage> stages,
        List<ChannelRecall> channelRecall,
        List<ChannelContribution> channelContribution,
        Map<Long, ScoreBreakdown> scores) {

    /** 单个漏斗阶段的进/出候选数。 */
    public record Stage(String name, int in, int out) {
    }

    /** 去重前某召回路的原始召回条数。 */
    public record ChannelRecall(String channel, int rawCount) {
    }

    /** 去重后某召回路对候选池的贡献条数。 */
    public record ChannelContribution(String channel, int count) {
    }

    /**
     * 单条候选的融合打分分解(与 {@code RecommendOrchestrator} 融合环逐项对应)。
     * {@code base = recallWeight·rNorm + rankWeight·rankScore + ftrl + bandit};
     * {@code finalScore = base · boost · persBoost · debias}。
     */
    public record ScoreBreakdown(
            double rNorm,
            double rankScore,
            double ftrl,
            double bandit,
            double base,
            double boost,
            double persBoost,
            double debias,
            double finalScore) {
    }
}
