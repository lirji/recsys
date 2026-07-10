package com.recsys.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * 方法级授权开关(P0)。与 {@link InternalSecurityConfig} 用同一 {@code recsys.security.enabled} 控制:
 * 开启时激活 {@code @PreAuthorize};nosec 时本配置不装配 → {@code @PreAuthorize} 注解不生效(不产生拦截器),
 * 各上下文写侧接口随 URL 层 permit-all 一并放行,实现真正的"一键关"。
 *
 * <p>DDD 注:这里只提供「粗粒度角色授权」的技术开关(通用子域)。细粒度的 owner 归属不变量
 * (如"广告主只能改自己的广告")属于领域逻辑,应在各限界上下文的领域服务/聚合内实现,不放在此。
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
@ConditionalOnProperty(prefix = "recsys.security", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MethodSecurityConfig {
}
