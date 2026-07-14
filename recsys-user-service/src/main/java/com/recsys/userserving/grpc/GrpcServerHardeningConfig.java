package com.recsys.userserving.grpc;

import com.recsys.platform.security.AuthProperties;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GlobalServerInterceptorConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC 服务端身份校验注册(P0 缺口补齐)。把 recsys-proto 里已备好的 {@link InternalAuthGrpcServerInterceptor}
 * 挂到本服务的 gRPC server:东西向调用(rec-engine → user-service)必须携带与上游同源的内部令牌,否则
 * {@code UNAUTHENTICATED} 拒绝——补齐"客户端签 token、服务端从不验"的零信任缺口。镜像 rec-engine 客户端侧
 * {@code GrpcClientHardeningConfig} 的注册范式。
 *
 * <p>{@link EnableConfigurationProperties} 自带 {@link AuthProperties}——gRPC 鉴权与 HTTP servlet security 正交,
 * 在此独立绑定 {@code recsys.security.*}。{@code recsys.security.enabled=false} 时不挂拦截器(放行,便于联调);
 * {@code recsys.grpc.server-auth-required}(默认 true)控制缺 / 错令牌是否直接拒(false=灰度软启放行匿名)。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class GrpcServerHardeningConfig {

    @Bean
    public GlobalServerInterceptorConfigurer recsysGrpcServerAuthInterceptor(
            AuthProperties auth,
            @Value("${recsys.grpc.server-auth-required:true}") boolean required) {
        return interceptors -> {
            if (auth.isEnabled()) {
                interceptors.add(new InternalAuthGrpcServerInterceptor(
                        auth.getInternalHeader(), auth.getInternalSecret(), required));
            }
        };
    }
}
