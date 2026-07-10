package com.recsys.recengine.grpc;

import com.recsys.platform.security.AuthProperties;
import com.recsys.proto.grpc.GrpcDeadlineClientInterceptor;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC 客户端弹性 + 身份传播(P1)。给 rec-engine 的所有出站 gRPC 通道(ad-serving/content/user)统一叠加:
 * <ol>
 *   <li>{@link GrpcDeadlineClientInterceptor}:每调用 deadline,防跨进程无限挂起(弹性核心)。</li>
 *   <li>{@link InternalAuthGrpcClientInterceptor}:注入内部令牌传播可信服务身份(安全启用时;nosec 跳过)。</li>
 * </ol>
 * deadline 不受 nosec 影响(与安全正交)。
 */
@Configuration
public class GrpcClientHardeningConfig {

    @Bean
    public GlobalClientInterceptorConfigurer recsysGrpcClientInterceptors(
            AuthProperties auth,
            @Value("${recsys.grpc.deadline-ms:800}") long deadlineMs) {
        return interceptors -> {
            interceptors.add(new GrpcDeadlineClientInterceptor(deadlineMs));
            if (auth.isEnabled()) {
                interceptors.add(new InternalAuthGrpcClientInterceptor(
                        auth.getInternalHeader(),
                        auth.getServiceName(),
                        "SERVICE",
                        auth.getTokenTtlSeconds(),
                        auth.getInternalSecret()));
            }
        };
    }
}
