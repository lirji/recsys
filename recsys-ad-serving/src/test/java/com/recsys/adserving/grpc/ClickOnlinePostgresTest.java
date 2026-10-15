package com.recsys.adserving.grpc;

import com.recsys.ad.billing.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.recengine.delivery.*;
import com.recsys.proto.ClickProtoMapper;
import com.recsys.proto.click.v1.*;
import com.recsys.proto.delivery.v1.GetPreparationRequest;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 两个真实PG owner、HTTP边界与真实TCP gRPC；不使用mock来证明资金事务。 */
@Tag("money-chain") @Testcontainers
class ClickOnlinePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ClickOnlineFixture f;
    final UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("admin","unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup() throws Exception { f=new ClickOnlineFixture(PG); }
    @AfterEach void close() throws Exception { f.close(); }
    DeliveryOperation.Request request() { return new DeliveryOperation.Request(UUID.randomUUID(),"admin",Long.MAX_VALUE,"手机",2,"search"); }
    Command command(String token,Kind kind) { return new Command(UUID.randomUUID(),"admin",token,kind,kind==Kind.VISIBLE?1000:0,kind==Kind.VISIBLE?5000:0,kind==Kind.VISIBLE); }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void completeHttpDeliveryClickFirstCaptureAndOriginalQueriesAreEqualAcrossModes(boolean remote) throws Exception {
        var gateway=f.gateway(remote,f.preparations,f.settlements);var request=request();
        var mvc=MockMvcBuilders.standaloneSetup(new SandboxClickDeliveryController(f.delivery(gateway)),new SandboxClickReceiptController(gateway)).build();
        String path="/api/sandbox/click-ad-deliveries/"+request.operationId();
        var response=mvc.perform(put(path).principal(admin).contentType("application/json").content("""
                {"userId":"9223372036854775807","query":"手机","slots":2,"scene":"search"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].adId").value(Long.toString(Long.MAX_VALUE-1)))
                .andExpect(jsonPath("$.items[0].priceUnit").value("PER_CLICK")).andExpect(jsonPath("$.items[0].bidType").value("OCPC"))
                .andExpect(jsonPath("$.items[0].preparation.status").value("QUALIFIED")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("offerToken"));
        var token=ClickOnlineFixture.JSON.readTree(response).at("/items/0/preparation/qualificationToken").asText();
        var click=command(token,Kind.CLICK);var visible=command(token,Kind.VISIBLE);
        String clickPath="/api/sandbox/click-receipts/"+click.receiptId();
        String clickBody=ClickOnlineFixture.JSON.writeValueAsString(Map.of("qualificationToken",token,"kind","CLICK","visibleDurationMs",0,"minVisibleRatioBps",0,"pageVisible",false));
        mvc.perform(put(clickPath).principal(admin).contentType("application/json").content(clickBody)).andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.status").value("ACCEPTED_WAITING")).andExpect(jsonPath("$.settlement.progress").value("WAITING_FOR_VISIBLE"))
                .andExpect(jsonPath("$.settlement.chargedAmount").isEmpty());
        var captured=gateway.acceptSandboxClick(visible);assertEquals(State.CAPTURED,captured.settlement().state());assertEquals("0.123456",captured.settlement().chargedAmount());
        var recovered=mvc.perform(get(clickPath).principal(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.receipt.status").value("ACCEPTED_WAITING"))
                .andExpect(jsonPath("$.settlement.progress").value("CAPTURED")).andExpect(jsonPath("$.settlement.chargedAmount").value("0.123456"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(recovered.contains(token));assertFalse(recovered.contains("qualificationToken"));
        mvc.perform(get("/api/sandbox/click-settlements/"+captured.receipt().qualificationId()).principal(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CAPTURED"));
        assertEquals(captured.settlement(),gateway.acceptSandboxClick(click).settlement());
        assertEquals(1,f.ad.queryForObject("SELECT count(*) FROM ad_click_ledger",Integer.class));
        assertEquals(1,f.ad.queryForObject("SELECT count(*) FROM ad_click_outbox",Integer.class));
        assertEquals(0,f.ad.queryForObject("SELECT count(*) FROM ad_billing_ledger",Integer.class));
        assertEquals(0,f.engine.queryForObject("SELECT count(*) FROM engine_ad_delivery",Integer.class));
        assertThrows(RuntimeException.class,()->gateway.findSandboxClickReceipt(click.receiptId(),"other"));
        // COMPLETE完全从engine快照恢复，不重做候选或重新预占。
        assertEquals(response,mvc.perform(get(path).principal(admin)).andReturn().getResponse().getContentAsString());
        verify(f.pipeline,times(1)).sandboxClickCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble());
        verify(f.pipeline,never()).recordClick(anyString(),anyLong(),anyLong());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void preparationCommitThenLostReplyResumesSamePlanAndBudget(boolean remote) throws Exception {
        var fail=new AtomicBoolean(true);
        var losing=new ClickPreparationService(f.pipeline,f.preparationRepository,f.quotes,f.settlements,f.tokens,true) {
            @Override public SandboxDelivery.Prepared prepare(SandboxDelivery.Prepare command) {
                var result=super.prepare(command);if(fail.getAndSet(false))throw new IllegalStateException("after commit lost reply");return result;
            }
        };
        var gateway=f.gateway(remote,losing,f.settlements);var service=f.delivery(gateway);var request=request();
        assertThrows(RuntimeException.class,()->service.deliver(request));
        var partial=f.operations.find(request.operationId(),"admin").orElseThrow();assertNull(partial.plan().getFirst().result());
        var id=partial.plan().getFirst().candidate().childId();var before=f.settlements.find(id,"admin").orElseThrow();
        var restored=f.delivery(gateway).deliver(request);assertEquals(DeliveryOperation.State.COMPLETE,restored.state());
        assertEquals(before.prepared().qualificationToken(),restored.plan().getFirst().result().qualificationToken());
        assertEquals(before,f.settlements.find(id,"admin").orElseThrow());
        var token=before.prepared().qualificationToken();var visible=gateway.acceptSandboxClick(command(token,Kind.VISIBLE));
        assertEquals(Progress.WAITING_FOR_CLICK,visible.settlement().progress());
        assertEquals(State.CAPTURED,gateway.acceptSandboxClick(command(token,Kind.CLICK)).settlement().state());
        verify(f.pipeline,times(1)).sandboxClickCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void receiptCommitThenLostReplyQueriesOriginalAndChangedBodyConflicts(boolean remote) throws Exception {
        var fail=new AtomicBoolean(true);
        var losing=new ClickSettlementService(new ClickSettlementRepository(f.sessions,ClickOnlineFixture.JSON),f.quotes,new ReservationRepository(f.sessions),f.tokens,
                ClickOnlineFixture.transactions(f.adDs),true,true,new ClickSettlementService.FraudPolicy(true,20)) {
            @Override public Result accept(Command c) { var result=super.accept(c);if(fail.getAndSet(false))throw new IllegalStateException("lost receipt reply");return result; }
        };
        var gateway=f.gateway(remote,f.preparations,losing);
        var token=f.delivery(gateway).deliver(request()).plan().getFirst().result().qualificationToken();var c=command(token,Kind.CLICK);
        assertThrows(RuntimeException.class,()->gateway.acceptSandboxClick(c));
        var original=gateway.findSandboxClickReceipt(c.receiptId(),"admin").orElseThrow();assertEquals(ReceiptStatus.ACCEPTED_WAITING,original.receipt().status());
        assertEquals(original,gateway.acceptSandboxClick(c));
        assertThrows(ClickSettlement.Conflict.class,()->gateway.acceptSandboxClick(new Command(c.receiptId(),"admin",token,Kind.VISIBLE,1000,5000,true)));
        assertEquals(1,f.ad.queryForObject("SELECT accepted_count FROM ad_click_rate WHERE user_id=?",Integer.class,Long.MAX_VALUE));
        assertEquals(0,f.ad.queryForObject("SELECT count(*) FROM ad_click_ledger",Integer.class));
    }
    @Test void actualSpringBothModesUseExplicitRepositoriesAndPrimaryOwner() {
        var flags=new String[]{"recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.clicks-enabled=true",
                "recsys.ad.billing.click-settlement-enabled=true","recsys.ad.billing.click-delivery-enabled=true","recsys.ad.billing.receipts-enabled=true",
                "recsys.ad.delivery.signing.current-key-id=test","recsys.ad.delivery.signing.keys.test="+Base64.getEncoder().encodeToString(new byte[32])};
        var contexts=new org.springframework.boot.test.context.runner.ApplicationContextRunner();
        contexts.withUserConfiguration(PreparationConfiguration.class,ClickPreparationConfiguration.class,ImpressionReceiptConfiguration.class)
                .withPropertyValues(flags).withBean("adDbJdbc",org.springframework.jdbc.core.JdbcTemplate.class,()->f.ad)
                .withBean("adDbDataSource",javax.sql.DataSource.class,()->f.adDs)
                .withBean("adDbSqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->f.sessions)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->ClickOnlineFixture.JSON)
                .withBean(com.recsys.ad.AdPipeline.class,()->f.pipeline).withBean(ClickQuoteRepository.class,()->f.quotes)
                .withBean(ClickSettlementService.class,()->f.settlements).withBean(CpmQuoteRepository.class,()->new CpmQuoteRepository(f.sessions))
                .withBean(ReservationRepository.class,()->new ReservationRepository(f.sessions))
                .withBean(ReservationService.class,()->new ReservationService(new ReservationRepository(f.sessions),new CpmQuoteRepository(f.sessions),ClickOnlineFixture.transactions(f.adDs),true))
                .run(c->{assertNull(c.getStartupFailure());assertEquals(2,c.getBeansOfType(PreparationRepository.class).size());
                    assertEquals(SandboxDelivery.ChargeMode.CLICK,c.getBean("clickPreparationRepository",PreparationRepository.class).mode());
                    assertEquals(SandboxDelivery.ChargeMode.IMPRESSION,c.getBean("preparationRepository",PreparationRepository.class).mode());
                    assertNotNull(c.getBean(ImpressionReceiptService.class));assertNotNull(c.getBean(ClickPreparationService.class));});
        contexts.withUserConfiguration(DeliveryOperationConfiguration.class,ClickDeliveryConfiguration.class,SandboxDeliveryController.class,SandboxClickDeliveryController.class)
                .withPropertyValues(flags).withBean("sqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->f.engineSessions)
                .withBean("adDbSqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->f.sessions)
                .withBean("adDbJdbc",org.springframework.jdbc.core.JdbcTemplate.class,()->f.ad)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->ClickOnlineFixture.JSON)
                .withBean(com.recsys.common.query.QueryUnderstandingService.class,()->f.queries)
                .withBean(com.recsys.recengine.experiment.ExperimentService.class,()->f.experiments)
                .withBean(com.recsys.ad.AdProperties.class,com.recsys.ad.AdProperties::new)
                .withBean(com.recsys.recengine.ad.AdServingGateway.class,()->mock(com.recsys.recengine.ad.AdServingGateway.class))
                .run(c->{assertNull(c.getStartupFailure());assertEquals(2,c.getBeansOfType(DeliveryOperationService.class).size());
                    assertEquals(SandboxDelivery.ChargeMode.CLICK,c.getBean("clickDeliveryOperationRepository",DeliveryOperationRepository.class).mode());
                    assertNotNull(c.getBean(SandboxDeliveryController.class));assertNotNull(c.getBean(SandboxClickDeliveryController.class));});
    }
    @Test void realRpcEnforcesCallerEvenNosecAndStrictKindsAndUnknownResults() throws Exception {
        f.gateway(true,f.preparations,f.settlements);
        var untrusted=SandboxClickServiceGrpc.newBlockingStub(f.channel);var get=GetPreparationRequest.newBuilder().setChildId(UUID.randomUUID().toString()).setSubject("admin").build();
        assertEquals(Status.Code.UNAUTHENTICATED,assertThrows(StatusRuntimeException.class,()->untrusted.getPreparation(get)).getStatus().getCode());
        var wrong=untrusted.withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token","recsys","SERVICE",60,ClickOnlineFixture.SECRET));
        assertEquals(Status.Code.PERMISSION_DENIED,assertThrows(StatusRuntimeException.class,()->wrong.getPreparation(get)).getStatus().getCode());
        var trusted=untrusted.withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token","recsys-rec-engine","SERVICE",60,ClickOnlineFixture.SECRET));
        assertEquals(Status.Code.NOT_FOUND,assertThrows(StatusRuntimeException.class,()->trusted.getPreparation(get)).getStatus().getCode());
        var malformed=ClickReceiptCommand.newBuilder().setReceiptId(UUID.randomUUID().toString()).setSubject("admin").setQualificationToken("opaque").setKind("future").build();
        assertEquals(Status.Code.INVALID_ARGUMENT,assertThrows(StatusRuntimeException.class,()->trusted.accept(malformed)).getStatus().getCode());
        assertEquals(0,f.ad.queryForObject("SELECT count(*) FROM ad_click_receipt",Integer.class));
    }
}
