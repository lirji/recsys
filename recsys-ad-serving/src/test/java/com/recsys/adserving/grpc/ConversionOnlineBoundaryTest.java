package com.recsys.adserving.grpc;

import com.recsys.ad.billing.ConversionPreparationConfiguration;
import com.recsys.advertiser.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ConversionProtoMapper;
import com.recsys.proto.conversion.v1.*;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import com.recsys.recengine.ad.GrpcAdServingGateway;
import com.recsys.recengine.delivery.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 无数据库的协议、调用者和开关验证；不会用Mock证明资金原子性。 */
@Tag("money-chain")
class ConversionOnlineBoundaryTest {
    @Test void disabledByDefaultAndGrpcEngineDoesNotInstallOwner() {
        new ApplicationContextRunner().withUserConfiguration(ConversionPreparationConfiguration.class,ConversionDeliveryConfiguration.class,
                SandboxConversionDeliveryController.class,SandboxConversionImpressionController.class,SandboxConversionGrpcService.class,
                SandboxConversionController.class,GrpcSandboxConversionClient.class).run(c->{
            assertNull(c.getStartupFailure());assertFalse(c.containsBean("conversionPreparationRepository"));assertFalse(c.containsBean("conversionDeliveryOperationRepository"));
            assertTrue(c.getBeansOfType(SandboxConversionController.class).isEmpty());assertTrue(c.getBeansOfType(GrpcSandboxConversionClient.class).isEmpty());
        });
        new ApplicationContextRunner().withUserConfiguration(ConversionPreparationConfiguration.class).withPropertyValues(
                "spring.application.name=recsys-rec-engine","recsys.ad.serving.mode=grpc","recsys.ad.billing.sandbox-enabled=true",
                "recsys.ad.billing.conversions-enabled=true","recsys.ad.billing.conversion-settlement-enabled=true","recsys.ad.billing.conversion-delivery-enabled=true")
                .run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("conversionPreparationRepository"));});
    }
    @Test void strictProtoPreservesNullZeroAndMicrosecondsAndRejectsWrongIdentityAndUnknownState() {
        var id=UUID.randomUUID();var now=Instant.parse("2026-10-15T00:00:00.123456Z");
        var prepared=new Prepared(id,Decision.HELD,LocalDate.of(2026,10,15),"0.000000",now,now.plusSeconds(300),now,"token");
        var pending=new Snapshot(prepared,State.HELD,0,null,null,null,null,null,null);
        assertEquals(pending,ConversionProtoMapper.fromProto(ConversionProtoMapper.toProto(pending)));assertFalse(ConversionProtoMapper.toProto(pending).hasChargedAmount());
        // visible/outcome各属独立命名空间；相同UUID仍可表达有效捕获。
        var receiptId=UUID.randomUUID();var captured=new Snapshot(prepared,State.CAPTURED,2,receiptId,receiptId,UUID.randomUUID(),"0.000000",now,CloseReason.CONVERSION_CAPTURED);
        assertEquals(captured,ConversionProtoMapper.fromProto(ConversionProtoMapper.toProto(captured)));assertTrue(ConversionProtoMapper.toProto(captured).hasChargedAmount());
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(ConversionProtoMapper.toProto(prepared).toBuilder().clearIssuedAt().build()));
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(ConversionProtoMapper.toProto(pending).toBuilder().setState("future").build()));
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(ConversionProtoMapper.toProto(prepared).toBuilder().setIssuedAt(com.google.protobuf.Timestamp.newBuilder().setNanos(-1)).build()));
        var receipt=new VisibleReceipt(receiptId,id,VisibleStatus.ACCEPTED_CAPTURED,receiptId,now,now);
        var visible=ConversionProtoMapper.toProto(new VisibleResult(receipt,captured));assertEquals(new VisibleResult(receipt,captured),ConversionProtoMapper.fromProto(visible));
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(visible.toBuilder().setReceipt(visible.getReceipt().toBuilder().setQualificationId(UUID.randomUUID().toString())).build()));
        var outcome=new Outcome(receiptId,Long.MAX_VALUE,UUID.randomUUID(),id,now,now,OutcomeStatus.ACCEPTED_CAPTURED,receiptId,now);
        var result=ConversionProtoMapper.toProto(new OutcomeResult(outcome,captured));assertEquals(new OutcomeResult(outcome,captured),ConversionProtoMapper.fromProto(result));
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(result.toBuilder().setOutcome(result.getOutcome().toBuilder().clearOccurredAt()).build()));
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(result.toBuilder().setOutcome(result.getOutcome().toBuilder().setQualificationId(UUID.randomUUID().toString())).build()));
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(result.toBuilder().setOutcome(result.getOutcome().toBuilder()
                .setOccurredAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(123456789))).build()));
        var stub=mock(SandboxConversionServiceGrpc.SandboxConversionServiceBlockingStub.class);
        var client=new GrpcSandboxConversionClient();ReflectionTestUtils.setField(client,"stub",stub);
        when(stub.getOutcome(any())).thenReturn(result);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->client.find(1,outcome.eventId(),"actor")).getStatusCode().value());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->client.find(Long.MAX_VALUE,UUID.randomUUID(),"actor")).getStatusCode().value());
        var gateway=new GrpcAdServingGateway();ReflectionTestUtils.setField(gateway,"conversionStub",stub);
        when(stub.getVisible(any())).thenReturn(visible);when(stub.getSettlement(any())).thenReturn(ConversionProtoMapper.toProto(captured));
        assertThrows(IllegalStateException.class,()->gateway.findSandboxConversionVisible(UUID.randomUUID(),"admin"));
        assertThrows(IllegalStateException.class,()->gateway.findSandboxConversionSettlement(UUID.randomUUID(),"admin"));
        when(stub.acceptVisible(any())).thenReturn(ConversionVisibleResult.getDefaultInstance());
        assertThrows(IllegalStateException.class,()->gateway.acceptSandboxConversionVisible(new VisibleCommand(receiptId,"admin","token",1000,5000,true)));
        var command=new ConversionCommand(Long.MAX_VALUE,UUID.randomUUID(),"actor","token",now);
        assertEquals(command,ConversionProtoMapper.fromProto(ConversionProtoMapper.toProto(command)));
        assertThrows(IllegalArgumentException.class,()->ConversionProtoMapper.fromProto(ConversionProtoMapper.toProto(command).toBuilder().setOccurredAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(123456789)).build()));
    }
    @Test void writeUnknownAnd404CannotBecomeSuccessAndMalformedOwnerReplyStaysUnknown() {
        var stub=mock(SandboxConversionServiceGrpc.SandboxConversionServiceBlockingStub.class);
        var client=new GrpcSandboxConversionClient();ReflectionTestUtils.setField(client,"stub",stub);
        var c=new ConversionCommand(1,UUID.randomUUID(),"actor","token",Instant.parse("2026-10-15T00:00:00Z"));
        var codes=Map.of(Status.DEADLINE_EXCEEDED,503,Status.UNAVAILABLE,503,Status.UNIMPLEMENTED,503,Status.ALREADY_EXISTS,409,
                Status.PERMISSION_DENIED,403,Status.INVALID_ARGUMENT,400,Status.NOT_FOUND,404);
        codes.forEach((grpc,http)->{
            doThrow(grpc.withDescription("secret-owner-detail").asRuntimeException()).when(stub).acceptConversion(any());
            var ex=assertThrows(ResponseStatusException.class,()->client.accept(c));assertEquals(http,ex.getStatusCode().value());assertFalse(ex.getMessage().contains("secret-owner-detail"));
        });
        doReturn(ConversionOutcomeResult.getDefaultInstance()).when(stub).acceptConversion(any());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->client.accept(c)).getStatusCode().value());
        when(stub.getOutcome(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->client.find(1,c.eventId(),"actor")).getStatusCode().value());
        var remote=new GrpcAdServingGateway();ReflectionTestUtils.setField(remote,"conversionStub",stub);
        when(stub.acceptVisible(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
        assertThrows(StatusRuntimeException.class,()->remote.acceptSandboxConversionVisible(new VisibleCommand(UUID.randomUUID(),"admin","token",1000,5000,true)));
        when(stub.getVisible(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());assertTrue(remote.findSandboxConversionVisible(UUID.randomUUID(),"admin").isEmpty());
        doThrow(Status.UNIMPLEMENTED.asRuntimeException()).when(stub).getVisible(any());assertThrows(StatusRuntimeException.class,()->remote.findSandboxConversionVisible(UUID.randomUUID(),"admin"));
    }
    @Test void serviceWithoutAuthInterceptorStillRequiresCallerAndForbidsSharedIdentity() {
        var preparations=mock(com.recsys.ad.billing.ConversionPreparationService.class);var money=mock(com.recsys.ad.billing.ConversionSettlementService.class);
        assertThrows(IllegalArgumentException.class,()->new SandboxConversionGrpcService(preparations,money,"same","same"));
        assertThrows(IllegalArgumentException.class,()->new SandboxConversionGrpcService(preparations,money,"recsys","advertiser"));
        var service=new SandboxConversionGrpcService(preparations,money,"engine","advertiser");
        var observer=new CapturingObserver<ConversionOutcomeResult>();service.acceptConversion(ConversionEventCommand.getDefaultInstance(),observer);
        assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(observer.failure).getCode());
        var wrong=new CapturingObserver<ConversionOutcomeResult>();
        Context.current().withValue(InternalAuthGrpcServerInterceptor.CALLER_SUBJECT,"engine").run(()->service.acceptConversion(ConversionEventCommand.getDefaultInstance(),wrong));
        assertEquals(Status.Code.PERMISSION_DENIED,Status.fromThrowable(wrong.failure).getCode());verifyNoInteractions(preparations,money);
    }
    static class CapturingObserver<T> implements StreamObserver<T> {
        Throwable failure;
        public void onNext(T value) { fail("unexpected response"); }
        public void onError(Throwable value) {failure=value;}
        public void onCompleted() {}
    }
}
