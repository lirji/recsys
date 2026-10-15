package com.recsys.recengine.user;

import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.GlobalExceptionHandler;
import com.recsys.proto.user.v1.*;
import com.recsys.recengine.UserInterestController;
import com.recsys.user.UserProfileService;
import com.recsys.user.persistence.mapper.UserProfileMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实TCP、重试/熔断代理和HTTP边界区分“没有兴趣”与“读取失败”，推荐允许独立降级。 */
class UserInterestReadHttpTest {
    @Test
    void emptyAndPresentInterestsRemainSuccessful() throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.context().run(c -> {
                var gateway = c.getBean(GrpcUserGateway.class);
                assertTrue(AopUtils.isAopProxy(gateway));
                var mvc = mvc(gateway);
                mvc.perform(get("/api/user/1/interests")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.userId").value(1)).andExpect(jsonPath("$.categories").isEmpty());
                rpc.categories = List.of("Drama", "Action", "Drama");
                mvc.perform(get("/api/user/1/interests")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.categories[2]").value("Drama"));
                assertEquals(rpc.categories, new GrpcUserProfileReader(gateway).categories(1));
                assertEquals(3, rpc.calls.get());
            });
        }
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "UNAUTHENTICATED", "PERMISSION_DENIED", "UNKNOWN"})
    void failuresKeepReadRetryPolicyButHttpDoesNotClaimEmpty(Status.Code failure) throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.failure = failure;
            rpc.failuresBeforeSuccess = Integer.MAX_VALUE;
            rpc.context().run(c -> {
                var gateway = c.getBean(GrpcUserGateway.class);
                assertTrue(AopUtils.isAopProxy(gateway));
                expectUnavailable(mvc(gateway));
                int attempts = failure == Status.Code.UNAVAILABLE || failure == Status.Code.DEADLINE_EXCEEDED ? 2 : 1;
                assertEquals(attempts, rpc.calls.get(), "瞬时读仅重试一次，其他错误不重试");
                assertTrue(new GrpcUserProfileReader(gateway).categories(1).isEmpty(), "推荐允许继续走冷启动");
                assertEquals(2 * attempts, rpc.calls.get());
            });
        }
    }

    @Test
    void transientReadRecoveryStillReturnsActualInterests() throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.failure = Status.Code.UNAVAILABLE;
            rpc.failuresBeforeSuccess = 1;
            rpc.categories = List.of("Action");
            rpc.context().run(c -> {
                mvc(c.getBean(GrpcUserGateway.class)).perform(get("/api/user/1/interests"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.categories[0]").value("Action"));
                assertEquals(2, rpc.calls.get());
            });
        }
    }

    @Test
    void openCircuitIsUnavailableForHttpAndEmptyOnlyForRecommendation() throws Exception {
        try (var rpc = new RpcFixture()) {
            rpc.context().run(c -> {
                var gateway = c.getBean(GrpcUserGateway.class);
                c.getBean(CircuitBreakerRegistry.class).circuitBreaker("user-grpc").transitionToOpenState();
                expectUnavailable(mvc(gateway));
                assertTrue(new GrpcUserProfileReader(gateway).categories(1).isEmpty());
                assertEquals(0, rpc.calls.get());
            });
        }
    }

    @Test
    void inProcessOwnerFailureIsNotEmptyAndSuccessCanRecover() throws Exception {
        var mapper = mock(UserProfileMapper.class);
        var gateway = new InProcessUserGateway(new UserProfileService(mapper));
        var mvc = mvc(gateway);
        when(mapper.findInterests(1)).thenThrow(new DataAccessResourceFailureException("private SQL token"));
        expectUnavailable(mvc);
        doReturn(List.of()).when(mapper).findInterests(1);
        mvc.perform(get("/api/user/1/interests")).andExpect(status().isOk()).andExpect(jsonPath("$.categories").isEmpty());
        doReturn(List.of("Drama")).when(mapper).findInterests(1);
        mvc.perform(get("/api/user/1/interests")).andExpect(status().isOk()).andExpect(jsonPath("$.categories[0]").value("Drama"));
        verify(mapper, times(3)).findInterests(1);
    }

    private static MockMvc mvc(UserGateway gateway) {
        return MockMvcBuilders.standaloneSetup(new UserInterestController(gateway))
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();
    }

    private static void expectUnavailable(MockMvc mvc) throws Exception {
        var body = mvc.perform(get("/api/user/1/interests")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_INTEREST_QUERY_FAILED.code()))
                .andExpect(jsonPath("$.message").value(ErrorCode.USER_INTEREST_QUERY_FAILED.message()))
                .andExpect(jsonPath("$.categories").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));
    }

    private static class RpcFixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<String> categories = List.of();
        volatile Status.Code failure = Status.Code.UNKNOWN;
        volatile int failuresBeforeSuccess;
        final GrpcUserGateway gateway = new GrpcUserGateway();
        final Server server;
        final ManagedChannel channel;

        RpcFixture() throws Exception {
            server = ServerBuilder.forPort(0).addService(new UserProfileServiceGrpc.UserProfileServiceImplBase() {
                @Override public void getInterests(UserId request, StreamObserver<Interests> observer) {
                    if (calls.incrementAndGet() <= failuresBeforeSuccess) {
                        observer.onError(Status.fromCode(failure).withDescription("private SQL token").asRuntimeException());
                    } else {
                        observer.onNext(Interests.newBuilder().addAllCategories(categories).build());
                        observer.onCompleted();
                    }
                }
            }).build().start();
            channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
            ReflectionTestUtils.setField(gateway, "stub", UserProfileServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS));
        }

        ApplicationContextRunner context() {
            return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                    CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class))
                    .withBean(GrpcUserGateway.class, () -> gateway)
                    .withPropertyValues("recsys.user.serving.mode=grpc", "spring.aop.proxy-target-class=true",
                            "resilience4j.circuitbreaker.instances.user-grpc.minimum-number-of-calls=100",
                            "resilience4j.retry.instances.user-grpc.max-attempts=2",
                            "resilience4j.retry.instances.user-grpc.wait-duration=10ms",
                            "resilience4j.retry.instances.user-grpc.retry-exception-predicate=com.recsys.proto.grpc.GrpcRetryablePredicate");
        }

        @Override public void close() throws Exception {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
