package com.recsys.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;

/**
 * 校验下游收到的内部令牌(P0)。网关在校验终端 JWT 后注入 {@code X-Internal-Auth};本过滤器在各下游
 * Servlet 服务解出身份并落 SecurityContext,供 {@code @PreAuthorize} 做方法级授权。
 *
 * <p>令牌缺失/非法不在此拒绝(保持匿名),交由授权规则决定是否放行——公开只读接口无令牌也可访问,
 * 写侧接口因 anyRequest().authenticated() 被拒。
 */
public class InternalTokenAuthFilter extends OncePerRequestFilter {

    private final AuthProperties props;

    public InternalTokenAuthFilter(AuthProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(props.getInternalHeader());
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            InternalToken.Claims claims =
                    InternalToken.verify(token, Instant.now().getEpochSecond(), props.getInternalSecret());
            if (claims != null) {
                var auth = new UsernamePasswordAuthenticationToken(
                        claims.subject(), null, toAuthorities(claims.roles()));
                auth.setDetails(claims.scene());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    private static java.util.List<org.springframework.security.core.GrantedAuthority> toAuthorities(String roles) {
        if (roles == null || roles.isBlank()) {
            return java.util.List.of();
        }
        String[] normalized = Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .toArray(String[]::new);
        return AuthorityUtils.createAuthorityList(normalized);
    }
}
