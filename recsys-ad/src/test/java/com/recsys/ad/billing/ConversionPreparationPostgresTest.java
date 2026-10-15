package com.recsys.ad.billing;

import com.recsys.ad.AdPipeline;
import com.recsys.ad.BiddingService;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.ConversionDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.query.StructuredQuery;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实PG逐检查点故障恢复；复用事件夹具，但本片不声称又跑了一次浏览器。 */
@Tag("money-chain") @Testcontainers
class ConversionPreparationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ConversionEventsFixture f;PreparationRepository repository;AdPipeline pipeline;ConversionPreparationService service;
    @BeforeEach void setup() throws Exception {
        f=new ConversionEventsFixture(PG);
        try(var in=new ClassPathResource(PreparationRepository.CONVERSION_MIGRATION).getInputStream();var c=f.ds.getConnection();var st=c.createStatement()) {
            st.execute("SET recsys.migration_sha256='"+BudgetRepository.migrationSha256(PreparationRepository.CONVERSION_MIGRATION)+"'");
            st.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        repository=new PreparationRepository(f.jdbc,ConversionEventsFixture.JSON,ChargeMode.CONVERSION);repository.verifySchema();
        pipeline=mock(AdPipeline.class);service=fresh(repository,f.cpm.tokens,true);
    }
    ConversionPreparationService fresh(PreparationRepository repo,DeliveryTokenCodec tokens,boolean admission) {
        return new ConversionPreparationService(pipeline,repo,f.quotes,f.settlement,tokens,admission);
    }
    Query query() { return new Query(UUID.randomUUID(),"admin",Long.MAX_VALUE,new StructuredQuery("q","q",null,null,null,null),2,"search","b",.1); }
    BiddingService.ConversionQuotedAd ad(long advertiser) {
        var money=new BigDecimal("0.123456");
        return new BiddingService.ConversionQuotedAd(new SponsoredAd(11,Long.MAX_VALUE,advertiser,0,"正文",AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,22,"CPA"),
                new ConversionPrice(new GspPrice(money,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,money)));
    }
    Prepare request(String limit) {
        long advertiser=1;f.cpm.budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),f.cpm.today().toString(),"CNY",limit,0,"admin"));
        when(pipeline.sandboxConversionCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble())).thenReturn(List.of(ad(advertiser)));
        var q=query();var c=service.candidates(q).getFirst();return new Prepare(q.operationId(),q.subject(),q.userId(),c.childId(),1,c.offerToken());
    }
    @Test void pureCandidatesHaveNoMoneyAndConcurrentPreparationKeepsOriginalTokenAndDeadline() throws Exception {
        var request=request("10");assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM ad_conversion_quote",Integer.class));
        assertEquals(0,f.cpm.budgets.findAccount(1,f.cpm.today()).orElseThrow().heldAmount().signum());
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Prepared>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->service.prepare(request)));
            var first=futures.getFirst().get(10,TimeUnit.SECONDS);for(var future:futures)assertEquals(first,future.get(10,TimeUnit.SECONDS));
            assertEquals(Status.QUALIFIED,first.status());assertEquals("0.123456",f.cpm.budgets.findAccount(1,f.cpm.today()).orElseThrow().heldAmount().toPlainString());
            assertEquals(first,fresh(new PreparationRepository(f.jdbc,ConversionEventsFixture.JSON,ChargeMode.CONVERSION),f.cpm.tokens,true).prepare(request));
        }
        verify(pipeline,never()).run(anyLong(),any(),anyInt(),anyString(),anyString(),anyDouble());
    }
    @Test void responseLostAfterHoldRecoversWithAdmissionClosedAndWithoutReholding() {
        var request=request("10");
        var failing=new PreparationRepository(f.jdbc,ConversionEventsFixture.JSON,ChargeMode.CONVERSION) {
            @Override public Prepared complete(Prepared p,String subject) { throw new IllegalStateException("lost after held"); }
        };
        assertThrows(IllegalStateException.class,()->fresh(failing,f.cpm.tokens,true).prepare(request));
        var held=f.settlement.find(request.childId(),"admin").orElseThrow();
        assertTrue(repository.find(request.childId(),"admin").isEmpty());
        var result=fresh(repository,f.cpm.tokens,false).prepare(request);
        assertEquals(held.prepared().qualificationToken(),result.qualificationToken());assertEquals(held.prepared().expiresAt(),result.expiresAt());
        assertEquals(held,f.settlement.find(request.childId(),"admin").orElseThrow());
    }
    @Test void originalResultSurvivesRemovedSigningKeyAndClosedAdmissionButChangedRequestDoesNot() {
        var request=request("10");var result=service.prepare(request);
        var rotated=new DeliveryTokenCodec(ConversionEventsFixture.JSON,"next",Map.of("next",Base64.getEncoder().encodeToString(new byte[32])));
        var owner=fresh(repository,rotated,false);
        assertEquals(result,owner.prepare(request));assertEquals(result,owner.find(request.childId(),"admin").orElseThrow());
        assertThrows(Conflict.class,()->owner.prepare(new Prepare(request.operationId(),"admin",request.userId(),request.childId(),2,request.offerToken())));
        assertThrows(Reservation.AccessDenied.class,()->owner.find(request.childId(),"other"));
        assertThrows(IllegalStateException.class,()->owner.candidates(query()));
    }
    @Test void expiredOfferIsStableAndWrongPurposeOrSubjectCannotCreateMoney() {
        var original=request("10");var offer=f.cpm.tokens.verify(original.offerToken(),ConversionDelivery.OFFER_PURPOSE,ConversionDelivery.Offer.class);
        var expired=new ConversionDelivery.Offer(offer.operationId(),offer.subject(),offer.userId(),offer.childId(),offer.ad(),offer.price(),offer.bucket(),repository.now().minusSeconds(1));
        var request=new Prepare(original.operationId(),"admin",original.userId(),original.childId(),1,f.cpm.tokens.sign(ConversionDelivery.OFFER_PURPOSE,expired));
        assertThrows(InvalidProof.class,()->service.prepare(new Prepare(request.operationId(),"admin",request.userId(),request.childId(),1,f.cpm.tokens.sign("offer",expired))));
        assertThrows(InvalidProof.class,()->service.prepare(new Prepare(request.operationId(),"other",request.userId(),request.childId(),1,request.offerToken())));
        var result=service.prepare(request);assertEquals(Status.OFFER_EXPIRED,result.status());assertEquals(result,service.prepare(request));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM ad_conversion_quote",Integer.class));
    }
    @Test void rejectionAndReleasedPreparationStayOriginalAndCpmCannotReadConversionCheckpoint() {
        var request=request("0");assertEquals(Status.INSUFFICIENT_BUDGET,service.prepare(request).status());
        assertTrue(new PreparationRepository(f.jdbc,ConversionEventsFixture.JSON).find(request.childId(),"admin").isEmpty());
        f.cpm.budgets.setLimit(BudgetCommand.parse(1,UUID.randomUUID().toString(),f.cpm.today().toString(),"CNY","10",1,"admin"));
        assertEquals(Status.INSUFFICIENT_BUDGET,service.prepare(request).status());
        var q=query();var c=service.candidates(q).getFirst();var funded=new Prepare(q.operationId(),q.subject(),q.userId(),c.childId(),1,c.offerToken());
        var first=service.prepare(funded);f.settlement.release(funded.childId(),"admin");assertEquals(first,service.prepare(funded));
        assertEquals(ConversionSettlement.State.RELEASED,f.settlement.find(funded.childId(),"admin").orElseThrow().state());
    }
    @Test void quoteCommittedBeforeHoldFailureRestartsWithoutRepricingOrDeadlineExtension() {
        var request=request("10");
        var failing=new ConversionSettlementService(new ConversionSettlementRepository(f.jdbc,ConversionEventsFixture.JSON),f.quotes,f.cpm.reservations,f.cpm.tokens,
                BudgetConfiguration.transactions(f.ds,5),true,true) {
            @Override public ConversionSettlement.Prepared prepare(UUID id,String subject) { throw new IllegalStateException("after quote before hold"); }
        };
        var interrupted=new ConversionPreparationService(pipeline,repository,f.quotes,failing,f.cpm.tokens,true);
        assertThrows(IllegalStateException.class,()->interrupted.prepare(request));
        var quote=f.quotes.find(request.childId(),"admin").orElseThrow();assertTrue(f.settlement.find(request.childId(),"admin").isEmpty());
        var result=service.prepare(request);assertEquals(quote.expiresAt(),result.expiresAt());assertEquals(quote.price().amount().toPlainString(),result.amount());
        assertEquals(quote,f.quotes.find(request.childId(),"admin").orElseThrow());
    }
    @Test void pausedAdmissionDoesNotCreateQuoteForPreviouslySignedOffer() {
        var request=request("10");
        assertThrows(IllegalStateException.class,()->fresh(repository,f.cpm.tokens,false).prepare(request));
        assertTrue(f.quotes.find(request.childId(),"admin").isEmpty());assertTrue(repository.find(request.childId(),"admin").isEmpty());
        assertEquals(0,f.cpm.budgets.findAccount(1,f.cpm.today()).orElseThrow().heldAmount().signum());
    }
    @Test void checksDatabaseImmutabilityCommentsSchemaAndOuterTransaction() {
        var request=request("10");service.prepare(request);
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("UPDATE ad_conversion_preparation SET result_json='{}' WHERE child_id=?",request.childId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("UPDATE ad_conversion_preparation SET slot=2 WHERE child_id=?",request.childId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->f.jdbc.update("DELETE FROM ad_conversion_preparation WHERE child_id=?",request.childId()));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='ad_conversion_preparation'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",Integer.class));
        BudgetConfiguration.transactions(f.ds,5).executeWithoutResult(tx->assertThrows(IllegalStateException.class,()->service.prepare(request)));
        f.jdbc.update("UPDATE ad_billing_schema_history SET script_sha256=repeat('0',64) WHERE version=15");
        assertThrows(IllegalStateException.class,repository::verifySchema);
        assertThrows(IllegalArgumentException.class,()->fresh(new PreparationRepository(f.jdbc,ConversionEventsFixture.JSON),f.cpm.tokens,true));
    }
}
