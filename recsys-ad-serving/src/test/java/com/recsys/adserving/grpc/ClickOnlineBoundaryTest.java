package com.recsys.adserving.grpc;

import com.recsys.ad.billing.ClickPreparationConfiguration;
import com.recsys.common.ad.billing.ClickSettlement.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ClickProtoMapper;
import com.recsys.proto.click.v1.*;
import com.recsys.recengine.ad.GrpcAdServingGateway;
import com.recsys.recengine.delivery.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 无需数据库的协议/开关验收；金额事务另由真实PG用例验证。 */
@Tag("money-chain")
class ClickOnlineBoundaryTest {
    @Test void disabledByDefaultAndGrpcEngineNeverInstallsMoneyOwner() {
        new ApplicationContextRunner().withUserConfiguration(ClickPreparationConfiguration.class,ClickDeliveryConfiguration.class,
                SandboxClickDeliveryController.class,SandboxClickReceiptController.class,SandboxClickGrpcService.class).run(c->{
            assertNull(c.getStartupFailure());assertFalse(c.containsBean("clickPreparationRepository"));assertFalse(c.containsBean("clickDeliveryOperationRepository"));
            assertTrue(c.getBeansOfType(SandboxClickReceiptController.class).isEmpty());
        });
        new ApplicationContextRunner().withUserConfiguration(ClickPreparationConfiguration.class).withPropertyValues(
                "spring.application.name=recsys-rec-engine","recsys.ad.serving.mode=grpc","recsys.ad.billing.sandbox-enabled=true",
                "recsys.ad.billing.clicks-enabled=true","recsys.ad.billing.click-settlement-enabled=true","recsys.ad.billing.click-delivery-enabled=true")
                .run(c->{assertNull(c.getStartupFailure());assertFalse(c.containsBean("clickPreparationRepository"));});
    }
    @Test void strictProtoPreservesNullZeroAndNanosecondsAndRejectsMismatchedIdentity() {
        var id=UUID.randomUUID();var now=Instant.parse("2026-10-15T00:00:00.123456789Z");
        var prepared=new Prepared(id,Decision.HELD,LocalDate.of(2026,10,15),"0.000000",now,now.plusSeconds(300),now,"token");
        var pending=new Snapshot(prepared,State.HELD,0,null,null,null,null,null,null);
        assertEquals(pending,ClickProtoMapper.fromProto(ClickProtoMapper.toProto(pending)));assertFalse(ClickProtoMapper.toProto(pending).hasChargedAmount());
        var visible=UUID.randomUUID();var click=UUID.randomUUID();
        var captured=new Snapshot(prepared,State.CAPTURED,2,visible,click,UUID.randomUUID(),"0.000000",now,CloseReason.CLICK_CAPTURED);
        assertEquals(captured,ClickProtoMapper.fromProto(ClickProtoMapper.toProto(captured)));assertTrue(ClickProtoMapper.toProto(captured).hasChargedAmount());
        assertThrows(IllegalArgumentException.class,()->ClickProtoMapper.fromProto(ClickProtoMapper.toProto(prepared).toBuilder().clearIssuedAt().build()));
        assertThrows(IllegalArgumentException.class,()->ClickProtoMapper.fromProto(ClickProtoMapper.toProto(pending).toBuilder().setState("future").build()));
        assertThrows(IllegalArgumentException.class,()->ClickProtoMapper.fromProto(ClickProtoMapper.toProto(prepared).toBuilder().setIssuedAt(com.google.protobuf.Timestamp.newBuilder().setNanos(-1)).build()));
        var receipt=new Receipt(click,id,Kind.CLICK,ReceiptStatus.ACCEPTED_CAPTURED,click,now,now);
        var result=ClickProtoMapper.toProto(new Result(receipt,captured));assertEquals(new Result(receipt,captured),ClickProtoMapper.fromProto(result));
        assertThrows(IllegalArgumentException.class,()->ClickProtoMapper.fromProto(result.toBuilder().setReceipt(result.getReceipt().toBuilder().setQualificationId(UUID.randomUUID().toString())).build()));
    }
    @Test void remoteUnknownNeverFallsBackAndQuery404IsDistinctFromWrite404() {
        var remote=new GrpcAdServingGateway();var stub=mock(SandboxClickServiceGrpc.SandboxClickServiceBlockingStub.class);ReflectionTestUtils.setField(remote,"clickStub",stub);
        var c=new Command(UUID.randomUUID(),"admin","token",Kind.CLICK,0,0,false);
        when(stub.accept(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());assertThrows(StatusRuntimeException.class,()->remote.acceptSandboxClick(c));
        doThrow(Status.ALREADY_EXISTS.asRuntimeException()).when(stub).accept(any());assertThrows(com.recsys.common.ad.billing.ClickSettlement.Conflict.class,()->remote.acceptSandboxClick(c));
        doThrow(Status.PERMISSION_DENIED.asRuntimeException()).when(stub).accept(any());assertThrows(SandboxDelivery.InvalidProof.class,()->remote.acceptSandboxClick(c));
        doThrow(Status.INVALID_ARGUMENT.asRuntimeException()).when(stub).accept(any());assertThrows(IllegalArgumentException.class,()->remote.acceptSandboxClick(c));
        doThrow(Status.NOT_FOUND.asRuntimeException()).when(stub).accept(any());assertThrows(StatusRuntimeException.class,()->remote.acceptSandboxClick(c));
        when(stub.getReceipt(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());assertTrue(remote.findSandboxClickReceipt(c.receiptId(),"admin").isEmpty());
        doThrow(Status.UNIMPLEMENTED.asRuntimeException()).when(stub).getReceipt(any());assertThrows(StatusRuntimeException.class,()->remote.findSandboxClickReceipt(c.receiptId(),"admin"));
    }
}
