package com.recsys.proto.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** gRPC 重试判定:仅瞬时错误(UNAVAILABLE/DEADLINE_EXCEEDED)重试,业务/永久错误与非 gRPC 异常不重试。 */
class GrpcRetryablePredicateTest {

    private final GrpcRetryablePredicate predicate = new GrpcRetryablePredicate();

    @Test
    void retriesTransientCodes() {
        assertTrue(predicate.test(new StatusRuntimeException(Status.UNAVAILABLE)));
        assertTrue(predicate.test(new StatusRuntimeException(Status.DEADLINE_EXCEEDED)));
    }

    @Test
    void doesNotRetryBusinessOrPermanentCodes() {
        assertFalse(predicate.test(new StatusRuntimeException(Status.UNAUTHENTICATED)),
                "鉴权失败不重试(重试无意义且放大故障)");
        assertFalse(predicate.test(new StatusRuntimeException(Status.INVALID_ARGUMENT)));
        assertFalse(predicate.test(new StatusRuntimeException(Status.NOT_FOUND)));
        assertFalse(predicate.test(new StatusRuntimeException(Status.PERMISSION_DENIED)));
    }

    @Test
    void doesNotRetryNonGrpcOrNull() {
        assertFalse(predicate.test(new RuntimeException("boom")), "非 gRPC 异常(含熔断快速失败)不重试");
        assertFalse(predicate.test(null));
    }
}
