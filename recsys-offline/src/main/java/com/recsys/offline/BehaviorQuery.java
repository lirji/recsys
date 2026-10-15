package com.recsys.offline;

import org.springframework.boot.ApplicationArguments;

import com.recsys.offline.persistence.model.BehaviorSource;

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
     * 解析 {@code --behavior-table}(#2):行为读的来源表。默认 {@code user_behavior}(直读行为上下文,回滚落点);
     * {@code behavior_log} = 读 offline 自有读仓(DB-per-service)。仅白名单值,防表名注入。
     */
    static String table(ApplicationArguments args) {
        return source(args).code();
    }

    /** 新Mapper只接受封闭来源，旧表名入口随其余作业逐个迁移退出。 */
    static BehaviorSource source(ApplicationArguments args) {
        String t = args.containsOption("behavior-table")
                ? args.getOptionValues("behavior-table").get(0) : "user_behavior";
        return BehaviorSource.fromCode(t);
    }
}
