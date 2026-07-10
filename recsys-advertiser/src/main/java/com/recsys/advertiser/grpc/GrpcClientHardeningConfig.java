package com.recsys.advertiser.grpc;

import com.recsys.platform.security.AuthProperties;
import com.recsys.proto.grpc.GrpcDeadlineClientInterceptor;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC 客户端弹性 + 身份传播(P1)。advertiser 的出站 gRPC(ad-report 读)统一叠加 deadline + 内部身份令牌,
 * 与 rec-engine 同源。deadline 防挂起(弹性核心),身份令牌在安全启用时注入(nosec 跳过)。
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
