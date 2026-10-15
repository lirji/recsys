package com.recsys.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 限流器直接setComplete不抛异常，需在安全/路由链外补齐空错误体。 */
@Component
public class EmptyGatewayErrorBodyFilter implements WebFilter, Ordered {
    private final GatewayErrorResponses responses;

    public EmptyGatewayErrorBodyFilter(GatewayErrorResponses responses) { this.responses = responses; }

    /** 包装响应，不缓存请求或下游body，不改变限流或鉴权决策。 */
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(responses.completeEmptyErrors(exchange));
    }

    /** 在安全链和路由链之前装饰，确保直接拒绝也具有相同契约。 */
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
