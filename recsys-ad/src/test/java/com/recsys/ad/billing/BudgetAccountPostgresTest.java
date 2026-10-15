package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.recsys.common.ad.billing.BudgetCommandResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PostgreSQL 证明唯一键竞争、账户行锁、约束和崩溃窗口的事务语义。 */
@Testcontainers
@Tag("money-chain")
class BudgetAccountPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final AtomicLong IDS = new AtomicLong(100);
    static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static BudgetRepository repository;
    static BudgetAccountService service;

    @BeforeAll
    static void migrate() throws Exception {
        ds = source();
        jdbc = new JdbcTemplate(ds);
        try (var connection = ds.getConnection(); var stmt = connection.createStatement()) {
            stmt.execute("SET recsys.migration_sha256 = '" + BudgetRepository.migrationSha256() + "'");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(BudgetRepository.MIGRATION));
        }
        repository = new BudgetRepository(jdbc);
        repository.verifySchema();
        service = new BudgetConfiguration().budgetAccountService(repository, ds, 5);
    }

    @Test
    void concurrentDuplicateReturnsOneOriginalResult() throws Exception {
        long advertiser = IDS.incrementAndGet();
        BudgetCommand command = command(advertiser, "123.123456", 0);
        var results = concurrent(16, i -> service.setLimit(command));
        assertTrue(results.stream().allMatch(r -> r.equals(results.getFirst())));
        assertEquals(APPLIED, results.getFirst().status());
        assertEquals(1, results.getFirst().account().version());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ad_budget_command WHERE advertiser_id = ?",
                Integer.class, advertiser));
    }

    @Test
    void distinctCommandsWithSameVersionOnlyOneApplies() throws Exception {
        long advertiser = IDS.incrementAndGet();
        var results = concurrent(16, i -> service.setLimit(command(advertiser, Integer.toString(i + 1), 0)));
        assertEquals(1, results.stream().filter(r -> r.status() == APPLIED).count());
        assertEquals(15, results.stream().filter(r -> r.status() == VERSION_CONFLICT).count());
        assertEquals(1, service.findAccount(advertiser, DAY).orElseThrow().version());
        assertEquals(16, jdbc.queryForObject("SELECT count(*) FROM ad_budget_command WHERE advertiser_id = ?",
                Integer.class, advertiser));
    }

    @Test
    void canonicalPayloadAndRestartKeepOriginalSnapshot() {
        long advertiser = IDS.incrementAndGet();
        var first = command(advertiser, "1", 0);
        var original = service.setLimit(first);
        var equivalent = BudgetCommand.parse(advertiser, first.commandId().toString(), DAY.toString(),
                "CNY", "1.000000", 0, "admin:test");
        assertEquals(original, service.setLimit(equivalent));
        service.setLimit(command(advertiser, "20", 1));
        var restarted = new BudgetConfiguration().budgetAccountService(new BudgetRepository(jdbc), ds, 5);
        assertEquals(original, restarted.findCommand(advertiser, first.commandId()).orElseThrow());
        assertEquals(original, restarted.setLimit(first));
        assertEquals(2, restarted.findAccount(advertiser, DAY).orElseThrow().version());
        assertTrue(restarted.findCommand(advertiser + 10000, first.commandId()).isEmpty());
    }

    @Test
    void keyCannotBeReusedWithDifferentAmountActorOrDate() {
        long advertiser = IDS.incrementAndGet();
        var first = command(advertiser, "10", 0);
        service.setLimit(first);
        for (var changed : new BudgetCommand[]{
                BudgetCommand.parse(advertiser, first.commandId().toString(), DAY.toString(), "CNY", "11", 0, first.actor()),
                BudgetCommand.parse(advertiser, first.commandId().toString(), DAY.toString(), "CNY", "10", 0, "other"),
                BudgetCommand.parse(advertiser, first.commandId().toString(), DAY.plusDays(1).toString(), "CNY", "10", 0, first.actor())}) {
            assertThrows(BudgetCommandResult.PayloadConflict.class, () -> service.setLimit(changed));
        }
    }

    @Test
    void rejectionBelowCommittedSurvivesLaterRelease() {
        long advertiser = IDS.incrementAndGet();
        service.setLimit(command(advertiser, "100", 0));
        jdbc.update("UPDATE ad_budget_account SET held_amount = 10, charged_amount = 20 WHERE advertiser_id = ?", advertiser);
        var rejected = command(advertiser, "29.999999", 1);
        var result = service.setLimit(rejected);
        assertEquals(BELOW_COMMITTED, result.status());
        jdbc.update("UPDATE ad_budget_account SET held_amount = 0 WHERE advertiser_id = ?", advertiser);
        assertEquals(result, service.setLimit(rejected));
        assertEquals(APPLIED, service.setLimit(command(advertiser, "20", 1)).status());
    }

    @Test
    void resultWriteFailureRollsBackAccountAndClaim() {
        long advertiser = IDS.incrementAndGet();
        var before = service.setLimit(command(advertiser, "10", 0)).account();
        // 故障在账户 UPDATE 之后、命令结果保存时发生，使用真实 DB 约束失败，不能用 Mock 证明回滚。
        jdbc.execute("ALTER TABLE ad_budget_command ADD CONSTRAINT test_result_failure CHECK "
                + "(advertiser_id <> " + advertiser + " OR result_version IS NULL OR result_version < 2)");
        var failing = command(advertiser, "50", 1);
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.setLimit(failing));
            assertEquals(before, service.findAccount(advertiser, DAY).orElseThrow());
            assertTrue(service.findCommand(advertiser, failing.commandId()).isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE ad_budget_command DROP CONSTRAINT test_result_failure");
        }
        assertEquals(APPLIED, service.setLimit(failing).status());
    }

    @Test
    void databaseRejectsOverspendNegativeAndNan() {
        long advertiser = IDS.incrementAndGet();
        service.setLimit(command(advertiser, "100", 0));
        for (String invalid : new String[]{"held_amount = 101", "charged_amount = -1", "limit_amount = 'NaN'"}) {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> jdbc.execute("UPDATE ad_budget_account SET " + invalid + " WHERE advertiser_id = " + advertiser));
        }
    }

    @Test
    void wrongDatasourceAndMissingMigrationFailClosed() {
        var other = source();
        assertThrows(IllegalArgumentException.class, () -> new BudgetAccountService(repository,
                new TransactionTemplate(new JdbcTransactionManager(other))));
        String url = POSTGRES.getJdbcUrl();
        other.setUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=missing_billing_schema");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> new BudgetRepository(new JdbcTemplate(other)).verifySchema());
    }

    @Test
    void realSpringAssemblyRejectsWrongPoolAndMigrationDrift() {
        var context = new ApplicationContextRunner().withUserConfiguration(BudgetConfiguration.class)
                .withPropertyValues("recsys.ad.billing.sandbox-enabled=true")
                .withBean("adDbDataSource", javax.sql.DataSource.class, () -> ds);
        context.withBean("adDbJdbc", JdbcTemplate.class, () -> jdbc).run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(BudgetAccountService.class);
        });
        context.withBean("adDbJdbc", JdbcTemplate.class, () -> new JdbcTemplate(source())).run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseMessage("预算事务与 Repository 必须绑定同一个 adDb DataSource");
        });
        jdbc.update("UPDATE ad_billing_schema_history SET script_sha256 = ? WHERE version = 1", "0".repeat(64));
        try {
            context.withBean("adDbJdbc", JdbcTemplate.class, () -> jdbc).run(app -> {
                assertThat(app).hasFailed();
                assertThat(app.getStartupFailure()).hasRootCauseMessage("adDb 预算迁移缺失或脚本摘要不一致");
            });
        } finally {
            jdbc.update("UPDATE ad_billing_schema_history SET script_sha256 = ? WHERE version = 1", BudgetRepository.migrationSha256());
        }
    }

    @Test
    void everyTableAndColumnHasComment() {
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
                WHERE n.nspname = 'public' AND t.relname IN
                    ('ad_billing_schema_history', 'ad_budget_account', 'ad_budget_command')
                AND (obj_description(t.oid, 'pg_class') IS NULL OR col_description(t.oid, a.attnum) IS NULL)
                """, Integer.class));
    }

    @Test
    void duplicateMigrationAbortsWithoutChangingAccounts() throws Exception {
        long advertiser = IDS.incrementAndGet();
        var before = service.setLimit(command(advertiser, "7", 0));
        try (var connection = ds.getConnection(); var stmt = connection.createStatement()) {
            stmt.execute("SET recsys.migration_sha256 = '" + BudgetRepository.migrationSha256() + "'");
            assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class,
                    () -> ScriptUtils.executeSqlScript(connection, new ClassPathResource(BudgetRepository.MIGRATION)));
            stmt.execute("ROLLBACK");
        }
        assertEquals(before.account(), service.findAccount(advertiser, DAY).orElseThrow());
    }

    private static DriverManagerDataSource source() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static BudgetCommand command(long advertiser, String limit, long version) {
        return BudgetCommand.parse(advertiser, UUID.randomUUID().toString(), DAY.toString(), "CNY", limit, version, "admin:test");
    }

    private static <T> java.util.List<T> concurrent(int count, java.util.function.IntFunction<T> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> { start.await(); return action.apply(index); }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
