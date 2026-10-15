package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.common.ad.billing.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 PG 验证预占/释放串行化、版本与状态约束；不使用 Mock 证明资金事务。 */
@Tag("money-chain")
@Testcontainers
class ReservationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final AtomicLong IDS = new AtomicLong(10000);
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static BudgetAccountService budgets;
    static CpmQuoteRepository quotes;
    static ReservationRepository repository;
    static ReservationService service;
    static final String SUBJECT = "sandbox-user:1";

    @BeforeAll
    static void migrate() throws Exception {
        ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        for (String migration : new String[]{BudgetRepository.MIGRATION, CpmQuoteRepository.MIGRATION, ReservationRepository.MIGRATION}) {
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SET recsys.migration_sha256 = '" + BudgetRepository.migrationSha256(migration) + "'");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
            }
        }
        budgets = new BudgetConfiguration().budgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))), ds, 5);
        quotes = new CpmQuoteRepository(jdbc);
        repository = new ReservationRepository(jdbc);
        repository.verifySchema();
        service = freshService(true);
    }

    @Test
    void availableForOneOnlyAllowsOneConcurrentHold() throws Exception {
        long advertiser = funded("1");
        var offers = new ArrayList<FrozenCpmQuote>();
        for (int i = 0; i < 12; i++) offers.add(quote(advertiser, "1"));
        var results = concurrent(12, i -> service.prepare(UUID.randomUUID(), offers.get(i).quoteId(), SUBJECT));
        assertEquals(1, results.stream().filter(r -> r.decision() == Reservation.Decision.HELD).count());
        assertEquals(11, results.stream().filter(r -> r.decision() == Reservation.Decision.INSUFFICIENT_BUDGET).count());
        assertEquals("1.000000", account(advertiser, today()).heldAmount().toPlainString());
        assertEquals("0.000000", account(advertiser, today()).availableAmount().toPlainString());
    }

    @Test
    void duplicateIdSameQuoteOnlyOneHoldAndOtherIdConflicts() throws Exception {
        long advertiser = funded("10");
        var offer = quote(advertiser, "1");
        UUID reservation = UUID.randomUUID();
        var results = concurrent(12, i -> service.prepare(reservation, offer.quoteId(), SUBJECT));
        assertTrue(results.stream().allMatch(results.getFirst()::equals));
        assertEquals("1.000000", account(advertiser, today()).heldAmount().toPlainString());
        assertThrows(Reservation.Conflict.class, () -> service.prepare(UUID.randomUUID(), offer.quoteId(), SUBJECT));
        assertThrows(Reservation.Conflict.class, () -> service.prepare(reservation, offer.quoteId(), "other"));
        assertThrows(Reservation.AccessDenied.class, () -> service.release(reservation, "other"));
        assertThrows(Reservation.AccessDenied.class, () -> service.find(reservation, "other"));
        var released = service.release(reservation, SUBJECT).orElseThrow();
        assertEquals(Reservation.State.RELEASED, released.state());
        assertEquals(results.getFirst(), freshService(true).prepare(reservation, offer.quoteId(), SUBJECT));
        assertEquals(released, service.release(reservation, SUBJECT).orElseThrow());
        assertEquals("0.000000", account(advertiser, today()).heldAmount().toPlainString());
    }

    @Test
    void rejectedDecisionNeverTurnsIntoHoldAfterFunding() {
        long advertiser = funded("0");
        var offer = quote(advertiser, "1");
        UUID id = UUID.randomUUID();
        var rejected = service.prepare(id, offer.quoteId(), SUBJECT);
        assertEquals(Reservation.Decision.INSUFFICIENT_BUDGET, rejected.decision());
        setBudget(advertiser, today(), "10", 1);
        assertEquals(rejected, service.prepare(id, offer.quoteId(), SUBJECT));
        assertEquals(Reservation.State.REJECTED, service.release(id, SUBJECT).orElseThrow().state());
        assertEquals("0.000000", account(advertiser, today()).heldAmount().toPlainString());
    }

    @Test
    void missingAccountAndExpiredQuoteAreStableRejections() {
        long advertiser = IDS.incrementAndGet();
        var missing = quote(advertiser, "1");
        UUID id = UUID.randomUUID();
        var rejected = service.prepare(id, missing.quoteId(), SUBJECT);
        assertEquals(Reservation.Decision.ACCOUNT_MISSING, rejected.decision());
        setBudget(advertiser, today(), "10", 0);
        assertEquals(rejected, service.prepare(id, missing.quoteId(), SUBJECT));
        var expired = quote(advertiser, "1");
        ageQuote(expired.quoteId());
        assertEquals(Reservation.Decision.QUOTE_EXPIRED, service.prepare(UUID.randomUUID(), expired.quoteId(), SUBJECT).decision());
        assertEquals("0.000000", account(advertiser, today()).heldAmount().toPlainString());
    }

    @Test
    void decisionFailureRollsBackAccountAndClaim() {
        long advertiser = funded("10");
        UUID id = UUID.randomUUID();
        var offer = quote(advertiser, "1");
        jdbc.execute("ALTER TABLE ad_budget_reservation ADD CONSTRAINT test_prepare_failure CHECK "
                + "(advertiser_id <> " + advertiser + " OR decision IS NULL)");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.prepare(id, offer.quoteId(), SUBJECT));
            assertTrue(service.find(id, SUBJECT).isEmpty());
            assertEquals("0.000000", account(advertiser, today()).heldAmount().toPlainString());
        } finally {
            jdbc.execute("ALTER TABLE ad_budget_reservation DROP CONSTRAINT test_prepare_failure");
        }
        assertEquals(Reservation.Decision.HELD, service.prepare(id, offer.quoteId(), SUBJECT).decision());
    }

    @Test
    void releaseFailureRollsBackHeldAndCanRecoverAfterRestart() {
        long advertiser = funded("10");
        UUID id = UUID.randomUUID();
        service.prepare(id, quote(advertiser, "1").quoteId(), SUBJECT);
        jdbc.execute("ALTER TABLE ad_budget_reservation ADD CONSTRAINT test_release_failure CHECK "
                + "(advertiser_id <> " + advertiser + " OR state <> 'RELEASED')");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.release(id, SUBJECT));
            assertEquals(Reservation.State.HELD, service.find(id, SUBJECT).orElseThrow().state());
            assertEquals("1.000000", account(advertiser, today()).heldAmount().toPlainString());
        } finally {
            jdbc.execute("ALTER TABLE ad_budget_reservation DROP CONSTRAINT test_release_failure");
        }
        assertEquals(Reservation.State.RELEASED, freshService(true).release(id, SUBJECT).orElseThrow().state());
        assertEquals("0.000000", account(advertiser, today()).heldAmount().toPlainString());
    }

    @Test
    void twoExpiryWorkersAndReleaseRaceCannotDoubleRelease() throws Exception {
        long advertiser = funded("20");
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 10; i++) {
            UUID id = UUID.randomUUID();
            var offer = quote(advertiser, "1");
            service.prepare(id, offer.quoteId(), SUBJECT);
            ids.add(id);
        }
        assertFalse(service.expireOne());
        jdbc.update("UPDATE ad_budget_reservation SET expires_at = expires_at - interval '10 minutes' WHERE advertiser_id = ?", advertiser);
        concurrent(3, i -> {
            if (i == 2) for (UUID id : ids) service.release(id, SUBJECT);
            else while (service.expireOne()) { /* 每笔独立事务，直到本轮无可领取项。 */ }
            return true;
        });
        for (UUID id : ids) {
            Reservation found = service.find(id, SUBJECT).orElseThrow();
            assertEquals(Reservation.State.EXPIRED, found.state());
            assertEquals(1, found.version());
        }
        assertEquals("0.000000", account(advertiser, today()).heldAmount().toPlainString());
        assertFalse(service.expireOne());
    }

    @Test
    void oldBudgetDayIsReleasedWithoutTouchingCurrentDay() {
        long advertiser = funded("10");
        UUID id = UUID.randomUUID();
        service.prepare(id, quote(advertiser, "1").quoteId(), SUBJECT);
        LocalDate previous = today().minusDays(1);
        // 在隔离库构造跨日仍未结清的历史义务；服务端释放必须用保存的原预算日。
        jdbc.update("UPDATE ad_budget_account SET budget_date = ? WHERE advertiser_id = ?", previous, advertiser);
        jdbc.update("UPDATE ad_budget_reservation SET budget_date = ? WHERE reservation_id = ?", previous, id);
        setBudget(advertiser, today(), "20", 0);
        service.release(id, SUBJECT);
        assertEquals("0.000000", account(advertiser, previous).heldAmount().toPlainString());
        assertEquals("20.000000", account(advertiser, today()).availableAmount().toPlainString());
        assertEquals(previous, service.find(id, SUBJECT).orElseThrow().prepared().budgetDate());
    }

    @Test
    void admissionPauseKeepsQueriesReplaysAndReleaseWorking() {
        long advertiser = funded("10");
        UUID id = UUID.randomUUID();
        var offer = quote(advertiser, "1");
        var prepared = service.prepare(id, offer.quoteId(), SUBJECT);
        var paused = freshService(false);
        assertEquals(prepared, paused.prepare(id, offer.quoteId(), SUBJECT));
        assertThrows(IllegalStateException.class, () -> paused.prepare(UUID.randomUUID(), quote(advertiser, "1").quoteId(), SUBJECT));
        assertTrue(paused.find(id, SUBJECT).isPresent());
        assertEquals(Reservation.State.RELEASED, paused.release(id, SUBJECT).orElseThrow().state());
    }

    @Test
    void budgetReductionRacingHoldNeverViolatesHardCap() throws Exception {
        long advertiser = funded("1");
        var offer = quote(advertiser, "1");
        concurrent(2, i -> {
            if (i == 0) service.prepare(UUID.randomUUID(), offer.quoteId(), SUBJECT);
            else setBudget(advertiser, today(), "0", 1);
            return true;
        });
        var account = account(advertiser, today());
        assertTrue(account.heldAmount().add(account.chargedAmount()).compareTo(account.limitAmount()) <= 0);
    }

    @Test
    void quoteExpiringWhileWaitingForAccountLockCannotBeHeld() throws Exception {
        long advertiser = funded("10");
        var offer = quote(advertiser, "1");
        try (var blocker = ds.getConnection(); var workers = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT advertiser_id FROM ad_budget_account WHERE advertiser_id = ? FOR UPDATE")) {
                lock.setLong(1, advertiser);
                lock.executeQuery().close();
            }
            jdbc.update("""
                    WITH stamp AS (SELECT clock_timestamp() AS instant)
                    UPDATE ad_cpm_quote SET issued_at = stamp.instant - interval '299 seconds',
                        expires_at = stamp.instant + interval '1 second' FROM stamp WHERE quote_id = ?
                    """, offer.quoteId());
            var future = workers.submit(() -> service.prepare(UUID.randomUUID(), offer.quoteId(), SUBJECT));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()
                            AND wait_event_type = 'Lock' AND query LIKE '%ad_budget_account%')
                        """, Boolean.class));
                if (waiting) break;
                Thread.sleep(10);
            }
            assertTrue(waiting, "必须真实进入账户锁等待窗口");
            Thread.sleep(1100);
            blocker.commit();
            assertEquals(Reservation.Decision.QUOTE_EXPIRED, future.get(5, TimeUnit.SECONDS).decision());
            assertEquals("0.000000", account(advertiser, today()).heldAmount().toPlainString());
        }
    }

    @Test
    void databaseRejectsIncompleteTerminalStateAndAllColumnsAreDocumented() {
        long advertiser = funded("10");
        UUID id = UUID.randomUUID();
        service.prepare(id, quote(advertiser, "1").quoteId(), SUBJECT);
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE ad_budget_reservation SET decision = NULL WHERE reservation_id = ?", id));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE ad_budget_reservation SET state = 'RELEASED', closed_at = clock_timestamp(), close_reason = NULL WHERE reservation_id = ?", id));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a WHERE a.attrelid = 'ad_budget_reservation'::regclass
                AND a.attnum > 0 AND NOT a.attisdropped AND col_description(a.attrelid, a.attnum) IS NULL
                """, Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT obj_description('ad_budget_reservation'::regclass, 'pg_class')", String.class));
        var other = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertThrows(IllegalArgumentException.class, () -> new ReservationService(repository,
                new CpmQuoteRepository(new JdbcTemplate(other)), BudgetConfiguration.transactions(ds, 5), true));
    }

    private static ReservationService freshService(boolean admission) {
        return new ReservationService(repository, quotes, BudgetConfiguration.transactions(ds, 5), admission);
    }

    private static LocalDate today() { return repository.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); }

    private static long funded(String limit) {
        long advertiser = IDS.incrementAndGet();
        setBudget(advertiser, today(), limit, 0);
        return advertiser;
    }

    private static BudgetCommandResult setBudget(long advertiser, LocalDate day, String amount, long version) {
        return budgets.setLimit(BudgetCommand.parse(advertiser, UUID.randomUUID().toString(), day.toString(), "CNY", amount, version, "admin:test"));
    }

    private static BudgetAccount account(long advertiser, LocalDate day) { return budgets.findAccount(advertiser, day).orElseThrow(); }

    private static FrozenCpmQuote quote(long advertiser, String amount) {
        var price = new CpmPrice(new BigDecimal(amount), BigDecimal.ONE, CnyAmount.parse("0"), CnyAmount.parse("0"), CnyAmount.parse(amount));
        return quotes.freeze(UUID.randomUUID(), UUID.randomUUID(), advertiser, advertiser, 1, price);
    }

    private static void ageQuote(UUID quote) {
        jdbc.update("UPDATE ad_cpm_quote SET issued_at = issued_at - interval '10 minutes', expires_at = expires_at - interval '10 minutes' WHERE quote_id = ?", quote);
    }

    private static <T> java.util.List<T> concurrent(int count, java.util.function.IntFunction<T> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(workers.submit(() -> { start.await(); return action.apply(index); }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
