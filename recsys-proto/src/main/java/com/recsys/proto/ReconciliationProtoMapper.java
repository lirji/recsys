package com.recsys.proto;

import com.google.protobuf.Timestamp;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.proto.reconciliation.v1.*;
import java.time.Instant;
import java.util.*;

/** 对账边界保留精确金额、缺失账户及不可变任务结果，拒绝未知版本和不一致的派生状态。 */
public final class ReconciliationProtoMapper {
    private static final int VERSION = 1;
    private ReconciliationProtoMapper() { }

    /** 原预算日不能根据调用时区重新计算。 */
    public static ReconciliationScope toProto(Scope scope) {
        return ReconciliationScope.newBuilder().setAdvertiserId(scope.advertiserId()).setBudgetDate(scope.budgetDate().toString()).build();
    }
    /** 所有请求及响应都检查相同的账户日范围。 */
    public static Scope fromProto(ReconciliationScope scope) {
        return new Scope(scope.getAdvertiserId(), BudgetCommand.parseDate(scope.getBudgetDate()));
    }
    /** 缺失账户时不填金额差额，避免将未知误报为账平。 */
    public static ReconciliationSnapshot toProto(Snapshot snapshot) {
        var b = ReconciliationSnapshot.newBuilder().setSchemaVersion(VERSION).setTrafficClass(BudgetAccount.TRAFFIC_CLASS)
                .setCurrency(CnyAmount.CURRENCY).setScope(toProto(snapshot.scope())).setObservedAt(time(snapshot.observedAt()))
                .setFundsStatus(snapshot.fundsStatus().name());
        if (snapshot.account() != null) b.setAccount(BudgetProtoMapper.toProto(snapshot.account()))
                .setHeldDelta(snapshot.heldDelta()).setChargedDelta(snapshot.chargedDelta());
        snapshot.families().forEach(f -> b.addFamilies(toProto(f)));
        return b.build();
    }
    /** 双向核对资金差额、完整三族及投影状态；金额不能被protobuf默认值补成零。 */
    public static Snapshot fromProto(ReconciliationSnapshot reply) {
        envelope(reply.getSchemaVersion(), reply.getTrafficClass(), reply.getCurrency());
        require(reply.hasScope() && reply.hasObservedAt(), "对账范围或观察时间缺失");
        BudgetAccount account = null;
        if (reply.hasAccount()) {
            var a = reply.getAccount();
            for (var amount : List.of(a.getLimitAmount(), a.getHeldAmount(), a.getChargedAmount(), a.getAvailableAmount())) six(amount);
            account = BudgetProtoMapper.fromProto(a);
        }
        var result = new Snapshot(fromProto(reply.getScope()), instant(reply.getObservedAt()), account,
                reply.getFamiliesList().stream().map(ReconciliationProtoMapper::fromProto).toList());
        require(reply.hasHeldDelta() == (account != null) && reply.hasChargedDelta() == (account != null), "对账差额存在性不一致");
        require(result.fundsStatus().name().equals(reply.getFundsStatus()), "对账资金状态不一致");
        if (account != null) require(result.heldDelta().equals(reply.getHeldDelta()) && result.chargedDelta().equals(reply.getChargedDelta()), "对账资金差额不一致");
        return result;
    }

    private static ReconciliationFamily toProto(FamilySummary f) {
        var b = ReconciliationFamily.newBuilder().setFamily(f.family().name()).setHeldCount(f.heldCount()).setHeldAmount(f.heldAmount())
                .setExpiredHeldCount(f.expiredHeldCount()).setLedgerCount(f.ledgerCount()).setLedgerAmount(f.ledgerAmount())
                .setMissingLedgerCount(f.missingLedgerCount()).setInvalidLedgerCount(f.invalidLedgerCount())
                .setPendingOutboxCount(f.pendingOutboxCount()).setDeadOutboxCount(f.deadOutboxCount()).setMissingOutboxCount(f.missingOutboxCount())
                .setFactCount(f.factCount()).setFactAmount(f.factAmount()).setMissingFactCount(f.missingFactCount())
                .setInvalidFactCount(f.invalidFactCount()).setUnexpectedFactCount(f.unexpectedFactCount())
                .setSummaryPresent(f.summaryPresent()).setSummaryCount(f.summaryCount()).setSummaryAmount(f.summaryAmount())
                .setProjectionStatus(f.projectionStatus().name());
        if (f.oldestPendingAt() != null) b.setOldestPendingAt(time(f.oldestPendingAt()));
        if (f.lastAppliedAt() != null) b.setLastAppliedAt(time(f.lastAppliedAt()));
        return b.build();
    }
    private static FamilySummary fromProto(ReconciliationFamily f) {
        var result = new FamilySummary(ChargeMode.valueOf(f.getFamily()), f.getHeldCount(), f.getHeldAmount(), f.getExpiredHeldCount(),
                f.getLedgerCount(), f.getLedgerAmount(), f.getMissingLedgerCount(), f.getInvalidLedgerCount(),
                f.getPendingOutboxCount(), f.getDeadOutboxCount(), f.getMissingOutboxCount(), f.hasOldestPendingAt() ? instant(f.getOldestPendingAt()) : null,
                f.getFactCount(), f.getFactAmount(), f.getMissingFactCount(), f.getInvalidFactCount(), f.getUnexpectedFactCount(),
                f.hasLastAppliedAt() ? instant(f.getLastAppliedAt()) : null, f.getSummaryPresent(), f.getSummaryCount(), f.getSummaryAmount());
        require(result.projectionStatus().name().equals(f.getProjectionStatus()), "对账投影状态不一致");
        return result;
    }

    /** 任务标识始终绑定广告主，避免把UUID当作权限。 */
    public static ReconciliationTaskIdentity task(long advertiserId, UUID taskId) {
        ReconciliationTask.identity(advertiserId, taskId);
        return ReconciliationTaskIdentity.newBuilder().setAdvertiserId(advertiserId).setTaskId(taskId.toString()).build();
    }
    /** 创建意图的actor来自可信offline边界，不来自浏览器。 */
    public static CreateReconciliationTask toProto(ReconciliationTask.Create c) {
        return CreateReconciliationTask.newBuilder().setAdvertiserId(c.advertiserId()).setTaskId(c.taskId().toString())
                .setStartDate(c.startDate().toString()).setEndDate(c.endDate().toString()).setReason(c.reason()).setActor(c.actor()).build();
    }
    /** 日期范围、原因与主体统一由领域规则校验。 */
    public static ReconciliationTask.Create fromProto(CreateReconciliationTask c) {
        return new ReconciliationTask.Create(c.getAdvertiserId(), BudgetCommand.parseId(c.getTaskId()),
                BudgetCommand.parseDate(c.getStartDate()), BudgetCommand.parseDate(c.getEndDate()), c.getReason(), c.getActor());
    }
    /** 取消保留独立稳定命令ID，不撤销已保存报告。 */
    public static CancelReconciliationTask toProto(ReconciliationTask.Cancel c) {
        return CancelReconciliationTask.newBuilder().setTask(task(c.advertiserId(), c.taskId())).setCommandId(c.commandId().toString())
                .setReason(c.reason()).setActor(c.actor()).build();
    }
    /** 未提供任务身份必须失败，不能落到默认账户。 */
    public static ReconciliationTask.Cancel fromProto(CancelReconciliationTask c) {
        require(c.hasTask(), "取消任务身份缺失");
        return new ReconciliationTask.Cancel(c.getTask().getAdvertiserId(), BudgetCommand.parseId(c.getTask().getTaskId()),
                BudgetCommand.parseId(c.getCommandId()), c.getReason(), c.getActor());
    }
    /** COMPLETED只是核查结束，资金是否一致仍由逐日报告决定。 */
    public static ReconciliationTaskReply toProto(ReconciliationTask.Task t) {
        var input = new ReconciliationTask.Create(t.advertiserId(), t.taskId(), t.startDate(), t.endDate(), t.reason(), t.createdBy());
        var b = ReconciliationTaskReply.newBuilder().setSchemaVersion(VERSION).setTrafficClass(BudgetAccount.TRAFFIC_CLASS).setCurrency(CnyAmount.CURRENCY)
                .setInput(toProto(input)).setCreatedAt(time(t.createdAt())).setState(t.state().name()).setNextDate(t.nextDate().toString())
                .setCompletedDays(t.completedDays()).setAttempts(t.attempts()).setVersion(t.version()).setAvailableAt(time(t.availableAt()))
                .setUpdatedBy(t.updatedBy()).setUpdatedAt(time(t.updatedAt()));
        if (t.leaseUntil() != null) b.setLeaseUntil(time(t.leaseUntil()));
        if (t.lastFailure() != null) b.setLastFailure(t.lastFailure().name());
        if (t.cancelCommandId() != null) b.setCancelCommandId(t.cancelCommandId().toString());
        if (t.cancelReason() != null) b.setCancelReason(t.cancelReason());
        var reply = b.build(); fromProto(reply); return reply;
    }
    /** 网络上的任务状态必须满足持久化检查点/租约/取消不变量。 */
    public static ReconciliationTask.Task fromProto(ReconciliationTaskReply t) {
        envelope(t.getSchemaVersion(), t.getTrafficClass(), t.getCurrency());
        require(t.hasInput() && t.hasCreatedAt() && t.hasAvailableAt() && t.hasUpdatedAt(), "对账任务字段缺失");
        var c = fromProto(t.getInput()); var state = ReconciliationTask.State.valueOf(t.getState());
        require(t.getCompletedDays() >= 0 && t.getCompletedDays() <= ReconciliationTask.MAX_DAYS
                && t.getAttempts() >= 0 && t.getAttempts() <= ReconciliationTask.MAX_ATTEMPTS && t.getVersion() >= 0, "对账任务进度越界");
        var next = c.startDate().plusDays(t.getCompletedDays());
        require(next.toString().equals(t.getNextDate()) && !next.isAfter(c.endDate().plusDays(1))
                && (state == ReconciliationTask.State.COMPLETED) == next.equals(c.endDate().plusDays(1)), "对账检查点不一致");
        require((state == ReconciliationTask.State.RUNNING) == t.hasLeaseUntil(), "对账租约不一致");
        require((state == ReconciliationTask.State.CANCELLED) == t.hasCancelCommandId() && t.hasCancelCommandId() == t.hasCancelReason(), "对账取消状态不一致");
        new ReconciliationTask.Create(c.advertiserId(), c.taskId(), c.startDate(), c.endDate(), c.reason(), t.getUpdatedBy());
        var cancelId = t.hasCancelCommandId() ? BudgetCommand.parseId(t.getCancelCommandId()) : null;
        if (cancelId != null) new ReconciliationTask.Cancel(c.advertiserId(), c.taskId(), cancelId, t.getCancelReason(), t.getUpdatedBy());
        return new ReconciliationTask.Task(c.taskId(), c.advertiserId(), c.startDate(), c.endDate(), c.reason(), c.actor(), instant(t.getCreatedAt()), state,
                next, t.getCompletedDays(), t.getAttempts(), t.getVersion(), instant(t.getAvailableAt()), t.hasLeaseUntil() ? instant(t.getLeaseUntil()) : null,
                t.hasLastFailure() ? ReconciliationTask.Failure.valueOf(t.getLastFailure()) : null, t.getUpdatedBy(), instant(t.getUpdatedAt()),
                cancelId, t.hasCancelReason() ? t.getCancelReason() : null);
    }
    /** 日结果有界且携带范围；不会用新快照覆盖原观察结果。 */
    public static ReconciliationDaysReply days(long advertiserId, UUID taskId, List<ReconciliationTask.Day> days) {
        var b = ReconciliationDaysReply.newBuilder().setTask(task(advertiserId, taskId));
        days.forEach(d -> b.addDays(ReconciliationDay.newBuilder().setBudgetDate(d.budgetDate().toString()).setFence(d.fence()).setSnapshot(toProto(d.snapshot()))));
        var result = b.build(); fromProto(result); return result;
    }
    /** 拒绝跨广告主、重复/乱序日期及没有快照的报告。 */
    public static List<ReconciliationTask.Day> fromProto(ReconciliationDaysReply reply) {
        require(reply.hasTask() && reply.getDaysCount() <= ReconciliationTask.MAX_DAYS, "对账日结果范围缺失或超界");
        ReconciliationTask.identity(reply.getTask().getAdvertiserId(), BudgetCommand.parseId(reply.getTask().getTaskId()));
        var result = new ArrayList<ReconciliationTask.Day>();
        for (var day : reply.getDaysList()) {
            require(day.hasSnapshot() && day.getFence() > 0, "对账日证据缺失");
            var snapshot = fromProto(day.getSnapshot()); var date = BudgetCommand.parseDate(day.getBudgetDate());
            require(snapshot.scope().advertiserId() == reply.getTask().getAdvertiserId() && date.equals(snapshot.scope().budgetDate())
                    && (result.isEmpty() || date.equals(result.getLast().budgetDate().plusDays(1))), "对账日范围或顺序不一致");
            result.add(new ReconciliationTask.Day(date, day.getFence(), snapshot));
        }
        return List.copyOf(result);
    }

    /** 恢复只传明确原目标，绝不传新收费金额。 */
    public static BillingRecoveryCommand toProto(BillingRecovery.Command c) {
        return BillingRecoveryCommand.newBuilder().setCommandId(c.commandId().toString()).setScope(toProto(new Scope(c.advertiserId(), c.budgetDate())))
                .setFamily(c.family().name()).setAction(c.action().name()).setTargetId(c.targetId().toString()).setReason(c.reason()).setActor(c.actor()).build();
    }
    /** 未知资金族/动作不能默认为另一个可写分支。 */
    public static BillingRecovery.Command fromProto(BillingRecoveryCommand c) {
        require(c.hasScope(), "恢复范围缺失"); var scope = fromProto(c.getScope());
        return new BillingRecovery.Command(BudgetCommand.parseId(c.getCommandId()), scope.advertiserId(), scope.budgetDate(), ChargeMode.valueOf(c.getFamily()),
                BillingRecovery.Action.valueOf(c.getAction()), BudgetCommand.parseId(c.getTargetId()), c.getReason(), c.getActor());
    }
    /** 保存首次决定时间及输入；重新查询不会覆盖历史结果。 */
    public static BillingRecoveryReply toProto(BillingRecovery.Result r) {
        var reply = BillingRecoveryReply.newBuilder().setSchemaVersion(VERSION).setTrafficClass(BudgetAccount.TRAFFIC_CLASS).setCurrency(CnyAmount.CURRENCY)
                .setCommand(toProto(r.command())).setStatus(r.status().name()).setCreatedAt(time(r.createdAt())).setCompletedAt(time(r.completedAt())).build();
        fromProto(reply); return reply;
    }
    /** 结果状态必须属于对应动作，PENDING不能作为提交成功结果暴露。 */
    public static BillingRecovery.Result fromProto(BillingRecoveryReply r) {
        envelope(r.getSchemaVersion(), r.getTrafficClass(), r.getCurrency());
        require(r.hasCommand() && r.hasCreatedAt() && r.hasCompletedAt(), "恢复结果缺失");
        var c = fromProto(r.getCommand()); var status = BillingRecovery.Status.valueOf(r.getStatus());
        boolean expireStatus = status == BillingRecovery.Status.EXPIRED || status == BillingRecovery.Status.ALREADY_TERMINAL;
        boolean publishStatus = status == BillingRecovery.Status.REQUEUED || status == BillingRecovery.Status.ALREADY_APPLIED || status == BillingRecovery.Status.NEEDS_REVIEW;
        require(!(c.action() == BillingRecovery.Action.REPUBLISH && expireStatus) && !(c.action() == BillingRecovery.Action.EXPIRE && publishStatus), "恢复动作与结果不一致");
        return new BillingRecovery.Result(c, status, instant(r.getCreatedAt()), instant(r.getCompletedAt()));
    }

    private static void envelope(int version, String traffic, String currency) {
        require(version == VERSION && BudgetAccount.TRAFFIC_CLASS.equals(traffic) && CnyAmount.CURRENCY.equals(currency), "不支持的对账版本、来源或币种");
    }
    private static void six(String amount) { require(amount.matches("(?:0|[1-9][0-9]*)\\.[0-9]{6}"), "对账账户金额不是六位精确小数"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static Timestamp time(Instant value) {
        var result = Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build(); instant(result); return result;
    }
    private static Instant instant(Timestamp value) {
        require(value.getSeconds() >= -62135596800L && value.getSeconds() <= 253402300799L && value.getNanos() >= 0 && value.getNanos() <= 999999999, "对账时间超界");
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }
}
