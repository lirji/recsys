package com.recsys.adserving.grpc;

import com.recsys.ad.AdProperties;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.GlobalExceptionHandler;
import com.recsys.proto.ad.v1.*;
import com.recsys.proto.user.v1.UserProfileServiceGrpc;
import com.recsys.proto.user.v1.UpdateInterestsRequest;
import com.recsys.recengine.*;
import com.recsys.recengine.ad.GrpcAdServingGateway;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.user.GrpcUserGateway;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实TCP、Spring切面、Controller链路验证普通写结果；服务端计数模拟可观察副作用，不替代数据库事务证明。 */
@Tag("money-chain")
class OrdinaryWriteHttpTest {
    @ParameterizedTest
    @ValueSource(strings = {"click", "conversion", "outcome", "interests"})
    void successAndDuplicateOutcomeKeepOriginalHttpContract(String operation) throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.context().run(c -> {
                var mvc = mvc(c);
                rpc.bindDeadline();
                var result = mvc.perform(request(operation)).andExpect(status().isOk());
                if (operation.equals("interests")) result.andExpect(jsonPath("$.ok").value(true))
                        .andExpect(jsonPath("$.categories[0]").value("Drama"));
                else result.andExpect(content().string(operation.equals("outcome") ? "true" : ""));
                assertEquals(1, rpc.calls.get());
                if (operation.equals("outcome")) {
                    rpc.ack.set(false);
                    rpc.bindDeadline();
                    mvc.perform(request(operation)).andExpect(status().isOk()).andExpect(content().string("false"));
                    assertEquals(2, rpc.calls.get());
                }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"click", "conversion", "outcome", "interests"})
    void rpcFailuresReturnUnknownWithoutAutomaticWriteRetries(String operation) throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.context().run(c -> {
                var mvc = mvc(c);
                int expectedCalls = 0;
                for (Status failure : new Status[]{Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED, Status.UNAUTHENTICATED,
                        Status.PERMISSION_DENIED, Status.UNKNOWN}) {
                    rpc.failure.set(failure);
                    rpc.bindDeadline();
                    expectUnknown(mvc, operation);
                    assertEquals(++expectedCalls, rpc.calls.get(), "非幂等写不能自动重试");
                }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"click", "conversion", "outcome", "interests"})
    void lostResponseAfterOwnerEffectRemainsUnknown(String operation) throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.dropResponse.set(true);
            rpc.context().run(c -> {
                var mvc = mvc(c);
                rpc.bindDeadline();
                expectUnknown(mvc, operation);
                assertEquals(1, rpc.calls.get(), "丢应答不能导致自动重试");
                // 同一输入已到owner，超时只能说明结果未知，不能声称没有发生副作用。
                var written = rpc.lastWrite.get();
                assertNotNull(written);
                if (written instanceof ClickRequest r) assertEquals("original-request", r.getRequestId());
                else if (written instanceof ConversionRequest r) assertEquals("original-request", r.getRequestId());
                else if (written instanceof OutcomeRequest r) assertEquals("original-event", r.getEventId());
                else assertEquals(1, ((UpdateInterestsRequest) written).getUserId());
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"click", "conversion", "interests"})
    void absentSuccessAckIsNotAcceptedAsSaved(String operation) throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.ack.set(false);
            rpc.context().run(c -> {
                var mvc = mvc(c);
                rpc.bindDeadline();
                expectUnknown(mvc, operation);
                assertEquals(1, rpc.calls.get());
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"click", "conversion", "outcome", "interests"})
    void openCircuitDoesNotBecomeSuccessOrInvokeOwner(String operation) throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.context().run(c -> {
                var mvc = mvc(c);
                c.getBean(CircuitBreakerRegistry.class).circuitBreaker(operation.equals("interests") ? "user-grpc" : "ad-serving-grpc")
                        .transitionToOpenState();
                expectUnknown(mvc, operation);
                assertEquals(0, rpc.calls.get());
            });
        }
    }

    @Test void invalidHttpBindingStillFailsBeforeRpc() throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.context().run(c -> {
                var mvc = mvc(c);
                mvc.perform(post("/api/ad/click").param("requestId", "request").param("adId", "invalid"))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
                mvc.perform(post("/api/user/1/interests").contentType(MediaType.APPLICATION_JSON).content("{"))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
                assertEquals(0, rpc.calls.get());
            });
        }
    }

    private static void expectUnknown(MockMvc mvc, String operation) throws Exception {
        var code = operation.equals("interests") ? ErrorCode.USER_INTEREST_WRITE_RESULT_UNKNOWN : ErrorCode.AD_WRITE_RESULT_UNKNOWN;
        var body = mvc.perform(request(operation)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(code.code())).andExpect(jsonPath("$.message").value(code.message()))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));
        assertFalse(body.contains("\"ok\":true"));
    }

    private static MockMvc mvc(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure());
        var ad = context.getBean(GrpcAdServingGateway.class);
        var user = context.getBean(GrpcUserGateway.class);
        assertTrue(AopUtils.isAopProxy(ad), "必须验证真实CircuitBreaker代理");
        assertTrue(AopUtils.isAopProxy(user), "必须验证真实CircuitBreaker代理");
        var orchestration = new SearchAdsOrchestrator(mock(QueryUnderstandingService.class), mock(ExperimentService.class),
                ad, new AdProperties(), mock(TraceIds.class));
        return MockMvcBuilders.standaloneSetup(new SearchAdsController(orchestration, mock(FeedOrchestrator.class)),
                new UserInterestController(user)).setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();
    }

    private static MockHttpServletRequestBuilder request(String operation) {
        if (operation.equals("interests")) return post("/api/user/1/interests").contentType(MediaType.APPLICATION_JSON).content("[\"Drama\"]");
        if (operation.equals("outcome")) return post("/api/ad/outcome").param("eventId", "original-event")
                .param("advertiserId", "1").param("userId", "1").param("occurredAtEpochMs", "1792000000000");
        return post("/api/ad/" + operation).param("requestId", "original-request").param("adId", "1").param("userId", "1");
    }

    private static class RpcFixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean ack = new AtomicBoolean(true);
        final AtomicBoolean dropResponse = new AtomicBoolean();
        final AtomicReference<Status> failure = new AtomicReference<>();
        final AtomicReference<Object> lastWrite = new AtomicReference<>();
        final GrpcAdServingGateway ad = new GrpcAdServingGateway();
        final GrpcUserGateway user = new GrpcUserGateway();
        final Server server;
        final ManagedChannel channel;

        RpcFixture() throws Exception {
            server = ServerBuilder.forPort(0).addService(new AdServingServiceGrpc.AdServingServiceImplBase() {
                @Override public void recordClick(ClickRequest r, StreamObserver<Ack> o) { respond(r, o, () -> Ack.newBuilder().setOk(ack.get()).build()); }
                @Override public void recordConversion(ConversionRequest r, StreamObserver<Ack> o) { respond(r, o, () -> Ack.newBuilder().setOk(ack.get()).build()); }
                @Override public void recordOutcome(OutcomeRequest r, StreamObserver<Ack> o) { respond(r, o, () -> Ack.newBuilder().setOk(ack.get()).build()); }
            }).addService(new UserProfileServiceGrpc.UserProfileServiceImplBase() {
                @Override public void updateInterests(UpdateInterestsRequest r, StreamObserver<com.recsys.proto.user.v1.Ack> o) {
                    respond(r, o, () -> com.recsys.proto.user.v1.Ack.newBuilder().setOk(ack.get()).build());
                }
            }).build().start();
            channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        }

        <T> void respond(Object request, StreamObserver<T> observer, Supplier<T> reply) {
            calls.incrementAndGet();
            lastWrite.set(request);
            if (failure.get() != null) observer.onError(failure.get().withDescription("private SQL token").asRuntimeException());
            else if (!dropResponse.get()) { observer.onNext(reply.get()); observer.onCompleted(); }
        }

        void bindDeadline() {
            ReflectionTestUtils.setField(ad, "stub", AdServingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS));
            ReflectionTestUtils.setField(user, "stub", UserProfileServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS));
        }

        ApplicationContextRunner context() {
            return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                    CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class))
                    .withBean(GrpcAdServingGateway.class, () -> ad).withBean(GrpcUserGateway.class, () -> user)
                    .withPropertyValues("recsys.ad.serving.mode=grpc", "recsys.user.serving.mode=grpc",
                            "spring.aop.proxy-target-class=true",
                            "resilience4j.circuitbreaker.instances.ad-serving-grpc.minimum-number-of-calls=100",
                            "resilience4j.circuitbreaker.instances.user-grpc.minimum-number-of-calls=100",
                            "resilience4j.retry.instances.ad-serving-grpc.max-attempts=3",
                            "resilience4j.retry.instances.user-grpc.max-attempts=3");
        }

        @Override public void close() throws Exception {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
