package com.recsys.ad.billing;

import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独占PG内产生真实三族资金事实；并发写入跨读取间隙，验证只读对账不制造假差额。 */
@Tag("money-chain") @Testcontainers
class ReconciliationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ConversionEventsFixture f;ReconciliationRepository repository;ReconciliationService service;
    @BeforeEach void setup() throws Exception {
        f=new ConversionEventsFixture(PG);
        for(var path:List.of(PreparationRepository.CONVERSION_MIGRATION,ReconciliationRepository.MIGRATION)) {
            try(var in=new ClassPathResource(path).getInputStream();var c=f.ds.getConnection();var st=c.createStatement()) {
                st.execute("SET recsys.migration_sha256='"+BudgetRepository.migrationSha256(path)+"'");
                st.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        repository=new ReconciliationRepository(f.cpm.sessions);repository.verifySchema();service=fresh(repository);
    }
    ReconciliationService fresh(ReconciliationRepository repo) {return new ReconciliationService(repo,ReconciliationConfiguration.transactions(f.ds));}
    Scope scope(long advertiser){return new Scope(advertiser,f.cpm.today());}
    void account(long advertiser,LocalDate day){f.cpm.budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),day.toString(),"CNY","100",0,"admin"));}
    FamilySummary family(Snapshot snapshot,ChargeMode family){return snapshot.families().stream().filter(v->v.family()==family).findFirst().orElseThrow();}
    void applyCpm(UUID event){f.cpm.projection.apply(f.cpm.event(event).key(),f.cpm.event(event).payload());}
    void applyClick(UUID event){f.clicks.projection.apply(f.clicks.event(event).key(),f.clicks.event(event).payload());}
    void applyConversion(UUID event){f.projection.apply(f.event(event).key(),f.event(event).payload());}

    @Test void allFamiliesReconcileLedgerWhileProjectionLagIsSeparateAndDuplicateApplyDoesNotAddMoney() {
        var cpm=f.cpm.capture(1,"0.100000");var click=f.clicks.capture(1,"0.200000");var conversion=f.capture(1,"0.300000");
        var before=service.inspect(scope(1));assertEquals(FundsStatus.BALANCED,before.fundsStatus());
        assertEquals("0.000000",before.heldDelta());assertEquals("0.000000",before.chargedDelta());
        assertEquals(new BigDecimal("0.600000"),before.account().chargedAmount());
        for(var row:before.families()) {
            assertEquals(1,row.ledgerCount());assertEquals(1,row.missingFactCount());assertEquals(1,row.pendingOutboxCount());
            assertEquals(ProjectionStatus.LAGGING,row.projectionStatus());assertNull(row.lastAppliedAt());assertNotNull(row.oldestPendingAt());assertFalse(row.summaryPresent());
        }
        // broker到达顺序独立于收费版本；逐事件应用，重复不应再次累加。
        applyConversion(conversion);applyCpm(cpm);applyClick(click);applyConversion(conversion);applyClick(click);
        var after=service.inspect(scope(1));assertEquals(FundsStatus.BALANCED,after.fundsStatus());
        for(var row:after.families()) {
            assertEquals(ProjectionStatus.CAUGHT_UP,row.projectionStatus());assertEquals(row.ledgerAmount(),row.factAmount());assertEquals(1,row.factCount());
            assertEquals(1,row.summaryCount());assertTrue(row.summaryPresent());assertNotNull(row.lastAppliedAt());
            assertEquals(1,row.pendingOutboxCount()); // 本测试直接调用真实投影，不能声称broker已确认。
        }
    }
    @Test void zeroValueStillNeedsDistinctEventAndMissingAccountIsNotZeroBalance() {
        var first=f.capture(1,"0.000000");var second=f.capture(1,"0.000000");applyConversion(first);
        var row=family(service.inspect(scope(1)),ChargeMode.CONVERSION);
        assertEquals("0.000000",row.ledgerAmount());assertEquals("0.000000",row.factAmount());assertEquals(2,row.ledgerCount());assertEquals(1,row.factCount());
        assertEquals(ProjectionStatus.LAGGING,row.projectionStatus());applyConversion(second);
        assertEquals(ProjectionStatus.CAUGHT_UP,family(service.inspect(scope(1)),ChargeMode.CONVERSION).projectionStatus());
        var absent=service.inspect(scope(99));assertEquals(FundsStatus.ACCOUNT_MISSING,absent.fundsStatus());assertNull(absent.account());
        assertNull(absent.heldDelta());assertNull(absent.chargedDelta());assertTrue(absent.families().stream().allMatch(v->v.ledgerCount()==0));
        account(2,f.cpm.today());var empty=service.inspect(scope(2));assertEquals(FundsStatus.BALANCED,empty.fundsStatus());
        assertTrue(empty.families().stream().allMatch(v->v.projectionStatus()==ProjectionStatus.CAUGHT_UP && !v.summaryPresent()));
    }
    @Test void accountAndOriginalBudgetDayAreIsolated() {
        f.capture(1,"0.123456");f.clicks.capture(2,"0.654321");var yesterday=f.cpm.today().minusDays(1);account(1,yesterday);
        var current=service.inspect(scope(1));assertEquals("0.123456",family(current,ChargeMode.CONVERSION).ledgerAmount());
        assertEquals(0,family(current,ChargeMode.CLICK).ledgerCount());
        var previous=service.inspect(new Scope(1,yesterday));assertEquals(FundsStatus.BALANCED,previous.fundsStatus());
        assertTrue(previous.families().stream().allMatch(v->v.ledgerCount()==0 && v.heldCount()==0));
        assertEquals(new BigDecimal("0.654321"),service.inspect(scope(2)).account().chargedAmount());
    }
    UUID hold(ChargeMode family,long advertiser,String amount) {
        UUID id=UUID.randomUUID();var money=new BigDecimal(amount);
        if(family==ChargeMode.IMPRESSION) {
            f.cpm.quotes.freeze(id,UUID.randomUUID(),advertiser,1,1,new CpmPrice(money,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,money));
            new ReservationService(f.cpm.reservations,f.cpm.quotes,BudgetConfiguration.transactions(f.ds,5),true).prepare(id,id,"admin");
        } else if(family==ChargeMode.CLICK) {
            f.clicks.quotes.freezeBefore(new ClickQuoteRepository.Draft(id,UUID.randomUUID(),"admin",1,advertiser,2,3,4,1,"点击",
                    new ClickPrice(BidType.CPC,new GspPrice(money,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,money))),Instant.now().plusSeconds(30));
            f.clicks.settlement.prepare(id,"admin");
        } else {
            f.quotes.freezeBefore(new ConversionQuoteRepository.Draft(id,UUID.randomUUID(),"admin",1,advertiser,2,3,4,1,"转化",
                    new ConversionPrice(new GspPrice(money,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,money))),Instant.now().plusSeconds(30));
            f.settlement.prepare(id,"admin");
        }
        return id;
    }
    @Test void expiredHeldRemainsAnObligationUntilActualOwnerRelease() {
        account(1,f.cpm.today());var ids=new EnumMap<ChargeMode,UUID>(ChargeMode.class);
        for(var mode:ChargeMode.values())ids.put(mode,hold(mode,1,"0.100000"));
        // 参数化观察时钟仅模拟六分钟后的核查，不宣称真实等待或恢复耗时。
        var future=new ReconciliationRepository(f.cpm.sessions){@Override public Instant observedAt(){return super.observedAt().plusSeconds(360);}};
        var result=fresh(future).inspect(scope(1));assertEquals(FundsStatus.BALANCED,result.fundsStatus());assertEquals(new BigDecimal("0.300000"),result.account().heldAmount());
        for(var row:result.families()){assertEquals(1,row.heldCount());assertEquals(1,row.expiredHeldCount());assertEquals("0.100000",row.heldAmount());}
        new ReservationService(f.cpm.reservations,f.cpm.quotes,BudgetConfiguration.transactions(f.ds,5),true).release(ids.get(ChargeMode.IMPRESSION),"admin");
        f.clicks.settlement.release(ids.get(ChargeMode.CLICK),"admin");f.settlement.release(ids.get(ChargeMode.CONVERSION),"admin");
        var released=service.inspect(scope(1));assertEquals(FundsStatus.BALANCED,released.fundsStatus());
        assertEquals(0,released.account().heldAmount().signum());assertTrue(released.families().stream().allMatch(v->v.heldCount()==0));
    }
    @Test void outboxPublishedIsNotProofOfProjectionAndDeadIsSeparatelyVisible() {
        var first=f.capture(1,"0.100000");f.capture(1,"0.200000");
        f.jdbc.update("UPDATE ad_conversion_outbox SET delivery_state='PUBLISHED',published_at=clock_timestamp() WHERE event_id=?",first);
        f.jdbc.update("UPDATE ad_conversion_outbox SET delivery_state='DEAD',attempts=20 WHERE event_id<>?",first);
        var snapshot=service.inspect(scope(1));var row=family(snapshot,ChargeMode.CONVERSION);
        assertEquals(FundsStatus.BALANCED,snapshot.fundsStatus());assertEquals(ProjectionStatus.LAGGING,row.projectionStatus());
        assertEquals(2,row.missingFactCount());assertEquals(0,row.pendingOutboxCount());assertEquals(1,row.deadOutboxCount());assertNull(row.oldestPendingAt());
    }
    @Test void driftIsExplainedWithoutRepairingAccountOrProjection() {
        var event=f.capture(1,"0.100000");applyConversion(event);
        // 在独占测试schema注入可写派生汇总/账户偏差，不禁用不可变账本或fact触发器。
        f.jdbc.update("UPDATE ad_budget_account SET held_amount=0.020000,charged_amount=0.110000,version=version+1 WHERE advertiser_id=1");
        f.jdbc.update("UPDATE ad_conversion_projection_daily SET charged_amount=0.090000 WHERE advertiser_id=1");
        var result=service.inspect(scope(1));assertEquals(FundsStatus.MISMATCH,result.fundsStatus());assertEquals("0.020000",result.heldDelta());assertEquals("0.010000",result.chargedDelta());
        assertEquals(ProjectionStatus.MISMATCH,family(result,ChargeMode.CONVERSION).projectionStatus());
        assertEquals(new BigDecimal("0.110000"),f.cpm.budgets.findAccount(1,f.cpm.today()).orElseThrow().chargedAmount());
        assertEquals(new BigDecimal("0.090000"),f.total());
        f.jdbc.update("UPDATE ad_budget_account SET charged_amount=0.090000,version=version+1 WHERE advertiser_id=1");
        assertEquals("-0.010000",service.inspect(scope(1)).chargedDelta());
    }
    @Test void sameTotalCannotHideWrongEventIdentityOrWrongAmount() throws Exception {
        var first=f.capture(1,"0.100000");var second=f.capture(1,"0.200000");
        var firstPayload=ConversionEventsFixture.JSON.readTree(f.event(first).payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)firstPayload).put("amount","0.200000");
        f.projection.apply(f.event(first).key(),firstPayload.toString());
        var secondPayload=ConversionEventsFixture.JSON.readTree(f.event(second).payload());
        ((com.fasterxml.jackson.databind.node.ObjectNode)secondPayload).put("amount","0.100000").put("eventId",UUID.randomUUID().toString());
        f.projection.apply(f.event(second).key(),secondPayload.toString());
        var row=family(service.inspect(scope(1)),ChargeMode.CONVERSION);
        assertEquals(row.ledgerAmount(),row.factAmount());assertEquals(row.ledgerCount(),row.factCount());
        assertEquals(1,row.invalidFactCount());assertEquals(1,row.unexpectedFactCount());assertEquals(1,row.missingFactCount());
        assertEquals(ProjectionStatus.MISMATCH,row.projectionStatus());
    }
    @Test void concurrentCaptureBetweenAccountAndLedgerQueriesDoesNotCreateFalseDifference() throws Exception {
        account(1,f.cpm.today());var read=new CountDownLatch(1);var committed=new CountDownLatch(1);
        var paused=new ReconciliationRepository(f.cpm.sessions) {
            @Override public Optional<BudgetAccount> account(Scope scope) {
                var value=super.account(scope);read.countDown();
                try {if(!committed.await(3,TimeUnit.SECONDS))throw new IllegalStateException("writer did not commit");}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}return value;
            }
        };
        try(var pool=Executors.newSingleThreadExecutor()) {
            var audit=pool.submit(()->fresh(paused).inspect(scope(1)));assertTrue(read.await(2,TimeUnit.SECONDS));
            try {f.capture(1,"0.100000");}finally{committed.countDown();}
            var result=audit.get(5,TimeUnit.SECONDS);assertEquals(FundsStatus.BALANCED,result.fundsStatus());assertEquals(0,result.account().chargedAmount().signum());
            assertEquals(0,family(result,ChargeMode.CONVERSION).ledgerCount());
        }
        var latest=service.inspect(scope(1));assertEquals(FundsStatus.BALANCED,latest.fundsStatus());assertEquals(1,family(latest,ChargeMode.CONVERSION).ledgerCount());
    }
    @Test void databaseEnforcesReadOnlyAndServiceRejectsOuterOrWrongResourceTransactions() {
        account(1,f.cpm.today());var writer=new ReconciliationRepository(f.cpm.sessions){@Override public Optional<BudgetAccount> account(Scope scope){
            f.jdbc.update("UPDATE ad_budget_account SET version=version+1 WHERE advertiser_id=1");return super.account(scope);}};
        assertThrows(DataAccessException.class,()->fresh(writer).inspect(scope(1)));
        BudgetConfiguration.transactions(f.ds,5).executeWithoutResult(tx->assertThrows(IllegalStateException.class,()->service.inspect(scope(1))));
        assertThrows(IllegalArgumentException.class,()->new ReconciliationService(repository,BudgetConfiguration.transactions(f.ds,5)));
        var wrong=new DriverManagerDataSource(f.ds.getUrl(),f.ds.getUsername(),f.ds.getPassword());
        assertThrows(IllegalArgumentException.class,()->new ReconciliationService(repository,ReconciliationConfiguration.transactions(wrong)));
    }
    @Test void dependencyFailureDoesNotReturnEmptyHealthyReportAndQueryHasDeadline() {
        var slow=new ReconciliationRepository(f.cpm.sessions){@Override public Optional<BudgetAccount> account(Scope scope){
            f.jdbc.execute("SELECT pg_sleep(7)");return super.account(scope);}};
        var timeout=assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->fresh(slow).inspect(scope(1)));
        // 当前PG驱动将Statement.cancel映射为资源失败；核对数据库取消SQLSTATE，不用宽泛异常冒充超时证据。
        var cancelled=assertInstanceOf(org.postgresql.util.PSQLException.class,timeout.getMostSpecificCause());assertEquals("57014",cancelled.getSQLState());
        // 不删除/修改财务数据；临时改名独占schema的表，验证不可查询不能变成0。
        f.jdbc.execute("ALTER TABLE ad_conversion_projection_daily RENAME TO unavailable_projection_daily");
        assertThrows(DataAccessException.class,repository::verifySchema);
        assertThrows(DataAccessException.class,()->service.inspect(scope(1)));
    }
    @Test void schemaChecksAndDefaultOffOwnerIsolationAreReal() {
        var contexts=new ApplicationContextRunner().withUserConfiguration(ReconciliationConfiguration.class);
        contexts.run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reconciliationRepository"));});
        contexts.withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.reconciliation-enabled=true",
                "spring.application.name=recsys-rec-engine","recsys.ad.serving.mode=grpc")
                .run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("reconciliationRepository"));});
        contexts.withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.reconciliation-enabled=true",
                "recsys.ad.billing.clicks-enabled=false","recsys.ad.billing.conversions-enabled=false")
                .withBean("adDbSqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->f.cpm.sessions).withBean("adDbDataSource",javax.sql.DataSource.class,()->f.ds)
                .run(c->{assertNull(c.getStartupFailure());assertEquals(FundsStatus.ACCOUNT_MISSING,c.getBean(ReconciliationService.class).inspect(scope(1)).fundsStatus());});
        assertEquals(3,f.jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname IN ('ad_click_quote_account_idx','ad_click_settlement_day_idx','ad_conversion_settlement_account_idx')",Integer.class));
        f.jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=repeat('0',64) WHERE version=16");assertThrows(IllegalStateException.class,repository::verifySchema);
    }
}
