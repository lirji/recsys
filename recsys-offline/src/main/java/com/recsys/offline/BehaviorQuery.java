package com.recsys.offline;

import org.springframework.boot.ApplicationArguments;

/**
 * 行为查询的时间切分工具——支撑**严格离线评估**(strict eval)。
 *
 * <p>背景:i2i / swing / u2u / hot / user-embedding 这些召回信号默认用**全量** user_behavior 聚合,
 * 含评估留出期(ts &gt; splitTs)的行为 → 在线 eval 复用这些存储时指标偏乐观(docs/04 §9 #4)。
 * 传 {@code --max-ts=<epochSeconds>} 让这些作业只用 {@code ts <= cutoff} 的行为聚合,
 * 重建出"无未来泄漏"的存储后再跑 eval,即得严格(可信)指标。
 *
 * <p>口径与 {@link AsOfFeatureBuilder}(排序样本的 as-of)一致:都是"只用切分点之前的信息"。
 */
final class BehaviorQuery {

    private BehaviorQuery() {
    }

    /** 解析 {@code --max-ts}(epoch 秒);未传返回 null(= 全量,默认行为)。 */
    static Long maxTs(ApplicationArguments args) {
        return args.containsOption("max-ts")
                ? Long.valueOf(args.getOptionValues("max-ts").get(0))
                : null;
    }

    /**
     * 正反馈查询 SQL:{@code action∈{CLICK,LIKE,PLAY}} 或 {@code RATING≥minRating};
     * maxTs 非空时追加 {@code AND extract(epoch from ts) <= ?} 时间切分。
     * 占位符顺序与 {@link #params} 一致:先 minRating,后(可选)maxTs。
     */
    static String positiveFeedbackSql(String selectCols, Long maxTs) {
        return "SELECT " + selectCols + " FROM user_behavior "
                + "WHERE (action IN ('CLICK','LIKE','PLAY') OR (action='RATING' AND value >= ?))"
                + (maxTs != null ? " AND extract(epoch from ts) <= ?" : "");
    }

    /** 与 {@link #positiveFeedbackSql} 配套的参数数组。 */
    static Object[] params(double minRating, Long maxTs) {
        return maxTs != null ? new Object[]{minRating, maxTs} : new Object[]{minRating};
    }
}
