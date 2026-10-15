package com.recsys.recengine.ad;

import com.recsys.ad.AdPipeline;
import com.recsys.ad.billing.SandboxPreparationService;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.query.StructuredQuery;
import com.recsys.proto.DeliveryProtoMapper;
import com.recsys.proto.delivery.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 两种 gateway 的新接口具有相同结果语义；grpc 故障不能走旧 empty fallback。 */
@Tag("money-chain")
class SandboxGatewayTest {
    @Test void bothModesReturnSamePreparedResultAndDoNotInvokeLegacyPipeline() {
        var pipeline = mock(AdPipeline.class); var owner = mock(SandboxPreparationService.class);
        ObjectProvider<SandboxPreparationService> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(owner);
        var local = new InProcessAdServingGateway(pipeline); ReflectionTestUtils.setField(local, "sandbox", provider);
        var remote = new GrpcAdServingGateway(); var stub = mock(SandboxDeliveryServiceGrpc.SandboxDeliveryServiceBlockingStub.class);
        ReflectionTestUtils.setField(remote, "deliveryStub", stub);
        var request = new Prepare(UUID.randomUUID(), "admin", 1, UUID.randomUUID(), 1, "opaque-offer");
        var result = new Prepared(request.childId(), com.recsys.common.ad.delivery.SandboxDelivery.Status.OFFER_EXPIRED, "", "", null);
        when(owner.prepare(request)).thenReturn(result); when(stub.prepare(any())).thenReturn(DeliveryProtoMapper.toProto(result));
        assertEquals(local.prepareSandbox(request), remote.prepareSandbox(request)); verifyNoInteractions(pipeline);
    }
    @Test void feedUsesDedicatedOwnerAndRemoteMethodWithoutLegacyFallback() {
        var pipeline=mock(AdPipeline.class); var owner=mock(com.recsys.ad.billing.FeedCandidateService.class);
        ObjectProvider<com.recsys.ad.billing.FeedCandidateService> provider=mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(owner);
        var local=new InProcessAdServingGateway(pipeline); ReflectionTestUtils.setField(local,"feedCandidates",provider);
        var remote=new GrpcAdServingGateway(); var stub=mock(SandboxDeliveryServiceGrpc.SandboxDeliveryServiceBlockingStub.class);
        ReflectionTestUtils.setField(remote,"deliveryStub",stub);
        var query=new Query(UUID.randomUUID(),"admin",Long.MAX_VALUE,new StructuredQuery("q","q",null,null,null,null),3,"feed","b",.1);
        var ad=new com.recsys.common.ad.SponsoredAd(1,Long.MAX_VALUE-1,2,0,"正文",com.recsys.common.ad.AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,0,"CPM");
        var c=new Candidate(UUID.randomUUID(),ad,"offer"); when(owner.candidates(query)).thenReturn(List.of(c));
        when(stub.feedCandidates(DeliveryProtoMapper.toProto(query))).thenReturn(CandidateReply.newBuilder().addCandidates(DeliveryProtoMapper.toProto(c)).build());
        assertEquals(List.of(c),local.sandboxFeedCandidates(query)); assertEquals(local.sandboxFeedCandidates(query),remote.sandboxFeedCandidates(query));
        when(stub.feedCandidates(any())).thenThrow(Status.UNIMPLEMENTED.asRuntimeException());
        assertThrows(StatusRuntimeException.class,()->remote.sandboxFeedCandidates(query));
        when(provider.getIfAvailable()).thenReturn(null); assertThrows(IllegalStateException.class,()->local.sandboxFeedCandidates(query));
        verify(stub,never()).candidates(any()); verifyNoInteractions(pipeline);
    }
    @Test void remoteTimeoutRemainsUnknownAndOnlyNotFoundBecomesEmptyQuery() {
        var remote = new GrpcAdServingGateway(); var stub = mock(SandboxDeliveryServiceGrpc.SandboxDeliveryServiceBlockingStub.class);
        ReflectionTestUtils.setField(remote, "deliveryStub", stub);
        var request = new Prepare(UUID.randomUUID(), "admin", 1, UUID.randomUUID(), 1, "opaque-offer");
        when(stub.prepare(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
        assertThrows(StatusRuntimeException.class, () -> remote.prepareSandbox(request));
        when(stub.getPreparation(any())).thenThrow(Status.NOT_FOUND.asRuntimeException());
        assertTrue(remote.findSandboxPreparation(request.childId(), "admin").isEmpty());
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(stub).getPreparation(any());
        assertThrows(StatusRuntimeException.class, () -> remote.findSandboxPreparation(request.childId(), "admin"));
        when(stub.candidates(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        assertThrows(StatusRuntimeException.class, () -> remote.sandboxCandidates(new Query(UUID.randomUUID(), "admin", 1,
                new StructuredQuery("q", "q", null, null, null, null), 1, "search", "b", .1)));
    }
}
