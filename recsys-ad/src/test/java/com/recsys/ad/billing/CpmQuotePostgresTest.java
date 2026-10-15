package com.recsys.ad.billing;

import com.recsys.common.ad.billing.CnyAmount;
import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.billing.FrozenCpmQuote;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 独立真实 PG 验证 V001→V002 扩展迁移、不可变金额、并发幂等和期限不可延长。 */
@Tag("money-chain")
@Testcontainers
class CpmQuotePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static CpmQuoteRepository repository;

    @BeforeAll
    static void migrate() throws Exception {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        for (String migration : new String[]{BudgetRepository.MIGRATION, CpmQuoteRepository.MIGRATION}) {
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SET recsys.migration_sha256 = '" + BudgetRepository.migrationSha256(migration) + "'");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
            }
        }
        new BudgetRepository(jdbc).verifySchema();
        repository = new CpmQuoteRepository(jdbc);
        repository.verifySchema();
    }

    @Test
    void concurrentFreezeAndRestartPreserveAmountAndExpiry() throws Exception {
        UUID quote = UUID.randomUUID(), delivery = UUID.randomUUID();
        var price = price("0.0000005");
        var ready = new CountDownLatch(1);
        var results = new ArrayList<FrozenCpmQuote>();
        try (var workers = Executors.newFixedThreadPool(12)) {
            var futures = new ArrayList<Future<FrozenCpmQuote>>();
            for (int i = 0; i < 12; i++) futures.add(workers.submit(() -> {
                ready.await();
                return repository.freeze(quote, delivery, 1, 2, 1, price);
            }));
            ready.countDown();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
        }
        assertTrue(results.stream().allMatch(results.getFirst()::equals));
        FrozenCpmQuote original = results.getFirst();
        assertEquals("0.000001", original.price().amount().toPlainString());
        assertEquals(Duration.ofMinutes(5), Duration.between(original.issuedAt(), original.expiresAt()));
        assertEquals(original, new CpmQuoteRepository(jdbc).freeze(quote, delivery, 1, 2, 1, price));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ad_cpm_quote WHERE quote_id = ?", Integer.class, quote));
    }

    @Test
    void changedPayloadAndSameDeliverySlotWithNewIdConflict() {
        UUID quote = UUID.randomUUID(), delivery = UUID.randomUUID();
        var original = repository.freeze(quote, delivery, 1, 2, 1, price("0.1"));
        assertThrows(FrozenCpmQuote.Conflict.class, () -> repository.freeze(quote, delivery, 1, 2, 1, price("0.2")));
        assertThrows(FrozenCpmQuote.Conflict.class, () -> repository.freeze(quote, delivery, 3, 2, 1, price("0.1")));
        assertThrows(FrozenCpmQuote.Conflict.class, () -> repository.freeze(UUID.randomUUID(), delivery, 1, 2, 1, price("0.1")));
        assertEquals(original, repository.find(quote).orElseThrow());
    }

    @Test
    void retryOfExpiredQuoteDoesNotRenewItsTerm() {
        UUID quote = UUID.randomUUID(), delivery = UUID.randomUUID();
        repository.freeze(quote, delivery, 1, 2, 1, price("0.1"));
        // 在隔离库构造已过期历史行，不用真实等待五分钟，也不修改产品时钟规则。
        jdbc.update("UPDATE ad_cpm_quote SET issued_at = issued_at - interval '10 minutes', "
                + "expires_at = expires_at - interval '10 minutes' WHERE quote_id = ?", quote);
        var expired = repository.find(quote).orElseThrow();
        assertTrue(expired.expiresAt().isBefore(java.time.Instant.now()));
        assertEquals(expired, repository.freeze(quote, delivery, 1, 2, 1, price("0.1")));
    }

    @Test
    void databaseConstraintsAndCommentsHold() {
        var quote = repository.freeze(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 1, price("0.1"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE ad_cpm_quote SET amount = 'NaN' WHERE quote_id = ?", quote.quoteId()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE ad_cpm_quote SET expires_at = expires_at + interval '1 second' WHERE quote_id = ?", quote.quoteId()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a WHERE a.attrelid = 'ad_cpm_quote'::regclass
                AND a.attnum > 0 AND NOT a.attisdropped AND col_description(a.attrelid, a.attnum) IS NULL
                """, Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT obj_description('ad_cpm_quote'::regclass, 'pg_class')", String.class));
    }

    @Test
    void enabledConfigurationRequiresValidMigrationAndDisabledNeedsNoDb() {
        new ApplicationContextRunner().withUserConfiguration(CpmQuoteConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CpmQuoteRepository.class));
        var runner = new ApplicationContextRunner().withUserConfiguration(CpmQuoteConfiguration.class)
                .withPropertyValues("recsys.ad.billing.sandbox-enabled=true").withBean("adDbJdbc", JdbcTemplate.class, () -> jdbc);
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(CpmQuoteRepository.class));
        jdbc.update("UPDATE ad_billing_schema_history SET script_sha256 = ? WHERE version = 2", "0".repeat(64));
        try {
            runner.run(context -> assertThat(context).hasFailed());
        } finally {
            jdbc.update("UPDATE ad_billing_schema_history SET script_sha256 = ? WHERE version = 2", BudgetRepository.migrationSha256(CpmQuoteRepository.MIGRATION));
        }
    }

    private static CpmPrice price(String threshold) {
        return new CpmPrice(new BigDecimal(threshold), BigDecimal.ONE, CnyAmount.parse("0"), CnyAmount.parse("0"), CnyAmount.parse("1"));
    }
}
