package com.recsys.proto.security;

import com.recsys.platform.security.InternalToken;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.time.Instant;

/**
 * gRPC 服务端身份校验(P0)。下游 gRPC 服务(ad-serving/content/user)校验入站内部令牌,把调用方 subject
 * 放入 gRPC {@link Context} 供业务读取(如计费落账记录发起方)。
 *
 * <p>{@code required=true} 时,缺失/非法令牌直接 UNAUTHENTICATED 关闭调用——用于钱链路(ad-serving)的零信任;
 * {@code required=false} 时放行匿名,便于灰度接入与本地联调。
 */
public class InternalAuthGrpcServerInterceptor implements ServerInterceptor {

    /** 供业务从 gRPC 上下文读取调用方身份。 */
    public static final Context.Key<String> CALLER_SUBJECT = Context.key("recsys-internal-subject");

    private final Metadata.Key<String> headerKey;
    private final String secret;
    private final boolean required;

    public InternalAuthGrpcServerInterceptor(String headerName, String secret, boolean required) {
        this.headerKey = Metadata.Key.of(headerName.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER);
        this.secret = secret;
        this.required = required;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String token = headers.get(headerKey);
        InternalToken.Claims claims = InternalToken.verify(token, Instant.now().getEpochSecond(), secret);
        if (claims == null) {
            if (required) {
                call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid internal token"), new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
            return next.startCall(call, headers);
        }
        Context ctx = Context.current().withValue(CALLER_SUBJECT, claims.subject());
        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
