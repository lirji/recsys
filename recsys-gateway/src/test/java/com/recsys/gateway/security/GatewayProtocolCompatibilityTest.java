package com.recsys.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.gateway.web.GatewayErrorResponses;
import com.recsys.gateway.web.EmptyGatewayErrorBodyFilter;
import com.recsys.platform.security.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 先在旧实现运行，保护错误体迁移不能改变的Bearer状态、挑战头和限流拒绝。 */
class GatewayProtocolCompatibilityTest {
    private WebTestClient secureClient() {
        var props = new AuthProperties();
        props.setEnabled(true);
        var decoder = mock(ReactiveJwtDecoder.class);
        when(decoder.decode("bad-request")).thenReturn(Mono.error(
                new OAuth2AuthenticationException(BearerTokenErrors.invalidRequest("private detail"))));
        when(decoder.decode("bad-token")).thenReturn(Mono.error(
                new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("private detail"))));
        when(decoder.decode("user")).thenReturn(Mono.just(Jwt.withTokenValue("user")
                .header("alg", "HS256").subject("user").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claim("roles", List.of("USER")).build()));
        var chain = new GatewaySecurityConfig().securityWebFilterChain(ServerHttpSecurity.http(), props,
                new EdgeCasdoorProperties(), decoder, new GatewaySecurityErrorHandler(new GatewayErrorResponses(new ObjectMapper())));
        return WebTestClient.bindToWebHandler(exchange -> exchange.getResponse().setComplete())
                .webFilter(new WebFilterChainProxy(chain)).build();
    }

    @Test
    void anonymousChallengeRemainsBearer401() {
        secureClient().get().uri("/api/sandbox/action").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void invalidRequestAndInvalidTokenRetainDifferentStatusAndMachineCode() {
        secureClient().get().uri("/api/sandbox/action").headers(h -> h.setBearerAuth("bad-request"))
                .exchange().expectStatus().isBadRequest()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        value -> assertThat(value).contains("invalid_request").doesNotContain("private", "error_description"))
                .expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST");
        secureClient().get().uri("/api/sandbox/action").headers(h -> h.setBearerAuth("bad-token"))
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        value -> assertThat(value).contains("invalid_token").doesNotContain("private", "error_description"))
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void insufficientRoleRetainsForbiddenAndScopeChallenge() {
        secureClient().get().uri("/api/sandbox/action").headers(h -> h.setBearerAuth("user"))
                .exchange().expectStatus().isForbidden()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
                        value -> assertThat(value).contains("insufficient_scope"))
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void disabledSecurityStillPermitsAccessAndFormatsApplicationErrors() {
        var props = new AuthProperties();
        props.setEnabled(false);
        var responses = new GatewayErrorResponses(new ObjectMapper());
        var decoder = mock(ReactiveJwtDecoder.class);
        var chain = new GatewaySecurityConfig().securityWebFilterChain(ServerHttpSecurity.http(), props,
                new EdgeCasdoorProperties(), decoder, new GatewaySecurityErrorHandler(responses));
        var client = WebTestClient.bindToWebHandler(exchange -> {
            if (exchange.getRequest().getPath().value().endsWith("failure"))
                exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }).webFilter(new EmptyGatewayErrorBodyFilter(responses), new WebFilterChainProxy(chain)).build();
        client.get().uri("/api/sandbox/action").exchange().expectStatus().isOk().expectBody().isEmpty();
        client.get().uri("/api/sandbox/failure").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
        verifyNoInteractions(decoder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualRateLimiterPreserves429HeadersAndNeverForwardsDeniedRequest() {
        RateLimiter<Object> limiter = mock(RateLimiter.class);
        when(limiter.isAllowed("test", "user")).thenReturn(Mono.just(
                new RateLimiter.Response(false, Map.of("X-RateLimit-Remaining", "0"))));
        var factory = new RequestRateLimiterGatewayFilterFactory(limiter, exchange -> Mono.just("user"));
        var config = new RequestRateLimiterGatewayFilterFactory.Config();
        config.setRouteId("test");
        var filter = factory.apply(config);
        var forwarded = new AtomicBoolean();
        WebTestClient.bindToWebHandler(exchange -> filter.filter(exchange, next -> {
            forwarded.set(true);
            return next.getResponse().setComplete();
        })).build().get().uri("/api/feed/test").exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");
        assertThat(forwarded).isFalse();
    }
}
