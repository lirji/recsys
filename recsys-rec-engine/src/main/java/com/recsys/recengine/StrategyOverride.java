package com.recsys.recengine;

import com.recsys.recengine.experiment.ExperimentDecision;

/**
 * 在线调试用的策略覆盖:强制本次请求走指定的 rank/rerank 策略或召回路,绕过按 userId 的实验分桶。
 *
 * <p>仅由 {@code /api/recommend} 的调试参数(rankStrategy/rerankStrategy/recallChannels)构造,
 * 供「策略对比台」在同一 (userId,size,q) 下并排对比不同策略。字段为空 = 该维度不覆盖(仍用实验分桶)。
 * 三项全空时 {@link #of} 返回 null,编排走完全常规链路(热路径零影响)。
 */
public record StrategyOverride(String rankStrategy, String rerankStrategy, String recallChannels) {

    /** 把非空覆盖项写进实验判定(保留各层其它参数)。 */
    public void applyTo(ExperimentDecision decision) {
        if (!blank(rankStrategy)) {
            decision.overrideParam(ExperimentDecision.LAYER_RANK, "strategy", rankStrategy.trim());
        }
        if (!blank(rerankStrategy)) {
            decision.overrideParam(ExperimentDecision.LAYER_RERANK, "strategy", rerankStrategy.trim());
        }
        if (!blank(recallChannels)) {
            decision.overrideParam(ExperimentDecision.LAYER_RECALL, "channels", recallChannels.trim());
        }
    }

    /** 工厂:三项都空 → null(表示无覆盖),否则返回覆盖对象。 */
    public static StrategyOverride of(String rankStrategy, String rerankStrategy, String recallChannels) {
        if (blank(rankStrategy) && blank(rerankStrategy) && blank(recallChannels)) {
            return null;
        }
        return new StrategyOverride(rankStrategy, rerankStrategy, recallChannels);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
