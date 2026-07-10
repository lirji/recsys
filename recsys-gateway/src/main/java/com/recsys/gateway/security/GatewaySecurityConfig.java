package com.recsys.gateway.security;

import com.recsys.platform.security.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 网关边缘认证(P0,响应式)。所有南北向流量在此校验终端 JWT(HS256,{@code recsys.security.edge-secret} 签发),
 * 并做粗粒度 RBAC:公开只读推荐/搜索放行,广告主写侧要 ADVERTISER/ADMIN,实验后台要 ADMIN,其余需登录。
 *
 * <p>安全链<b>始终注册</b>:{@code recsys.security.enabled=false}(nosec)时退化为 permit-all——避免关闭安全后
 * Spring Boot 默认响应式安全把网关锁死。细粒度 owner 归属校验在下游各上下文的方法级 {@code @PreAuthorize}(P0-3)。
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, AuthProperties props,
                                                         ReactiveJwtDecoder jwtDecoder) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable);

        if (!props.isEnabled()) {
            http.authorizeExchange(ex -> ex.anyExchange().permitAll());
            return http.build();
        }

        http.authorizeExchange(ex -> ex
                        // 健康探针 + prometheus 指标抓取 + 登录端点:公开(/actuator/refresh 等仍需认证)
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/api/auth/**").permitAll()
                        // 公开只读:推荐/搜索/混排/query 理解 + 离线报表(仅 GET)
                        .pathMatchers(HttpMethod.GET,
                                "/api/recommend/**", "/api/search/**", "/api/feed/**",
                                "/api/query/**", "/api/console/**").permitAll()
                        // 广告主写侧后台:需广告主或管理员
                        .pathMatchers("/api/advertiser/**").hasAnyRole("ADVERTISER", "ADMIN")
                        // 实验分流管理:仅管理员
                        .pathMatchers("/api/experiment/**").hasRole("ADMIN")
                        // 其余(行为上报、广告点击/转化计费、写操作等)需登录
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j
                        .jwtDecoder(jwtDecoder)
                        .jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(rolesConverter()))));
        return http.build();
    }

    /** 用对称密钥(HS256)校验终端 JWT;自包含、无需外部 IdP/JWK 服务器,适合本地一键演示。 */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(AuthProperties props) {
        SecretKeySpec key = new SecretKeySpec(props.getEdgeSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }

    private static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
