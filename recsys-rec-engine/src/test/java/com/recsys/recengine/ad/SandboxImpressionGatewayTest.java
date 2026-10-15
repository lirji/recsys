package com.recsys.recengine.ad;

import com.recsys.ad.AdPipeline;
import com.recsys.ad.billing.ImpressionReceiptService;
import com.recsys.common.ad.billing.ImpressionReceipt;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.proto.ImpressionProtoMapper;
import com.recsys.proto.impression.v1.SandboxImpressionServiceGrpc;
import io.grpc.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 两模式拒绝/成功相同，远端超时不伪成功且幂等冲突不被变成普通重试。 */
@Tag("money-chain")
class SandboxImpressionGatewayTest {
    @Test void localAndRemoteHaveSameResultAndErrorsRemainVisible() {
        var command=new ImpressionReceipt.Command(UUID.randomUUID(),"admin","proof",1000,5000,true);
        var result=new ImpressionReceipt.Result(command.receiptId(),UUID.randomUUID(),ImpressionReceipt.Status.EXPIRED,null,null,"0.000000",null,null,Instant.now());
        var owner=mock(ImpressionReceiptService.class); ObjectProvider<ImpressionReceiptService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(owner); when(owner.accept(command)).thenReturn(result);
        var pipeline=mock(AdPipeline.class); var local=new InProcessAdServingGateway(pipeline); ReflectionTestUtils.setField(local,"impressions",provider);
        var remote=new GrpcAdServingGateway(); var stub=mock(SandboxImpressionServiceGrpc.SandboxImpressionServiceBlockingStub.class);
        ReflectionTestUtils.setField(remote,"impressionStub",stub); when(stub.accept(any())).thenReturn(ImpressionProtoMapper.toProto(result));
        assertEquals(local.acceptSandboxImpression(command),remote.acceptSandboxImpression(command)); verifyNoInteractions(pipeline);
        doThrow(Status.DEADLINE_EXCEEDED.asRuntimeException()).when(stub).accept(any());
        assertThrows(StatusRuntimeException.class,()->remote.acceptSandboxImpression(command));
        doThrow(Status.ALREADY_EXISTS.asRuntimeException()).when(stub).accept(any());
        assertThrows(ImpressionReceipt.Conflict.class,()->remote.acceptSandboxImpression(command));
        doThrow(Status.PERMISSION_DENIED.asRuntimeException()).when(stub).accept(any());
        assertThrows(SandboxDelivery.InvalidProof.class,()->remote.acceptSandboxImpression(command));
        when(stub.getReceipt(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());
        assertTrue(remote.findSandboxImpression(command.receiptId(),"admin").isEmpty());
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(stub).getReceipt(any());
        assertThrows(StatusRuntimeException.class,()->remote.findSandboxImpression(command.receiptId(),"admin"));
    }
}
