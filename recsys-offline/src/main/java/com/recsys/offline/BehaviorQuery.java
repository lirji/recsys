package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

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
        return args.containsOption("max-ts") ? JobArguments.longInteger(args, "max-ts", 0) : null;
    }

    /** 只解析封闭来源枚举，业务调用方不再取得可拼接的表名。 */
    static BehaviorSource source(ApplicationArguments args) {
        String value = JobArguments.text(args, "behavior-table", "user_behavior");
        try {
            return BehaviorSource.fromCode(value);
        } catch (IllegalArgumentException invalid) {
            // 枚举保留内部校验语义，CLI只公开固定目录，不反射用户输入。
            throw new PublicErrorException(ErrorCode.JOB_BEHAVIOR_SOURCE_INVALID, invalid);
        }
    }
}
