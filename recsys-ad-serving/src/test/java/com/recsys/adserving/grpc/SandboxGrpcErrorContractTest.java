package com.recsys.adserving.grpc;

import com.recsys.ad.billing.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.billing.v1.*;
import com.recsys.proto.click.v1.SandboxClickServiceGrpc;
import com.recsys.proto.conversion.v1.SandboxConversionServiceGrpc;
import com.recsys.proto.delivery.v1.*;
import com.recsys.proto.impression.v1.*;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import io.grpc.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实TCP传输验证五个服务的码/恢复提示及恶意下游错误隔离；数据库事务由原PG套件验证。 */
@Tag("money-chain")
class SandboxGrpcErrorContractTest {
    private static final String SECRET = "rpc-error-contract-test-only-secret";
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Metadata.Key<String> PRIVATE = Metadata.Key.of("private-detail", Metadata.ASCII_STRING_MARSHALLER);
    record Boundary(BindableService service, String caller, Consumer<Channel> invoke,
                    Consumer<RuntimeException> failOwner, RuntimeException conflict,
                    ErrorCode missing, ErrorCode invalid, ErrorCode conflicting, ErrorCode unknown) {}

    static Stream<Boundary> boundaries() {
        var budget = mock(BudgetAccountService.class);
        when(budget.findCommand(1, ID)).thenReturn(Optional.empty());
        var delivery = mock(SandboxPreparationService.class);
        when(delivery.find(ID, "admin")).thenReturn(Optional.empty());
        var receipt = mock(ImpressionReceiptService.class);
        when(receipt.find(ID, "admin")).thenReturn(Optional.empty());
        var click = mock(ClickPreparationService.class);
        when(click.find(ID, "admin")).thenReturn(Optional.empty());
        var conversion = mock(ConversionPreparationService.class);
        when(conversion.find(ID, "admin")).thenReturn(Optional.empty());
        var get = GetPreparationRequest.newBuilder().setChildId(ID.toString()).setSubject("admin").build();
        return Stream.of(
                new Boundary(new SandboxBudgetGrpcService(budget, "advertiser"), "advertiser",
                        c -> SandboxBudgetServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS).getCommand(
                                GetBudgetCommandRequest.newBuilder().setAdvertiserId(1).setCommandId(ID.toString()).build()),
                        ex -> doThrow(ex).when(budget).findCommand(1, ID), new BudgetCommandResult.PayloadConflict(),
                        ErrorCode.RPC_BUDGET_COMMAND_NOT_FOUND, ErrorCode.RPC_BUDGET_INVALID_ARGUMENT,
                        ErrorCode.RPC_BUDGET_PAYLOAD_CONFLICT, ErrorCode.RPC_BUDGET_RESULT_UNKNOWN),
                new Boundary(new SandboxDeliveryGrpcService(delivery, "engine"), "engine",
                        c -> SandboxDeliveryServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS).getPreparation(get),
                        ex -> doThrow(ex).when(delivery).find(ID, "admin"), new SandboxDelivery.Conflict(),
                        ErrorCode.RPC_DELIVERY_NOT_FOUND, ErrorCode.RPC_DELIVERY_INVALID_ARGUMENT,
                        ErrorCode.RPC_DELIVERY_CONFLICT, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN),
                new Boundary(new SandboxImpressionGrpcService(receipt, "engine"), "engine",
                        c -> SandboxImpressionServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS).getReceipt(
                                GetVisibleReceiptRequest.newBuilder().setReceiptId(ID.toString()).setSubject("admin").build()),
                        ex -> doThrow(ex).when(receipt).find(ID, "admin"), new ImpressionReceipt.Conflict(),
                        ErrorCode.RPC_RECEIPT_NOT_FOUND, ErrorCode.RPC_RECEIPT_INVALID_ARGUMENT,
                        ErrorCode.RPC_RECEIPT_CONFLICT, ErrorCode.RPC_RECEIPT_RESULT_UNKNOWN),
                new Boundary(new SandboxClickGrpcService(click, mock(ClickSettlementService.class), "engine"), "engine",
                        c -> SandboxClickServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS).getPreparation(get),
                        ex -> doThrow(ex).when(click).find(ID, "admin"), new ClickSettlement.Conflict(),
                        ErrorCode.RPC_CLICK_NOT_FOUND, ErrorCode.RPC_CLICK_INVALID_ARGUMENT,
                        ErrorCode.RPC_CLICK_CONFLICT, ErrorCode.RPC_CLICK_RESULT_UNKNOWN),
                new Boundary(new SandboxConversionGrpcService(conversion, mock(ConversionSettlementService.class), "engine", "advertiser"), "engine",
                        c -> SandboxConversionServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS).getPreparation(get),
                        ex -> doThrow(ex).when(conversion).find(ID, "admin"), new ConversionSettlement.Conflict(),
                        ErrorCode.RPC_CONVERSION_NOT_FOUND, ErrorCode.RPC_CONVERSION_INVALID_ARGUMENT,
                        ErrorCode.RPC_CONVERSION_CONFLICT, ErrorCode.RPC_CONVERSION_RESULT_UNKNOWN));
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void safeErrorsSurviveRealTransportWithoutUpstreamDetails(Boundary b) throws Exception {
        Server server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(b.service(),
                new InternalAuthGrpcServerInterceptor("x-internal-token", SECRET, false))).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
            assertError(b, channel, Status.Code.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
            Channel wrongCaller = ClientInterceptors.intercept(channel,
                    new InternalAuthGrpcClientInterceptor("x-internal-token", "wrong-service", "SERVICE", 60, SECRET));
            assertError(b, wrongCaller, Status.Code.PERMISSION_DENIED,
                    b.caller().equals("advertiser") ? ErrorCode.RPC_BUDGET_CALLER_FORBIDDEN : ErrorCode.RPC_INTERNAL_CALLER_FORBIDDEN);
            Channel trusted = ClientInterceptors.intercept(channel,
                    new InternalAuthGrpcClientInterceptor("x-internal-token", b.caller(), "SERVICE", 60, SECRET));
            assertError(b, trusted, Status.Code.NOT_FOUND, b.missing());
            b.failOwner().accept(new IllegalArgumentException("private SQL token"));
            assertError(b, trusted, Status.Code.INVALID_ARGUMENT, b.invalid());
            b.failOwner().accept(b.conflict());
            assertError(b, trusted, Status.Code.ALREADY_EXISTS, b.conflicting());
            b.failOwner().accept(new IllegalStateException("private SQL token"));
            assertError(b, trusted, Status.Code.UNAVAILABLE, b.unknown());
            // 对端即使伪造合法目录码，也不能让任意描述或额外metadata回流。
            Metadata forged = new Metadata();
            forged.put(GrpcErrors.ERROR_CODE, b.missing().code());
            forged.put(PRIVATE, "private SQL token");
            for (Status.Code status : new Status.Code[]{Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL}) {
                b.failOwner().accept(Status.fromCode(status).withDescription("private SQL token")
                        .withCause(new IllegalStateException("private cause")).asRuntimeException(forged));
                assertError(b, trusted, status, b.unknown());
            }
            b.failOwner().accept(Status.RESOURCE_EXHAUSTED.withDescription("private limit").asRuntimeException(forged));
            assertError(b, trusted, Status.Code.RESOURCE_EXHAUSTED, ErrorCode.TOO_MANY_REQUESTS);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void assertError(Boundary b, Channel channel, Status.Code status, ErrorCode code) {
        var error = assertThrows(StatusRuntimeException.class, () -> b.invoke().accept(channel));
        assertEquals(status, error.getStatus().getCode());
        assertEquals(code.code(), error.getTrailers().get(GrpcErrors.ERROR_CODE));
        assertEquals("[" + code.code() + "] " + code.message(), error.getStatus().getDescription());
        assertNull(error.getTrailers().get(PRIVATE));
        assertFalse(error.getMessage().contains("private"));
    }
}
