package com.recsys.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.handler.ExceptionHandlingWebHandler;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实WebFlux响应链证明补空体不吞现有业务体、不重写已提交流。限流使用实际GatewayFilter。 */
class GatewayErrorResponsesTest {
    private final GatewayErrorResponses responses = new GatewayErrorResponses(new ObjectMapper());

    private WebTestClient client(WebHandler handler) {
        var filters = new FilteringWebHandler(handler, List.of(new EmptyGatewayErrorBodyFilter(responses)));
        return WebTestClient.bindToWebHandler(new ExceptionHandlingWebHandler(filters,
                List.of(new GatewayExceptionHandler(responses)))).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualLimiterReturnsJsonFor429AndEmptyKey403WithoutForwarding() {
        RateLimiter<Object> limiter = mock(RateLimiter.class);
        when(limiter.isAllowed("test", "user")).thenReturn(Mono.just(new RateLimiter.Response(false,
                Map.of("X-RateLimit-Remaining", "0"))));
        var factory = new RequestRateLimiterGatewayFilterFactory(limiter,
                exchange -> exchange.getRequest().getPath().value().endsWith("empty-key") ? Mono.empty() : Mono.just("user"));
        var config = new RequestRateLimiterGatewayFilterFactory.Config();
        config.setRouteId("test");
        var limiterFilter = factory.apply(config);
        var forwarded = new AtomicBoolean();
        var client = client(exchange -> limiterFilter.filter(exchange, next -> {
            forwarded.set(true);
            return next.getResponse().setComplete();
        }));
        client.get().uri("/limited").exchange().expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody().jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS")
                .jsonPath("$.path").isEqualTo("/limited");
        client.get().uri("/empty-key").exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client.head().uri("/limited").exchange().expectStatus().isEqualTo(429).expectBody().isEmpty();
        assertThat(forwarded).isFalse();
    }

    @Test
    void downstreamBusinessBodyAndStreamingBodyAreNotOverwritten() {
        String body = "{\"code\":\"DELIVERY_503\",\"message\":\"原ID查询\",\"custom\":true}";
        for (boolean streaming : new boolean[]{false, true}) {
            client(exchange -> {
                var response = exchange.getResponse();
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                var buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
                return (streaming ? response.writeAndFlushWith(Mono.just(Mono.just(buffer)))
                        : response.writeWith(Mono.just(buffer))).then(Mono.defer(response::setComplete));
            }).get().uri("/existing").exchange().expectStatus().isEqualTo(503)
                    .expectBody(String.class).isEqualTo(body);
        }
    }

    @Test
    void successfulEmptyResponsesRemainEmpty() {
        client(exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();
        }).get().uri("/ok").exchange().expectStatus().isNoContent().expectBody().isEmpty();
    }

    @Test
    void committedResponsePropagatesOriginalFailureWithoutAppendingJson() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/stream"));
        byte[] body = "already sent".getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body))).block();
        var failure = new IllegalStateException("private failure");
        assertThatThrownBy(() -> new GatewayExceptionHandler(responses).handle(exchange, failure).block())
                .isSameAs(failure);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("already sent");
    }
}
