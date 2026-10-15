package com.recsys.adserving.grpc;

import com.recsys.ad.AdPipeline;
import com.recsys.adserving.report.AdReportStatsRepository;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.proto.grpc.GrpcErrors;
import com.recsys.proto.ad.v1.*;
import io.grpc.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 普通广告RPC的失败保持原UNKNOWN，不能借错误文案改造新增重试或成功ACK。 */
class AdServingGrpcErrorContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"search", "click", "conversion", "outcome", "report"})
    void failureNeverAcknowledgesSuccessAndKeepsExistingStatus(String operation) throws Exception {
        var pipeline = mock(AdPipeline.class);
        var reports = mock(AdReportStatsRepository.class);
        var server = ServerBuilder.forPort(0).addService(new AdServingGrpcService(pipeline, reports)).build().start();
        var channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
            for (RuntimeException failure : new RuntimeException[]{new IllegalArgumentException("private SQL token"),
                    new IllegalStateException("private SQL token"), Status.UNAVAILABLE.withDescription("private upstream token").asRuntimeException()}) {
                doThrow(failure).when(pipeline).run(anyLong(), any(), anyInt(), anyString(), anyString(), anyDouble());
                doThrow(failure).when(pipeline).recordClick(anyString(), anyLong(), anyLong());
                doThrow(failure).when(pipeline).recordConversion(anyString(), anyLong(), anyLong());
                doThrow(failure).when(pipeline).recordOutcome(anyString(), anyLong(), anyLong(), anyString(), anyDouble(), any());
                doThrow(failure).when(reports).statsByAds(any());
                var stub = AdServingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                var observed = assertThrows(StatusRuntimeException.class, () -> {
                    switch (operation) {
                        case "search" -> stub.searchAds(SearchAdsRequest.getDefaultInstance());
                        case "click" -> stub.recordClick(ClickRequest.getDefaultInstance());
                        case "conversion" -> stub.recordConversion(ConversionRequest.getDefaultInstance());
                        case "outcome" -> stub.recordOutcome(OutcomeRequest.getDefaultInstance());
                        case "report" -> stub.getAdEventStats(AdEventStatsRequest.getDefaultInstance());
                        default -> fail("unhandled test operation");
                    }
                });
                assertEquals(Status.Code.UNKNOWN, observed.getStatus().getCode());
                var code = switch (operation) {
                    case "search" -> ErrorCode.RPC_AD_QUERY_FAILED;
                    case "report" -> ErrorCode.RPC_AD_REPORT_QUERY_FAILED;
                    default -> ErrorCode.RPC_AD_WRITE_RESULT_UNKNOWN;
                };
                assertEquals(code.code(), observed.getTrailers().get(GrpcErrors.ERROR_CODE));
                assertEquals(code.displayMessage(), observed.getStatus().getDescription());
                assertFalse(observed.getMessage().contains("private"));
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test void successAndDuplicateOutcomeRemainNormalReplies() throws Exception {
        var pipeline = mock(AdPipeline.class);
        var reports = mock(AdReportStatsRepository.class);
        when(pipeline.run(anyLong(), any(), anyInt(), anyString(), anyString(), anyDouble()))
                .thenReturn(new SearchAdsResponse(1, "q", "request", List.of(), "trace"));
        when(pipeline.recordOutcome(anyString(), anyLong(), anyLong(), anyString(), anyDouble(), any())).thenReturn(true, false);
        when(reports.statsByAds(any())).thenReturn(List.of(new AdReportStatsRepository.Stat(Long.MAX_VALUE, 3, 2, 1, 0.5)));
        var server = ServerBuilder.forPort(0).addService(new AdServingGrpcService(pipeline, reports)).build().start();
        var channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
            var stub = AdServingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
            assertEquals("request", stub.searchAds(SearchAdsRequest.getDefaultInstance()).getRequestId());
            assertTrue(stub.recordClick(ClickRequest.getDefaultInstance()).getOk());
            assertTrue(stub.recordConversion(ConversionRequest.getDefaultInstance()).getOk());
            assertTrue(stub.recordOutcome(OutcomeRequest.getDefaultInstance()).getOk());
            assertFalse(stub.recordOutcome(OutcomeRequest.getDefaultInstance()).getOk());
            var stat = stub.getAdEventStats(AdEventStatsRequest.getDefaultInstance()).getStats(0);
            assertEquals(Long.MAX_VALUE, stat.getAdId());
            assertEquals(3, stat.getImpressions());
            assertEquals(0.5, stat.getSpend());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
