package com.recsys.recengine.feed;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.*;
import com.recsys.ad.billing.*;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.dto.*;
import com.recsys.common.exposure.*;
import com.recsys.common.query.*;
import com.recsys.content.Item;
import com.recsys.recengine.RecommendOrchestrator;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.feed.FeedDelivery.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 两个真实 PG owner 校验最终位置与资金；候选替身不参与幂等/事务正确性的证明。 */
@Tag("money-chain") @Tag("natural-exposure") @Tag("feed-exposure")
@Testcontainers
class FeedDeliveryPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final AtomicLong IDS=new AtomicLong(100000);
    static JdbcTemplate engine,adDb;
    static DriverManagerDataSource adSource;
    static FeedDeliveryRepository repository;
    static BudgetAccountService budgets;
    static ReservationRepository reservations;
    static ReservationService reservationService;
    static CpmQuoteRepository quotes;
    static PreparationRepository preparations;
    static ImpressionReceiptRepository receipts;
    static SandboxPreparationService owner;
    static ImpressionReceiptService capture;
    static DeliveryTokenCodec adTokens;
    static NaturalQualificationToken naturalTokens;
    static FeedCandidateRepository frequency;
    RecommendOrchestrator recommender;
    ContentGateway content;
    QueryUnderstandingService queries;
    ExperimentService experiments;
    AdServingGateway gateway;
    AdProperties properties;

    @BeforeAll static void migrate() throws Exception {
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        admin.execute("CREATE SCHEMA feed_engine"); admin.execute("CREATE SCHEMA feed_ad");
        var engineSource=source("feed_engine"); adSource=source("feed_ad");
        engine=new JdbcTemplate(engineSource); adDb=new JdbcTemplate(adSource);
        for(String script:List.of("db/engine-delivery/V001__delivery_operation.sql","db/engine-delivery/V002__natural_delivery.sql",FeedDeliveryRepository.MIGRATION)) migrate(engineSource,script);
        for(String script:List.of(BudgetRepository.MIGRATION,CpmQuoteRepository.MIGRATION,ReservationRepository.MIGRATION,
                PreparationRepository.MIGRATION,ImpressionReceiptRepository.MIGRATION,"db/ad-billing/V006__billing_event_delivery.sql",FeedCandidateRepository.MIGRATION)) migrate(adSource,script);
        repository=new FeedDeliveryRepository(engine,JSON); repository.verifySchema();
        budgets=new BudgetConfiguration().budgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDb.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))),adSource,5);
        reservations=new ReservationRepository(adDb); quotes=new CpmQuoteRepository(adDb);
        reservationService=new ReservationService(reservations,quotes,transactions(),true);
        preparations=new PreparationRepository(adDb,JSON); receipts=new ImpressionReceiptRepository(adDb,JSON);
        adTokens=new DeliveryTokenCodec(JSON,"test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
        naturalTokens=new NaturalQualificationToken("test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
        owner=new SandboxPreparationService(mock(AdPipeline.class),preparations,quotes,reservationService,adTokens);
        capture=new ImpressionReceiptService(receipts,preparations,reservations,quotes,adTokens,transactions(),true);
        frequency=new FeedCandidateRepository(adDb); frequency.verifySchema();
    }
    static DriverManagerDataSource source(String schema) { return new DriverManagerDataSource(PG.getJdbcUrl()+"?currentSchema="+schema,PG.getUsername(),PG.getPassword()); }
    static void migrate(DriverManagerDataSource ds,String path) throws Exception {
        try(var c=ds.getConnection();var stmt=c.createStatement()) {
            stmt.execute("SET recsys.migration_sha256='"+sha256(path)+"'");
            try(var in=new ClassPathResource(path).getInputStream()) { stmt.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
        }
    }
    static TransactionTemplate transactions() {
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(adSource));
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(5); return tx;
    }
    static String sha256(String path) throws Exception {
        try(var in=new ClassPathResource(path).getInputStream()) {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }
    @BeforeEach void fixture() {
        recommender=mock(RecommendOrchestrator.class); content=mock(ContentGateway.class);
        queries=mock(QueryUnderstandingService.class); experiments=mock(ExperimentService.class); gateway=mock(AdServingGateway.class);
        properties=new AdProperties(); properties.getAdLoad().setSlots(List.of(2,4));
        when(queries.parse(anyString(),anyLong())).thenAnswer(i->new StructuredQuery(i.getArgument(0),i.getArgument(0),null,null,null,null));
        when(recommender.naturalCandidates(any())).thenAnswer(i->{
            RecommendRequest r=i.getArgument(0);
            return new RecommendOrchestrator.NaturalCandidates(new RecommendResponse(r.userId(),r.scene(),
                    List.of(new RecommendItem(1,.9,List.of("seed"),"理由"),new RecommendItem(2,.8,List.of(),null),
                            new RecommendItem(3,.7,List.of(),null),new RecommendItem(4,.6,List.of(),null),new RecommendItem(5,.5,List.of(),null)),"trace"),"natural-test");
        });
        when(content.findByIds(any())).thenAnswer(i->{
            Map<Long,Item> map=new HashMap<>(); for(long id:(List<Long>)i.getArgument(0)) map.put(id,new Item(id,"正文 "+id,"分类",List.of(),"描述",.5)); return map;
        });
        when(gateway.findSandboxPreparation(any(),anyString())).thenAnswer(i->owner.find(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->owner.prepare(i.getArgument(0))).when(gateway).prepareSandbox(any());
    }
    FeedDeliveryService service() { return service(repository,true); }
    FeedDeliveryService service(FeedDeliveryRepository repo,boolean admission) {
        return new FeedDeliveryService(repo,recommender,content,queries,experiments,gateway,properties,naturalTokens,NaturalQualification.TEXT_POLICY_V1,admission);
    }
    Request request() { return new Request(UUID.randomUUID(),"admin",IDS.incrementAndGet(),5,"feed","phone"); }
    Candidate candidate(Request request,long itemId,String limit) {
        return candidate(request,itemId,IDS.incrementAndGet(),IDS.incrementAndGet(),limit);
    }
    Candidate candidate(Request request,long itemId,long adId,long advertiser,String limit) {
        var day=reservations.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        if(budgets.findAccount(advertiser,day).isEmpty()) budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),day.toString(),"CNY",limit,0,"admin"));
        var ad=new SponsoredAd(adId,itemId,advertiser,0,"赞助正文",AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,0,"CPM");
        var price=new CpmPrice(new BigDecimal("0.123456"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE);
        var child=UUID.randomUUID(); var offer=new Offer(request.operationId(),request.subject(),request.userId(),child,ad,price,"ad-test",reservations.databaseNow().plusSeconds(300));
        return new Candidate(child,ad,adTokens.sign("offer",offer));
    }
    BudgetAccount account(Candidate c) {
        return budgets.findAccount(c.ad().advertiserId(),reservations.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()).orElseThrow();
    }
    List<Entry> ads(Operation op) { return op.plan().entries().stream().filter(e->e.candidate()!=null).toList(); }
    void capture(Entry entry) {
        var result=capture.accept(new ImpressionReceipt.Command(UUID.randomUUID(),"admin",entry.preparation().qualificationToken(),1000,5000,true));
        assertEquals(ImpressionReceipt.Status.ACCEPTED,result.status());
    }
    @Test void onlyFinalAdsReserveAndVisibleReceiptsChargeOriginalFinalPositions() throws Exception {
        var r=request(); var removed=candidate(r,1,"10"); var a=candidate(r,99,"10"); var b=candidate(r,100,"10");
        when(gateway.sandboxFeedCandidates(any())).thenReturn(List.of(removed,a,b));
        var op=service().deliver(r); assertEquals(State.COMPLETE,op.state());
        assertEquals(List.of(1L,99L,2L,100L,3L),op.plan().entries().stream().map(Entry::itemId).toList());
        assertTrue(owner.find(removed.childId(),"admin").isEmpty()); assertEquals("0.000000",account(removed).heldAmount().toPlainString());
        assertEquals("0.123456",account(a).heldAmount().toPlainString()); assertEquals("0.000000",account(a).chargedAmount().toPlainString());
        for(var e:op.plan().entries()) if(e.natural()!=null) {
            var q=naturalTokens.verify(e.natural().qualificationToken());
            assertEquals(NaturalQualification.Kind.FEED,q.kind()); assertEquals(e.slot(),q.slot()); assertEquals(r.operationId(),q.operationId());
            assertEquals(r.userId(),q.userId()); assertEquals(NaturalQualification.TEXT_POLICY_V1,q.policy());
        }
        Thread.sleep(1050); for(var e:ads(op)) capture(e);
        assertEquals("0.123456",account(a).chargedAmount().toPlainString()); assertEquals("0.000000",account(a).heldAmount().toPlainString());
        assertEquals(List.of(2,4),adDb.queryForList("SELECT slot FROM ad_billing_ledger WHERE operation_id=? ORDER BY slot",Integer.class,r.operationId()));
        verify(gateway,never()).sandboxCandidates(any()); verify(gateway,never()).searchAds(anyLong(),any(),anyInt(),anyString(),anyString(),anyDouble());
        verify(recommender,never()).recommend(any());
    }
    @Test void unknownPrepareExposesFrozenNaturalsThenRecoversSameChildDespiteChangedCandidatesAndConfig() {
        var r=request(); var a=candidate(r,99,"10"); when(gateway.sandboxFeedCandidates(any())).thenReturn(List.of(a));
        doAnswer(i->{ owner.prepare(i.getArgument(0)); throw new IllegalStateException("ACK lost"); }).when(gateway).prepareSandbox(any());
        var first=service().deliver(r); assertEquals(State.PREPARING,first.state()); assertNull(ads(first).getFirst().preparation());
        assertNotNull(first.plan().entries().getFirst().natural()); assertEquals("0.123456",account(a).heldAmount().toPlainString());
        var frozen=first.plan().entries().stream().filter(e->e.natural()!=null).toList();
        properties.getAdLoad().setSlots(List.of(1)); properties.getAdLoad().setMaxAds(1);
        when(gateway.sandboxFeedCandidates(any())).thenReturn(List.of(candidate(r,888,"10")));
        doThrow(new IllegalStateException("content changed")).when(content).findByIds(any());
        var restored=service(new FeedDeliveryRepository(engine,JSON),false).deliver(r);
        assertEquals(State.COMPLETE,restored.state()); assertEquals(frozen,restored.plan().entries().stream().filter(e->e.natural()!=null).toList());
        assertEquals(2,ads(restored).getFirst().slot()); assertEquals(a.childId(),ads(restored).getFirst().preparation().childId());
        assertEquals(restored,service().deliver(r)); verify(gateway,times(1)).sandboxFeedCandidates(any()); verify(gateway,times(1)).prepareSandbox(any());
        assertEquals("0.123456",account(a).heldAmount().toPlainString());
        assertThrows(IllegalStateException.class,()->service(repository,false).deliver(request()));
    }
    @Test void pureCandidateFailureDegradesOnceWithoutLaterAddingAds() {
        var r=request(); when(gateway.sandboxFeedCandidates(any())).thenThrow(new IllegalStateException("owner down"));
        var first=service().deliver(r); assertEquals(State.COMPLETE,first.state());
        assertEquals(AdDisposition.CANDIDATES_UNAVAILABLE,first.plan().adDisposition()); assertEquals(5,first.plan().entries().size());
        doReturn(List.of(candidate(r,99,"10"))).when(gateway).sandboxFeedCandidates(any());
        assertEquals(first,service().deliver(r)); verify(gateway,never()).prepareSandbox(any()); verify(gateway,times(1)).sandboxFeedCandidates(any());
    }
    @Test void budgetRejectionRemainsInFinalSlotAndNoReplacementIsPrepared() {
        var r=request(); var a=candidate(r,99,"0"); var b=candidate(r,100,"10");
        when(gateway.sandboxFeedCandidates(any())).thenReturn(List.of(a,b));
        var result=service().deliver(r); assertEquals(State.COMPLETE,result.state());
        assertEquals(SandboxDelivery.Status.INSUFFICIENT_BUDGET,ads(result).getFirst().preparation().status());
        assertEquals(List.of(2,4),ads(result).stream().map(Entry::slot).toList());
        assertEquals("0.000000",account(a).heldAmount().toPlainString()); assertEquals("0.000000",account(a).chargedAmount().toPlainString());
    }
    @Test void failedFreezeOrMissingContentCannotPrepareUncommittedEntries() {
        var r=request(); doReturn(List.of(candidate(r,99,"10"))).when(gateway).sandboxFeedCandidates(any());
        var broken=new FeedDeliveryRepository(engine,JSON) {
            @Override public boolean freeze(Operation operation,Plan plan) { throw new IllegalStateException("PG failure"); }
        };
        assertThrows(IllegalStateException.class,()->service(broken,true).deliver(r));
        assertNull(repository.find(r.operationId(),r.subject()).orElseThrow().plan()); verify(gateway,never()).prepareSandbox(any());
        doReturn(Map.of()).when(content).findByIds(any());
        assertThrows(IllegalStateException.class,()->service().deliver(r)); verify(gateway,never()).prepareSandbox(any());
    }
    @Test void concurrentClaimAndStaleFenceCannotReplaceOrReleaseFrozenPlan() throws Exception {
        var r=request(); var c=candidate(r,99,"10"); var settings=new Settings(true,List.of(2),1,"b",.1,NaturalQualification.TEXT_POLICY_V1);
        repository.create(r,settings);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<Optional<Operation>>>();
            for(int i=0;i<6;i++) futures.add(pool.submit(()->repository.acquire(r.operationId(),r.subject())));
            var winners=new ArrayList<Operation>();for(var f:futures) f.get(5,TimeUnit.SECONDS).ifPresent(winners::add);
            assertEquals(1,winners.size()); var stale=winners.getFirst();
            engine.update("UPDATE engine_feed_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE operation_id=?",r.operationId());
            var next=repository.acquire(r.operationId(),r.subject()).orElseThrow();
            var plan=new Plan("b","t",AdDisposition.CANDIDATES_AVAILABLE,List.of(new Entry(1,null,c,null)));
            assertFalse(repository.freeze(stale,plan)); assertTrue(repository.freeze(next,plan));
            repository.relinquish(r.operationId(),stale.fence()); assertTrue(repository.acquire(r.operationId(),r.subject()).isEmpty());
            var old=new Operation(r,settings,State.PREPARING,stale.fence(),plan);
            var prepared=owner.prepare(new Prepare(r.operationId(),r.subject(),r.userId(),c.childId(),1,c.offerToken()));
            var finished=new Plan("b","t",AdDisposition.CANDIDATES_AVAILABLE,List.of(new Entry(1,null,c,prepared)));
            assertFalse(repository.progress(old,finished));
            var current=repository.find(r.operationId(),r.subject()).orElseThrow(); assertTrue(repository.progress(current,finished));
        }
    }
    @Test void databaseGuardsOriginalRequestFrozenIdentityResultsAndComments() {
        var r=request(); var a=candidate(r,99,"10"); when(gateway.sandboxFeedCandidates(any())).thenReturn(List.of(a));
        doThrow(new IllegalStateException("pending")).when(gateway).prepareSandbox(any()); var partial=service().deliver(r);
        assertThrows(NaturalReceipt.AccessDenied.class,()->service().find(r.operationId(),"other"));
        assertThrows(NaturalReceipt.Conflict.class,()->service().deliver(new Request(r.operationId(),"admin",r.userId(),5,"other","phone")));
        assertThrows(org.springframework.dao.DataAccessException.class,()->engine.update("UPDATE engine_feed_delivery SET settings_json='{}' WHERE operation_id=?",r.operationId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->engine.update("UPDATE engine_feed_delivery SET plan_json=jsonb_set(plan_json::jsonb,'{entries,0,slot}','2')::text WHERE operation_id=?",r.operationId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->engine.update("UPDATE engine_feed_delivery SET state='COMPLETE',lease_until=NULL WHERE operation_id=?",r.operationId()));
        doAnswer(i->owner.prepare(i.getArgument(0))).when(gateway).prepareSandbox(any()); var complete=service().deliver(r);
        assertEquals(State.COMPLETE,complete.state());
        assertThrows(org.springframework.dao.DataAccessException.class,()->engine.update("DELETE FROM engine_feed_delivery WHERE operation_id=?",r.operationId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->engine.update("UPDATE engine_feed_delivery SET plan_json=? WHERE operation_id=?",JSON.valueToTree(partial.plan()).toString(),r.operationId()));
        assertEquals(0,engine.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='engine_feed_delivery'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",Integer.class));
        assertNotNull(engine.queryForObject("SELECT obj_description('engine_feed_delivery'::regclass,'pg_class')",String.class));
    }
    @Test void noQueryEmptyAndDisabledLoadNeverAskAdOwner() {
        var r=request(); var noQuery=new Request(r.operationId(),r.subject(),r.userId(),5,"feed",null);
        assertEquals(AdDisposition.NOT_REQUESTED,service().deliver(noQuery).plan().adDisposition());
        properties.getAdLoad().setEnabled(false); assertEquals(AdDisposition.NOT_REQUESTED,service().deliver(request()).plan().adDisposition());
        doReturn(new RecommendOrchestrator.NaturalCandidates(new RecommendResponse(1,"feed",List.of(),null),"")).when(recommender).naturalCandidates(any());
        assertTrue(service().deliver(request()).plan().entries().isEmpty()); verify(gateway,never()).sandboxFeedCandidates(any());
    }
    @Test void strictBoundsAndOuterTransactionRefuseBeforeRemoteEffects() {
        assertThrows(IllegalArgumentException.class,()->new Settings(true,List.of(11),1,"b",.1,NaturalQualification.TEXT_POLICY_V1));
        assertThrows(IllegalArgumentException.class,()->new Request(UUID.randomUUID(),"admin",1,51,"feed",null));
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(engine.getDataSource()));
        tx.executeWithoutResult(t->assertThrows(IllegalStateException.class,()->service().deliver(request()))); verifyNoInteractions(gateway);
    }
    @Test void actualAcceptedLedgerFiltersByAdAdvertiserAndUserWithoutCountingHeld() throws Exception {
        var r=request(); var a=candidate(r,99,"10"); when(gateway.sandboxFeedCandidates(any())).thenReturn(List.of(a));
        var op=service().deliver(r); assertEquals(Set.of(a.ad().adId()),frequency.eligible(r.userId(),List.of(a),1,1));
        Thread.sleep(1050); capture(ads(op).getFirst());
        assertTrue(frequency.eligible(r.userId(),List.of(a),1,9).isEmpty());
        var sibling=candidate(r,100,IDS.incrementAndGet(),a.ad().advertiserId(),"10");
        assertTrue(frequency.eligible(r.userId(),List.of(sibling),9,1).isEmpty());
        assertEquals(Set.of(sibling.ad().adId()),frequency.eligible(r.userId()+1,List.of(sibling),1,1));
        var before=account(a);
        var prep=mock(SandboxPreparationService.class); when(prep.candidates(any())).thenReturn(List.of(a,sibling));
        var helper=new FeedCandidateService(prep,frequency,true,1,1);
        assertTrue(helper.candidates(new Query(r.operationId(),r.subject(),r.userId(),new StructuredQuery("q","q",null,null,null,null),2,"feed","b",.1)).isEmpty());
        assertEquals(before,account(a)); assertTrue(owner.find(sibling.childId(),"admin").isEmpty());
    }
    @Test void frequencyUsesShanghaiAcceptanceDayEvenWhenReservationBelongsToPreviousDay() {
        var r=request();
        Instant start=reservations.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var before=candidate(r,101,"10"); var first=candidate(r,102,"10"); var last=candidate(r,103,"10"); var after=candidate(r,104,"10");
        seedHistoricalCapture(r,before,start.minusMillis(1),1); seedHistoricalCapture(r,first,start,2);
        seedHistoricalCapture(r,last,start.plusSeconds(86400).minusMillis(1),3); seedHistoricalCapture(r,after,start.plusSeconds(86400),4);
        assertEquals(Set.of(before.ad().adId(),after.ad().adId()),frequency.eligible(r.userId(),List.of(before,first,last,after),1,1));
        var day=adDb.queryForObject("SELECT budget_date FROM ad_billing_ledger WHERE qualification_id=?",LocalDate.class,first.childId());
        assertEquals(start.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(1),day);
    }
    /** 隔离库以显式时钟生成完整历史资金链；不修改已写账本，验证查询的是接受日而非预占日。 */
    void seedHistoricalCapture(Request request,Candidate candidate,Instant at,int slot) {
        Instant issued=at.minusSeconds(2); LocalDate day=issued.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        long advertiser=candidate.ad().advertiserId();
        if(budgets.findAccount(advertiser,day).isEmpty()) budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),day.toString(),"CNY","10",0,"admin"));
        var offer=adTokens.verify(candidate.offerToken(),"offer",Offer.class);
        quotes.freeze(candidate.childId(),request.operationId(),advertiser,candidate.ad().adId(),slot,offer.price());
        adDb.update("UPDATE ad_cpm_quote SET issued_at=?,expires_at=? WHERE quote_id=?",
                java.sql.Timestamp.from(issued),java.sql.Timestamp.from(issued.plusSeconds(300)),candidate.childId());
        var prepareClock=new ReservationRepository(adDb) { @Override public Instant databaseNow() { return issued; } };
        var preparer=new SandboxPreparationService(mock(AdPipeline.class),preparations,quotes,
                new ReservationService(prepareClock,quotes,transactions(),true),adTokens);
        var prepared=preparer.prepare(new Prepare(request.operationId(),request.subject(),request.userId(),candidate.childId(),slot,candidate.offerToken()));
        assertEquals(SandboxDelivery.Status.QUALIFIED,prepared.status());
        var acceptClock=new ReservationRepository(adDb) { @Override public Instant databaseNow() { return at; } };
        var acceptor=new ImpressionReceiptService(receipts,preparations,acceptClock,quotes,adTokens,transactions(),true);
        assertEquals(ImpressionReceipt.Status.ACCEPTED,acceptor.accept(new ImpressionReceipt.Command(UUID.randomUUID(),"admin",prepared.qualificationToken(),1000,5000,true)).status());
    }

}
