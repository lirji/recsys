package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.billing.ReconciliationTask;
import com.recsys.common.ad.billing.ReconciliationTask.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 每例独占真实PG schema，验证提交窗口、租约接管与跨账户边界，不用Mock证明事务。 */
@Tag("money-chain")
@Testcontainers
class ReconciliationTaskPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    ConversionEventsFixture f;
    ReconciliationTaskRepository repository;
    ReconciliationService snapshots;
    ReconciliationTaskService service;

    @BeforeEach void setup() throws Exception {
        f = new ConversionEventsFixture(PG);
        for (var path : List.of(PreparationRepository.CONVERSION_MIGRATION, ReconciliationRepository.MIGRATION, ReconciliationTaskRepository.MIGRATION)) {
            try (var in = new ClassPathResource(path).getInputStream(); var c = f.ds.getConnection(); var st = c.createStatement()) {
                st.execute("SET recsys.migration_sha256='" + BudgetRepository.migrationSha256(path) + "'");
                st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        repository = new ReconciliationTaskRepository(f.cpm.sessions, ConversionEventsFixture.JSON);
        repository.verifySchema();
        snapshots = new ReconciliationService(new ReconciliationRepository(f.cpm.sessions), ReconciliationConfiguration.transactions(f.ds));
        service = fresh(repository, snapshots);
    }

    ReconciliationTaskService fresh(ReconciliationTaskRepository repo, ReconciliationService source) {
        return new ReconciliationTaskService(repo, source, BudgetConfiguration.transactions(f.ds, ReconciliationTask.TRANSACTION_SECONDS));
    }
    Create command(long advertiser, int days) {
        return new Create(advertiser, UUID.randomUUID(), f.cpm.today().minusDays(days - 1), f.cpm.today(), "核查演练", "operator");
    }
    void expire(UUID task) {
        f.jdbc.update("UPDATE ad_reconciliation_task SET lease_until=clock_timestamp()-interval '1 second' WHERE task_id=?", task);
    }
    void due(UUID task) {
        f.jdbc.update("UPDATE ad_reconciliation_task SET available_at=clock_timestamp()-interval '1 second' WHERE task_id=?", task);
    }

    @Test void persistentDailyProgressSurvivesNewServiceAndCompletionDoesNotPromiseBalancedFunds() {
        f.capture(1, "0.123456");
        var input = command(1, 2);
        service.create(input);
        var first = service.advance(1, input.taskId());
        assertEquals(State.READY, first.state()); assertEquals(1, first.completedDays());
        assertEquals(FundsStatus.ACCOUNT_MISSING, service.days(1, input.taskId()).getFirst().snapshot().fundsStatus());
        var restarted = fresh(new ReconciliationTaskRepository(f.cpm.sessions, ConversionEventsFixture.JSON), snapshots);
        var completed = restarted.advance(1, input.taskId());
        assertEquals(State.COMPLETED, completed.state()); assertEquals(2, completed.completedDays());
        assertEquals(input.endDate().plusDays(1), completed.nextDate());
        assertEquals(0, completed.attempts());
        var days = restarted.days(1, input.taskId());
        assertEquals(List.of(input.startDate(), input.endDate()), days.stream().map(Day::budgetDate).toList());
        assertEquals("0.000000", days.getLast().snapshot().chargedDelta());
        assertTrue(days.getLast().snapshot().observedAt().isAfter(days.getFirst().snapshot().observedAt()));
        assertEquals(completed, restarted.advance(1, input.taskId())); // 提交后丢响应只能查回原结果，不能重写报告。
        assertEquals(5L, f.jdbc.queryForObject("SELECT count(*) FROM ad_reconciliation_audit", Long.class));
        assertThrows(Conflict.class, () -> restarted.cancel(new Cancel(1, input.taskId(), UUID.randomUUID(), "取消", "operator")));
    }

    @Test void duplicateInputIsIdempotentWhileDifferentContentAndActiveTaskConflict() {
        var input = command(1, 1); var original = service.create(input);
        assertEquals(original, service.create(new Create(1, input.taskId(), input.startDate(), input.endDate(), input.reason(), "another-operator")));
        assertThrows(Conflict.class, () -> service.create(new Create(1, input.taskId(), input.startDate(), input.endDate(), "改原因", "operator")));
        assertThrows(Conflict.class, () -> service.create(command(1, 1)));
        assertThrows(Conflict.class, () -> service.create(new Create(2, input.taskId(), input.startDate(), input.endDate(), input.reason(), "operator")));
        assertThrows(NotFound.class, () -> service.find(2, input.taskId()));
        assertThrows(NotFound.class, () -> service.days(2, input.taskId()));
        assertEquals(State.READY, service.create(command(2, 1)).state());
        assertEquals(2L, f.jdbc.queryForObject("SELECT count(*) FROM ad_reconciliation_audit", Long.class));
    }

    @Test void concurrentCreateAndClaimHaveSingleWinnerWithoutBlockingAnotherAdvertiser() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Task>>();
            for (int i = 0; i < 6; i++) tasks.add(pool.submit(() -> {
                gate.await();
                try { return service.create(command(1, 1)); } catch (Conflict expected) { return null; }
            }));
            gate.countDown(); var winners = new ArrayList<Task>();
            for (var task : tasks) { var value = task.get(15, TimeUnit.SECONDS); if (value != null) winners.add(value); }
            assertEquals(1, winners.size());
            var id = winners.getFirst().taskId();
            var claims = new ArrayList<Future<Optional<Task>>>();
            for (int i = 0; i < 6; i++) claims.add(pool.submit(() -> service.claim(1, id)));
            int count = 0;
            for (var claim : claims) if (claim.get(15, TimeUnit.SECONDS).isPresent()) count++;
            assertEquals(1, count); assertEquals(1, service.find(1, id).attempts());
            var other = service.create(command(2, 1));
            assertEquals(State.COMPLETED, service.advance(2, other.taskId()).state());
        }
    }

    @Test void expiredAndSupersededExecutorsCannotWriteOrOverwriteNewResult() {
        var task = service.create(command(1, 1));
        var old = service.claim(1, task.taskId()).orElseThrow();
        var snapshot = snapshots.inspect(new Scope(1, old.nextDate()));
        expire(task.taskId());
        assertFalse(service.complete(old, snapshot, repository.encode(snapshot)));
        var current = service.claim(1, task.taskId()).orElseThrow();
        assertTrue(current.version() > old.version()); assertEquals(2, current.attempts());
        service.fail(old);
        assertEquals(current, service.find(1, task.taskId()));
        assertFalse(service.complete(old, snapshot, repository.encode(snapshot)));
        assertTrue(service.complete(current, snapshot, repository.encode(snapshot)));
        assertEquals(current.version(), service.days(1, task.taskId()).getFirst().fence());
        assertFalse(service.complete(current, snapshot, repository.encode(snapshot)));
        assertEquals(1, service.days(1, task.taskId()).size());
    }

    @Test void snapshotFailureIsPersistentBoundedAndCannotPretendEmptyHealthyReport() {
        var failing = new ReconciliationService(new ReconciliationRepository(f.cpm.sessions), ReconciliationConfiguration.transactions(f.ds)) {
            @Override public Snapshot inspect(Scope scope) { throw new IllegalStateException("sensitive upstream detail"); }
        };
        var worker = fresh(repository, failing); var task = worker.create(command(1, 1));
        for (int attempt = 1; attempt <= ReconciliationTask.MAX_ATTEMPTS; attempt++) {
            var result = worker.advance(1, task.taskId());
            assertEquals(attempt, result.attempts()); assertEquals(Failure.SNAPSHOT_FAILED, result.lastFailure());
            assertEquals(attempt == ReconciliationTask.MAX_ATTEMPTS ? State.FAILED : State.READY, result.state());
            assertTrue(result.availableAt().isAfter(result.updatedAt()));
            assertTrue(java.time.Duration.between(result.updatedAt(), result.availableAt()).getSeconds() <= ReconciliationTask.MAX_BACKOFF_SECONDS);
            assertTrue(worker.claim(1, task.taskId()).isEmpty());
            due(task.taskId());
        }
        assertTrue(worker.days(1, task.taskId()).isEmpty());
        assertEquals(State.FAILED, fresh(repository, snapshots).advance(1, task.taskId()).state());
        assertEquals(41L, f.jdbc.queryForObject("SELECT count(*) FROM ad_reconciliation_audit", Long.class));
        assertEquals(Failure.SNAPSHOT_FAILED, worker.find(1, task.taskId()).lastFailure());
    }

    @Test void repeatedCrashesConsumeAttemptBudgetAcrossLeaseTakeovers() {
        var task = service.create(command(1, 1));
        for (int attempt = 1; attempt <= ReconciliationTask.MAX_ATTEMPTS; attempt++) {
            var claim = service.claim(1, task.taskId()).orElseThrow();
            assertEquals(attempt, claim.attempts()); expire(task.taskId());
        }
        assertTrue(service.claim(1, task.taskId()).isEmpty());
        var terminal = service.find(1, task.taskId());
        assertEquals(State.FAILED, terminal.state()); assertEquals(Failure.LEASE_EXHAUSTED, terminal.lastFailure());
        assertTrue(service.days(1, task.taskId()).isEmpty());
    }

    @Test void reportAndCheckpointRollbackTogetherThenRecoverAfterLostExecutor() {
        var failingRepo = new ReconciliationTaskRepository(f.cpm.sessions, ConversionEventsFixture.JSON) {
            @Override void insertDay(Task task, Snapshot snapshot, String report) {
                super.insertDay(task, snapshot, report);
                throw new IllegalStateException("提交前故障注入");
            }
        };
        var worker = fresh(failingRepo, snapshots); var task = worker.create(command(1, 1));
        assertThrows(IllegalStateException.class, () -> worker.advance(1, task.taskId()));
        var pending = service.find(1, task.taskId());
        assertEquals(State.RUNNING, pending.state()); assertEquals(0, pending.completedDays());
        assertTrue(service.days(1, task.taskId()).isEmpty());
        assertEquals(2L, f.jdbc.queryForObject("SELECT count(*) FROM ad_reconciliation_audit", Long.class));
        expire(task.taskId());
        assertEquals(State.COMPLETED, service.advance(1, task.taskId()).state());
        assertEquals(1, service.days(1, task.taskId()).size());
    }

    @Test void cancelPreservesPartialResultsAndFencesWorkerWithOriginalCommandAudit() {
        var task = service.create(command(1, 2)); service.advance(1, task.taskId());
        var claim = service.claim(1, task.taskId()).orElseThrow();
        var snapshot = snapshots.inspect(new Scope(1, claim.nextDate()));
        var command = new Cancel(1, task.taskId(), UUID.randomUUID(), "人工停止", "operator");
        var cancelled = service.cancel(command);
        assertEquals(State.CANCELLED, cancelled.state()); assertEquals(1, cancelled.completedDays());
        assertFalse(service.complete(claim, snapshot, repository.encode(snapshot))); service.fail(claim);
        assertEquals(cancelled, service.cancel(new Cancel(1, task.taskId(), command.commandId(), command.reason(), "another-operator")));
        assertEquals(cancelled, service.advance(1, task.taskId()));
        assertThrows(Conflict.class, () -> service.cancel(new Cancel(1, task.taskId(), command.commandId(), "改原因", "operator")));
        assertThrows(Conflict.class, () -> service.cancel(new Cancel(1, task.taskId(), UUID.randomUUID(), command.reason(), "operator")));
        assertThrows(NotFound.class, () -> service.cancel(new Cancel(2, task.taskId(), command.commandId(), command.reason(), "operator")));
        assertEquals(1, service.days(1, task.taskId()).size());
        assertEquals("operator", f.jdbc.queryForObject("SELECT actor FROM ad_reconciliation_audit WHERE event_type='CANCELLED'", String.class));
        assertEquals(State.READY, service.create(command(1, 1)).state());
    }

    @Test void storedReportsAndAuditAreImmutableAndAllColumnsDocumented() {
        var task = service.create(command(1, 1)); service.advance(1, task.taskId());
        assertThrows(DataAccessException.class, () -> f.jdbc.update("UPDATE ad_reconciliation_day SET report_version=1"));
        assertThrows(DataAccessException.class, () -> f.jdbc.update("DELETE FROM ad_reconciliation_day"));
        assertThrows(DataAccessException.class, () -> f.jdbc.update("DELETE FROM ad_reconciliation_audit"));
        assertEquals(0L, f.jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON a.attrelid=c.oid
                WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN
                ('ad_reconciliation_task','ad_reconciliation_day','ad_reconciliation_audit')
                AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL
                """, Long.class));
    }

    @Test void rangeAndRuntimeGuardsRejectUnboundedWorkAndWrongTransactions() {
        var date = f.cpm.today();
        assertDoesNotThrow(() -> new Create(1, UUID.randomUUID(), date.minusDays(30), date, "范围", "operator"));
        assertThrows(IllegalArgumentException.class, () -> new Create(1, UUID.randomUUID(), date.minusDays(31), date, "范围", "operator"));
        assertThrows(IllegalArgumentException.class, () -> new Create(1, UUID.randomUUID(), date.plusDays(1), date, "范围", "operator"));
        assertThrows(IllegalArgumentException.class, () -> new Create(1, UUID.randomUUID(), date, date, "秘密\n文本", "operator"));
        var tx = BudgetConfiguration.transactions(f.ds, 5);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> service.create(command(1, 1))));
        assertThrows(IllegalArgumentException.class, () -> fresh(repository,
                new ReconciliationService(new ReconciliationRepository(f.cpm.sessions), ReconciliationConfiguration.transactions(f.ds)))
                .find(0, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationTaskService(repository, snapshots, BudgetConfiguration.transactions(f.ds, 6)));
        new ApplicationContextRunner().withUserConfiguration(ReconciliationTaskConfiguration.class).run(ctx -> {
            assertNull(ctx.getStartupFailure()); assertTrue(ctx.getBeansOfType(ReconciliationTaskService.class).isEmpty());
        });
        f.jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=repeat('0',64) WHERE version=17");
        assertThrows(IllegalStateException.class, repository::verifySchema);
    }
}
