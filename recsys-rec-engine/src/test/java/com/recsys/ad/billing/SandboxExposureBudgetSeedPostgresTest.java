package com.recsys.ad.billing;

import com.recsys.persistence.spring.SpringSqlSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 隔离 PG 中验证浏览器预算种子跨上海午夜；只控制种子输入，不更改 JVM、数据库或产品时钟。 */
@Tag("money-chain")
@Testcontainers
class SandboxExposureBudgetSeedPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private BudgetAccountService budgets;

    @BeforeEach
    void createOwnedBudgetSchema() throws Exception {
        // 每个边界使用独立 schema，避免前一用例的次日账户掩盖种子遗漏。
        String schema = "browser_budget_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET recsys.migration_sha256 = '" + BudgetRepository.migrationSha256() + "'");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(BudgetRepository.MIGRATION));
        }
        var sessions = new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/ad/addb/**/*.xml"));
        budgets = new BudgetAccountService(new BudgetRepository(sessions), BudgetConfiguration.transactions(ds, 5));
    }

    @ParameterizedTest(name = "种子时间 {0} 覆盖上海预算日 {1} 和 {2}")
    @CsvSource({
            "2026-10-16T15:59:59.999999Z, 2026-10-16, 2026-10-17",
            "2026-10-31T15:59:59.999999Z, 2026-10-31, 2026-11-01",
            "2026-12-31T15:59:59.999999Z, 2026-12-31, 2027-01-01",
            "2026-10-16T16:00:00Z, 2026-10-17, 2026-10-18"
    })
    void seedsSeparateFullBudgetsAcrossShanghaiMidnight(String seededAt, LocalDate before, LocalDate after) {
        SandboxExposureBrowserFixtureTest.seedBudgets(budgets, Instant.parse(seededAt));

        // 显式日期与金额断言：不能靠当前非午夜时间的浏览器重跑证明修复。
        for (LocalDate day : List.of(before, after)) {
            var account = budgets.findAccount(1, day).orElseThrow(() -> new AssertionError("缺失预算日 " + day));
            assertEquals(day, account.budgetDate());
            assertEquals(1, account.advertiserId());
            assertEquals("100.000000", account.limitAmount().toPlainString());
            assertEquals("100.000000", account.availableAmount().toPlainString());
            assertEquals("0.000000", account.heldAmount().toPlainString());
            assertEquals("0.000000", account.chargedAmount().toPlainString());
            assertEquals(1, account.version());
        }
        assertEquals(List.of(before, after), jdbc.query(
                "SELECT budget_date FROM ad_budget_account ORDER BY budget_date",
                (rs, row) -> rs.getObject(1, LocalDate.class)));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM ad_budget_account
                WHERE traffic_class='SANDBOX' AND advertiser_id=1 AND currency='CNY'
                """, Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ad_budget_command WHERE status='APPLIED'", Integer.class));
        assertTrue(budgets.findAccount(1, before.minusDays(1)).isEmpty());
        assertTrue(budgets.findAccount(1, after.plusDays(1)).isEmpty());
    }
}
