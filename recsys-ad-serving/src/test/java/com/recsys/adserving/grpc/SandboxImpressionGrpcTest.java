package com.recsys.adserving.grpc;

import com.recsys.ad.billing.ImpressionReceiptService;
import com.recsys.common.ad.billing.ImpressionReceipt;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ImpressionProtoMapper;
import com.recsys.proto.impression.v1.*;
import com.recsys.proto.security.*;
import io.grpc.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实 gRPC socket 验证收费边界权限及未知结果，资金原子性由 PG 用例另证。 */
@Tag("money-chain")
class SandboxImpressionGrpcTest {
    static final String SECRET="receipt-test-only-secret";
    ImpressionReceiptService service;
    Server server;
    ManagedChannel channel;
    SandboxImpressionServiceGrpc.SandboxImpressionServiceBlockingStub stub;
    ImpressionReceipt.Command command=new ImpressionReceipt.Command(UUID.randomUUID(),"admin","opaque-proof",1000,5000,true);
    @BeforeEach void start() throws Exception {
        service=mock(ImpressionReceiptService.class);
        server=ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new SandboxImpressionGrpcService(service,"recsys-rec-engine"),
                new InternalAuthGrpcServerInterceptor("x-internal-token",SECRET,false))).build().start();
        channel=ManagedChannelBuilder.forAddress("localhost",server.getPort()).usePlaintext().build();
        stub=SandboxImpressionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
    }
    @AfterEach void stop() throws Exception { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
    SandboxImpressionServiceGrpc.SandboxImpressionServiceBlockingStub as(String caller) {
        return stub.withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token",caller,"SERVICE",60,SECRET));
    }
    void code(Status.Code code,org.junit.jupiter.api.function.Executable call) { assertEquals(code,assertThrows(StatusRuntimeException.class,call).getStatus().getCode()); }
    @Test void anonymousAndWrongCallerNeverReachMoneyEvenUnderNosec() {
        code(Status.Code.UNAUTHENTICATED,()->stub.accept(ImpressionProtoMapper.toProto(command)));
        code(Status.Code.PERMISSION_DENIED,()->as("recsys-advertiser").accept(ImpressionProtoMapper.toProto(command)));
        code(Status.Code.PERMISSION_DENIED,()->as("recsys").getReceipt(GetVisibleReceiptRequest.newBuilder().setReceiptId(command.receiptId().toString()).setSubject("admin").build()));
        verifyNoInteractions(service);
    }
    @Test void acceptedAndDurableRejectedResultsRemainExact() {
        var at=Instant.parse("2026-10-15T01:02:03.123456Z");
        var result=new ImpressionReceipt.Result(command.receiptId(),UUID.randomUUID(),ImpressionReceipt.Status.ACCEPTED,UUID.randomUUID(),command.receiptId(),"0.000001",LocalDate.of(2026,10,14),at,at);
        when(service.accept(command)).thenReturn(result); when(service.find(command.receiptId(),"admin")).thenReturn(Optional.of(result));
        var trusted=as("recsys-rec-engine");
        assertEquals(result,ImpressionProtoMapper.fromProto(trusted.accept(ImpressionProtoMapper.toProto(command))));
        assertEquals(result,ImpressionProtoMapper.fromProto(trusted.getReceipt(GetVisibleReceiptRequest.newBuilder().setReceiptId(command.receiptId().toString()).setSubject("admin").build())));
        var rejected=new ImpressionReceipt.Result(command.receiptId(),UUID.randomUUID(),ImpressionReceipt.Status.POLICY_NOT_MET,null,null,"0.000000",null,null,at);
        when(service.accept(command)).thenReturn(rejected);
        assertEquals(rejected,ImpressionProtoMapper.fromProto(trusted.accept(ImpressionProtoMapper.toProto(command))));
    }
    @Test void invalidProofConflictAndUnknownUseExplicitErrorsWithoutSecrets() {
        var trusted=as("recsys-rec-engine");
        doThrow(new SandboxDelivery.InvalidProof()).when(service).accept(any());
        code(Status.Code.PERMISSION_DENIED,()->trusted.accept(ImpressionProtoMapper.toProto(command)));
        doThrow(new ImpressionReceipt.Conflict()).when(service).accept(any());
        code(Status.Code.ALREADY_EXISTS,()->trusted.accept(ImpressionProtoMapper.toProto(command)));
        doThrow(new IllegalStateException("private-db-secret")).when(service).accept(any());
        var error=assertThrows(StatusRuntimeException.class,()->trusted.accept(ImpressionProtoMapper.toProto(command)));
        assertEquals(Status.Code.UNAVAILABLE,error.getStatus().getCode()); assertFalse(error.getMessage().contains("private-db-secret"));
        code(Status.Code.NOT_FOUND,()->trusted.getReceipt(GetVisibleReceiptRequest.newBuilder().setReceiptId(UUID.randomUUID().toString()).setSubject("admin").build()));
    }
}
