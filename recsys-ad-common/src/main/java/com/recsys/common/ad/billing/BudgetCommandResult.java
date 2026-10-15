package com.recsys.common.ad.billing;

import java.util.UUID;

/** 命令首次执行后的原始快照；拒绝结果也必须持久化，重试不能改变结论。 */
public record BudgetCommandResult(UUID commandId, Status status, BudgetAccount account) {
    /** 显式稳定 code 用于数据库和接口，禁止以 ordinal 存储。 */
    public enum Status { APPLIED, VERSION_CONFLICT, BELOW_COMMITTED }

    /** 同一幂等键重用于不同语义时拒绝，不能覆盖既有命令。 */
    public static final class PayloadConflict extends RuntimeException {
        public PayloadConflict() {
            super("commandId 已用于不同预算命令");
        }
    }
}
