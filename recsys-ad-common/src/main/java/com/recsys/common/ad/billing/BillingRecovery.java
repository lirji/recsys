package com.recsys.common.ad.billing;

import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** 恢复只续办既有义务和原事件；每个稳定命令保存一个不可变目标结果。 */
public final class BillingRecovery {
    public static final int REPUBLISH_WAIT_SECONDS = 60;
    public enum Action { REPUBLISH, EXPIRE }
    public enum Status { REQUEUED, ALREADY_APPLIED, NOT_ELIGIBLE, NEEDS_REVIEW, EXPIRED, ALREADY_TERMINAL, NOT_FOUND }
    private BillingRecovery() { }

    /** 精确范围和目标来自受控操作，actor只能由可信调用方提供。 */
    public record Command(UUID commandId, long advertiserId, LocalDate budgetDate, ChargeMode family,
                          Action action, UUID targetId, String reason, String actor) {
        public Command {
            ReconciliationTask.identity(advertiserId, commandId);
            Objects.requireNonNull(family); Objects.requireNonNull(action); Objects.requireNonNull(targetId);
            // 复用已冻结的身份、日期及审计文本边界，不另建宽松恢复入口。
            new ReconciliationTask.Create(advertiserId, commandId, budgetDate, budgetDate, reason, actor);
        }
        /** actor只保留首次审计，不能用改actor使同一命令产生第二次业务效果。 */
        public boolean sameIntent(Command other) {
            return commandId.equals(other.commandId) && advertiserId == other.advertiserId && budgetDate.equals(other.budgetDate)
                    && family == other.family && action == other.action && targetId.equals(other.targetId) && reason.equals(other.reason);
        }
    }

    /** 首次事务的观察结果保持原样，后续当前状态由独立核查取得。 */
    public record Result(Command command, Status status, Instant createdAt, Instant completedAt) { }
}
