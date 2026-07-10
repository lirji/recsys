package com.recsys.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Mono;

/**
 * 网关限流键解析(E7)。{@code RequestRateLimiter} 按此键分桶做 Redis 令牌桶限流:
 * 优先按下游用户维度({@code userId} 查询参数,推荐/搜索请求都带)——用户级公平限流;
 * 无 userId 时退按客户端 IP;再退全局桶(保证键非空,{@code deny-empty-key} 不会拦所有请求)。
 *
 * <p>限流本体在 {@code application.yml} 的 {@code default-filters} 声明,受
 * {@code spring.cloud.gateway.filter.request-rate-limiter.enabled} 开关控制(默认关,开启需 Redis)。
 */
@Configuration
@Profile("ratelimit")
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getQueryParams().getFirst("userId");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("u:" + userId);
            }
            var addr = exchange.getRequest().getRemoteAddress();
            if (addr != null && addr.getAddress() != null) {
                return Mono.just("ip:" + addr.getAddress().getHostAddress());
            }
            return Mono.just("global");
        };
    }
}
