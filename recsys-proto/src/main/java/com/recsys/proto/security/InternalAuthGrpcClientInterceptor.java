package com.recsys.proto.security;

import com.recsys.platform.security.InternalToken;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.time.Instant;

/**
 * gRPC 客户端身份传播(P0)。东西向调用(rec-engine→ad-serving/content/user、advertiser→ad-report)出站前
 * 注入一枚短时内部令牌到 metadata,使拆成跨进程后下游仍能拿到可信调用方身份(计费审计 / 反作弊归因所需)。
 *
 * <p>令牌用 {@link InternalToken} 的 HMAC 自签,secret 与下游 {@link InternalAuthGrpcServerInterceptor} 同源。
 */
public class InternalAuthGrpcClientInterceptor implements ClientInterceptor {

    private final Metadata.Key<String> headerKey;
    private final String subject;
    private final String roles;
    private final long ttlSeconds;
    private final String secret;

    public InternalAuthGrpcClientInterceptor(String headerName, String subject, String roles,
                                             long ttlSeconds, String secret) {
        this.headerKey = Metadata.Key.of(headerName.toLowerCase(), Metadata.ASCII_STRING_MARSHALLER);
        this.subject = subject;
        this.roles = roles;
        this.ttlSeconds = ttlSeconds;
        this.secret = secret;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(headerKey, InternalToken.mint(
                        subject, roles, "internal", ttlSeconds, Instant.now().getEpochSecond(), secret));
                super.start(responseListener, headers);
            }
        };
    }
}
