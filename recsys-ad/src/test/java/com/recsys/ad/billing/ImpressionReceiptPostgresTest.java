package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdPipeline;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ImpressionReceipt.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
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

/** 真实 PG 证明四份业务效果同事务，不能用 mock 计数代替资金与故障恢复验证。 */
@Tag("money-chain")
@Testcontainers
class ImpressionReceiptPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final AtomicLong IDS = new AtomicLong(50000);
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static BudgetAccountService budgets;
    static CpmQuoteRepository quotes;
    static ReservationRepository reservations;
    static ReservationService reservationService;
    static PreparationRepository preparations;
    static ImpressionReceiptRepository receipts;
    static DeliveryTokenCodec tokens;
    static SandboxPreparationService preparationService;
    static ImpressionReceiptService service;
    @BeforeAll static void setup() throws Exception {
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); jdbc = new JdbcTemplate(ds);
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"));
        for (String script : List.of(BudgetRepository.MIGRATION, CpmQuoteRepository.MIGRATION, ReservationRepository.MIGRATION,
                PreparationRepository.MIGRATION, ImpressionReceiptRepository.MIGRATION)) {
            try (var c=ds.getConnection(); var stmt=c.createStatement()) {
                stmt.execute("SET recsys.migration_sha256 = '"+BudgetRepository.migrationSha256(script)+"'");
                ScriptUtils.executeSqlScript(c, new ClassPathResource(script));
            }
        }
        budgets = new BudgetConfiguration().budgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))), ds, 5);
        quotes = new CpmQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))); reservations = new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));
        reservationService = new ReservationService(reservations, quotes, BudgetConfiguration.transactions(ds,5), true);
        preparations = new PreparationRepository(sessions,JSON); receipts = new ImpressionReceiptRepository(sessions,JSON); receipts.verifySchema();
        tokens = new DeliveryTokenCodec(JSON,"test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
        preparationService = new SandboxPreparationService(mock(AdPipeline.class), preparations, quotes, reservationService, tokens);
        service = fresh(receipts,reservations);
    }
    static ImpressionReceiptService fresh(ImpressionReceiptRepository r, ReservationRepository rs) {
        return new ImpressionReceiptService(r,preparations,rs,quotes,tokens,BudgetConfiguration.transactions(ds,5),true);
    }
    static LocalDate today() { return reservations.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); }
    record Fixture(long advertiser, UUID id, SandboxDelivery.Prepared preparation) {
        Command command() { return new Command(UUID.randomUUID(),"admin",preparation.qualificationToken(),1000,5000,true); }
    }
    Fixture fixture() { return fixture(298); }
    Fixture fixture(int remainingSeconds) {
        long advertiser = IDS.incrementAndGet(); UUID id=UUID.randomUUID(), operation=UUID.randomUUID();
        budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),today().toString(),"CNY","10",0,"admin"));
        var ad = new SponsoredAd(10,20,advertiser,0,"title",AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,88,"CPM");
        var price = new CpmPrice(new BigDecimal("0.123456"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE);
        quotes.freeze(id,operation,advertiser,ad.adId(),1,price);
        // 隔离历史 fixture：模拟资格签发后已过两秒或临近到期，仍严格保持五分钟原期限。
        jdbc.update("""
                WITH t AS (SELECT clock_timestamp() + (? * interval '1 second') AS expiry)
                UPDATE ad_cpm_quote SET issued_at=t.expiry-interval '5 minutes',expires_at=t.expiry FROM t WHERE quote_id=?
                """,remainingSeconds,id);
        var offer=new Offer(operation,"admin",1,id,ad,price,"test",reservations.databaseNow().plusSeconds(300));
        var prepared=preparationService.prepare(new Prepare(operation,"admin",1,id,1,tokens.sign("offer",offer)));
        assertEquals(SandboxDelivery.Status.QUALIFIED,prepared.status());
        return new Fixture(advertiser,id,prepared);
    }
    BudgetAccount account(Fixture fixture) { return budgets.findAccount(fixture.advertiser(),today()).orElseThrow(); }
    int count(String table, String column, UUID id) {
        // 测试 helper 仅固定表/列常量调用；产品 SQL 均参数绑定且不使用此 helper。
        return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+column+"=?",Integer.class,id);
    }
    @Test void sameReceiptConcurrencyCapturesOnceAndRestartReturnsOriginal() throws Exception {
        var fixture=fixture(); var command=fixture.command();
        var results=concurrent(10,i->service.accept(command));
        for (var result:results) assertEquals(results.getFirst(),result);
        var first=results.getFirst(); assertEquals(ImpressionReceipt.Status.ACCEPTED,first.status());
        assertEquals("0.123456",first.chargedAmount()); assertEquals("0.000000",account(fixture).heldAmount().toPlainString());
        assertEquals("0.123456",account(fixture).chargedAmount().toPlainString()); assertEquals(3,account(fixture).version());
        assertEquals(1,count("ad_billing_ledger","qualification_id",fixture.id())); assertEquals(1,count("ad_billing_outbox","event_id",first.ledgerId()));
        assertEquals(first,fresh(new ImpressionReceiptRepository(sessions,JSON),reservations).accept(command));
        assertEquals(first,service.find(command.receiptId(),"admin").orElseThrow());
    }
    @Test void differentReceiptIdsShareOneSemanticCaptureAndOneOutbox() throws Exception {
        var fixture=fixture(); var results=concurrent(10,i->service.accept(fixture.command()));
        assertEquals(1,results.stream().filter(r->r.status()==ImpressionReceipt.Status.ACCEPTED).count());
        assertEquals(9,results.stream().filter(r->r.status()==ImpressionReceipt.Status.ALREADY_ACCEPTED).count());
        assertEquals(1,results.stream().map(Result::ledgerId).distinct().count());
        assertEquals(1,results.stream().map(Result::canonicalReceiptId).distinct().count());
        assertEquals(1,count("ad_billing_ledger","qualification_id",fixture.id())); assertEquals("0.123456",account(fixture).chargedAmount().toPlainString());
    }
    @Test void mutatedPayloadWrongSubjectAndInvalidProofNeverWriteMoney() {
        var fixture=fixture(); var command=fixture.command(); service.accept(command);
        assertThrows(ImpressionReceipt.Conflict.class,()->service.accept(new Command(command.receiptId(),"admin",command.qualificationToken(),1001,5000,true)));
        assertThrows(Reservation.AccessDenied.class,()->service.find(command.receiptId(),"other"));
        assertThrows(Reservation.AccessDenied.class,()->service.accept(new Command(UUID.randomUUID(),"other",command.qualificationToken(),1000,5000,true)));
        var invalid=new Command(UUID.randomUUID(),"admin","tampered",1000,5000,true);
        assertThrows(InvalidProof.class,()->service.accept(invalid)); assertTrue(service.find(invalid.receiptId(),"admin").isEmpty());
        var q=tokens.verify(command.qualificationToken(),"qualification",Qualification.class);
        var forged=tokens.sign("qualification",new Qualification(q.operationId(),UUID.randomUUID(),q.subject(),q.userId(),q.advertiserId(),q.adId(),q.creativeId(),q.slot(),q.amount(),q.trafficClass(),q.currency(),q.policy(),q.issuedAt(),q.expiresAt()));
        assertThrows(InvalidProof.class,()->service.accept(new Command(UUID.randomUUID(),"admin",forged,1000,5000,true)));
        assertEquals("0.123456",account(fixture).chargedAmount().toPlainString());
    }
    @Test void policyRejectionsAreDurableAndOnlyNewValidObservationMayCapture() {
        var fixture=fixture(); String token=fixture.preparation().qualificationToken();
        for (var command:List.of(new Command(UUID.randomUUID(),"admin",token,999,5000,true),
                new Command(UUID.randomUUID(),"admin",token,1000,4999,true),new Command(UUID.randomUUID(),"admin",token,1000,5000,false),
                new Command(UUID.randomUUID(),"admin",token,200000,10000,true))) {
            var rejected=service.accept(command); assertEquals(ImpressionReceipt.Status.POLICY_NOT_MET,rejected.status());
            assertEquals(rejected,service.accept(command)); assertEquals("0.000000",rejected.chargedAmount());
        }
        assertEquals("0.000000",account(fixture).chargedAmount().toPlainString());
        assertEquals(ImpressionReceipt.Status.ACCEPTED,service.accept(fixture.command()).status());
    }
    @Test void eachFailureBoundaryRollsBackMoneyReservationReceiptLedgerAndOutbox() throws Exception {
        for (int point=0;point<4;point++) {
            var fixture=fixture(); var command=fixture.command(); final int failurePoint=point;
            var faultyReceipts=new ImpressionReceiptRepository(sessions,JSON) {
                @Override public void appendLedger(UUID ledgerId,Command c,Qualification q,Reservation r,long v,Instant at) {
                    super.appendLedger(ledgerId,c,q,r,v,at); if(failurePoint==1) throw new IllegalStateException("after ledger");
                }
                @Override public void appendOutbox(CapturedEvent event) {
                    super.appendOutbox(event); if(failurePoint==2) throw new IllegalStateException("after outbox");
                }
                @Override public Result complete(Result result) {
                    super.complete(result); if(failurePoint==3) throw new IllegalStateException("after result"); return result;
                }
            };
            var faultyReservations=new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))) {
                @Override public BudgetAccount capture(Reservation r,BudgetAccount a,Instant at) {
                    var result=super.capture(r,a,at); if(failurePoint==0) throw new IllegalStateException("after capture"); return result;
                }
            };
            assertThrows(IllegalStateException.class,()->fresh(faultyReceipts,faultyReservations).accept(command));
            assertTrue(service.find(command.receiptId(),"admin").isEmpty());
            assertEquals(Reservation.State.HELD,reservationService.find(fixture.id(),"admin").orElseThrow().state());
            assertEquals("0.123456",account(fixture).heldAmount().toPlainString()); assertEquals("0.000000",account(fixture).chargedAmount().toPlainString());
            assertEquals(0,count("ad_billing_ledger","qualification_id",fixture.id()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ad_billing_outbox WHERE partition_key=?",Integer.class,fixture.advertiser()+":"+today()));
            assertEquals(ImpressionReceipt.Status.ACCEPTED,service.accept(command).status());
        }
    }
    @Test void captureAndReleaseRaceCanOnlyReachOneTerminalAndNeverDoubleSubtract() throws Exception {
        for(int i=0;i<6;i++) {
            var fixture=fixture(); var command=fixture.command();
            concurrent(2,n->{ if(n==0) return service.accept(command); return reservationService.release(fixture.id(),"admin").orElseThrow(); });
            var state=reservationService.find(fixture.id(),"admin").orElseThrow().state();
            assertTrue(state==Reservation.State.CAPTURED || state==Reservation.State.RELEASED);
            assertEquals("0.000000",account(fixture).heldAmount().toPlainString());
            assertEquals(state==Reservation.State.CAPTURED?"0.123456":"0.000000",account(fixture).chargedAmount().toPlainString());
            assertEquals(state==Reservation.State.CAPTURED?1:0,count("ad_billing_ledger","qualification_id",fixture.id()));
        }
    }
    @Test void expiryWhileWaitingForAccountLockRejectsThenWorkerRecoversHold() throws Exception {
        var fixture=fixture(1); var command=fixture.command();
        try(var connection=ds.getConnection();var workers=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT 1 FROM ad_budget_account WHERE advertiser_id=? FOR UPDATE")) {
                lock.setLong(1,fixture.advertiser()); lock.executeQuery().close();
            }
            var future=workers.submit(()->service.accept(command)); boolean waiting=false;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%ad_budget_account%')",Boolean.class));
                if(waiting) break; Thread.sleep(10);
            }
            assertTrue(waiting,"必须真实等待账户锁"); Thread.sleep(1100); connection.commit();
            assertEquals(ImpressionReceipt.Status.EXPIRED,future.get(5,TimeUnit.SECONDS).status());
        }
        assertEquals(0,count("ad_billing_ledger","qualification_id",fixture.id()));
        assertTrue(reservationService.expireOne());
        assertEquals(Reservation.State.EXPIRED,reservationService.find(fixture.id(),"admin").orElseThrow().state());
        assertEquals("0.000000",account(fixture).heldAmount().toPlainString());
    }
    @Test void crossDayReceiptCapturesOnlyOriginalReservationDay() {
        var fixture=fixture(); var previous=today().minusDays(1);
        budgets.setLimit(BudgetCommand.parse(fixture.advertiser(),UUID.randomUUID().toString(),previous.toString(),"CNY","10",0,"admin"));
        // 隔离跨日 fixture 搬回原历史预算日，金额不变；验证捕获不偷偷选今天的账户。
        jdbc.update("UPDATE ad_budget_account SET held_amount=0 WHERE advertiser_id=? AND budget_date=?",fixture.advertiser(),today());
        jdbc.update("UPDATE ad_budget_account SET held_amount=.123456 WHERE advertiser_id=? AND budget_date=?",fixture.advertiser(),previous);
        jdbc.update("UPDATE ad_budget_reservation SET budget_date=? WHERE reservation_id=?",previous,fixture.id());
        var result=service.accept(fixture.command()); assertEquals(previous,result.budgetDate());
        assertEquals("0.123456",budgets.findAccount(fixture.advertiser(),previous).orElseThrow().chargedAmount().toPlainString());
        assertEquals("0.000000",account(fixture).chargedAmount().toPlainString());
    }
    @Test void ledgerIsImmutableAndOutboxContainsVersionedExactFactWithoutProof() throws Exception {
        var fixture=fixture(); var command=fixture.command(); var result=service.accept(command);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ad_billing_ledger SET amount=0 WHERE ledger_id=?",result.ledgerId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM ad_billing_ledger WHERE ledger_id=?",result.ledgerId()));
        String payload=jdbc.queryForObject("SELECT payload_json FROM ad_billing_outbox WHERE event_id=?",String.class,result.ledgerId());
        var event=JSON.readValue(payload,CapturedEvent.class);
        assertEquals(result.ledgerId().toString(),event.eventId()); assertEquals("0.123456",event.amount()); assertEquals(CpmPrice.UNIT,event.priceUnit());
        assertEquals(CapturedEvent.TYPE,event.eventType()); assertEquals(1,event.schemaVersion());
        assertTrue(JSON.readTree(payload).get("acceptedAt").isTextual()); assertFalse(payload.contains(command.qualificationToken())); assertFalse(payload.contains("admin"));
        assertNull(jdbc.queryForObject("SELECT published_at FROM ad_billing_outbox WHERE event_id=?",OffsetDateTime.class,result.ledgerId()));
    }
    @Test void sameResultSurvivesKeyRotationAndWrongDataSourceCannotAssemble() {
        var fixture=fixture(); var command=fixture.command(); var original=service.accept(command);
        var rotated=new DeliveryTokenCodec(JSON,"next",Map.of("next",Base64.getEncoder().encodeToString(new byte[32])));
        var fresh=new ImpressionReceiptService(receipts,preparations,reservations,quotes,rotated,BudgetConfiguration.transactions(ds,5),true);
        assertEquals(original,fresh.accept(command));
        assertThrows(InvalidProof.class,()->fresh.accept(fixture.command()));
        var other=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        assertThrows(IllegalArgumentException.class,()->new ImpressionReceiptService(new ImpressionReceiptRepository(new SqlSessionTemplate(SpringSqlSessions.create(other, "classpath*:mapper/ad/addb/**/*.xml")),JSON),
                preparations,reservations,quotes,tokens,BudgetConfiguration.transactions(ds,5),true));
    }
    @Test void inconsistentLedgerAndReservationFailClosedInsteadOfReturningSuccessfulDuplicate() {
        var fixture=fixture(); service.accept(fixture.command());
        // 仅隔离库注入损坏，模拟非法手工修复；新回执不得替这种不一致返回成功。
        jdbc.update("UPDATE ad_budget_reservation SET state='HELD',closed_at=NULL,close_reason=NULL WHERE reservation_id=?",fixture.id());
        var next=fixture.command();
        assertThrows(IllegalStateException.class,()->service.accept(next));
        assertTrue(service.find(next.receiptId(),"admin").isEmpty());
        assertEquals("0.123456",account(fixture).chargedAmount().toPlainString());
    }

    @Test void admissionPausePreservesCommittedResultsAndQueriesWithoutNewCharges() {
        var fixture=fixture(); var command=fixture.command(); var result=service.accept(command);
        var paused=new ImpressionReceiptService(receipts,preparations,reservations,quotes,tokens,BudgetConfiguration.transactions(ds,5),false);
        assertEquals(result,paused.accept(command)); assertEquals(result,paused.find(command.receiptId(),"admin").orElseThrow());
        assertThrows(IllegalStateException.class,()->paused.accept(fixture.command()));
        assertEquals("0.123456",account(fixture).chargedAmount().toPlainString());
    }

    /** 故障注入只在隔离库；空结果字段不能掩盖已提交PENDING，也不能绕过主体校验。 */
    @Test void unfinishedReceiptIsRejectedBeforeNullableResultMapping() {
        var fixture = fixture(); var command = fixture.command();
        assertTrue(receipts.claim(command, fixture.id()));
        assertThrows(Reservation.AccessDenied.class, () -> receipts.find(command.receiptId(), "other"));
        var failure = assertThrows(IllegalStateException.class, () -> receipts.find(command.receiptId(), "admin"));
        assertEquals("出现未完成的已提交回执", failure.getMessage());
        assertEquals("0.000000", account(fixture).chargedAmount().toPlainString());
        assertEquals(0, count("ad_billing_ledger", "qualification_id", fixture.id()));
    }

    @Test void columnsAreDocumentedAndTerminalStateCannotHaveMissingReason() {
        for(String table:List.of("ad_impression_receipt","ad_billing_ledger","ad_billing_outbox")) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",Integer.class,table));
            assertNotNull(jdbc.queryForObject("SELECT obj_description(?::regclass,'pg_class')",String.class,table));
        }
        var fixture=fixture();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ad_budget_reservation SET state='CAPTURED',closed_at=clock_timestamp(),close_reason=NULL WHERE reservation_id=?",fixture.id()));
    }
    static <T> List<T> concurrent(int count,java.util.function.IntFunction<T> action) throws Exception {
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(count)) {
            var futures=new ArrayList<Future<T>>();
            for(int i=0;i<count;i++) { final int index=i; futures.add(workers.submit(()->{start.await();return action.apply(index);})); }
            start.countDown(); var result=new ArrayList<T>();
            for(var future:futures) result.add(future.get(20,TimeUnit.SECONDS)); return result;
        }
    }
}
