package com.recsys.recengine.ad;

import com.recsys.common.ad.billing.ClickSettlement;
import com.recsys.common.ad.billing.ImpressionReceipt;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.query.StructuredQuery;
import com.recsys.proto.click.v1.*;
import com.recsys.proto.delivery.v1.*;
import com.recsys.proto.impression.v1.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 输入合法而owner返回损坏结果时必须保持未知，不能成为用户400或空结果，写调用也不能自动重试。 */
@Tag("money-chain")
class SandboxMalformedReplyGatewayTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final GrpcAdServingGateway gateway = new GrpcAdServingGateway();
    private final SandboxDelivery.Prepare prepare = new SandboxDelivery.Prepare(ID, "admin", 1, UUID.randomUUID(), 1, "offer");
    private final SandboxDelivery.Query query = new SandboxDelivery.Query(ID, "admin", 1,
            new StructuredQuery("q", "q", null, null, null, null), 1, "search", "b", .1);

    @Test void deliveryReplyFailureRemainsUnknownAcrossReadAndWrite() {
        var stub = mock(SandboxDeliveryServiceGrpc.SandboxDeliveryServiceBlockingStub.class);
        ReflectionTestUtils.setField(gateway, "deliveryStub", stub);
        when(stub.prepare(any())).thenReturn(PreparedDelivery.getDefaultInstance());
        when(stub.getPreparation(any())).thenReturn(PreparedDelivery.getDefaultInstance());
        when(stub.candidates(any())).thenReturn(CandidateReply.newBuilder().addCandidates(DeliveryCandidate.getDefaultInstance()).build());
        when(stub.feedCandidates(any())).thenReturn(CandidateReply.newBuilder().addCandidates(DeliveryCandidate.getDefaultInstance()).build());
        assertUnknown(ErrorCode.RPC_DELIVERY_REPLY_INVALID, () -> gateway.prepareSandbox(prepare));
        assertUnknown(ErrorCode.RPC_DELIVERY_REPLY_INVALID, () -> gateway.findSandboxPreparation(ID, "admin"));
        assertUnknown(ErrorCode.RPC_DELIVERY_REPLY_INVALID, () -> gateway.sandboxCandidates(query));
        assertUnknown(ErrorCode.RPC_DELIVERY_REPLY_INVALID, () -> gateway.sandboxFeedCandidates(query));
        verify(stub, times(1)).prepare(any());
    }

    @Test void impressionReplyFailureRemainsUnknownAcrossReadAndWrite() {
        var stub = mock(SandboxImpressionServiceGrpc.SandboxImpressionServiceBlockingStub.class);
        ReflectionTestUtils.setField(gateway, "impressionStub", stub);
        when(stub.accept(any())).thenReturn(VisibleReceiptResult.getDefaultInstance());
        when(stub.getReceipt(any())).thenReturn(VisibleReceiptResult.getDefaultInstance());
        assertUnknown(ErrorCode.RPC_RECEIPT_REPLY_INVALID, () -> gateway.acceptSandboxImpression(
                new ImpressionReceipt.Command(ID, "admin", "proof", 1000, 5000, true)));
        assertUnknown(ErrorCode.RPC_RECEIPT_REPLY_INVALID, () -> gateway.findSandboxImpression(ID, "admin"));
        verify(stub, times(1)).accept(any());
    }

    @Test void clickReplyFailureRemainsUnknownAcrossPreparationAndSettlement() {
        var stub = mock(SandboxClickServiceGrpc.SandboxClickServiceBlockingStub.class);
        ReflectionTestUtils.setField(gateway, "clickStub", stub);
        when(stub.prepare(any())).thenReturn(PreparedDelivery.getDefaultInstance());
        when(stub.getPreparation(any())).thenReturn(PreparedDelivery.getDefaultInstance());
        when(stub.candidates(any())).thenReturn(CandidateReply.newBuilder().addCandidates(DeliveryCandidate.getDefaultInstance()).build());
        when(stub.accept(any())).thenReturn(ClickReceiptResult.getDefaultInstance());
        when(stub.getReceipt(any())).thenReturn(ClickReceiptResult.getDefaultInstance());
        when(stub.getSettlement(any())).thenReturn(ClickSettlementSnapshot.getDefaultInstance());
        assertUnknown(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> gateway.prepareSandboxClick(prepare));
        assertUnknown(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> gateway.findSandboxClickPreparation(ID, "admin"));
        assertUnknown(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> gateway.sandboxClickCandidates(query));
        assertUnknown(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> gateway.acceptSandboxClick(
                new ClickSettlement.Command(ID, "admin", "proof", ClickSettlement.Kind.CLICK, 0, 0, false)));
        assertUnknown(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> gateway.findSandboxClickReceipt(ID, "admin"));
        assertUnknown(ErrorCode.RPC_CLICK_REPLY_INVALID, () -> gateway.findSandboxClickSettlement(ID, "admin"));
        verify(stub, times(1)).prepare(any());
        verify(stub, times(1)).accept(any());
    }

    private void assertUnknown(ErrorCode code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code.displayMessage(), assertThrows(IllegalStateException.class, call).getMessage());
    }
}
