package com.recsys.adserving.grpc;

import com.recsys.ad.billing.SandboxPreparationService;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.query.StructuredQuery;
import com.recsys.proto.DeliveryProtoMapper;
import com.recsys.proto.delivery.v1.*;
import com.recsys.proto.security.*;
import io.grpc.*;
import io.grpc.Status;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 gRPC 传输检查身份隔离、精确结果及失败语义，不靠直接调 service 假装 RPC 通过。 */
@Tag("money-chain")
class SandboxDeliveryGrpcTest {
    static final String SECRET = "sandbox-delivery-test-only-secret";
    SandboxPreparationService service;
    Server server;
    ManagedChannel channel;
    SandboxDeliveryServiceGrpc.SandboxDeliveryServiceBlockingStub stub;
    Prepare request = new Prepare(UUID.randomUUID(), "admin", 1, UUID.randomUUID(), 2, "opaque-test-offer");
    @BeforeEach void start() throws Exception {
        service = mock(SandboxPreparationService.class);
        server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new SandboxDeliveryGrpcService(service, "recsys-rec-engine"),
                new InternalAuthGrpcServerInterceptor("x-internal-token", SECRET, false))).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = SandboxDeliveryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }
    @AfterEach void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    SandboxDeliveryServiceGrpc.SandboxDeliveryServiceBlockingStub as(String caller) {
        return stub.withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token", caller, "SERVICE", 60, SECRET));
    }
    void code(Status.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(StatusRuntimeException.class, action).getStatus().getCode());
    }
    @Test void nosecAnonymousAndWrongServiceStillCannotPrepare() {
        code(Status.Code.UNAUTHENTICATED, () -> stub.prepare(DeliveryProtoMapper.toProto(request)));
        code(Status.Code.PERMISSION_DENIED, () -> as("recsys-advertiser").prepare(DeliveryProtoMapper.toProto(request)));
        code(Status.Code.PERMISSION_DENIED, () -> as("recsys").getPreparation(GetPreparationRequest.newBuilder().setChildId(request.childId().toString()).setSubject("admin").build()));
        verifyNoInteractions(service);
    }
    @Test void preciseQualificationAndQueriesRoundTripWithoutRenewal() {
        var prepared = new Prepared(request.childId(), com.recsys.common.ad.delivery.SandboxDelivery.Status.QUALIFIED, "0.000001", "signed-qualification", Instant.parse("2026-10-15T01:00:00.123456Z"));
        when(service.prepare(request)).thenReturn(prepared); when(service.find(request.childId(), "admin")).thenReturn(Optional.of(prepared));
        var trusted = as("recsys-rec-engine");
        assertEquals(prepared, DeliveryProtoMapper.fromProto(trusted.prepare(DeliveryProtoMapper.toProto(request))));
        assertEquals(prepared, DeliveryProtoMapper.fromProto(trusted.getPreparation(GetPreparationRequest.newBuilder().setChildId(request.childId().toString()).setSubject("admin").build())));
    }
    @Test void invalidProofConflictAndUnknownAreNotSuccess() {
        var trusted = as("recsys-rec-engine");
        when(service.prepare(any())).thenThrow(new InvalidProof());
        code(Status.Code.PERMISSION_DENIED, () -> trusted.prepare(DeliveryProtoMapper.toProto(request)));
        doThrow(new Conflict()).when(service).prepare(any());
        code(Status.Code.ALREADY_EXISTS, () -> trusted.prepare(DeliveryProtoMapper.toProto(request)));
        doThrow(new IllegalStateException("sensitive")).when(service).prepare(any());
        var error = assertThrows(StatusRuntimeException.class, () -> trusted.prepare(DeliveryProtoMapper.toProto(request)));
        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode()); assertFalse(error.getMessage().contains("sensitive"));
        code(Status.Code.NOT_FOUND, () -> trusted.getPreparation(GetPreparationRequest.newBuilder().setChildId(request.childId().toString()).setSubject("admin").build()));
    }
    @Test void pureQueryFailureMustNotBecomeEmptySuccessfulResponse() {
        var query = new Query(UUID.randomUUID(), "admin", 1, new StructuredQuery("q", "q", null, null, null, null), 1, "search", "b", .1);
        when(service.candidates(any())).thenThrow(new IllegalStateException("down"));
        code(Status.Code.UNAVAILABLE, () -> as("recsys-rec-engine").candidates(DeliveryProtoMapper.toProto(query)));
        doReturn(List.of()).when(service).candidates(any());
        assertEquals(0, as("recsys-rec-engine").candidates(DeliveryProtoMapper.toProto(query)).getCandidatesCount());
    }
}
