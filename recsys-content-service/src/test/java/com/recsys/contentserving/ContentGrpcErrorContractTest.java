package com.recsys.contentserving;

import com.recsys.content.ContentService;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.contentserving.grpc.ContentGrpcService;
import com.recsys.proto.content.v1.*;
import io.grpc.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实TCP刻画普通服务的错误状态，保护后续安全文案迁移不意外启用额外重试。 */
class ContentGrpcErrorContractTest {
    @Test void ordinaryFailuresKeepTheirExistingWireStatusAndSuccessCanRecover() throws Exception {
        var owner = mock(ContentService.class);
        var server = ServerBuilder.forPort(0).addService(new ContentGrpcService(owner)).build().start();
        var channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
            var request = BatchGetItemsRequest.newBuilder().addItemIds(1).build();
            for (RuntimeException failure : new RuntimeException[]{new IllegalArgumentException("private SQL token"),
                    new IllegalStateException("private SQL token"), Status.UNAVAILABLE.withDescription("private upstream token").asRuntimeException()}) {
                doThrow(failure).when(owner).findByIds(List.of(1L));
                var observed = assertThrows(StatusRuntimeException.class, () -> ContentServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS).batchGetItems(request));
                assertEquals(Status.Code.UNKNOWN, observed.getStatus().getCode());
                assertEquals(ErrorCode.RPC_CONTENT_QUERY_FAILED.code(), observed.getTrailers().get(GrpcErrors.ERROR_CODE));
                assertEquals(ErrorCode.RPC_CONTENT_QUERY_FAILED.displayMessage(), observed.getStatus().getDescription());
                assertFalse(observed.getMessage().contains("private"));
            }
            doReturn(Map.of()).when(owner).findByIds(List.of(1L));
            assertEquals(0, ContentServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .batchGetItems(request).getItemsCount());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
