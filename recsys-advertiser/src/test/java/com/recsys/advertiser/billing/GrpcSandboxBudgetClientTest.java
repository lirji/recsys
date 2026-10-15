package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.*;
import com.recsys.proto.BudgetProtoMapper;
import com.recsys.proto.billing.v1.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 RPC 超时窗口及错误映射；服务端可先产生效果再丢响应，客户端不可返回成功或换键。 */
@Tag("money-chain")
class GrpcSandboxBudgetClientTest {
    private Server server;
    private ManagedChannel channel;
    private final GrpcSandboxBudgetClient client = new GrpcSandboxBudgetClient();
    private final AtomicReference<BudgetCommandReply> committed = new AtomicReference<>();
    private final AtomicReference<Status> error = new AtomicReference<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private final BudgetCommand command = BudgetCommand.parse(123, UUID.randomUUID().toString(),
            "2026-10-15", "CNY", "0.000001", 0, "admin");

    @BeforeEach
    void start() throws Exception {
        server = ServerBuilder.forPort(0).addService(new SandboxBudgetServiceGrpc.SandboxBudgetServiceImplBase() {
            @Override
            public void setLimit(SetBudgetLimitRequest request, StreamObserver<BudgetCommandReply> observer) {
                attempts.incrementAndGet();
                if (error.get() != null) {
                    observer.onError(error.get().withDescription("internal secret details").asRuntimeException());
                    return;
                }
                var parsed = BudgetProtoMapper.fromProto(request);
                var account = new BudgetAccount(parsed.advertiserId(), parsed.budgetDate(), parsed.limitAmount(),
                        BigDecimal.ZERO, BigDecimal.ZERO, 1);
                committed.set(BudgetProtoMapper.toProto(new BudgetCommandResult(parsed.commandId(),
                        BudgetCommandResult.Status.APPLIED, account)));
                // 故意不发送响应，真实 deadline 到期；模拟已提交但应答丢失，不用 sleep 猜窗口。
            }

            @Override
            public void getCommand(GetBudgetCommandRequest request, StreamObserver<BudgetCommandReply> observer) {
                assertEquals(command.commandId().toString(), request.getCommandId());
                observer.onNext(committed.get());
                observer.onCompleted();
            }
        }).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        ReflectionTestUtils.setField(client, "stub", SandboxBudgetServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(2, TimeUnit.SECONDS));
    }

    @AfterEach
    void close() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void lostResponseIsUnknownAndOriginalCommandCanBeQueried() {
        var failure = assertThrows(ResponseStatusException.class, () -> client.setLimit(command));
        assertEquals(503, failure.getStatusCode().value());
        assertTrue(failure.getReason().contains("结果未知"));
        assertEquals(1, attempts.get());
        assertNotNull(committed.get());
        // 每次调用有独立 deadline；测试直接创建新 stub 模拟全局拦截器逐次设置 deadline。
        ReflectionTestUtils.setField(client, "stub", SandboxBudgetServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(2, TimeUnit.SECONDS));
        var found = client.getCommand(123, command.commandId());
        assertEquals(BudgetCommandResult.Status.APPLIED, found.status());
        assertEquals(command.commandId(), found.commandId());
        assertEquals("0.000001", found.account().limitAmount().toPlainString());
    }

    @Test
    void conflictsNotFoundAndUnavailableKeepDistinctHttpSemantics() {
        for (var scenario : new Object[][]{
                {Status.ALREADY_EXISTS, 409}, {Status.NOT_FOUND, 404}, {Status.INVALID_ARGUMENT, 400},
                // 内部token无效不是终端用户权限不足，旧服务不带metadata时也不能误导用户去申请权限。
                {Status.PERMISSION_DENIED, 403}, {Status.UNAUTHENTICATED, 503}, {Status.UNAVAILABLE, 503}}) {
            error.set((Status) scenario[0]);
            var failure = assertThrows(ResponseStatusException.class, () -> client.setLimit(command));
            assertEquals(scenario[1], failure.getStatusCode().value());
            assertFalse(failure.getReason().contains("internal secret"));
        }
    }
}
