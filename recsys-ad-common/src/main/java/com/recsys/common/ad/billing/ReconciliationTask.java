package com.recsys.common.ad.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 有界对账任务只保存核查证据；完成不表示账平，取消不撤销已有资金效果。 */
public final class ReconciliationTask {
    public static final int MAX_DAYS = 31;
    public static final int MAX_ATTEMPTS = 20;
    public static final int LEASE_SECONDS = 30;
    public static final int TRANSACTION_SECONDS = 5;
    public static final int MAX_BACKOFF_SECONDS = 300;
    public static final int REPORT_VERSION = 1;
    public static final String WORKER = "SYSTEM_RECONCILIATION";

    private ReconciliationTask() { }

    public enum State { READY, RUNNING, COMPLETED, FAILED, CANCELLED }
    public enum Failure { SNAPSHOT_FAILED, LEASE_EXHAUSTED }
    public enum Event { CREATED, CLAIMED, DAY_SAVED, RETRY_SCHEDULED, FAILED, CANCELLED }

    /** actor来自可信调用边界，只审计首次提交；重试不能用改actor覆盖原命令。 */
    public record Create(long advertiserId, UUID taskId, LocalDate startDate, LocalDate endDate,
                         String reason, String actor) {
        public Create {
            identity(advertiserId, taskId);
            Objects.requireNonNull(startDate); Objects.requireNonNull(endDate);
            BudgetCommand.parseDate(startDate.toString()); BudgetCommand.parseDate(endDate.toString());
            long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("对账范围须为1至31个原预算日");
            text(reason, 256); text(actor, 128);
        }
    }

    /** 同一取消命令和原因可重试，终态与不同命令不能被后来的请求覆盖。 */
    public record Cancel(long advertiserId, UUID taskId, UUID commandId, String reason, String actor) {
        public Cancel {
            identity(advertiserId, taskId); Objects.requireNonNull(commandId);
            text(reason, 256); text(actor, 128);
        }
    }

    /** nextDate是下一未完成日期；COMPLETED时为endDate后一日，部分结果始终保留。 */
    public record Task(UUID taskId, long advertiserId, LocalDate startDate, LocalDate endDate,
                       String reason, String createdBy, Instant createdAt, State state,
                       LocalDate nextDate, int completedDays, int attempts, long version,
                       Instant availableAt, Instant leaseUntil, Failure lastFailure,
                       String updatedBy, Instant updatedAt, UUID cancelCommandId, String cancelReason) { }

    /** 日结果不可变，独立observedAt不代表整个日期范围共享同一MVCC快照。 */
    public record Day(LocalDate budgetDate, long fence, BillingReconciliation.Snapshot snapshot) { }

    /** 仅内部业务冲突，后续协议适配必须映射统一安全错误码。 */
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("对账任务命令内容或当前状态冲突"); }
    }

    /** 查询对其他广告主同样返回不存在，避免泄露任务归属。 */
    public static final class NotFound extends RuntimeException {
        public NotFound() { super("对账任务不存在"); }
    }

    /** 所有任务入口统一校验账户与命令标识，避免遗漏租户范围。 */
    public static void identity(long advertiserId, UUID taskId) {
        if (advertiserId <= 0 || taskId == null) throw new IllegalArgumentException("对账任务标识非法");
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("对账原因或主体格式非法");
        }
    }
}
