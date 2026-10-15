package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdPipeline;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PG 恢复证据：报价/预占/资格之间崩溃后，不重占、不续期、不替换原结果。 */
@Tag("money-chain")
@Testcontainers
class PreparationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final AtomicLong IDS = new AtomicLong(30000);
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static CpmQuoteRepository quotes;
    static ReservationService reservations;
    static PreparationRepository repository;
    static DeliveryTokenCodec tokens;
    static BudgetAccountService budgets;
    static SandboxPreparationService service;
    @BeforeAll static void setup() throws Exception {
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); jdbc = new JdbcTemplate(ds);
        for (String migration : List.of(BudgetRepository.MIGRATION, CpmQuoteRepository.MIGRATION, ReservationRepository.MIGRATION, PreparationRepository.MIGRATION)) {
            try (var c = ds.getConnection(); var stmt = c.createStatement()) {
                stmt.execute("SET recsys.migration_sha256 = '" + BudgetRepository.migrationSha256(migration) + "'");
                ScriptUtils.executeSqlScript(c, new ClassPathResource(migration));
            }
        }
        quotes = new CpmQuoteRepository(jdbc); repository = new PreparationRepository(jdbc, JSON); repository.verifySchema();
        reservations = new ReservationService(new ReservationRepository(jdbc), quotes, BudgetConfiguration.transactions(ds, 5), true);
        tokens = new DeliveryTokenCodec(JSON, "test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        budgets = new BudgetConfiguration().budgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))), ds, 5);
        service = fresh(repository);
    }
    static SandboxPreparationService fresh(PreparationRepository repo) {
        return new SandboxPreparationService(mock(AdPipeline.class), repo, quotes, reservations, tokens);
    }
    static LocalDate today() { return repository.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); }
    static Prepare request(String limit, Instant expiry) {
        long advertiser = IDS.incrementAndGet();
        budgets.setLimit(BudgetCommand.parse(advertiser, UUID.randomUUID().toString(), today().toString(), "CNY", limit, 0, "admin"));
        UUID id = UUID.randomUUID(), operation = UUID.randomUUID();
        var ad = new SponsoredAd(10, 20, advertiser, 0, "title", AdChannel.KW_EXACT, 1, 1, 1, 1, 1, 1, 1, 1, 99, "CPM");
        var price = new CpmPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        var offer = new Offer(operation, "admin", 1, id, ad, price, "test", expiry);
        return new Prepare(operation, "admin", 1, id, 1, tokens.sign("offer", offer));
    }
    @Test void duplicateConcurrencyAndRestartReturnOriginalQualification() throws Exception {
        var request = request("10", repository.now().plusSeconds(300));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Prepared>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> service.prepare(request)));
            var result = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var f : futures) assertEquals(result, f.get(10, TimeUnit.SECONDS));
            assertEquals(result, fresh(new PreparationRepository(jdbc, JSON)).prepare(request));
            assertEquals(Status.QUALIFIED, result.status());
            var qualification = tokens.verify(result.qualificationToken(), "qualification", Qualification.class);
            assertEquals(request.operationId(), qualification.operationId()); assertEquals(request.childId(), qualification.childId());
            assertEquals("1.000000", qualification.amount()); assertEquals(99, qualification.creativeId());
            assertEquals(Duration.ofMinutes(5), Duration.between(qualification.issuedAt(), qualification.expiresAt()));
            var reserve = reservations.find(request.childId(), "admin").orElseThrow();
            assertEquals("1.000000", budgets.findAccount(reserve.prepared().advertiserId(), today()).orElseThrow().heldAmount().toPlainString());
        }
    }
    @Test void failureAfterHoldRecoversWithoutAnotherHoldOrTtlExtension() {
        var request = request("10", repository.now().plusSeconds(300));
        var failing = new PreparationRepository(jdbc, JSON) {
            @Override public Prepared complete(Prepared result, String subject) { throw new IllegalStateException("injected lost result"); }
        };
        assertThrows(IllegalStateException.class, () -> fresh(failing).prepare(request));
        assertTrue(repository.find(request.childId(), "admin").isEmpty());
        var before = reservations.find(request.childId(), "admin").orElseThrow();
        var restored = service.prepare(request);
        assertEquals(before.prepared().expiresAt(), restored.expiresAt());
        assertEquals(before, reservations.find(request.childId(), "admin").orElseThrow());
        assertEquals(2, budgets.findAccount(before.prepared().advertiserId(), today()).orElseThrow().version());
    }
    @Test void expiredOfferNeverCreatesQuoteOrReservationAndRejectionIsStable() {
        var request = request("10", repository.now().minusSeconds(1));
        var result = service.prepare(request);
        assertEquals(Status.OFFER_EXPIRED, result.status());
        assertTrue(quotes.find(request.childId()).isEmpty()); assertTrue(reservations.find(request.childId(), "admin").isEmpty());
        assertEquals(result, service.prepare(request));
    }
    @Test void insufficientBudgetAndReleasedQualificationDoNotReacquire() {
        var request = request("0", repository.now().plusSeconds(300));
        assertEquals(Status.INSUFFICIENT_BUDGET, service.prepare(request).status());
        var funded = request("10", repository.now().plusSeconds(300));
        var first = service.prepare(funded); reservations.release(funded.childId(), "admin");
        assertEquals(first, service.prepare(funded));
        assertEquals(Reservation.State.RELEASED, reservations.find(funded.childId(), "admin").orElseThrow().state());
    }
    @Test void changedPositionSubjectOrParentCannotReuseSignedOffer() {
        var request = request("10", repository.now().plusSeconds(300)); service.prepare(request);
        assertThrows(Conflict.class, () -> service.prepare(new Prepare(request.operationId(), "admin", 1, request.childId(), 2, request.offerToken())));
        assertThrows(InvalidProof.class, () -> service.prepare(new Prepare(UUID.randomUUID(), "admin", 1, request.childId(), 1, request.offerToken())));
        assertThrows(InvalidProof.class, () -> service.prepare(new Prepare(request.operationId(), "other", 1, request.childId(), 1, request.offerToken())));
        assertThrows(Reservation.AccessDenied.class, () -> service.find(request.childId(), "other"));
    }
    @Test void failureAfterQuoteThenExpiredRetryCannotRefreshQuoteDeadline() {
        var request = request("10", repository.now().plusSeconds(300));
        var failingReservations = new ReservationService(new ReservationRepository(jdbc), quotes, BudgetConfiguration.transactions(ds, 5), true) {
            @Override public Reservation.Prepared prepare(UUID id, UUID quoteId, String subject) { throw new IllegalStateException("injected before hold"); }
        };
        var interrupted = new SandboxPreparationService(mock(AdPipeline.class), repository, quotes, failingReservations, tokens);
        assertThrows(IllegalStateException.class, () -> interrupted.prepare(request));
        assertTrue(quotes.find(request.childId()).isPresent());
        // 隔离 fixture 缩短剩余时间，不修改产品时钟，也不改变报价原定五分钟长度。
        jdbc.update("UPDATE ad_cpm_quote SET issued_at=statement_timestamp()-interval '10 minutes', expires_at=statement_timestamp()-interval '5 minutes' WHERE quote_id=?", request.childId());
        var expired = quotes.find(request.childId()).orElseThrow();
        var result = service.prepare(request);
        assertEquals(Status.QUOTE_EXPIRED, result.status());
        assertEquals(expired.expiresAt(), result.expiresAt());
        assertEquals(Reservation.State.REJECTED, reservations.find(request.childId(), "admin").orElseThrow().state());
    }

    @Test void ownerRejectsOuterTransactionsAndEveryColumnHasComment() {
        var request = request("10", repository.now().plusSeconds(300));
        BudgetConfiguration.transactions(ds, 5).executeWithoutResult(tx -> assertThrows(IllegalStateException.class, () -> service.prepare(request)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='ad_delivery_preparation'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL", Integer.class));
    }
}
