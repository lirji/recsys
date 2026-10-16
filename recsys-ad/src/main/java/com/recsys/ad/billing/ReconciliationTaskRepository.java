package com.recsys.ad.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.persistence.mapper.addb.ReconciliationTaskMapper;
import com.recsys.common.ad.billing.BillingReconciliation.Snapshot;
import com.recsys.common.ad.billing.ReconciliationTask;
import com.recsys.common.ad.billing.ReconciliationTask.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 任务持久层只修改任务元数据和原报告；不会更新资金账本或触发事件重发。 */
public class ReconciliationTaskRepository {
    public static final String MIGRATION = "db/ad-billing/V017__reconciliation_tasks.sql";
    private final ReconciliationTaskMapper mapper;
    private final DataSource dataSource;
    private final ObjectMapper json;

    /** 会话工厂提供实际事务资源，禁止不同连接池伪装同库事务。 */
    public ReconciliationTaskRepository(SqlSessionTemplate sessions, ObjectMapper json) {
        this.mapper = sessions.getMapper(ReconciliationTaskMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        this.json = json.copy();
    }

    /** 用资源身份校验任务事务，不能仅比较连接字符串。 */
    public DataSource dataSource() { return dataSource; }

    /** 默认不开启迁移；启用时同时校验摘要及真实列结构。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("对账任务迁移缺失或漂移");
        }
        mapper.verifySchema();
    }

    boolean insert(Create command) { return mapper.insert(command) == 1; }
    Optional<Task> find(long advertiserId, UUID taskId, boolean lock) {
        return mapper.find(advertiserId, taskId, lock).stream().findFirst();
    }
    Instant now() { return mapper.now().toInstant(); }
    boolean claim(Task task) { return mapper.claim(task, ReconciliationTask.LEASE_SECONDS, ReconciliationTask.WORKER) == 1; }
    boolean exhaust(Task task) { return mapper.exhaust(task, ReconciliationTask.WORKER) == 1; }
    boolean fail(Task task, State state, int delaySeconds) {
        return mapper.fail(task, state, Failure.SNAPSHOT_FAILED, delaySeconds, ReconciliationTask.WORKER) == 1;
    }
    boolean complete(Task task) { return mapper.complete(task, ReconciliationTask.WORKER) == 1; }
    void cancel(Task task, Cancel command) { requireOne(mapper.cancel(task, command)); }
    void audit(Task task, Event event) {
        requireOne(mapper.audit(task.taskId(), event, task.nextDate(), task.attempts()));
    }

    /** 序列化在回写事务前完成，避免持有任务锁时处理大对象。 */
    String encode(Snapshot snapshot) {
        try { return json.writeValueAsString(snapshot); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("对账报告编码失败", ex); }
    }

    /** 由用例与检查点共同提交；唯一键冲突必须回滚，不能忽略插入失败。 */
    void insertDay(Task task, Snapshot snapshot, String report) {
        requireOne(mapper.insertDay(task, report, ReconciliationTask.REPORT_VERSION, snapshot.observedAt().atOffset(ZoneOffset.UTC)));
    }

    /** 存储损坏不能变成空结果；同时校验外层范围和内层报告身份。 */
    List<Day> days(long advertiserId, UUID taskId) {
        return mapper.days(advertiserId, taskId).stream().map(row -> {
            try {
                if (row.reportVersion() != ReconciliationTask.REPORT_VERSION) throw new IllegalStateException("对账报告版本未知");
                Snapshot snapshot = json.readValue(row.reportJson(), Snapshot.class);
                if (snapshot.scope().advertiserId() != advertiserId || !snapshot.scope().budgetDate().equals(row.budgetDate())
                        || !snapshot.observedAt().equals(row.observedAt().toInstant())) {
                    throw new IllegalStateException("对账报告范围或观察时间不一致");
                }
                return new Day(row.budgetDate(), row.fence(), snapshot);
            } catch (JsonProcessingException ex) { throw new IllegalStateException("对账报告不可读取", ex); }
        }).toList();
    }

    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("对账任务状态写入未生效");
    }
}
