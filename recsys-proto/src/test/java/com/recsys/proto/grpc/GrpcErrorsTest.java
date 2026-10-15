package com.recsys.proto.grpc;

import com.recsys.common.error.ErrorCode;
import io.grpc.Metadata;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 受控目录与未信任传输错误必须分开，伪造trailer不能提升信任。 */
class GrpcErrorsTest {
    private static final Metadata.Key<String> PRIVATE = Metadata.Key.of("private-detail", Metadata.ASCII_STRING_MARSHALLER);

    @Test void safeFactoryExposesCodeAndFixedMessage() {
        var error = GrpcErrors.exception(Status.Code.UNAVAILABLE, ErrorCode.RPC_BUDGET_RESULT_UNKNOWN);
        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
        assertEquals("RPC_BUDGET_RESULT_UNKNOWN", error.getTrailers().get(GrpcErrors.ERROR_CODE));
        assertEquals("[RPC_BUDGET_RESULT_UNKNOWN] 账务结果未知，请保留原 commandId 查询或重试", error.getStatus().getDescription());
        assertNull(error.getCause());
    }

    @Test void everyUntrustedStatusKeepsProtocolButDiscardsDetailsCauseAndTrailers() {
        for (Status.Code status : Status.Code.values()) {
            Metadata forged = new Metadata();
            forged.put(GrpcErrors.ERROR_CODE, "RPC_BUDGET_COMMAND_NOT_FOUND");
            forged.put(PRIVATE, "private SQL token");
            var unsafe = Status.fromCode(status).withDescription("private SQL token")
                    .withCause(new IllegalArgumentException("private cause")).asRuntimeException(forged);
            var safe = GrpcErrors.sanitize(unsafe, ErrorCode.RPC_BUDGET_RESULT_UNKNOWN);
            assertEquals(status == Status.Code.OK ? Status.Code.INTERNAL : status, safe.getStatus().getCode());
            assertEquals(Set.of("recsys-error-code"), safe.getTrailers().keys());
            assertFalse(safe.getStatus().getDescription().contains("private"));
            assertNotEquals("RPC_BUDGET_COMMAND_NOT_FOUND", safe.getTrailers().get(GrpcErrors.ERROR_CODE));
            assertNull(safe.getCause());
            assertNotNull(ErrorCode.fromCode(safe.getTrailers().get(GrpcErrors.ERROR_CODE)));
        }
    }

    @Test void trustedFactoryIsRebuiltAfterMutableTrailersAreTamperedWith() {
        var original = GrpcErrors.exception(Status.Code.NOT_FOUND, ErrorCode.RPC_RECEIPT_NOT_FOUND);
        original.getTrailers().put(PRIVATE, "private SQL token");
        original.getTrailers().put(GrpcErrors.ERROR_CODE, "forged-code");
        var safe = GrpcErrors.sanitize(original, ErrorCode.RPC_RECEIPT_RESULT_UNKNOWN);
        assertEquals("RPC_RECEIPT_NOT_FOUND", safe.getTrailers().get(GrpcErrors.ERROR_CODE));
        assertEquals(Set.of("recsys-error-code"), safe.getTrailers().keys());
        assertEquals("[RPC_RECEIPT_NOT_FOUND] 当前未观察到回执结果", safe.getStatus().getDescription());
    }

    @Test void successStatusCannotHideFailure() {
        var failure = GrpcErrors.exception(Status.Code.OK, ErrorCode.RPC_DELIVERY_RESULT_UNKNOWN);
        assertEquals(Status.Code.INTERNAL, failure.getStatus().getCode());
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), failure.getTrailers().get(GrpcErrors.ERROR_CODE));
    }
}
