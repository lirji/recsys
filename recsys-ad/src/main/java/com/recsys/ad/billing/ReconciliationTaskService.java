package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BillingReconciliation.Scope;
import com.recsys.common.ad.billing.BillingReconciliation.Snapshot;
import com.recsys.common.ad.billing.ReconciliationTask;
import com.recsys.common.ad.billing.ReconciliationTask.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 领取、只读核查、回写分开，防止慢核查长时间持有任务锁或阻塞正常资金请求。 */
public class ReconciliationTaskService {
    private final ReconciliationTaskRepository repository;
    private final ReconciliationService snapshots;
    private final TransactionTemplate transactions;

    /** 强制同owner资源及独立短事务，不允许与请求外层事务共享锁生命周期。 */
    public ReconciliationTaskService(ReconciliationTaskRepository repository, ReconciliationService snapshots,
                                     TransactionTemplate transactions) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != repository.dataSource() || snapshots.dataSource() != repository.dataSource()
                || transactions.isReadOnly() || transactions.getIsolationLevel() != TransactionDefinition.ISOLATION_READ_COMMITTED
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW
                || transactions.getTimeout() != ReconciliationTask.TRANSACTION_SECONDS) {
            throw new IllegalArgumentException("对账任务必须使用同adDb的独立五秒读写事务");
        }
        this.repository = repository; this.snapshots = snapshots; this.transactions = transactions;
    }

    /** 双唯一约束阻止并发活跃任务；同命令重试返回已持久化状态。 */
    public Task create(Create command) {
        noOuterTransaction();
        return transactions.execute(tx -> {
            boolean inserted = repository.insert(command);
            Task task = repository.find(command.advertiserId(), command.taskId(), false).orElseThrow(Conflict::new);
            if (!task.startDate().equals(command.startDate()) || !task.endDate().equals(command.endDate())
                    || !task.reason().equals(command.reason())) throw new Conflict();
            if (inserted) repository.audit(task, Event.CREATED);
            return task;
        });
    }

    /** 一次只处理一个日期；数据库响应未知直接失败，调用方随后查原任务恢复。 */
    public Task advance(long advertiserId, UUID taskId) {
        noOuterTransaction(); ReconciliationTask.identity(advertiserId, taskId);
        Optional<Task> claim = claim(advertiserId, taskId);
        if (claim.isEmpty()) return find(advertiserId, taskId);
        Task task = claim.get();
        Snapshot snapshot;
        String report;
        try {
            snapshot = snapshots.inspect(new Scope(advertiserId, task.nextDate()));
            report = repository.encode(snapshot);
        } catch (RuntimeException ex) {
            // 仅存固定错误类型，原异常不得写进任务表或将依赖不可用伪装成空报告。
            fail(task);
            return find(advertiserId, taskId);
        }
        complete(task, snapshot, report);
        return find(advertiserId, taskId);
    }

    /** 先持有任务行锁再读DB时间；重新领取也计入原日期的尝试预算。 */
    Optional<Task> claim(long advertiserId, UUID taskId) {
        noOuterTransaction();
        return transactions.execute(tx -> {
            Task task = locked(advertiserId, taskId);
            Instant now = repository.now();
            if (task.state() == State.RUNNING && !task.leaseUntil().isAfter(now) && task.attempts() >= ReconciliationTask.MAX_ATTEMPTS) {
                if (repository.exhaust(task)) repository.audit(task, Event.FAILED);
                return Optional.empty();
            }
            if (!repository.claim(task)) return Optional.empty();
            Task claimed = repository.find(advertiserId, taskId, false).orElseThrow();
            repository.audit(claimed, Event.CLAIMED);
            return Optional.of(claimed);
        });
    }

    /** 有效fence才能保存；更新检查点、插入报告及审计任一步失败都回滚。 */
    boolean complete(Task claim, Snapshot snapshot, String report) {
        noOuterTransaction();
        if (snapshot.scope().advertiserId() != claim.advertiserId() || !snapshot.scope().budgetDate().equals(claim.nextDate())) {
            throw new IllegalArgumentException("对账执行结果范围不符");
        }
        return transactions.execute(tx -> {
            locked(claim.advertiserId(), claim.taskId());
            if (!repository.complete(claim)) return false;
            repository.insertDay(claim, snapshot, report);
            repository.audit(claim, Event.DAY_SAVED);
            return true;
        });
    }

    /** 失败回写也须有效租约，过期执行器不能覆盖新执行器的错误或成功结果。 */
    void fail(Task claim) {
        noOuterTransaction();
        transactions.executeWithoutResult(tx -> {
            locked(claim.advertiserId(), claim.taskId());
            State state = claim.attempts() >= ReconciliationTask.MAX_ATTEMPTS ? State.FAILED : State.READY;
            int base = Math.min(ReconciliationTask.MAX_BACKOFF_SECONDS, 1 << Math.min(claim.attempts() - 1, 9));
            int delay = Math.min(ReconciliationTask.MAX_BACKOFF_SECONDS, base + ThreadLocalRandom.current().nextInt(base / 5 + 1));
            if (repository.fail(claim, state, delay)) repository.audit(claim, state == State.FAILED ? Event.FAILED : Event.RETRY_SCHEDULED);
        });
    }

    /** 取消保留所有原报告；重复取消只认原命令与原因，actor不能改写原审计。 */
    public Task cancel(Cancel command) {
        noOuterTransaction();
        return transactions.execute(tx -> {
            Task task = locked(command.advertiserId(), command.taskId());
            if (task.state() == State.CANCELLED && command.commandId().equals(task.cancelCommandId())
                    && command.reason().equals(task.cancelReason())) return task;
            if (task.state() != State.READY && task.state() != State.RUNNING) throw new Conflict();
            repository.cancel(task, command); repository.audit(task, Event.CANCELLED);
            return repository.find(command.advertiserId(), command.taskId(), false).orElseThrow();
        });
    }

    /** 不存在和其他广告主统一返回NotFound，查询错误继续传播未知。 */
    public Task find(long advertiserId, UUID taskId) {
        noOuterTransaction(); ReconciliationTask.identity(advertiserId, taskId);
        return repository.find(advertiserId, taskId, false).orElseThrow(NotFound::new);
    }

    /** 最多31日，报告按预算日排序；运行中的任务可读取已提交部分。 */
    public List<Day> days(long advertiserId, UUID taskId) {
        find(advertiserId, taskId);
        return repository.days(advertiserId, taskId);
    }

    private Task locked(long advertiserId, UUID taskId) {
        return repository.find(advertiserId, taskId, true).orElseThrow(NotFound::new);
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("对账任务不能加入外层事务");
    }
}
