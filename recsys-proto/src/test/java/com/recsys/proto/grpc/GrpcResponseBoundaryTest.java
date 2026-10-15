package com.recsys.proto.grpc;

import com.recsys.common.error.ErrorCode;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 一元响应必须只有一个终态；不能把网络发送失败再处理成第二个业务错误。 */
class GrpcResponseBoundaryTest {
    @Test void failedActionEmitsOnlyOneSafeErrorWithoutSuccessReply() {
        var observer = new RecordingObserver();
        GrpcErrors.respondWithUnknownOnFailure(observer, ErrorCode.RPC_AD_WRITE_RESULT_UNKNOWN,
                () -> { throw new IllegalStateException("private SQL token"); });
        assertEquals(List.of("error"), observer.events);
        var failure = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.UNKNOWN, failure.getStatus().getCode());
        assertEquals(ErrorCode.RPC_AD_WRITE_RESULT_UNKNOWN.displayMessage(), failure.getStatus().getDescription());
    }

    @Test void responseObserverFailureDoesNotSendSecondTerminalSignal() {
        var observer = new RecordingObserver();
        var failure = new IllegalStateException("transport cancelled");
        observer.sendFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                GrpcErrors.respondWithUnknownOnFailure(observer, ErrorCode.RPC_CONTENT_QUERY_FAILED, () -> "reply")));
        assertEquals(List.of("next:reply"), observer.events);
    }

    @Test void fatalErrorsAreNotReportedAsOrdinaryBusinessFailures() {
        var observer = new RecordingObserver();
        assertThrows(AssertionError.class, () -> GrpcErrors.respondWithUnknownOnFailure(observer,
                ErrorCode.RPC_CONTENT_QUERY_FAILED, () -> { throw new AssertionError("fatal"); }));
        assertTrue(observer.events.isEmpty());
    }

    private static class RecordingObserver implements StreamObserver<String> {
        final List<String> events = new ArrayList<>();
        Throwable error;
        RuntimeException sendFailure;
        @Override public void onNext(String value) {
            events.add("next:" + value);
            if (sendFailure != null) throw sendFailure;
        }
        @Override public void onError(Throwable failure) { events.add("error"); error = failure; }
        @Override public void onCompleted() { events.add("complete"); }
    }
}
