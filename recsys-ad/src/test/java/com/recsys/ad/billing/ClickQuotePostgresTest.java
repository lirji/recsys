package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 在真实隔离PG验证报价并发、不可变与滚动兼容；没有预占/收费副作用。 */
@Tag("money-chain") @Testcontainers
class ClickQuotePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static ClickQuoteRepository repository;
    @BeforeAll static void migrate() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        for (String path : List.of(BudgetRepository.MIGRATION,CpmQuoteRepository.MIGRATION,ReservationRepository.MIGRATION,
                PreparationRepository.MIGRATION,ImpressionReceiptRepository.MIGRATION,BillingOutboxRepository.MIGRATION,
                FeedCandidateRepository.MIGRATION,ClickQuoteRepository.MIGRATION)) {
            try (var in = new ClassPathResource(path).getInputStream(); var c=ds.getConnection(); var statement=c.createStatement()) {
                statement.execute("SET recsys.migration_sha256='"+BudgetRepository.migrationSha256(path)+"'");
                statement.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        repository = new ClickQuoteRepository(jdbc); repository.verifySchema();
    }
    @Test void concurrentIdenticalIntentAndRestartKeepOneFrozenQuoteWithoutHoldingMoney() throws Exception {
        var d = draft(); var ready = new CountDownLatch(1); var values = new ArrayList<FrozenClickQuote>();
        try (var workers = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<Future<FrozenClickQuote>>();
            for (int i=0;i<12;i++) tasks.add(workers.submit(()->{ready.await();return repository.freezeBefore(d,deadline()).orElseThrow();}));
            ready.countDown(); for (var task:tasks) values.add(task.get(15,TimeUnit.SECONDS));
        }
        assertTrue(values.stream().allMatch(values.getFirst()::equals));
        assertEquals("0.000001",values.getFirst().price().amount().toPlainString());
        assertEquals(Duration.ofMinutes(5),Duration.between(values.getFirst().issuedAt(),values.getFirst().expiresAt()));
        assertEquals(values.getFirst(),new ClickQuoteRepository(jdbc).freezeBefore(d,deadline()).orElseThrow());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ad_click_quote WHERE quote_id=?",Integer.class,d.quoteId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ad_budget_account",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ad_budget_reservation",Integer.class));
    }
    @Test void changedPriceModeUserTitleAndSlotCannotReuseIdentity() {
        var d = draft(); var original=repository.freezeBefore(d,deadline()).orElseThrow();
        for (var changed : List.of(
                copy(d,d.quoteId(),d.userId(),d.slot(),d.title(),new ClickPrice(BidType.OCPC,d.price().basis())),
                copy(d,d.quoteId(),d.userId(),d.slot(),d.title(),price("0.2")),
                copy(d,d.quoteId(),d.userId()-1,d.slot(),d.title(),d.price()),
                copy(d,d.quoteId(),d.userId(),d.slot()+1,d.title(),d.price()),
                copy(d,d.quoteId(),d.userId(),d.slot(),"changed",d.price()),
                copy(d,UUID.randomUUID(),d.userId(),d.slot(),d.title(),d.price()))) {
            assertThrows(FrozenClickQuote.Conflict.class,()->repository.freezeBefore(changed,deadline()));
        }
        assertThrows(Reservation.AccessDenied.class,()->repository.find(d.quoteId(),"different-subject"));
        var other = new ClickQuoteRepository.Draft(d.quoteId(),d.operationId(),"different-subject",d.userId(),d.advertiserId(),d.adId(),d.creativeId(),d.itemId(),d.slot(),d.title(),d.price());
        assertThrows(Reservation.AccessDenied.class,()->repository.freezeBefore(other,deadline()));
        assertEquals(original,repository.find(d.quoteId(),d.subject()).orElseThrow());
    }
    @Test void expiredCandidateCannotCreateButOriginalQuoteSurvivesExpiredCandidate() {
        var d = draft();
        assertTrue(repository.freezeBefore(d,Instant.EPOCH).isEmpty());
        assertTrue(repository.find(d.quoteId(),d.subject()).isEmpty());
        var original = repository.freezeBefore(d,deadline()).orElseThrow();
        assertEquals(original,repository.freezeBefore(d,Instant.EPOCH).orElseThrow());
        assertThrows(FrozenClickQuote.Conflict.class,()->repository.freezeBefore(copy(d,UUID.randomUUID(),d.userId(),d.slot(),d.title(),d.price()),Instant.EPOCH));
    }
    @Test void expiredHistoricalQuoteDoesNotRenewAndCannotBeChangedOrDeleted() {
        var d = draft();
        // 隔离库只对本条INSERT模拟历史时钟；不禁用产品不可变触发器，不真实等待五分钟。
        jdbc.execute("""
                CREATE FUNCTION test_click_history_clock() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.quote_id='%s'::uuid THEN
                  NEW.issued_at=NEW.issued_at-interval '10 minutes'; NEW.expires_at=NEW.expires_at-interval '10 minutes';
                END IF; RETURN NEW; END; $$;
                CREATE TRIGGER test_click_history_clock BEFORE INSERT ON ad_click_quote FOR EACH ROW EXECUTE FUNCTION test_click_history_clock();
                """.formatted(d.quoteId()));
        FrozenClickQuote old;
        try { old=repository.freezeBefore(d,deadline()).orElseThrow(); }
        finally { jdbc.execute("DROP TRIGGER test_click_history_clock ON ad_click_quote; DROP FUNCTION test_click_history_clock()"); }
        assertTrue(old.expiresAt().isBefore(Instant.now()));
        assertEquals(old,repository.freezeBefore(d,deadline()).orElseThrow());
        for (String sql:List.of("UPDATE ad_click_quote SET expires_at=expires_at+interval '1 second' WHERE quote_id=?",
                "UPDATE ad_click_quote SET amount=0 WHERE quote_id=?","DELETE FROM ad_click_quote WHERE quote_id=?")) {
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,d.quoteId()));
        }
    }
    @Test void migrationCommentsAndOldCpmWriterRemainCompatible() {
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute WHERE attrelid='ad_click_quote'::regclass AND attnum>0 AND NOT attisdropped
                AND col_description(attrelid,attnum) IS NULL
                """,Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT obj_description('ad_click_quote'::regclass,'pg_class')",String.class));
        var cpm = new CpmQuoteRepository(jdbc); cpm.verifySchema();
        var q = cpm.freeze(UUID.randomUUID(),UUID.randomUUID(),1,2,1,new CpmPrice(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE));
        assertEquals("1.000000",q.price().amount().toPlainString());
        assertEquals(q,cpm.find(q.quoteId()).orElseThrow());
    }
    @Test void defaultOffOwnerAndMigrationGatesAreIndependentOfOldCpm() throws Exception {
        var sessions=new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"));
        new ApplicationContextRunner().withUserConfiguration(ClickQuoteConfiguration.class).run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ClickQuoteRepository.class));
        var runner = new ApplicationContextRunner().withUserConfiguration(ClickQuoteConfiguration.class)
                .withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.clicks-enabled=true")
                .withBean("adDbJdbc",JdbcTemplate.class,()->jdbc)
                .withBean("adDbSqlSessionTemplate",SqlSessionTemplate.class,()->sessions);
        runner.run(c->assertThat(c).hasNotFailed().hasSingleBean(ClickQuoteRepository.class));
        runner.withPropertyValues("recsys.ad.serving.mode=grpc","spring.application.name=recsys-rec-engine")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ClickQuoteRepository.class));
        runner.withPropertyValues("recsys.ad.serving.mode=grpc","spring.application.name=recsys-ad-serving")
                .run(c->assertThat(c).hasNotFailed().hasSingleBean(ClickQuoteRepository.class));
        var unavailable = new ApplicationContextRunner().withUserConfiguration(ClickQuoteConfiguration.class)
                .withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.clicks-enabled=true");
        unavailable.run(c->assertThat(c).hasFailed());
        jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=? WHERE version=8","0".repeat(64));
        try {
            runner.run(c->assertThat(c).hasFailed());
            new CpmQuoteRepository(jdbc).verifySchema();
        } finally {
            jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=? WHERE version=8",BudgetRepository.migrationSha256(ClickQuoteRepository.MIGRATION));
        }
    }
    private static Instant deadline() { return Instant.now().plusSeconds(60); }
    private static ClickPrice price(String value) { return new ClickPrice(BidType.CPC,new GspPrice(new BigDecimal(value),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE)); }
    private static ClickQuoteRepository.Draft draft() { return new ClickQuoteRepository.Draft(UUID.randomUUID(),UUID.randomUUID(),"sandbox-admin",Long.MAX_VALUE,1,2,3,9007199254740993L,1,"图文\n点击报价",price("0.0000005")); }
    private static ClickQuoteRepository.Draft copy(ClickQuoteRepository.Draft d,UUID id,long user,int slot,String title,ClickPrice price) {
        return new ClickQuoteRepository.Draft(id,d.operationId(),d.subject(),user,d.advertiserId(),d.adId(),d.creativeId(),d.itemId(),slot,title,price);
    }
}
