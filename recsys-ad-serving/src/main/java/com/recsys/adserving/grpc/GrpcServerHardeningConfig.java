package com.recsys.adserving.grpc;

import com.recsys.platform.security.AuthProperties;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GlobalServerInterceptorConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC 服务端身份校验注册(P0 缺口补齐)。把 recsys-proto 里已备好的 {@link InternalAuthGrpcServerInterceptor}
 * 挂到本服务的 gRPC server:东西向调用(rec-engine → ad-serving,含计费 money path)必须携带与上游同源的内部
 * 令牌,否则 {@code UNAUTHENTICATED} 拒绝——补齐"客户端签 token、服务端从不验"的零信任缺口。镜像 rec-engine
 * 客户端侧 {@code GrpcClientHardeningConfig} 用 {@code GlobalXxxInterceptorConfigurer} 的注册范式。
 *
 * <p>{@link EnableConfigurationProperties} 自带 {@link AuthProperties}——本服务无 servlet security 链
 * ({@code InternalSecurityConfig} 因 {@code @ConditionalOnClass(SecurityFilterChain)} 不装配),gRPC 鉴权与
 * HTTP 安全正交,故在此独立绑定 {@code recsys.security.*}。
 *
 * <ul>
 *   <li>{@code recsys.security.enabled=false}(nosec):不挂拦截器,东西向放行,便于本地讲解 / 联调。</li>
 *   <li>{@code recsys.grpc.server-auth-required=true}(默认):缺 / 错令牌直接拒(money path 严格);
 *       灰度软启可设 {@code false}(env {@code GRPC_SERVER_AUTH_REQUIRED=false})先放行匿名、观察日志再收紧。</li>
 * </ul>
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
