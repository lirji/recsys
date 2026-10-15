package com.recsys.adserving.grpc;

import com.recsys.advertiser.GrpcAdReportReader;
import com.recsys.common.error.ErrorCode;
import com.recsys.proto.ad.v1.AdServingServiceGrpc;
import com.recsys.proto.content.v1.ContentServiceGrpc;
import com.recsys.proto.user.v1.UserProfileServiceGrpc;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.grpc.GrpcRetryablePredicate;
import com.recsys.recengine.ad.GrpcAdServingGateway;
import com.recsys.recengine.content.GrpcContentGateway;
import com.recsys.recengine.user.GrpcUserGateway;
import io.grpc.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 四类普通客户端的全部RPC入口清除恶意详情，同时保持状态和既有读重试判定。 */
class OrdinaryRpcClientErrorTest {
    @ParameterizedTest
    @ValueSource(strings = {"search", "click", "conversion", "outcome", "report", "content", "user-read", "user-write"})
    void remoteStatusIsPreservedWithoutRemoteDescriptionCauseOrMetadata(String operation) {
        var ad = mock(AdServingServiceGrpc.AdServingServiceBlockingStub.class);
        var content = mock(ContentServiceGrpc.ContentServiceBlockingStub.class);
        var user = mock(UserProfileServiceGrpc.UserProfileServiceBlockingStub.class);
        var adGateway = new GrpcAdServingGateway(); ReflectionTestUtils.setField(adGateway, "stub", ad);
        var reportReader = new GrpcAdReportReader(); ReflectionTestUtils.setField(reportReader, "stub", ad);
        var contentGateway = new GrpcContentGateway(); ReflectionTestUtils.setField(contentGateway, "stub", content);
        var userGateway = new GrpcUserGateway(); ReflectionTestUtils.setField(userGateway, "stub", user);
        var privateKey = Metadata.Key.of("private-detail", Metadata.ASCII_STRING_MARSHALLER);
        for (var status : Status.Code.values()) {
            if (status == Status.Code.OK) continue;
            var metadata = new Metadata();
            metadata.put(privateKey, "private SQL token");
            metadata.put(GrpcErrors.ERROR_CODE, ErrorCode.RPC_USER_WRITE_RESULT_UNKNOWN.code());
            var upstream = Status.fromCode(status).withDescription("private upstream token")
                    .withCause(new IllegalStateException("private cause")).asRuntimeException(metadata);
            doThrow(upstream).when(ad).searchAds(any());
            doThrow(upstream).when(ad).recordClick(any());
            doThrow(upstream).when(ad).recordConversion(any());
            doThrow(upstream).when(ad).recordOutcome(any());
            doThrow(upstream).when(ad).getAdEventStats(any());
            doThrow(upstream).when(content).batchGetItems(any());
            doThrow(upstream).when(user).getInterests(any());
            doThrow(upstream).when(user).updateInterests(any());
            var result = assertThrows(StatusRuntimeException.class, () -> {
                switch (operation) {
                    case "search" -> adGateway.searchAds(1, null, 1, "search", "b", .1);
                    case "click" -> adGateway.recordClick("request", 1, 1);
                    case "conversion" -> adGateway.recordConversion("request", 1, 1);
                    case "outcome" -> adGateway.recordOutcome("event", 1, 1, "purchase", 0, null);
                    case "report" -> reportReader.statsByAds(List.of(1L));
                    case "content" -> contentGateway.findByIds(List.of(1L));
                    case "user-read" -> userGateway.getInterests(1);
                    case "user-write" -> userGateway.updateInterests(1, List.of("Drama"));
                    default -> fail("unhandled test operation");
                }
            });
            assertEquals(status, result.getStatus().getCode());
            assertEquals(new GrpcRetryablePredicate().test(upstream), new GrpcRetryablePredicate().test(result));
            assertNull(result.getCause());
            assertNull(result.getTrailers().get(privateKey));
            assertFalse(result.getMessage().contains("private"));
            assertNotNull(ErrorCode.fromCode(result.getTrailers().get(GrpcErrors.ERROR_CODE)));
            if (status == Status.Code.UNKNOWN || status == Status.Code.UNAVAILABLE || status == Status.Code.DEADLINE_EXCEEDED) {
                var expected = switch (operation) {
                    case "search" -> ErrorCode.RPC_AD_QUERY_FAILED;
                    case "report" -> ErrorCode.RPC_AD_REPORT_QUERY_FAILED;
                    case "content" -> ErrorCode.RPC_CONTENT_QUERY_FAILED;
                    case "user-read" -> ErrorCode.RPC_USER_QUERY_FAILED;
                    case "user-write" -> ErrorCode.RPC_USER_WRITE_RESULT_UNKNOWN;
                    default -> ErrorCode.RPC_AD_WRITE_RESULT_UNKNOWN;
                };
                assertEquals(expected.displayMessage(), result.getStatus().getDescription());
            }
        }
    }
}
