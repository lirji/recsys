package com.recsys.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * 各 Servlet 服务复用的安全链(P0)。用 {@code @ConditionalOnClass} 守卫:只有具备 webmvc + security 的
 * web 服务才装配;gRPC-only/纯库在扫描期按元数据跳过。
 *
 * <p>安全链<b>始终注册</b>:{@code recsys.security.enabled=true} 时无状态认证 + permit-paths 放行 + 内部令牌解析;
 * {@code false}(nosec)时退化为 permit-all——这样关闭安全不会触发 Spring Boot 默认安全把服务锁死。
 * 方法级授权由独立的 {@link MethodSecurityConfig} 按同一开关控制。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnClass(name = {
        "org.springframework.web.servlet.DispatcherServlet",
        "org.springframework.security.web.SecurityFilterChain"})
public class InternalSecurityConfig {

    @Bean
    public SecurityFilterChain recsysSecurityFilterChain(HttpSecurity http, AuthProperties props) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!props.isEnabled()) {
            // nosec:显式 permit-all,覆盖 Spring Boot 默认安全,避免关闭安全反被锁死。
            http.authorizeHttpRequests(reg -> reg.anyRequest().permitAll());
            return http.build();
        }

        String[] permit = props.getPermitPaths().toArray(new String[0]);
        http.authorizeHttpRequests(reg -> reg
                        .requestMatchers(permit).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new InternalTokenAuthFilter(props), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeError(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                                        "需要有效身份", req.getRequestURI()))
                        .accessDeniedHandler((req, res, e) ->
                                writeError(res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                                        "无权访问该资源", req.getRequestURI())));
        return http.build();
    }

    /** 手写紧凑 JSON(与 {@link com.recsys.platform.web.ApiError} 同构),避免为过滤器链引入 Jackson 编译依赖。 */
    private static void writeError(HttpServletResponse res, int status, String code, String message, String path)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        String json = "{\"traceId\":" + jsonStr(mdcTraceId())
                + ",\"code\":" + jsonStr(code)
                + ",\"message\":" + jsonStr(message)
                + ",\"path\":" + jsonStr(path)
                + ",\"fields\":null"
                + ",\"timestamp\":" + System.currentTimeMillis() + "}";
        res.getWriter().write(json);
    }

    private static String mdcTraceId() {
        try {
            return org.slf4j.MDC.get("traceId");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String jsonStr(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
