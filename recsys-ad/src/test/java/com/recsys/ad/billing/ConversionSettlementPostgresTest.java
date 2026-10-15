package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdProperties;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 真实隔离PG验证双条件资金、共享账户、锁竞争和失败恢复，不用mock证明事务。 */
@Tag("money-chain") @Testcontainers
class ConversionSettlementPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final AtomicLong IDS=new AtomicLong(80000);
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static ReservationRepository accounts;
    static BudgetAccountService budgets;
    static ConversionQuoteRepository quotes;
    static ConversionSettlementRepository repository;
    static ConversionSettlementService service;
    static DeliveryTokenCodec tokens;
    @BeforeAll static void setup() throws Exception {
        ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); jdbc=new JdbcTemplate(ds);
        sessions=new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/ad/addb/**/*.xml"));
        for(String path:List.of(BudgetRepository.MIGRATION,CpmQuoteRepository.MIGRATION,ReservationRepository.MIGRATION,
                PreparationRepository.MIGRATION,ImpressionReceiptRepository.MIGRATION,BillingOutboxRepository.MIGRATION,
                FeedCandidateRepository.MIGRATION,ClickQuoteRepository.MIGRATION,ClickSettlementRepository.MIGRATION,
                "db/ad-billing/V010__click_event_delivery.sql",PreparationRepository.CLICK_MIGRATION,ConversionQuoteRepository.MIGRATION,ConversionSettlementRepository.MIGRATION)) {
            try(var in=new ClassPathResource(path).getInputStream();var c=ds.getConnection();var st=c.createStatement()) {
                st.execute("SET recsys.migration_sha256='"+BudgetRepository.migrationSha256(path)+"'");
                st.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        // 只在隔离fixture的INSERT上模拟签发时刻，不关闭或修改产品历史不可变触发器。
        jdbc.execute("""
                CREATE TABLE test_conversion_clock(quote_id UUID PRIMARY KEY,issued_at TIMESTAMPTZ NOT NULL);
                COMMENT ON TABLE test_conversion_clock IS '测试独占的签发时钟夹具';
                COMMENT ON COLUMN test_conversion_clock.quote_id IS '仅匹配本测试创建的报价';
                COMMENT ON COLUMN test_conversion_clock.issued_at IS '模拟DB签发时刻，仍保持五分钟窗口';
                CREATE FUNCTION test_conversion_clock() RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE t TIMESTAMPTZ;
                BEGIN SELECT issued_at INTO t FROM test_conversion_clock WHERE quote_id=NEW.quote_id;
                IF FOUND THEN NEW.issued_at=t; NEW.expires_at=t+interval '5 minutes'; END IF; RETURN NEW; END; $$;
                CREATE TRIGGER test_conversion_clock BEFORE INSERT ON ad_conversion_quote FOR EACH ROW EXECUTE FUNCTION test_conversion_clock();
                """);
        accounts=new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))); quotes=new ConversionQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));
        budgets=new BudgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))),BudgetConfiguration.transactions(ds,5));
        repository=new ConversionSettlementRepository(sessions,JSON); repository.verifySchema();
        tokens=new DeliveryTokenCodec(JSON,"test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
        service=fresh(repository,accounts,true,true,tokens);
    }
    static ConversionSettlementService fresh(ConversionSettlementRepository repo,ReservationRepository money,boolean prep,boolean receipt,DeliveryTokenCodec codec) {
        return new ConversionSettlementService(repo,quotes,money,codec,BudgetConfiguration.transactions(ds,5),prep,receipt);
    }
    static LocalDate today() { return accounts.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); }
    static void budget(long advertiser,LocalDate day,String amount) {
        budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),day.toString(),"CNY",amount,0,"viewer"));
    }
    record Fixture(FrozenConversionQuote quote,Prepared prepared) {
        UUID id() { return quote.quoteId(); }
        VisibleCommand visible() { return new VisibleCommand(UUID.randomUUID(),"viewer",prepared.qualificationToken(),1000,5000,true); }
        ConversionCommand conversion() { return new ConversionCommand(quote.advertiserId(),UUID.randomUUID(),"advertiser-actor",prepared.qualificationToken(),quote.issuedAt().plusSeconds(1)); }
    }
    Fixture fixture() { return fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0.123456",298); }
    Fixture fixture(long advertiser,long user,String amount,int remaining) {
        if(budgets.findAccount(advertiser,today()).isEmpty()) budget(advertiser,today(),"10");
        var q=quote(advertiser,user,amount,accounts.databaseNow().plusSeconds(remaining-300L));
        return new Fixture(q,service.prepare(q.quoteId(),"viewer"));
    }
    FrozenConversionQuote quote(long advertiser,long user,String amount,Instant issued) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO test_conversion_clock(quote_id,issued_at) VALUES(?,?)",id,java.sql.Timestamp.from(issued));
        var basis=new GspPrice(new BigDecimal(amount),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal(amount));
        return quotes.freezeBefore(new ConversionQuoteRepository.Draft(id,UUID.randomUUID(),"viewer",user,advertiser,2,3,9007199254740993L,1,"冻结转化正文",new ConversionPrice(basis)),Instant.now().plusSeconds(60)).orElseThrow();
    }
    BudgetAccount account(Fixture f) { return budgets.findAccount(f.quote().advertiserId(),f.prepared().budgetDate()).orElseThrow(); }
    int ledgerCount(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM ad_conversion_ledger WHERE qualification_id=?",Integer.class,id); }
    static <T> List<T> concurrent(int count,java.util.function.IntFunction<T> action) throws Exception {
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(count)) {
            var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++) { final int index=i; futures.add(workers.submit(()->{start.await();return action.apply(index);})); }
            start.countDown(); var results=new ArrayList<T>();
            for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS)); return results;
        }
    }

    /** 两类事实都禁止提交PENDING；事务内读取也必须先拒绝半成品，不能误构造可空结果。 */
    @Test void unfinishedVisibleAndOutcomeCannotBecomeCanonicalOrJoinedResults() {
        var f = fixture(); var visible = f.visible(); var conversion = f.conversion();
        UUID outcomeId = UUID.randomUUID(); var before = account(f);
        assertThrows(DataAccessException.class, () -> repository.claimVisible(visible, f.id()));
        assertThrows(DataAccessException.class, () -> repository.claimOutcome(outcomeId, conversion, f.id()));
        BudgetConfiguration.transactions(ds, 5).executeWithoutResult(tx -> {
            assertTrue(repository.lockQuote(f.id()));
            assertTrue(repository.claimVisible(visible, f.id()));
            assertTrue(repository.claimOutcome(outcomeId, conversion, f.id()));
            assertEquals("发现未完成CPA可见决定", assertThrows(IllegalStateException.class,
                    () -> repository.canonicalVisible(visible.receiptId())).getMessage());
            assertEquals("发现未完成CPA可见决定", assertThrows(IllegalStateException.class,
                    () -> repository.findVisible(visible.receiptId(), "viewer")).getMessage());
            assertEquals("发现未完成CPA业务决定", assertThrows(IllegalStateException.class,
                    () -> repository.outcome(outcomeId)).getMessage());
            assertEquals("发现未完成CPA业务决定", assertThrows(IllegalStateException.class,
                    () -> repository.findOutcome(conversion.advertiserId(), conversion.eventId())).getMessage());
            assertEquals(State.HELD, repository.find(f.id(), "viewer").orElseThrow().state());
            tx.setRollbackOnly();
        });
        assertTrue(repository.findVisible(visible.receiptId(), "viewer").isEmpty());
        assertTrue(repository.findOutcome(conversion.advertiserId(), conversion.eventId()).isEmpty());
        assertEquals(before, account(f)); assertEquals(0, ledgerCount(f.id()));
    }

    @Test void preparationConcurrencyAndOriginalResultNeverRenewOrRehold() throws Exception {
        long advertiser=IDS.incrementAndGet();budget(advertiser,today(),"1");
        var q=quote(advertiser,IDS.incrementAndGet(),"0.123456",accounts.databaseNow().minusSeconds(2));
        var results=concurrent(12,i->service.prepare(q.quoteId(),"viewer"));
        assertTrue(results.stream().allMatch(results.getFirst()::equals));
        assertEquals("0.123456",budgets.findAccount(advertiser,today()).orElseThrow().heldAmount().toPlainString());
        service.release(q.quoteId(),"viewer");
        assertEquals(results.getFirst(),fresh(repository,accounts,false,false,tokens).prepare(q.quoteId(),"viewer"));
        assertEquals("0.000000",budgets.findAccount(advertiser,today()).orElseThrow().heldAmount().toPlainString());
    }
    @Test void prepareRejectsRemainStableAfterBudgetChanges() {
        long adv=IDS.incrementAndGet();var q=quote(adv,1,"1",accounts.databaseNow());
        assertEquals(Decision.ACCOUNT_MISSING,service.prepare(q.quoteId(),"viewer").decision());
        budget(adv,today(),"0.5");assertEquals(Decision.ACCOUNT_MISSING,service.prepare(q.quoteId(),"viewer").decision());
        var low=quote(adv,1,"1",accounts.databaseNow());assertEquals(Decision.INSUFFICIENT_BUDGET,service.prepare(low.quoteId(),"viewer").decision());
        var expired=quote(adv,1,"0.1",accounts.databaseNow().minusSeconds(301));
        assertEquals(Decision.QUOTE_EXPIRED,service.prepare(expired.quoteId(),"viewer").decision());
        assertEquals("0.000000",budgets.findAccount(adv,today()).orElseThrow().heldAmount().toPlainString());
    }
    @Test void conversionFirstWaitsThenVisibleCapturesAndRetryKeepsFirstDecision() {
        var f=fixture();var c=f.conversion();var first=service.acceptAuthorizedConversion(c);
        assertEquals(OutcomeStatus.ACCEPTED_WAITING,first.outcome().status());assertEquals(Progress.WAITING_FOR_VISIBLE,first.settlement().progress());
        assertNull(first.settlement().chargedAmount());assertEquals(0,ledgerCount(f.id()));
        var captured=service.acceptVisible(f.visible());assertEquals(VisibleStatus.ACCEPTED_CAPTURED,captured.receipt().status());
        assertEquals("0.123456",account(f).chargedAmount().toPlainString());assertEquals("0.000000",account(f).heldAmount().toPlainString());
        var retry=service.acceptAuthorizedConversion(c);assertEquals(first.outcome(),retry.outcome());assertEquals(State.CAPTURED,retry.settlement().state());
        assertEquals(1,ledgerCount(f.id()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ad_conversion_outbox WHERE event_id=?",Integer.class,captured.settlement().ledgerId()));
        String payload=jdbc.queryForObject("SELECT payload_json FROM ad_conversion_outbox WHERE event_id=?",String.class,captured.settlement().ledgerId());
        assertTrue(payload.contains(c.eventId().toString()));assertFalse(payload.contains("viewer"));assertFalse(payload.contains("advertiser-actor"));assertFalse(payload.contains(c.qualificationToken()));
    }
    @Test void visibleFirstAndDistinctBusinessEventsOnlyChargeCanonicalOne() throws Exception {
        var f=fixture();assertEquals(Progress.WAITING_FOR_CONVERSION,service.acceptVisible(f.visible()).settlement().progress());
        var results=concurrent(12,i->service.acceptAuthorizedConversion(f.conversion()));
        assertEquals(1,results.stream().filter(r->r.outcome().status()==OutcomeStatus.ACCEPTED_CAPTURED).count());
        assertEquals(11,results.stream().filter(r->r.outcome().status()==OutcomeStatus.DUPLICATE_QUALIFICATION).count());
        assertEquals(1,ledgerCount(f.id()));assertEquals("0.123456",account(f).chargedAmount().toPlainString());
        var canonical=results.stream().filter(r->r.outcome().status()==OutcomeStatus.ACCEPTED_CAPTURED).findFirst().orElseThrow().outcome().outcomeId();
        assertTrue(results.stream().allMatch(r->canonical.equals(r.outcome().canonicalOutcomeId())));
    }
    @Test void simultaneousSameBusinessAndVisibleCommandsHaveOneEffect() throws Exception {
        var f=fixture();var c=f.conversion();var v=f.visible();
        concurrent(12,i->i%2==0?service.acceptAuthorizedConversion(c):service.acceptVisible(v));
        assertEquals(1,ledgerCount(f.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ad_conversion_outcome WHERE qualification_id=?",Integer.class,f.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ad_conversion_visible WHERE qualification_id=?",Integer.class,f.id()));
    }
    @Test void advertiserBusinessIdentityCannotBeReboundAcrossQualifications() throws Exception {
        long adv=IDS.incrementAndGet();var a=fixture(adv,1,"0.1",298);var b=fixture(adv,2,"0.1",298);UUID key=UUID.randomUUID();
        var one=new ConversionCommand(adv,key,"actor-a",a.prepared().qualificationToken(),a.quote().issuedAt().plusSeconds(1));
        var two=new ConversionCommand(adv,key,"actor-b",b.prepared().qualificationToken(),b.quote().issuedAt().plusSeconds(1));
        var results=concurrent(2,i->{try{service.acceptAuthorizedConversion(i==0?one:two);return "accepted";}catch(ConversionSettlement.Conflict e){return "conflict";}});
        assertEquals(1,Collections.frequency(results,"accepted"));assertEquals(1,Collections.frequency(results,"conflict"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ad_conversion_outcome WHERE advertiser_id=? AND event_id=?",Integer.class,adv,key));
        assertEquals(0,ledgerCount(a.id())+ledgerCount(b.id()));
    }
    @Test void sameEventUuidInDifferentAdvertisersIsIndependentAndActorChangeOnlyRestores() {
        var a=fixture();var b=fixture();UUID key=UUID.randomUUID();
        var ca=new ConversionCommand(a.quote().advertiserId(),key,"actor-a",a.prepared().qualificationToken(),a.quote().issuedAt().plusSeconds(1));
        var cb=new ConversionCommand(b.quote().advertiserId(),key,"actor-b",b.prepared().qualificationToken(),b.quote().issuedAt().plusSeconds(1));
        var first=service.acceptAuthorizedConversion(ca);var second=service.acceptAuthorizedConversion(cb);
        assertNotEquals(first.outcome().outcomeId(),second.outcome().outcomeId());
        assertEquals(first,service.acceptAuthorizedConversion(new ConversionCommand(ca.advertiserId(),key,"actor-new",ca.qualificationToken(),ca.occurredAt())));
        assertEquals("actor-a",jdbc.queryForObject("SELECT actor FROM ad_conversion_outcome WHERE outcome_id=?",String.class,first.outcome().outcomeId()));
        assertThrows(ConversionSettlement.Conflict.class,()->service.acceptAuthorizedConversion(new ConversionCommand(ca.advertiserId(),key,"actor-new",ca.qualificationToken(),ca.occurredAt().plusMillis(1))));
        assertTrue(service.findAuthorizedOutcome(IDS.incrementAndGet(),key).isEmpty());
    }
    @Test void wrongAdvertiserDisplaySubjectPurposeAndHashCannotMutateFunds() {
        var f=fixture();var c=f.conversion();
        assertThrows(Reservation.AccessDenied.class,()->service.acceptAuthorizedConversion(new ConversionCommand(c.advertiserId()+1,c.eventId(),c.actor(),c.qualificationToken(),c.occurredAt())));
        assertThrows(Reservation.AccessDenied.class,()->service.acceptVisible(new VisibleCommand(UUID.randomUUID(),"other",c.qualificationToken(),1000,5000,true)));
        assertThrows(Reservation.AccessDenied.class,()->service.prepare(f.id(),"other"));assertThrows(Reservation.AccessDenied.class,()->service.release(f.id(),"other"));
        var proof=tokens.verify(c.qualificationToken(),ConversionSettlement.TOKEN_PURPOSE,Proof.class);
        for(String token:List.of(tokens.sign("click-qualification",proof),tokens.sign(ConversionSettlement.TOKEN_PURPOSE,new Proof(f.id(),"viewer","0".repeat(64),proof.budgetDate(),ConversionSettlement.POLICY)))) {
            assertThrows(SandboxDelivery.InvalidProof.class,()->service.acceptAuthorizedConversion(new ConversionCommand(c.advertiserId(),c.eventId(),c.actor(),token,c.occurredAt())));
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ad_conversion_outcome WHERE qualification_id=?",Integer.class,f.id()));assertEquals(0,ledgerCount(f.id()));
    }
    @Test void outsideAttributionIsPersistedAndCannotBeFixedByMutatingSameEvent() {
        var f=fixture();
        for(Instant at:List.of(f.quote().issuedAt().minusSeconds(1),f.quote().expiresAt(),accounts.databaseNow().plusSeconds(60))) {
            var c=new ConversionCommand(f.quote().advertiserId(),UUID.randomUUID(),"actor",f.prepared().qualificationToken(),at);
            var rejected=service.acceptAuthorizedConversion(c);assertEquals(OutcomeStatus.OUTSIDE_ATTRIBUTION,rejected.outcome().status());
            assertEquals(rejected,service.acceptAuthorizedConversion(c));assertEquals(State.HELD,rejected.settlement().state());
        }
        service.acceptVisible(f.visible());assertEquals(State.CAPTURED,service.acceptAuthorizedConversion(f.conversion()).settlement().state());
    }
    @Test void visibilityPolicyAndOriginalReceiptAreIndependentOfBusinessOutcome() {
        var f=fixture();service.acceptAuthorizedConversion(f.conversion());
        for(var v:List.of(new VisibleCommand(UUID.randomUUID(),"viewer",f.prepared().qualificationToken(),999,5000,true),
                new VisibleCommand(UUID.randomUUID(),"viewer",f.prepared().qualificationToken(),1000,4999,true),
                new VisibleCommand(UUID.randomUUID(),"viewer",f.prepared().qualificationToken(),1000,5000,false),
                new VisibleCommand(UUID.randomUUID(),"viewer",f.prepared().qualificationToken(),300000,5000,true))) {
            var result=service.acceptVisible(v);assertEquals(VisibleStatus.POLICY_NOT_MET,result.receipt().status());assertEquals(result,service.acceptVisible(v));
            assertThrows(ConversionSettlement.Conflict.class,()->service.acceptVisible(new VisibleCommand(v.receiptId(),"viewer",v.qualificationToken(),1500,5000,true)));
        }
        assertEquals(State.CAPTURED,service.acceptVisible(f.visible()).settlement().state());
    }
    @Test void releasedQualificationRecordsLateOutcomeWithoutReholding() {
        var f=fixture();service.release(f.id(),"viewer");var c=f.conversion();
        var r=service.acceptAuthorizedConversion(c);assertEquals(OutcomeStatus.NOT_HELD,r.outcome().status());assertEquals(State.RELEASED,r.settlement().state());
        assertNull(r.settlement().chargedAmount());assertEquals("0.000000",account(f).heldAmount().toPlainString());assertEquals(0,ledgerCount(f.id()));
        assertEquals(r,service.acceptAuthorizedConversion(c));
    }
    @Test void zeroAmountStillHasUniqueLedgerAndOutcome() {
        var f=fixture(IDS.incrementAndGet(),1,"0",298);service.acceptVisible(f.visible());var r=service.acceptAuthorizedConversion(f.conversion());
        assertEquals(State.CAPTURED,r.settlement().state());assertEquals("0.000000",r.settlement().chargedAmount());assertNotNull(r.settlement().ledgerId());assertEquals(1,ledgerCount(f.id()));
    }
    @Test void failedLedgerOutboxOrOutcomeCompletionRollsBackEveryMoneyEffect() {
        for(int failure=0;failure<3;failure++) {
            var f=fixture();service.acceptVisible(f.visible());var c=f.conversion();var bad=spy(new ConversionSettlementRepository(sessions,JSON));
            if(failure==0) doThrow(new IllegalStateException("ledger unavailable")).when(bad).appendLedger(any(),any(),any(),any(),any(),anyLong(),any());
            if(failure==1) doThrow(new IllegalStateException("outbox unavailable")).when(bad).appendOutbox(any());
            if(failure==2) doThrow(new IllegalStateException("outcome unavailable")).when(bad).completeOutcome(any(),any(),any(),any());
            assertThrows(IllegalStateException.class,()->fresh(bad,accounts,true,true,tokens).acceptAuthorizedConversion(c));
            assertTrue(service.findAuthorizedOutcome(c.advertiserId(),c.eventId()).isEmpty());assertEquals(0,ledgerCount(f.id()));
            assertEquals("0.123456",account(f).heldAmount().toPlainString());assertEquals("0.000000",account(f).chargedAmount().toPlainString());
            assertEquals(State.CAPTURED,service.acceptAuthorizedConversion(c).settlement().state());
        }
    }
    @Test void preparationFailureRollsBackHoldAndOriginalResultSurvivesPauseAndKeyRotation() {
        long adv=IDS.incrementAndGet();budget(adv,today(),"1");var q=quote(adv,1,"0.1",accounts.databaseNow().minusSeconds(2));
        var bad=spy(new ConversionSettlementRepository(sessions,JSON));doThrow(new IllegalStateException("prepare result failed")).when(bad).insert(any(),anyString(),anyLong());
        assertThrows(IllegalStateException.class,()->fresh(bad,accounts,true,true,tokens).prepare(q.quoteId(),"viewer"));
        assertEquals("0.000000",budgets.findAccount(adv,today()).orElseThrow().heldAmount().toPlainString());
        var f=new Fixture(q,service.prepare(q.quoteId(),"viewer"));var c=f.conversion();var original=service.acceptAuthorizedConversion(c);
        var rotated=new DeliveryTokenCodec(JSON,"next",Map.of("next",Base64.getEncoder().encodeToString(new byte[32])));
        var paused=fresh(repository,accounts,false,false,rotated);
        assertEquals(f.prepared(),paused.prepare(f.id(),"viewer"));assertEquals(original,paused.acceptAuthorizedConversion(c));
        assertThrows(IllegalStateException.class,()->paused.acceptVisible(f.visible()));
        assertEquals(State.RELEASED,paused.release(f.id(),"viewer").orElseThrow().state());
        assertThrows(SandboxDelivery.InvalidProof.class,()->fresh(repository,accounts,true,true,rotated).acceptVisible(f.visible()));
    }
    @Test void releaseRacesCaptureOnlyOneTerminalOutcomeWins() throws Exception {
        var f=fixture();service.acceptVisible(f.visible());var c=f.conversion();
        concurrent(2,i->i==0?service.acceptAuthorizedConversion(c):service.release(f.id(),"viewer"));
        var state=service.find(f.id(),"viewer").orElseThrow();assertTrue(Set.of(State.RELEASED,State.CAPTURED).contains(state.state()));
        assertEquals("0.000000",account(f).heldAmount().toPlainString());assertEquals(state.state()==State.CAPTURED?1:0,ledgerCount(f.id()));
        assertEquals(state.state()==State.CAPTURED?"0.123456":"0.000000",account(f).chargedAmount().toPlainString());
    }
    @Test void delayedAccountLockExpiresInsteadOfCapturing() throws Exception {
        var f=fixture(IDS.incrementAndGet(),1,"0.1",2);service.acceptVisible(f.visible());var c=f.conversion();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var blocker=workers.submit(()->BudgetConfiguration.transactions(ds,5).execute(tx->{accounts.lockAccount(c.advertiserId(),f.prepared().budgetDate());locked.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}return true;}));
            assertTrue(locked.await(3,TimeUnit.SECONDS));var attempt=workers.submit(()->service.acceptAuthorizedConversion(c));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)==0) Thread.sleep(10);
                assertTrue(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>0);
                while(accounts.databaseNow().isBefore(f.quote().expiresAt())) Thread.sleep(10);
            } finally { release.countDown(); }
            blocker.get(5,TimeUnit.SECONDS);var r=attempt.get(5,TimeUnit.SECONDS);assertEquals(OutcomeStatus.EXPIRED,r.outcome().status());assertEquals(State.EXPIRED,r.settlement().state());
        }
        assertEquals("0.000000",account(f).heldAmount().toPlainString());assertEquals(0,ledgerCount(f.id()));
    }
    @Test void expiredWaitingOutcomeSurvivesWorkerAndLaterVisibleCannotCharge() throws Exception {
        var f=fixture();var c=f.conversion();var first=service.acceptAuthorizedConversion(c);
        var future=spy(new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))));doReturn(f.quote().expiresAt().plusSeconds(1)).when(future).databaseNow();
        var expired=fresh(repository,future,true,true,tokens).acceptVisible(f.visible());assertEquals(VisibleStatus.EXPIRED,expired.receipt().status());
        var restored=service.acceptAuthorizedConversion(c);assertEquals(first.outcome(),restored.outcome());assertEquals(State.EXPIRED,restored.settlement().state());
        assertEquals(0,ledgerCount(f.id()));assertEquals("0.000000",account(f).heldAmount().toPlainString());
    }
    @Test void schemaEnforcesCompleteCommandsAndImmutableHistoryAndComments() {
        var f=fixture();var c=f.conversion();service.acceptVisible(f.visible());var r=service.acceptAuthorizedConversion(c);
        for(String table:List.of("ad_conversion_settlement","ad_conversion_visible","ad_conversion_outcome","ad_conversion_ledger","ad_conversion_outbox")) {
            assertNotNull(jdbc.queryForObject("SELECT obj_description(?::regclass,'pg_class')",String.class,table));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",Integer.class,table));
        }
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE ad_conversion_outcome SET actor='tampered' WHERE outcome_id=?",r.outcome().outcomeId()));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE ad_conversion_settlement SET state='HELD',ledger_id=NULL,version=version+1 WHERE qualification_id=?",f.id()));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM ad_conversion_ledger WHERE qualification_id=?",f.id()));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE ad_conversion_outbox SET payload_json='{}' WHERE event_id=?",r.settlement().ledgerId()));
        var unused=fixture();
        var incomplete=assertThrows(org.springframework.transaction.TransactionSystemException.class,()->BudgetConfiguration.transactions(ds,5).execute(tx->{repository.lockQuote(unused.id());repository.claimOutcome(UUID.randomUUID(),unused.conversion(),unused.id());return null;}));
        assertTrue(incomplete.getMostSpecificCause().getMessage().contains("conversion outcome must be decided before commit"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ad_conversion_outcome WHERE qualification_id=?",Integer.class,unused.id()));
    }
    @Test void defaultOffOwnerAndResourceIdentityAreFailClosed() {
        new ApplicationContextRunner().withUserConfiguration(ConversionSettlementConfiguration.class).run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ConversionSettlementService.class));
        new ApplicationContextRunner().withUserConfiguration(ConversionSettlementConfiguration.class).withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.conversions-enabled=true","recsys.ad.billing.conversion-settlement-enabled=true","recsys.ad.serving.mode=grpc","spring.application.name=recsys-rec-engine")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ConversionSettlementService.class));
        var other=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        assertThrows(IllegalArgumentException.class,()->new ConversionSettlementService(repository,quotes,accounts,tokens,BudgetConfiguration.transactions(other,5),true,true));
        jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=? WHERE version=13","0".repeat(64));
        try{assertThrows(IllegalStateException.class,repository::verifySchema);}finally{jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=? WHERE version=13",BudgetRepository.migrationSha256(ConversionSettlementRepository.MIGRATION));}
    }
    @Test void twoExpiryWorkersSkipLockedQuoteAndRecoverAfterRestartWithAdmissionPaused() throws Exception {
        var a=fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0.123456",1);
        var b=fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0.123456",1);
        service.acceptAuthorizedConversion(a.conversion());
        while(accounts.databaseNow().isBefore(b.quote().expiresAt())) Thread.sleep(20);
        var paused=fresh(repository,accounts,false,false,tokens);
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            try(var statement=c.prepareStatement("SELECT quote_id FROM ad_conversion_quote WHERE quote_id=? FOR UPDATE")) {
                statement.setObject(1,a.id()); statement.executeQuery().close();
            }
            assertTrue(paused.expireOne()); assertEquals(State.EXPIRED,paused.find(b.id(),"viewer").orElseThrow().state());
            assertEquals(State.HELD,paused.find(a.id(),"viewer").orElseThrow().state()); c.commit();
        }
        concurrent(2,i->paused.expireOne());
        assertEquals(State.EXPIRED,paused.find(a.id(),"viewer").orElseThrow().state());
        assertEquals("0.000000",account(a).heldAmount().toPlainString()); assertEquals("0.000000",account(b).heldAmount().toPlainString());
        assertEquals(OutcomeStatus.EXPIRED,service.acceptAuthorizedConversion(b.conversion()).outcome().status());
        assertEquals(0,ledgerCount(a.id())+ledgerCount(b.id()));
    }
    @Test void crossMidnightCapturedFundsUseOriginalBudgetDate() throws Exception {
        long advertiser=IDS.incrementAndGet(); var day=today().minusDays(1);
        budget(advertiser,day,"1"); budget(advertiser,today(),"1");
        var before=day.atTime(23,59,58).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        var clocked=spy(new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))));
        // 模拟两个真实DB时刻的跨午夜边界；资金/锁/约束仍由真实PG执行，不移动或篡改已冻结历史。
        var time=new java.util.concurrent.atomic.AtomicReference<>(before);
        doAnswer(call->jdbc.queryForObject("SELECT ?::timestamptz",OffsetDateTime.class,java.sql.Timestamp.from(time.get())).toInstant()).when(clocked).databaseNow();
        var crossing=fresh(repository,clocked,true,true,tokens);
        var q=quote(advertiser,IDS.incrementAndGet(),"0.123456",before.minusSeconds(2));
        var f=new Fixture(q,crossing.prepare(q.quoteId(),"viewer"));
        crossing.acceptAuthorizedConversion(f.conversion()); time.set(before.plusSeconds(4));
        var result=crossing.acceptVisible(f.visible()); assertEquals(day,result.settlement().prepared().budgetDate());
        assertEquals("0.123456",budgets.findAccount(advertiser,day).orElseThrow().chargedAmount().toPlainString());
        assertEquals("0.000000",budgets.findAccount(advertiser,today()).orElseThrow().chargedAmount().toPlainString());
    }
    @Test void cpaCpcAndCpmConcurrentHoldsShareOneHardLimit() throws Exception {
        long adv=IDS.incrementAndGet();budget(adv,today(),"0.5");
        var conversionQuotes=new ArrayList<FrozenConversionQuote>();var clickQuotes=new ArrayList<FrozenClickQuote>();var cpmQuotes=new ArrayList<FrozenCpmQuote>();
        var clickRepo=new ClickQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));var cpmRepo=new CpmQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));
        for(int i=0;i<6;i++) {
            conversionQuotes.add(quote(adv,1,"0.1",accounts.databaseNow().minusSeconds(2)));
            var basis=new GspPrice(new BigDecimal("0.1"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE);
            clickQuotes.add(clickRepo.freezeBefore(new ClickQuoteRepository.Draft(UUID.randomUUID(),UUID.randomUUID(),"viewer",1,adv,2,3,4,1,"CPC",new ClickPrice(BidType.CPC,basis)),Instant.now().plusSeconds(60)).orElseThrow());
            cpmQuotes.add(cpmRepo.freeze(UUID.randomUUID(),UUID.randomUUID(),adv,2,1,new CpmPrice(new BigDecimal("0.1"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE)));
        }
        var clicks=new ClickSettlementService(new ClickSettlementRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")),JSON),clickRepo,accounts,tokens,BudgetConfiguration.transactions(ds,5),true,true,new ClickSettlementService.FraudPolicy(true,20));
        var cpms=new ReservationService(accounts,cpmRepo,BudgetConfiguration.transactions(ds,5),true);
        var held=concurrent(18,i->switch(i%3) {
            case 0->service.prepare(conversionQuotes.get(i/3).quoteId(),"viewer").decision()==Decision.HELD;
            case 1->clicks.prepare(clickQuotes.get(i/3).quoteId(),"viewer").decision()==ClickSettlement.Decision.HELD;
            default->cpms.prepare(cpmQuotes.get(i/3).quoteId(),cpmQuotes.get(i/3).quoteId(),"viewer").decision()==Reservation.Decision.HELD;
        });
        assertEquals(5,held.stream().filter(Boolean::booleanValue).count());assertEquals("0.500000",budgets.findAccount(adv,today()).orElseThrow().heldAmount().toPlainString());
        for(var q:conversionQuotes)service.release(q.quoteId(),"viewer");for(var q:clickQuotes)clicks.release(q.quoteId(),"viewer");for(var q:cpmQuotes)cpms.release(q.quoteId(),"viewer");
        assertEquals("0.000000",budgets.findAccount(adv,today()).orElseThrow().heldAmount().toPlainString());
    }
    @Test void enabledOwnerConfigurationWiresIndependentExpiryAndAdmission() {
        new ApplicationContextRunner().withUserConfiguration(ConversionSettlementConfiguration.class)
                .withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.conversions-enabled=true","recsys.ad.billing.conversion-settlement-enabled=true","recsys.ad.billing.conversion-preparation-admission-enabled=false","recsys.ad.billing.conversion-receipt-admission-enabled=false")
                .withBean("adDbJdbc",JdbcTemplate.class,()->jdbc).withBean("adDbDataSource",javax.sql.DataSource.class,()->ds)
                .withBean("adDbSqlSessionTemplate",SqlSessionTemplate.class,()->sessions)
                .withBean(ObjectMapper.class,()->JSON).withBean(ConversionQuoteRepository.class,()->quotes).withBean(ReservationRepository.class,()->accounts)
                .withBean(DeliveryTokenCodec.class,()->tokens).withBean(io.micrometer.core.instrument.MeterRegistry.class,SimpleMeterRegistry::new)
                .run(c->{assertThat(c).hasNotFailed().hasSingleBean(ConversionSettlementService.class).hasSingleBean(ConversionExpiryJob.class);
                    var f=fixture();assertEquals(f.prepared(),c.getBean(ConversionSettlementService.class).prepare(f.id(),"viewer"));
                    assertThrows(IllegalStateException.class,()->c.getBean(ConversionSettlementService.class).acceptAuthorizedConversion(f.conversion()));});
    }
}
