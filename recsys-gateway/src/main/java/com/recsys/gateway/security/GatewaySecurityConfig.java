package com.recsys.gateway.security;

import com.recsys.platform.security.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 网关边缘认证(P0,响应式)。所有南北向流量在此校验终端 JWT,并做粗粒度 RBAC:公开只读推荐/搜索放行,
 * 广告主写侧要 ADVERTISER/ADMIN,实验后台要 ADMIN,其余需登录。
 *
 * <p>两种边缘模式(按 {@code recsys.security.casdoor.enabled} 切换,默认关=行为不变):
 * <ul>
 *   <li><b>legacy(默认)</b>:自签 HS256({@code edge-secret},/api/auth/login 演示登录签发),roles claim 直取角色。</li>
 *   <li><b>casdoor</b>:统一登录平台——Casdoor JWKS(RS256)验签 + iss 校验 + aud 家族校验(方案C:
 *       {@code <base>-org-<owner>} 绑定 owner 防跨租户),{@code groups}(如 {@code recsys/advertisers})
 *       经 {@link EdgeCasdoorProperties#getGroupRoles()} 映射为角色。subject={@code sub}(UUID),
 *       经 {@link IdentityPropagationFilter} 下传后,下游判权主体自动对齐 Casdoor sub。</li>
 * </ul>
 *
 * <p>安全链<b>始终注册</b>:{@code recsys.security.enabled=false}(nosec)时退化为 permit-all——避免关闭安全后
 * Spring Boot 默认响应式安全把网关锁死。细粒度 owner 归属校验在下游各上下文(advertiser 已接 auth-platform
 * ReBAC,见 recsys docs/09)。
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({AuthProperties.class, EdgeCasdoorProperties.class})
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, AuthProperties props,
                                                         EdgeCasdoorProperties casdoor, ReactiveJwtDecoder jwtDecoder) {
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
                        .jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(
                                casdoor.isEnabled() ? casdoorConverter(casdoor) : legacyRolesConverter()))));
        return http.build();
    }

    /**
     * 终端 JWT 解码器:casdoor 模式=JWKS(RS256)+iss+aud 家族校验;legacy=对称密钥 HS256
     * (自包含、无需外部 IdP,适合本地一键演示)。
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(AuthProperties props, EdgeCasdoorProperties casdoor) {
        if (casdoor.isEnabled()) {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(casdoor.getJwkSetUri()).build();
            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
            validators.add(new JwtTimestampValidator());
            validators.add(new JwtIssuerValidator(casdoor.getIssuer()));
            if (casdoor.getOrganization() != null && !casdoor.getOrganization().isBlank()) {
                String org = casdoor.getOrganization();
                // 租户钉死:本网关只服务一个 org,别的租户(哪怕同 shared app 家族)一律 401——
                // 否则跨租户 token 仅靠"组名不撞"才拿不到角色,不是边界。
                validators.add(jwt -> org.equals(jwt.getClaimAsString("owner"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                "owner(org) 须为 " + org + ", 实际: " + jwt.getClaimAsString("owner"), null)));
            }
            if (!casdoor.getAudiences().isEmpty()) {
                List<String> allowed = casdoor.getAudiences();
                validators.add(jwt -> audienceOk(jwt.getAudience(), jwt.getClaimAsString("owner"), allowed)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                "aud 不在白名单(精确或 <base>-org-<owner> 家族): " + jwt.getAudience(), null)));
            }
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        }
        SecretKeySpec key = new SecretKeySpec(props.getEdgeSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }

    /**
     * aud 校验(casdoor 模式):精确命中白名单,或方案C 派生家族——aud 形如 {@code <base>-org-<owner>}
     * 且 base 在白名单、后缀与 token 的 owner(org)一致(绑定防"A 租户的派生 client 混用 B 租户 token")。
     */
    static boolean audienceOk(List<String> tokenAud, String owner, List<String> allowed) {
        if (tokenAud == null) {
            return false;
        }
        for (String aud : tokenAud) {
            for (String base : allowed) {
                if (aud.equals(base)) {
                    return true;
                }
                if (owner != null && !owner.isBlank() && aud.equals(base + "-org-" + owner)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** casdoor 模式:groups claim(全路径 {@code <org>/<group>})取短名 → group-roles 映射 → ROLE_*;未映射的组忽略。 */
    private static JwtAuthenticationConverter casdoorConverter(EdgeCasdoorProperties casdoor) {
        Map<String, String> groupRoles = casdoor.getGroupRoles();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object groups = jwt.getClaim("groups");
            return rolesFromGroups(groups instanceof Collection<?> col ? col : List.of(), groupRoles);
        });
        return converter;
    }

    static Collection<GrantedAuthority> rolesFromGroups(Collection<?> groups, Map<String, String> groupRoles) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (Object g : groups) {
            String s = String.valueOf(g);
            int i = s.lastIndexOf('/');
            String shortName = i >= 0 ? s.substring(i + 1) : s;
            String role = groupRoles.get(shortName);
            if (role != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    }

    /** legacy 模式:roles claim 直取(演示登录 /api/auth/login 签发)。 */
    private static JwtAuthenticationConverter legacyRolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
