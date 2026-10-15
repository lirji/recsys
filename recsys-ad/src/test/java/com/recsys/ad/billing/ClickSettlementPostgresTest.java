package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdProperties;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.*;
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
class ClickSettlementPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final AtomicLong IDS=new AtomicLong(80000);
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static ReservationRepository accounts;
    static BudgetAccountService budgets;
    static ClickQuoteRepository quotes;
    static ClickSettlementRepository repository;
    static ClickSettlementService service;
    static DeliveryTokenCodec tokens;
    @BeforeAll static void setup() throws Exception {
        ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); jdbc=new JdbcTemplate(ds);
        sessions=new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/ad/addb/**/*.xml"));
        for(String path:List.of(BudgetRepository.MIGRATION,CpmQuoteRepository.MIGRATION,ReservationRepository.MIGRATION,
                PreparationRepository.MIGRATION,ImpressionReceiptRepository.MIGRATION,BillingOutboxRepository.MIGRATION,
                FeedCandidateRepository.MIGRATION,ClickQuoteRepository.MIGRATION,ClickSettlementRepository.MIGRATION)) {
            try(var in=new ClassPathResource(path).getInputStream();var c=ds.getConnection();var st=c.createStatement()) {
                st.execute("SET recsys.migration_sha256='"+BudgetRepository.migrationSha256(path)+"'");
                st.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        // 只在隔离fixture的INSERT上模拟签发时刻，不关闭或修改产品历史不可变触发器。
        jdbc.execute("""
                CREATE TABLE test_click_clock(quote_id UUID PRIMARY KEY,issued_at TIMESTAMPTZ NOT NULL);
                COMMENT ON TABLE test_click_clock IS '测试独占的签发时钟夹具';
                COMMENT ON COLUMN test_click_clock.quote_id IS '仅匹配本测试创建的报价';
                COMMENT ON COLUMN test_click_clock.issued_at IS '模拟DB签发时刻，仍保持五分钟窗口';
                CREATE FUNCTION test_click_clock() RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE t TIMESTAMPTZ;
                BEGIN SELECT issued_at INTO t FROM test_click_clock WHERE quote_id=NEW.quote_id;
                IF FOUND THEN NEW.issued_at=t; NEW.expires_at=t+interval '5 minutes'; END IF; RETURN NEW; END; $$;
                CREATE TRIGGER test_click_clock BEFORE INSERT ON ad_click_quote FOR EACH ROW EXECUTE FUNCTION test_click_clock();
                """);
        accounts=new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))); quotes=new ClickQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));
        budgets=new BudgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))),BudgetConfiguration.transactions(ds,5));
        repository=new ClickSettlementRepository(sessions,JSON); repository.verifySchema();
        tokens=new DeliveryTokenCodec(JSON,"test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
        service=fresh(repository,accounts,true,true,20,tokens);
    }
    static ClickSettlementService fresh(ClickSettlementRepository repo,ReservationRepository money,boolean prep,boolean receipt,int max,DeliveryTokenCodec codec) {
        return new ClickSettlementService(repo,quotes,money,codec,BudgetConfiguration.transactions(ds,5),prep,receipt,new ClickSettlementService.FraudPolicy(true,max));
    }
    static LocalDate today() { return accounts.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); }
    static void budget(long advertiser,LocalDate day,String amount) {
        budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),day.toString(),"CNY",amount,0,"admin"));
    }
    record Fixture(FrozenClickQuote quote,Prepared prepared) {
        UUID id() { return quote.quoteId(); }
        Command command(Kind kind) { return new Command(UUID.randomUUID(),"admin",prepared.qualificationToken(),kind,kind==Kind.VISIBLE?1000:0,kind==Kind.VISIBLE?5000:0,kind==Kind.VISIBLE); }
    }
    Fixture fixture() { return fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0.123456",298); }
    Fixture fixture(long advertiser,long user,String amount,int remaining) {
        if(budgets.findAccount(advertiser,today()).isEmpty()) budget(advertiser,today(),"10");
        var q=quote(advertiser,user,amount,accounts.databaseNow().plusSeconds(remaining-300L),BidType.CPC);
        return new Fixture(q,service.prepare(q.quoteId(),"admin"));
    }
    FrozenClickQuote quote(long advertiser,long user,String amount,Instant issued,BidType type) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO test_click_clock(quote_id,issued_at) VALUES(?,?)",id,java.sql.Timestamp.from(issued));
        var basis=new GspPrice(new BigDecimal(amount),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal(amount));
        return quotes.freezeBefore(new ClickQuoteRepository.Draft(id,UUID.randomUUID(),"admin",user,advertiser,2,3,9007199254740993L,1,"冻结点击正文",new ClickPrice(type,basis)),Instant.now().plusSeconds(60)).orElseThrow();
    }
    BudgetAccount account(Fixture f) { return budgets.findAccount(f.quote().advertiserId(),f.prepared().budgetDate()).orElseThrow(); }
    int ledgerCount(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM ad_click_ledger WHERE qualification_id=?",Integer.class,id); }
    long rate(long user) { return jdbc.queryForObject("SELECT coalesce(sum(accepted_count),0) FROM ad_click_rate WHERE user_id=?",Long.class,user); }
    static <T> List<T> concurrent(int count,java.util.function.IntFunction<T> action) throws Exception {
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(count)) {
            var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++) { final int index=i; futures.add(workers.submit(()->{start.await();return action.apply(index);})); }
            start.countDown(); var results=new ArrayList<T>();
            for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS)); return results;
        }
    }

    /** 数据库禁止提交半成品；事务内两条读取路径也必须在映射可空字段前拒绝。 */
    @Test void unfinishedReceiptCannotBecomeCanonicalOrJoinedResult() {
        var f = fixture(); var command = f.command(Kind.CLICK); var before = account(f);
        assertThrows(DataAccessException.class, () -> repository.claim(command, f.id()));
        BudgetConfiguration.transactions(ds, 5).executeWithoutResult(tx -> {
            assertTrue(repository.lockQuote(f.id()));
            assertTrue(repository.claim(command, f.id()));
            assertEquals("发现未完成点击回执", assertThrows(IllegalStateException.class,
                    () -> repository.canonical(command.receiptId())).getMessage());
            assertEquals("发现未完成点击回执", assertThrows(IllegalStateException.class,
                    () -> repository.findReceipt(command.receiptId(), "admin")).getMessage());
            assertEquals(State.HELD, repository.find(f.id(), "admin").orElseThrow().state());
            tx.setRollbackOnly();
        });
        assertTrue(repository.findReceipt(command.receiptId(), "admin").isEmpty());
        assertEquals(before, account(f)); assertEquals(0, ledgerCount(f.id()));
    }

    @Test void preparationConcurrencyAndRestartHoldExactlyOnceWithoutRenewing() throws Exception {
        long advertiser=IDS.incrementAndGet(); budget(advertiser,today(),"1");
        var q=quote(advertiser,IDS.incrementAndGet(),"0.123456",accounts.databaseNow().minusSeconds(2),BidType.OCPC);
        var prepared=concurrent(12,i->service.prepare(q.quoteId(),"admin"));
        assertTrue(prepared.stream().allMatch(prepared.getFirst()::equals));
        assertEquals("0.123456",budgets.findAccount(advertiser,today()).orElseThrow().heldAmount().toPlainString());
        assertEquals(q.expiresAt(),prepared.getFirst().expiresAt());
        service.release(q.quoteId(),"admin");
        assertEquals(prepared.getFirst(),fresh(repository,accounts,false,false,20,tokens).prepare(q.quoteId(),"admin"));
        assertEquals("0.000000",budgets.findAccount(advertiser,today()).orElseThrow().heldAmount().toPlainString());
    }
    @Test void prepareRejectsAreDurableAfterBudgetChangesAndExpiredQuotesNeverHold() {
        long advertiser=IDS.incrementAndGet();
        var missing=quote(advertiser,IDS.incrementAndGet(),"1",accounts.databaseNow(),BidType.CPC);
        assertEquals(Decision.ACCOUNT_MISSING,service.prepare(missing.quoteId(),"admin").decision());
        budget(advertiser,today(),"0.5");
        assertEquals(Decision.ACCOUNT_MISSING,service.prepare(missing.quoteId(),"admin").decision());
        var insufficient=quote(advertiser,IDS.incrementAndGet(),"1",accounts.databaseNow(),BidType.CPC);
        assertEquals(Decision.INSUFFICIENT_BUDGET,service.prepare(insufficient.quoteId(),"admin").decision());
        var expired=quote(advertiser,IDS.incrementAndGet(),"0.1",accounts.databaseNow().minusSeconds(301),BidType.CPC);
        assertEquals(Decision.QUOTE_EXPIRED,service.prepare(expired.quoteId(),"admin").decision());
        assertNull(service.find(expired.quoteId(),"admin").orElseThrow().prepared().qualificationToken());
        assertEquals("0.000000",budgets.findAccount(advertiser,today()).orElseThrow().heldAmount().toPlainString());
    }
    @Test void clickFirstWaitsThenVisibleAtomicallyCapturesAndOriginalDecisionRemains() {
        var f=fixture(); var click=f.command(Kind.CLICK); var waiting=service.accept(click);
        assertEquals(ReceiptStatus.ACCEPTED_WAITING,waiting.receipt().status());
        assertEquals(Progress.WAITING_FOR_VISIBLE,waiting.settlement().progress()); assertNull(waiting.settlement().chargedAmount());
        assertEquals("0.123456",account(f).heldAmount().toPlainString()); assertEquals(0,ledgerCount(f.id()));
        var captured=service.accept(f.command(Kind.VISIBLE));
        assertEquals(ReceiptStatus.ACCEPTED_CAPTURED,captured.receipt().status()); assertEquals(Progress.CAPTURED,captured.settlement().progress());
        var restored=service.accept(click); assertEquals(waiting.receipt(),restored.receipt());
        assertEquals(captured.settlement(),restored.settlement());
        assertEquals("0.000000",account(f).heldAmount().toPlainString()); assertEquals("0.123456",account(f).chargedAmount().toPlainString());
        assertEquals(1,ledgerCount(f.id())); assertEquals(1,rate(f.quote().userId()));
    }
    @Test void visibleFirstAndConcurrentDistinctReceiptsStillProduceOneCapture() throws Exception {
        var f=fixture(); var first=service.accept(f.command(Kind.VISIBLE));
        assertEquals(Progress.WAITING_FOR_CLICK,first.settlement().progress());
        var results=concurrent(12,i->service.accept(f.command(i%2==0?Kind.CLICK:Kind.VISIBLE)));
        assertEquals(1,results.stream().filter(r->r.receipt().status()==ReceiptStatus.ACCEPTED_CAPTURED).count());
        assertEquals(11,results.stream().filter(r->r.receipt().status()==ReceiptStatus.ALREADY_ACCEPTED).count());
        assertEquals(1,ledgerCount(f.id())); assertEquals(1,rate(f.quote().userId()));
        assertEquals("0.123456",account(f).chargedAmount().toPlainString());
    }
    @Test void simultaneousConditionsAndSameCommandRetriesHaveOneCanonicalPerKind() throws Exception {
        var f=fixture(); var click=f.command(Kind.CLICK); var visible=f.command(Kind.VISIBLE);
        var results=concurrent(12,i->service.accept(i%2==0?click:visible));
        var clickReceipt=results.stream().filter(r->r.receipt().kind()==Kind.CLICK).findFirst().orElseThrow().receipt();
        assertTrue(results.stream().filter(r->r.receipt().kind()==Kind.CLICK).allMatch(r->r.receipt().equals(clickReceipt)));
        assertEquals(1,ledgerCount(f.id())); assertEquals(1,rate(f.quote().userId()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ad_click_receipt WHERE qualification_id=?",Integer.class,f.id()));
    }
    @Test void crossQualificationReceiptIdRaceConflictsWithoutDeadlockOrDoubleCount() throws Exception {
        var first=fixture(); var second=fixture(); UUID receipt=UUID.randomUUID();
        var a=new Command(receipt,"admin",first.prepared().qualificationToken(),Kind.CLICK,0,0,false);
        var b=new Command(receipt,"admin",second.prepared().qualificationToken(),Kind.CLICK,0,0,false);
        var results=concurrent(2,i->{ try { service.accept(i==0?a:b); return "accepted"; } catch(ClickSettlement.Conflict ex) { return "conflict"; } });
        assertEquals(1,Collections.frequency(results,"accepted")); assertEquals(1,Collections.frequency(results,"conflict"));
        assertEquals(1,rate(first.quote().userId())+rate(second.quote().userId()));
        assertEquals(0,ledgerCount(first.id())+ledgerCount(second.id()));
    }
    @Test void payloadMutationSubjectAndPurposeIsolationCannotWriteOrReuseMoney() {
        var f=fixture(); var command=f.command(Kind.CLICK); service.accept(command);
        assertThrows(ClickSettlement.Conflict.class,()->service.accept(new Command(command.receiptId(),"admin",command.qualificationToken(),Kind.VISIBLE,1000,5000,true)));
        assertThrows(Reservation.AccessDenied.class,()->service.findReceipt(command.receiptId(),"another"));
        assertThrows(Reservation.AccessDenied.class,()->service.prepare(f.id(),"another"));
        assertThrows(Reservation.AccessDenied.class,()->service.release(f.id(),"another"));
        assertThrows(Reservation.AccessDenied.class,()->service.accept(new Command(UUID.randomUUID(),"another",command.qualificationToken(),Kind.CLICK,0,0,false)));
        var proof=tokens.verify(command.qualificationToken(),ClickSettlement.TOKEN_PURPOSE,Proof.class);
        for(String token:List.of(tokens.sign("qualification",proof),tokens.sign(ClickSettlement.TOKEN_PURPOSE,
                new Proof(f.id(),"admin","0".repeat(64),proof.budgetDate(),ClickSettlement.POLICY)))) {
            assertThrows(SandboxDelivery.InvalidProof.class,()->service.accept(new Command(UUID.randomUUID(),"admin",token,Kind.CLICK,0,0,false)));
        }
        assertEquals(1,rate(f.quote().userId())); assertEquals(0,ledgerCount(f.id()));
    }
    @Test void failedVisibilityCanBeNewObservationButSameReceiptDecisionIsImmutable() {
        var f=fixture();
        for(var command:List.of(new Command(UUID.randomUUID(),"admin",f.prepared().qualificationToken(),Kind.VISIBLE,999,5000,true),
                new Command(UUID.randomUUID(),"admin",f.prepared().qualificationToken(),Kind.VISIBLE,1000,4999,true),
                new Command(UUID.randomUUID(),"admin",f.prepared().qualificationToken(),Kind.VISIBLE,1000,5000,false),
                new Command(UUID.randomUUID(),"admin",f.prepared().qualificationToken(),Kind.VISIBLE,300000,5000,true))) {
            var rejected=service.accept(command); assertEquals(ReceiptStatus.POLICY_NOT_MET,rejected.receipt().status());
            assertEquals(rejected,service.accept(command)); assertEquals(Progress.WAITING_FOR_BOTH,rejected.settlement().progress());
        }
        service.accept(f.command(Kind.CLICK)); assertEquals(Progress.CAPTURED,service.accept(f.command(Kind.VISIBLE)).settlement().progress());
        assertThrows(IllegalArgumentException.class,()->new Command(UUID.randomUUID(),"admin",f.prepared().qualificationToken(),Kind.CLICK,1,0,false));
    }
    @Test void fraudLimitIsAtomicAcrossAdvertisersAndRejectedQualificationNeverRecovers() throws Exception {
        long user=IDS.incrementAndGet(); var fixtures=new ArrayList<Fixture>();
        for(int i=0;i<8;i++) fixtures.add(fixture(IDS.incrementAndGet(),user,"0.123456",298));
        var fixed=spy(new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))));
        // 固定本组事务的DB决策时刻，避免测试恰跨整分钟把两个合法窗口误判成超限。
        doReturn(accounts.databaseNow()).when(fixed).databaseNow();
        var limited=fresh(repository,fixed,true,true,2,tokens);
        var results=concurrent(8,i->limited.accept(fixtures.get(i).command(Kind.CLICK)));
        assertEquals(2,results.stream().filter(r->r.receipt().status()==ReceiptStatus.ACCEPTED_WAITING).count());
        assertEquals(6,results.stream().filter(r->r.receipt().status()==ReceiptStatus.FRAUD_REJECTED).count()); assertEquals(2,rate(user));
        for(int i=0;i<fixtures.size();i++) if(results.get(i).receipt().status()==ReceiptStatus.FRAUD_REJECTED) {
            var f=fixtures.get(i); assertEquals("0.000000",account(f).heldAmount().toPlainString());
            assertEquals(ReceiptStatus.NOT_HELD,service.accept(f.command(Kind.VISIBLE)).receipt().status());
            assertEquals(ReceiptStatus.NOT_HELD,service.accept(f.command(Kind.CLICK)).receipt().status()); assertEquals(0,ledgerCount(f.id()));
        }
    }
    @Test void failureAfterOutboxInsertRollsBackSecondConditionMoneyRateAndFacts() {
        var f=fixture(); var visible=f.command(Kind.VISIBLE); var waiting=service.accept(visible); var click=f.command(Kind.CLICK);
        var failing=spy(new ClickSettlementRepository(sessions,JSON));
        doAnswer(call->{ call.callRealMethod(); throw new IllegalStateException("injected crash after outbox"); }).when(failing).appendOutbox(any());
        assertThrows(IllegalStateException.class,()->fresh(failing,accounts,true,true,20,tokens).accept(click));
        assertEquals(waiting,service.findReceipt(visible.receiptId(),"admin").orElseThrow());
        assertTrue(service.findReceipt(click.receiptId(),"admin").isEmpty()); assertEquals(0,rate(f.quote().userId()));
        assertEquals(0,ledgerCount(f.id())); assertEquals("0.123456",account(f).heldAmount().toPlainString());
        assertEquals("0.000000",account(f).chargedAmount().toPlainString());
        var recovered=fresh(new ClickSettlementRepository(sessions,JSON),accounts,true,true,20,tokens).accept(click);
        assertEquals(Progress.CAPTURED,recovered.settlement().progress()); assertEquals(1,rate(f.quote().userId())); assertEquals(1,ledgerCount(f.id()));
    }
    @Test void failureAfterFirstReceiptDecisionRollsBackWaitingPointerAndRate() {
        var f=fixture(); var click=f.command(Kind.CLICK); var failing=spy(new ClickSettlementRepository(sessions,JSON));
        doAnswer(call->{call.callRealMethod(); throw new IllegalStateException("injected response save crash");}).when(failing).complete(any());
        assertThrows(IllegalStateException.class,()->fresh(failing,accounts,true,true,20,tokens).accept(click));
        assertEquals(0,rate(f.quote().userId())); assertTrue(service.findReceipt(click.receiptId(),"admin").isEmpty());
        assertEquals(Progress.WAITING_FOR_BOTH,service.find(f.id(),"admin").orElseThrow().progress());
        assertEquals(ReceiptStatus.ACCEPTED_WAITING,service.accept(click).receipt().status()); assertEquals(1,rate(f.quote().userId()));
    }
    @Test void fraudRejectionReleaseAlsoRollsBackIfDecisionCannotCommit() throws Exception {
        long user=IDS.incrementAndGet(); var first=fixture(IDS.incrementAndGet(),user,"0.123456",298);
        var denied=fixture(IDS.incrementAndGet(),user,"0.123456",298);
        var fixed=spy(new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")))); doReturn(accounts.databaseNow()).when(fixed).databaseNow();
        fresh(repository,fixed,true,true,1,tokens).accept(first.command(Kind.CLICK));
        var command=denied.command(Kind.CLICK); var failing=spy(new ClickSettlementRepository(sessions,JSON));
        doAnswer(call->{call.callRealMethod(); throw new IllegalStateException("injected reject save failure");}).when(failing).complete(any());
        assertThrows(IllegalStateException.class,()->fresh(failing,fixed,true,true,1,tokens).accept(command));
        assertTrue(service.findReceipt(command.receiptId(),"admin").isEmpty());
        assertEquals(State.HELD,service.find(denied.id(),"admin").orElseThrow().state()); assertEquals("0.123456",account(denied).heldAmount().toPlainString());
        var result=fresh(repository,fixed,true,true,1,tokens).accept(command);
        assertEquals(ReceiptStatus.FRAUD_REJECTED,result.receipt().status()); assertEquals(CloseReason.FRAUD_REJECTED,result.settlement().closeReason());
        assertEquals("0.000000",account(denied).heldAmount().toPlainString()); assertEquals(1,rate(user));
    }
    @Test void minuteResetAndExplicitFraudDisableKeepQualificationDeduplication() {
        var f=fixture();
        jdbc.update("INSERT INTO ad_click_rate(user_id,minute_start,accepted_count) VALUES(?,date_trunc('minute',clock_timestamp())-interval '1 minute',100)",f.quote().userId());
        var click=f.command(Kind.CLICK); assertEquals(ReceiptStatus.ACCEPTED_WAITING,service.accept(click).receipt().status());
        assertEquals(1,rate(f.quote().userId())); service.accept(f.command(Kind.CLICK)); assertEquals(1,rate(f.quote().userId()));
        var next=fixture(); var disabled=new ClickSettlementService(repository,quotes,accounts,tokens,BudgetConfiguration.transactions(ds,5),true,true,new ClickSettlementService.FraudPolicy(false,1));
        disabled.accept(next.command(Kind.VISIBLE)); disabled.accept(next.command(Kind.CLICK)); disabled.accept(next.command(Kind.CLICK));
        assertEquals(1,ledgerCount(next.id())); assertEquals(0,rate(next.quote().userId()));
    }
    @Test void releaseVersusSecondConditionHasExactlyOneTerminalAndNoNegativeHeld() throws Exception {
        for(int attempt=0;attempt<4;attempt++) {
            var f=fixture(); service.accept(f.command(Kind.CLICK));
            concurrent(2,i->i==0?service.release(f.id(),"admin").orElseThrow():service.accept(f.command(Kind.VISIBLE)).settlement());
            var finalState=service.find(f.id(),"admin").orElseThrow(); assertTrue(finalState.state()==State.CAPTURED || finalState.state()==State.RELEASED);
            assertEquals("0.000000",account(f).heldAmount().toPlainString());
            assertEquals(finalState.state()==State.CAPTURED?1:0,ledgerCount(f.id()));
            assertEquals(finalState,service.release(f.id(),"admin").orElseThrow());
            assertEquals(finalState.state()==State.CAPTURED?"0.123456":"0.000000",account(f).chargedAmount().toPlainString());
        }
    }
    @Test void lockWaitPastDeadlineRejectsSecondConditionAndReleasesOriginalHold() throws Exception {
        var f=fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0.123456",2); service.accept(f.command(Kind.CLICK));
        try(var connection=ds.getConnection();var workers=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT 1 FROM ad_budget_account WHERE advertiser_id=? FOR UPDATE")) {
                statement.setLong(1,f.quote().advertiserId()); statement.executeQuery().close();
            }
            var future=workers.submit(()->service.accept(f.command(Kind.VISIBLE)));
            boolean waiting=false; long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<end) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%ad_budget_account%')",Boolean.class));
                if(waiting) break; Thread.sleep(10);
            }
            assertTrue(waiting,"必须真实等待共享账户行锁");
            while(accounts.databaseNow().isBefore(f.quote().expiresAt())) Thread.sleep(20);
            connection.commit(); var result=future.get(5,TimeUnit.SECONDS);
            assertEquals(ReceiptStatus.EXPIRED,result.receipt().status()); assertEquals(State.EXPIRED,result.settlement().state());
        }
        assertEquals(0,ledgerCount(f.id())); assertEquals("0.000000",account(f).heldAmount().toPlainString());
        assertEquals(ReceiptStatus.ALREADY_ACCEPTED,service.accept(f.command(Kind.CLICK)).receipt().status());
        assertEquals(State.EXPIRED,service.find(f.id(),"admin").orElseThrow().state());
    }
    @Test void twoExpiryWorkersSkipLockedQuoteAndRecoverAfterRestartWithAdmissionPaused() throws Exception {
        var a=fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0.123456",1);
        var b=fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0.123456",1);
        service.accept(a.command(Kind.CLICK));
        while(accounts.databaseNow().isBefore(b.quote().expiresAt())) Thread.sleep(20);
        var paused=fresh(repository,accounts,false,false,20,tokens);
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            try(var statement=c.prepareStatement("SELECT quote_id FROM ad_click_quote WHERE quote_id=? FOR UPDATE")) {
                statement.setObject(1,a.id()); statement.executeQuery().close();
            }
            assertTrue(paused.expireOne()); assertEquals(State.EXPIRED,paused.find(b.id(),"admin").orElseThrow().state());
            assertEquals(State.HELD,paused.find(a.id(),"admin").orElseThrow().state()); c.commit();
        }
        concurrent(2,i->paused.expireOne());
        assertEquals(State.EXPIRED,paused.find(a.id(),"admin").orElseThrow().state());
        assertEquals("0.000000",account(a).heldAmount().toPlainString()); assertEquals("0.000000",account(b).heldAmount().toPlainString());
    }
    @Test void sharedCpmAndClickAccountCannotExceedHardBudgetUnderConcurrentHolds() throws Exception {
        long advertiser=IDS.incrementAndGet(); budget(advertiser,today(),"0.500000");
        var clicks=new ArrayList<FrozenClickQuote>(); var cpms=new ArrayList<FrozenCpmQuote>(); var cpmQuotes=new CpmQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));
        for(int i=0;i<8;i++) {
            clicks.add(quote(advertiser,IDS.incrementAndGet(),"0.1",accounts.databaseNow().minusSeconds(2),BidType.CPC));
            cpms.add(cpmQuotes.freeze(UUID.randomUUID(),UUID.randomUUID(),advertiser,2,1,new CpmPrice(new BigDecimal("0.1"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE)));
        }
        var old=new ReservationService(accounts,cpmQuotes,BudgetConfiguration.transactions(ds,5),true);
        var held=concurrent(16,i-> i%2==0 ? service.prepare(clicks.get(i/2).quoteId(),"admin").decision()==Decision.HELD
                : old.prepare(cpms.get(i/2).quoteId(),cpms.get(i/2).quoteId(),"admin").decision()==Reservation.Decision.HELD);
        assertEquals(5,held.stream().filter(Boolean::booleanValue).count());
        var a=budgets.findAccount(advertiser,today()).orElseThrow(); assertEquals("0.500000",a.heldAmount().toPlainString());
        for(var q:clicks) service.release(q.quoteId(),"admin"); for(var q:cpms) old.release(q.quoteId(),"admin");
        assertEquals("0.000000",budgets.findAccount(advertiser,today()).orElseThrow().heldAmount().toPlainString());
    }
    @Test void crossMidnightCapturedFundsUseOriginalBudgetDate() throws Exception {
        long advertiser=IDS.incrementAndGet(); var day=today().minusDays(1);
        budget(advertiser,day,"1"); budget(advertiser,today(),"1");
        var before=day.atTime(23,59,58).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        var clocked=spy(new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))));
        // 模拟两个真实DB时刻的跨午夜边界；资金/锁/约束仍由真实PG执行，不移动或篡改已冻结历史。
        var time=new java.util.concurrent.atomic.AtomicReference<>(before);
        doAnswer(call->jdbc.queryForObject("SELECT ?::timestamptz",OffsetDateTime.class,java.sql.Timestamp.from(time.get())).toInstant()).when(clocked).databaseNow();
        var crossing=fresh(repository,clocked,true,true,20,tokens);
        var q=quote(advertiser,IDS.incrementAndGet(),"0.123456",before.minusSeconds(2),BidType.OCPC);
        var f=new Fixture(q,crossing.prepare(q.quoteId(),"admin"));
        crossing.accept(f.command(Kind.CLICK)); time.set(before.plusSeconds(4));
        var result=crossing.accept(f.command(Kind.VISIBLE)); assertEquals(day,result.settlement().prepared().budgetDate());
        assertEquals("0.123456",budgets.findAccount(advertiser,day).orElseThrow().chargedAmount().toPlainString());
        assertEquals("0.000000",budgets.findAccount(advertiser,today()).orElseThrow().chargedAmount().toPlainString());
    }
    @Test void zeroPriceStillHasOneLedgerAndVersionedOutboxWithoutProof() throws Exception {
        var f=fixture(IDS.incrementAndGet(),IDS.incrementAndGet(),"0",298); service.accept(f.command(Kind.VISIBLE));
        var result=service.accept(f.command(Kind.CLICK)); assertEquals("0.000000",result.settlement().chargedAmount());
        assertNotNull(result.settlement().ledgerId()); assertEquals(1,ledgerCount(f.id()));
        String payload=jdbc.queryForObject("SELECT payload_json FROM ad_click_outbox WHERE event_id=?",String.class,result.settlement().ledgerId());
        var event=JSON.readValue(payload,CapturedEvent.class); assertEquals(ClickPrice.UNIT,event.priceUnit()); assertEquals(CapturedEvent.TYPE,event.eventType());
        assertEquals("9007199254740993",event.itemId()); assertEquals("0.000000",event.amount());
        assertEquals(result.settlement().clickReceiptId().toString(),event.clickReceiptId());
        assertEquals(result.settlement().visibleReceiptId().toString(),event.visibleReceiptId());
        assertTrue(JSON.readTree(payload).get("acceptedAt").isTextual()); assertFalse(payload.contains(f.prepared().qualificationToken())); assertFalse(payload.contains("admin"));
        assertEquals("READY",jdbc.queryForObject("SELECT delivery_state FROM ad_click_outbox WHERE event_id=?",String.class,result.settlement().ledgerId()));
    }
    @Test void originalReceiptSurvivesKeyRotationAndPauseButNewCommandsCannotEnter() {
        var f=fixture(); var command=f.command(Kind.CLICK); var result=service.accept(command);
        var rotated=new DeliveryTokenCodec(JSON,"new",Map.of("new",Base64.getEncoder().encodeToString(new byte[32])));
        var paused=fresh(repository,accounts,false,false,20,rotated);
        assertEquals(result,paused.accept(command)); assertEquals(result,paused.findReceipt(command.receiptId(),"admin").orElseThrow());
        assertEquals(f.prepared(),paused.prepare(f.id(),"admin"));
        assertThrows(IllegalStateException.class,()->paused.accept(f.command(Kind.VISIBLE)));
        assertEquals(State.RELEASED,paused.release(f.id(),"admin").orElseThrow().state());
        assertEquals(ReceiptStatus.ACCEPTED_WAITING,paused.accept(command).receipt().status());
        assertEquals(Progress.RELEASED,paused.accept(command).settlement().progress());
        var other=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        assertThrows(IllegalArgumentException.class,()->fresh(new ClickSettlementRepository(new SqlSessionTemplate(SpringSqlSessions.create(other, "classpath*:mapper/ad/addb/**/*.xml")),JSON),accounts,true,true,20,tokens));
    }
    @Test void migrationGuardsDecisionsHistoryAndEveryColumnHasComment() {
        var f=fixture(); var click=f.command(Kind.CLICK); service.accept(click); var result=service.accept(f.command(Kind.VISIBLE));
        for(String sql:List.of("UPDATE ad_click_ledger SET amount=0 WHERE qualification_id=?","DELETE FROM ad_click_ledger WHERE qualification_id=?",
                "UPDATE ad_click_settlement SET state='HELD',closed_at=NULL,close_reason=NULL,ledger_id=NULL WHERE qualification_id=?",
                "DELETE FROM ad_click_settlement WHERE qualification_id=?","UPDATE ad_click_receipt SET decided_at=clock_timestamp() WHERE qualification_id=?")) {
            assertThrows(DataAccessException.class,()->jdbc.update(sql,f.id()));
        }
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE ad_click_outbox SET payload_json='{}' WHERE event_id=?",result.settlement().ledgerId()));
        // 延迟约束在提交时也拒绝只领取没有完成的命令，防止恢复时遇到永久PENDING。
        assertThrows(DataAccessException.class,()->repository.claim(f.command(Kind.CLICK),f.id()));
        for(String table:List.of("ad_click_settlement","ad_click_receipt","ad_click_rate","ad_click_ledger","ad_click_outbox")) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",Integer.class,table));
            assertNotNull(jdbc.queryForObject("SELECT obj_description(?::regclass,'pg_class')",String.class,table));
        }
    }
    @Test void installationDefaultsOwnerMigrationAndFraudConfigurationFailClosed() throws Exception {
        new ApplicationContextRunner().withUserConfiguration(ClickSettlementConfiguration.class)
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ClickSettlementService.class));
        var runner=new ApplicationContextRunner().withUserConfiguration(ClickSettlementConfiguration.class)
                .withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.clicks-enabled=true","recsys.ad.billing.click-settlement-enabled=true")
                .withBean("adDbJdbc",JdbcTemplate.class,()->jdbc).withBean("adDbDataSource",javax.sql.DataSource.class,()->ds)
                .withBean("adDbSqlSessionTemplate",SqlSessionTemplate.class,()->sessions)
                .withBean(ObjectMapper.class,()->JSON).withBean(ClickQuoteRepository.class,()->quotes)
                .withBean(ReservationRepository.class,()->accounts).withBean(DeliveryTokenCodec.class,()->tokens)
                .withBean(AdProperties.class,AdProperties::new).withBean(io.micrometer.core.instrument.MeterRegistry.class,SimpleMeterRegistry::new);
        runner.run(c->assertThat(c).hasNotFailed().hasSingleBean(ClickSettlementService.class));
        runner.withPropertyValues("recsys.ad.serving.mode=grpc","spring.application.name=recsys-rec-engine")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ClickSettlementService.class));
        jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=? WHERE version=9","0".repeat(64));
        try { runner.run(c->assertThat(c).hasFailed()); new ClickQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))).verifySchema(); new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))).verifySchema(); }
        finally { jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=? WHERE version=9",BudgetRepository.migrationSha256(ClickSettlementRepository.MIGRATION)); }
        assertThrows(IllegalArgumentException.class,()->new ClickSettlementService.FraudPolicy(true,0));
    }
}
