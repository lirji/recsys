package com.recsys.proto.security;

import com.recsys.platform.security.InternalToken;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 服务端身份校验契约(P0):同源令牌放行并注入调用方 subject;缺 / 错令牌在 {@code required=true} 时
 * 以 {@code UNAUTHENTICATED} 关闭调用、不进业务 handler;{@code required=false} 时放行匿名(灰度软启)。
 */
class InternalAuthGrpcServerInterceptorTest {

    private static final String SECRET = "test-internal-secret-xyz";
    private static final String HEADER = "x-internal-auth";
    private static final Metadata.Key<String> KEY =
            Metadata.Key.of(HEADER, Metadata.ASCII_STRING_MARSHALLER);

    private static String mint(String subject, String secret) {
        return InternalToken.mint(subject, "SERVICE", "internal", 300,
                Instant.now().getEpochSecond(), secret);
    }

    @Test
    void validToken_passesThrough_andExposesSubject() {
        var interceptor = new InternalAuthGrpcServerInterceptor(HEADER, SECRET, true);
        Metadata md = new Metadata();
        md.put(KEY, mint("recsys-rec-engine", SECRET));

        AtomicReference<String> subject = new AtomicReference<>();
        ServerCallHandler<String, String> handler = (call, headers) -> {
            subject.set(InternalAuthGrpcServerInterceptor.CALLER_SUBJECT.get());
            return new ServerCall.Listener<>() {
            };
        };
        RecordingServerCall call = new RecordingServerCall();

        interceptor.interceptCall(call, md, handler);

        assertNull(call.closedStatus, "有效令牌不应关闭调用");
        assertEquals("recsys-rec-engine", subject.get(), "调用方 subject 应注入 gRPC Context");
    }

    @Test
    void missingToken_required_closesUnauthenticated() {
        var interceptor = new InternalAuthGrpcServerInterceptor(HEADER, SECRET, true);
        AtomicBoolean handlerEntered = new AtomicBoolean(false);
        RecordingServerCall call = new RecordingServerCall();

        interceptor.interceptCall(call, new Metadata(), (c, h) -> {
            handlerEntered.set(true);
            return new ServerCall.Listener<>() {
            };
        });

        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus.getCode());
        assertFalse(handlerEntered.get(), "无令牌不应进入业务 handler");
    }

    @Test
    void wrongSecret_required_closesUnauthenticated() {
        var interceptor = new InternalAuthGrpcServerInterceptor(HEADER, SECRET, true);
        Metadata md = new Metadata();
        md.put(KEY, mint("attacker", "some-other-secret"));
        RecordingServerCall call = new RecordingServerCall();

        interceptor.interceptCall(call, md, (c, h) -> new ServerCall.Listener<>() {
        });

        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus.getCode(),
                "secret 不同源应拒绝");
    }

    @Test
    void missingToken_notRequired_passesAnonymous() {
        var interceptor = new InternalAuthGrpcServerInterceptor(HEADER, SECRET, false);
        AtomicBoolean handlerEntered = new AtomicBoolean(false);
        RecordingServerCall call = new RecordingServerCall();

        interceptor.interceptCall(call, new Metadata(), (c, h) -> {
            handlerEntered.set(true);
            return new ServerCall.Listener<>() {
            };
        });

        assertNull(call.closedStatus, "required=false 时缺令牌应放行");
        assertTrue(handlerEntered.get(), "灰度软启应进入业务 handler(匿名)");
    }

    /** 记录 close(status) 的最小 ServerCall 假体;其余方法空实现。 */
    private static final class RecordingServerCall extends ServerCall<String, String> {
        private Status closedStatus;

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            this.closedStatus = status;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return null;
        }
    }
}
