package com.recsys.gateway.security;

import com.recsys.platform.security.AuthProperties;
import com.recsys.platform.security.InternalToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * 边缘→内部身份传播(P0)。网关校验终端 JWT 后,把认证身份重签为一枚短时内部令牌({@link InternalToken})
 * 注入下游 {@code X-Internal-Auth};同时剥离入站的 {@code Authorization} 与任何客户端伪造的内部 header,
 * 实现「终端令牌不落下游、下游只信网关签发的内部令牌」的零信任传播。
 */
@Component
@ConditionalOnProperty(prefix = "recsys.security", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    private final AuthProperties props;

    public IdentityPropagationFilter(AuthProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated()
                        && !(auth instanceof AnonymousAuthenticationToken))
                .map(auth -> {
                    String roles = auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .map(r -> r.startsWith("ROLE_") ? r.substring("ROLE_".length()) : r)
                            .collect(Collectors.joining(","));
                    String token = InternalToken.mint(auth.getName(), roles, "edge",
                            props.getTokenTtlSeconds(), Instant.now().getEpochSecond(), props.getInternalSecret());
                    return mutate(exchange, token);
                })
                .defaultIfEmpty(mutate(exchange, null))
                .flatMap(chain::filter);
    }

    /** token 非空 → 注入内部令牌;为空(匿名/公开路径)→ 仅剥离伪造 header。两种情况都剥离入站 Authorization。 */
    private ServerWebExchange mutate(ServerWebExchange exchange, String internalToken) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(HttpHeaders.AUTHORIZATION);
                    h.remove(props.getInternalHeader());
                    if (internalToken != null) {
                        h.set(props.getInternalHeader(), internalToken);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    @Override
    public int getOrder() {
        // 在 Security 的 WebFilter 之后(此时 SecurityContext 已就绪)、在路由转发之前注入内部令牌。
        return 0;
    }
}
