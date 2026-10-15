package com.recsys.adserving.grpc;

import com.lrj.authz.protocol.*;
import com.recsys.ad.billing.*;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.recengine.delivery.*;
import com.recsys.proto.conversion.v1.*;
import com.recsys.proto.delivery.v1.GetPreparationRequest;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 两个独占PG owner、真实广告主守卫/HTTP和TCP RPC；权限引擎替身只证明调用策略。 */
@Tag("money-chain") @Testcontainers
class ConversionOnlinePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ConversionOnlineFixture f;
    final AuthzEngine authz=mock(AuthzEngine.class);
    final UsernamePasswordAuthenticationToken admin=login("admin","ADMIN");
    final UsernamePasswordAuthenticationToken actor=login("advertiser-member","ADVERTISER");
    @BeforeEach void setup() throws Exception { f=new ConversionOnlineFixture(PG);when(authz.check(any(),anyString(),any(),any())).thenReturn(true); }
    @AfterEach void close() throws Exception { SecurityContextHolder.clearContext();f.close(); }
    static UsernamePasswordAuthenticationToken login(String name,String role) {
        return new UsernamePasswordAuthenticationToken(name,"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    DeliveryOperation.Request request() { return new DeliveryOperation.Request(UUID.randomUUID(),"admin",Long.MAX_VALUE,"手机",2,"search"); }
    VisibleCommand visible(String token) { return new VisibleCommand(UUID.randomUUID(),"admin",token,1000,5000,true); }
    String path(UUID event) { return "/api/advertiser/"+Long.MAX_VALUE+"/sandbox-conversions/"+event; }
    String body(String token,Instant occurred) throws Exception {
        return ConversionOnlineFixture.JSON.writeValueAsString(Map.of("qualificationToken",token,"occurredAt",occurred.toString()));
    }
    Instant occurred(UUID qualification) { return f.quotes.find(qualification,"admin").orElseThrow().issuedAt().plusSeconds(1); }
    SandboxConversionServiceGrpc.SandboxConversionServiceBlockingStub stub(String caller) {
        return SandboxConversionServiceGrpc.newBlockingStub(f.channel).withDeadlineAfter(20,java.util.concurrent.TimeUnit.SECONDS)
                .withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token",caller,"SERVICE",60,ConversionOnlineFixture.SECRET));
    }
    MockMvc advertiser() {
        var client=new GrpcSandboxConversionClient();ReflectionTestUtils.setField(client,"stub",stub("recsys-advertiser"));
        var guard=new AdvertiserAuthz("disabled",ConversionOnlineFixture.provider(authz));
        SecurityContextHolder.getContext().setAuthentication(actor);
        return MockMvcBuilders.standaloneSetup(new SandboxConversionController(guard,client)).build();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void conversionFirstHttpThenVisibleCapturesOnceAndDuplicateOwnChargeRemainsNull(boolean remote) throws Exception {
        var gateway=f.gateway(remote,f.preparations,f.settlements);var request=request();
        var mvc=MockMvcBuilders.standaloneSetup(new SandboxConversionDeliveryController(f.delivery(gateway)),new SandboxConversionImpressionController(gateway)).build();
        String deliveryPath="/api/sandbox/conversion-ad-deliveries/"+request.operationId();
        var response=mvc.perform(put(deliveryPath).principal(admin).contentType("application/json").content("""
                {"userId":"9223372036854775807","query":"手机","slots":2,"scene":"search"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].adId").value(Long.toString(Long.MAX_VALUE-1)))
                .andExpect(jsonPath("$.items[0].priceUnit").value("PER_CONVERSION")).andExpect(jsonPath("$.items[0].bidType").value("CPA"))
                .andExpect(jsonPath("$.items[0].preparation.status").value("QUALIFIED")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("offerToken"));
        var prepared=f.operations.find(request.operationId(),"admin").orElseThrow().plan().getFirst().result();
        var token=prepared.qualificationToken();var event=UUID.randomUUID();var business=advertiser();String body=body(token,occurred(prepared.childId()));
        business.perform(put(path(event)).principal(actor).contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED_WAITING")).andExpect(jsonPath("$.advertiserId").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.chargedForThisOutcome").value(false)).andExpect(jsonPath("$.chargedAmount").isEmpty())
                .andExpect(jsonPath("$.settlement.progress").value("WAITING_FOR_VISIBLE"));
        var visible=visible(token);String visiblePath="/api/sandbox/conversion-impressions/"+visible.receiptId();
        mvc.perform(put(visiblePath).principal(admin).contentType("application/json").content(ConversionOnlineFixture.JSON.writeValueAsString(
                Map.of("qualificationToken",token,"visibleDurationMs",1000,"minVisibleRatioBps",5000,"pageVisible",true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settlement.progress").value("CAPTURED"));
        String recovered=business.perform(get(path(event)).principal(actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED_WAITING")).andExpect(jsonPath("$.chargedForThisOutcome").value(true))
                .andExpect(jsonPath("$.chargedAmount").value("0.123456")).andReturn().getResponse().getContentAsString();
        assertFalse(recovered.contains(token));assertFalse(recovered.contains("qualificationToken"));assertFalse(recovered.contains(actor.getName()));
        business.perform(put(path(UUID.randomUUID())).principal(actor).contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE_QUALIFICATION")).andExpect(jsonPath("$.chargedForThisOutcome").value(false))
                .andExpect(jsonPath("$.chargedAmount").isEmpty()).andExpect(jsonPath("$.settlement.chargedAmount").value("0.123456"));
        business.perform(put(path(event)).principal(actor).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(content().json(recovered));
        mvc.perform(get(visiblePath).principal(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.receipt.status").value("ACCEPTED_CAPTURED"));
        mvc.perform(get("/api/sandbox/conversion-settlements/"+prepared.childId()).principal(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CAPTURED"));
        assertEquals(response,mvc.perform(get(deliveryPath).principal(admin)).andReturn().getResponse().getContentAsString());
        assertEquals(1,f.ad.queryForObject("SELECT count(*) FROM ad_conversion_ledger",Integer.class));
        assertEquals(1,f.ad.queryForObject("SELECT count(*) FROM ad_conversion_outbox",Integer.class));
        assertEquals(0,f.ad.queryForObject("SELECT count(*) FROM ad_click_ledger",Integer.class));
        assertEquals(0,f.engine.queryForObject("SELECT count(*) FROM engine_ad_delivery",Integer.class));
        assertThrows(RuntimeException.class,()->gateway.findSandboxConversionVisible(visible.receiptId(),"other"));
        verify(authz,atLeast(4)).check(SubjectRef.user(actor.getName()),"edit",ResourceRef.of("advertiser",Long.toString(Long.MAX_VALUE)),Consistency.fullyConsistent());
        verify(f.pipeline,times(1)).sandboxConversionCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void preparationCommitLostReplyThenVisibleFirstRestoresOriginalQualification(boolean remote) throws Exception {
        var fail=new AtomicBoolean(true);
        var losing=new ConversionPreparationService(f.pipeline,f.preparationRepository,f.quotes,f.settlements,f.tokens,true) {
            @Override public SandboxDelivery.Prepared prepare(SandboxDelivery.Prepare c) {
                var r=super.prepare(c);if(fail.getAndSet(false))throw new IllegalStateException("lost after hold");return r;
            }
        };
        var gateway=f.gateway(remote,losing,f.settlements);var request=request();
        assertThrows(RuntimeException.class,()->f.delivery(gateway).deliver(request));
        var pending=f.operations.find(request.operationId(),"admin").orElseThrow();assertNull(pending.plan().getFirst().result());
        var id=pending.plan().getFirst().candidate().childId();var before=f.settlements.find(id,"admin").orElseThrow();
        var restored=f.delivery(gateway).deliver(request);assertEquals(DeliveryOperation.State.COMPLETE,restored.state());
        assertEquals(before.prepared().qualificationToken(),restored.plan().getFirst().result().qualificationToken());
        assertEquals(before,f.settlements.find(id,"admin").orElseThrow());
        var token=before.prepared().qualificationToken();assertEquals(Progress.WAITING_FOR_CONVERSION,gateway.acceptSandboxConversionVisible(visible(token)).settlement().progress());
        advertiser().perform(put(path(UUID.randomUUID())).principal(actor).contentType("application/json").content(body(token,occurred(id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED_CAPTURED")).andExpect(jsonPath("$.chargedForThisOutcome").value(true));
        verify(f.pipeline,times(1)).sandboxConversionCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void businessCommitLostReplyQueriesOriginalButRevokedActorCannotRestore(boolean remote) throws Exception {
        var fail=new AtomicBoolean(true);
        var losing=new ConversionSettlementService(new ConversionSettlementRepository(f.ad,ConversionOnlineFixture.JSON),f.quotes,new ReservationRepository(f.sessions),f.tokens,
                ConversionOnlineFixture.transactions(f.adDs),true,true) {
            @Override public OutcomeResult acceptAuthorizedConversion(ConversionCommand c) {
                var r=super.acceptAuthorizedConversion(c);if(fail.getAndSet(false))throw new IllegalStateException("lost business reply");return r;
            }
        };
        var gateway=f.gateway(remote,f.preparations,losing);var p=f.delivery(gateway).deliver(request()).plan().getFirst().result();
        gateway.acceptSandboxConversionVisible(visible(p.qualificationToken()));
        var mvc=advertiser();var event=UUID.randomUUID();var body=body(p.qualificationToken(),occurred(p.childId()));
        mvc.perform(put(path(event)).principal(actor).contentType("application/json").content(body)).andExpect(status().isServiceUnavailable());
        var original=mvc.perform(get(path(event)).principal(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.chargedForThisOutcome").value(true))
                .andReturn().getResponse().getContentAsString();
        when(authz.check(any(),anyString(),any(),any())).thenReturn(false);
        mvc.perform(get(path(event)).principal(actor)).andExpect(status().isForbidden());
        mvc.perform(put(path(event)).principal(actor).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var other=login("other-authorized-member","ADMIN");SecurityContextHolder.getContext().setAuthentication(other);
        when(authz.check(any(),anyString(),any(),any())).thenReturn(true);
        mvc.perform(put(path(event)).principal(other).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(content().json(original));
        mvc.perform(put(path(event)).principal(other).contentType("application/json").content(body(p.qualificationToken(),occurred(p.childId()).plusMillis(1))))
                .andExpect(status().isConflict());
        assertEquals(1,f.ad.queryForObject("SELECT count(*) FROM ad_conversion_outcome",Integer.class));
        assertEquals(1,f.ad.queryForObject("SELECT count(*) FROM ad_conversion_ledger",Integer.class));
        assertEquals(actor.getName(),f.ad.queryForObject("SELECT actor FROM ad_conversion_outcome",String.class));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void malformedUnauthorizedOutsideWindowAndReleasedRequestsCannotCharge(boolean remote) throws Exception {
        var gateway=f.gateway(remote,f.preparations,f.settlements);var p=f.delivery(gateway).deliver(request()).plan().getFirst().result();
        var mvc=advertiser();var event=UUID.randomUUID();var body=body(p.qualificationToken(),occurred(p.childId()));
        mvc.perform(put(path(event)).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(path(event)).principal(login("viewer","USER")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path(event)).principal(actor).contentType("application/json").content(body.replace("Z\"","123Z\""))).andExpect(status().isBadRequest());
        mvc.perform(put(path(event)).principal(actor).contentType("application/json").content(body.replace("Z\"","+00:00\""))).andExpect(status().isBadRequest());
        mvc.perform(put(path(event)).principal(actor).contentType("application/json").content(body("wrong-proof",occurred(p.childId())))).andExpect(status().isForbidden());
        mvc.perform(put("/api/advertiser/1/sandbox-conversions/"+event).principal(actor).contentType("application/json").content(body)).andExpect(status().isForbidden());
        when(authz.check(any(),anyString(),any(),any())).thenThrow(new IllegalStateException("sensitive-authz-response"));
        String error=mvc.perform(put(path(event)).principal(actor).contentType("application/json").content(body)).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertFalse(error.contains("sensitive"));assertEquals(0,f.ad.queryForObject("SELECT count(*) FROM ad_conversion_outcome",Integer.class));
        doReturn(true).when(authz).check(any(),anyString(),any(),any());
        mvc.perform(put(path(event)).principal(actor).contentType("application/json").content(body(p.qualificationToken(),occurred(p.childId()).minusSeconds(10))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.status").value("OUTSIDE_ATTRIBUTION"));
        f.settlements.release(p.childId(),"admin");
        mvc.perform(put(path(UUID.randomUUID())).principal(actor).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("NOT_HELD")).andExpect(jsonPath("$.chargedForThisOutcome").value(false));
        assertEquals(0,f.ad.queryForObject("SELECT count(*) FROM ad_conversion_ledger",Integer.class));
    }
    @Test void realRpcSeparatesAdvertiserAndEngineCallersAndRejectsMissingTime() throws Exception {
        f.gateway(true,f.preparations,f.settlements);
        var get=GetPreparationRequest.newBuilder().setChildId(UUID.randomUUID().toString()).setSubject("admin").build();
        var outcome=GetConversionOutcomeRequest.newBuilder().setAdvertiserId(Long.MAX_VALUE).setEventId(UUID.randomUUID().toString()).setActor(actor.getName()).build();
        var anonymous=SandboxConversionServiceGrpc.newBlockingStub(f.channel);
        assertEquals(Status.Code.UNAUTHENTICATED,assertThrows(StatusRuntimeException.class,()->anonymous.getPreparation(get)).getStatus().getCode());
        assertEquals(Status.Code.PERMISSION_DENIED,assertThrows(StatusRuntimeException.class,()->stub("recsys").getOutcome(outcome)).getStatus().getCode());
        assertEquals(Status.Code.PERMISSION_DENIED,assertThrows(StatusRuntimeException.class,()->stub("recsys-rec-engine").getOutcome(outcome)).getStatus().getCode());
        assertEquals(Status.Code.PERMISSION_DENIED,assertThrows(StatusRuntimeException.class,()->stub("recsys-advertiser").getPreparation(get)).getStatus().getCode());
        assertEquals(Status.Code.NOT_FOUND,assertThrows(StatusRuntimeException.class,()->stub("recsys-advertiser").getOutcome(outcome)).getStatus().getCode());
        var c=ConversionEventCommand.newBuilder().setAdvertiserId(Long.MAX_VALUE).setEventId(UUID.randomUUID().toString()).setActor(actor.getName()).setQualificationToken("opaque").build();
        assertEquals(Status.Code.INVALID_ARGUMENT,assertThrows(StatusRuntimeException.class,()->stub("recsys-advertiser").acceptConversion(c)).getStatus().getCode());
        assertEquals(Status.Code.PERMISSION_DENIED,assertThrows(StatusRuntimeException.class,()->stub("recsys-rec-engine").acceptConversion(c)).getStatus().getCode());
        assertEquals(0,f.ad.queryForObject("SELECT count(*) FROM ad_conversion_outcome",Integer.class));
    }
    @Test void actualSpringThreeChargeModesUseExplicitRepositoriesAndPrimaryOwner() {
        var flags=new String[]{"recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.clicks-enabled=true",
                "recsys.ad.billing.click-settlement-enabled=true","recsys.ad.billing.click-delivery-enabled=true","recsys.ad.billing.receipts-enabled=true",
                "recsys.ad.billing.conversions-enabled=true","recsys.ad.billing.conversion-settlement-enabled=true","recsys.ad.billing.conversion-delivery-enabled=true",
                "recsys.ad.delivery.signing.current-key-id=test","recsys.ad.delivery.signing.keys.test="+Base64.getEncoder().encodeToString(new byte[32])};
        var contexts=new org.springframework.boot.test.context.runner.ApplicationContextRunner();
        contexts.withUserConfiguration(PreparationConfiguration.class,ClickPreparationConfiguration.class,ConversionPreparationConfiguration.class,ImpressionReceiptConfiguration.class)
                .withPropertyValues(flags).withBean("adDbJdbc",org.springframework.jdbc.core.JdbcTemplate.class,()->f.ad)
                .withBean("adDbDataSource",javax.sql.DataSource.class,()->f.adDs)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->ConversionOnlineFixture.JSON)
                .withBean(com.recsys.ad.AdPipeline.class,()->f.pipeline).withBean(ClickQuoteRepository.class,()->new ClickQuoteRepository(f.ad))
                .withBean(ConversionQuoteRepository.class,()->f.quotes).withBean(ConversionSettlementService.class,()->f.settlements)
                .withBean(ClickSettlementService.class,()->new ClickSettlementService(new ClickSettlementRepository(f.ad,ConversionOnlineFixture.JSON),new ClickQuoteRepository(f.ad),new ReservationRepository(f.sessions),f.tokens,ConversionOnlineFixture.transactions(f.adDs),true,true,new ClickSettlementService.FraudPolicy(true,20))).withBean(CpmQuoteRepository.class,()->new CpmQuoteRepository(f.sessions))
                .withBean(ReservationRepository.class,()->new ReservationRepository(f.sessions))
                .withBean(ReservationService.class,()->new ReservationService(new ReservationRepository(f.sessions),new CpmQuoteRepository(f.sessions),ConversionOnlineFixture.transactions(f.adDs),true))
                .run(c->{assertNull(c.getStartupFailure());assertEquals(3,c.getBeansOfType(PreparationRepository.class).size());
                    assertEquals(SandboxDelivery.ChargeMode.CLICK,c.getBean("clickPreparationRepository",PreparationRepository.class).mode());
                    assertEquals(SandboxDelivery.ChargeMode.IMPRESSION,c.getBean("preparationRepository",PreparationRepository.class).mode());
                    assertNotNull(c.getBean(ImpressionReceiptService.class));assertNotNull(c.getBean(ClickPreparationService.class));assertNotNull(c.getBean(ConversionPreparationService.class));
                    assertEquals(SandboxDelivery.ChargeMode.CONVERSION,c.getBean("conversionPreparationRepository",PreparationRepository.class).mode());});
        contexts.withUserConfiguration(DeliveryOperationConfiguration.class,ClickDeliveryConfiguration.class,ConversionDeliveryConfiguration.class,SandboxDeliveryController.class,SandboxClickDeliveryController.class,SandboxConversionDeliveryController.class)
                .withPropertyValues(flags).withBean("jdbcTemplate",org.springframework.jdbc.core.JdbcTemplate.class,()->f.engine)
                .withBean("adDbJdbc",org.springframework.jdbc.core.JdbcTemplate.class,()->f.ad)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->ConversionOnlineFixture.JSON)
                .withBean(com.recsys.common.query.QueryUnderstandingService.class,()->f.queries)
                .withBean(com.recsys.recengine.experiment.ExperimentService.class,()->f.experiments)
                .withBean(com.recsys.ad.AdProperties.class,com.recsys.ad.AdProperties::new)
                .withBean(com.recsys.recengine.ad.AdServingGateway.class,()->mock(com.recsys.recengine.ad.AdServingGateway.class))
                .run(c->{assertNull(c.getStartupFailure());assertEquals(3,c.getBeansOfType(DeliveryOperationService.class).size());
                    assertEquals(SandboxDelivery.ChargeMode.CLICK,c.getBean("clickDeliveryOperationRepository",DeliveryOperationRepository.class).mode());
                    assertNotNull(c.getBean(SandboxDeliveryController.class));assertNotNull(c.getBean(SandboxClickDeliveryController.class));assertNotNull(c.getBean(SandboxConversionDeliveryController.class));
                    assertEquals(SandboxDelivery.ChargeMode.CONVERSION,c.getBean("conversionDeliveryOperationRepository",DeliveryOperationRepository.class).mode());});
    }
}
