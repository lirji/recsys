package com.recsys.adserving.grpc;

import com.recsys.ad.billing.BudgetAccountService;
import com.recsys.common.ad.billing.*;
import com.recsys.proto.BudgetProtoMapper;
import com.recsys.proto.billing.v1.*;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import io.grpc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用真实 gRPC 传输及内部令牌拦截器验证边界；数据库事务由独立 PG 测试证明。 */
@Tag("money-chain")
class SandboxBudgetGrpcTest {
    private static final String SECRET = "sandbox-budget-test-only-secret";
    private final BudgetAccountService service = mock(BudgetAccountService.class);
    private Server server;
    private ManagedChannel channel;
    private SandboxBudgetServiceGrpc.SandboxBudgetServiceBlockingStub stub;
    private final BudgetCommand command = BudgetCommand.parse(123, UUID.randomUUID().toString(),
            "2026-10-15", "CNY", "123.123456", 0, "admin:test");

    @BeforeEach
    void start() throws Exception {
        // required=false 模拟旧 nosec 的宽松入口：无可信 subject 仍须由新业务边界拒绝。
        server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(
                new SandboxBudgetGrpcService(service, "recsys-advertiser"),
                new InternalAuthGrpcServerInterceptor("x-internal-token", SECRET, false))).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = SandboxBudgetServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void anonymousWrongCallerAndBadSignatureCannotTouchMoney() {
        var request = BudgetProtoMapper.toProto(command);
        assertStatus(Status.Code.UNAUTHENTICATED, () -> stub.setLimit(request));
        assertStatus(Status.Code.PERMISSION_DENIED, () -> as("recsys-rec-engine", SECRET).setLimit(request));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> as("recsys-advertiser", "incorrect").setLimit(request));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> stub.getAccount(GetBudgetAccountRequest.newBuilder()
                .setAdvertiserId(123).setBudgetDate("2026-10-15").build()));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> stub.getCommand(GetBudgetCommandRequest.newBuilder()
                .setAdvertiserId(123).setCommandId(command.commandId().toString()).build()));
        verifyNoInteractions(service);
    }

    @Test
    void trustedCallerRoundTripsPreciseCommandAndRejectedResult() {
        var account = new BudgetAccount(123, command.budgetDate(), command.limitAmount(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        var result = new BudgetCommandResult(command.commandId(), BudgetCommandResult.Status.VERSION_CONFLICT, account);
        when(service.setLimit(command)).thenReturn(result);
        var reply = as("recsys-advertiser", SECRET).setLimit(BudgetProtoMapper.toProto(command));
        assertEquals(result, BudgetProtoMapper.fromProto(reply));
        verify(service).setLimit(command);
    }

    @Test
    void invalidPayloadAndConflictHaveExplicitCodes() {
        var trusted = as("recsys-advertiser", SECRET);
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> trusted.setLimit(BudgetProtoMapper.toProto(command)
                .toBuilder().setLimitAmount("1e2").build()));
        verifyNoInteractions(service);
        when(service.setLimit(command)).thenThrow(new BudgetCommandResult.PayloadConflict());
        assertStatus(Status.Code.ALREADY_EXISTS, () -> trusted.setLimit(BudgetProtoMapper.toProto(command)));
    }

    @Test
    void notFoundAndDatabaseFailureAreNotSuccessfulAck() {
        when(service.findCommand(123, command.commandId())).thenReturn(Optional.empty());
        var trusted = as("recsys-advertiser", SECRET);
        assertStatus(Status.Code.NOT_FOUND, () -> trusted.getCommand(GetBudgetCommandRequest.newBuilder()
                .setAdvertiserId(123).setCommandId(command.commandId().toString()).build()));
        when(service.setLimit(command)).thenThrow(new IllegalStateException("internal-db-details"));
        var error = assertThrows(StatusRuntimeException.class, () -> trusted.setLimit(BudgetProtoMapper.toProto(command)));
        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        assertFalse(error.getMessage().contains("internal-db-details"));
        assertTrue(error.getMessage().contains("结果未知"));
    }

    private SandboxBudgetServiceGrpc.SandboxBudgetServiceBlockingStub as(String subject, String secret) {
        return stub.withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token", subject, "SERVICE", 60, secret));
    }

    private static void assertStatus(Status.Code expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(StatusRuntimeException.class, call).getStatus().getCode());
    }
}
