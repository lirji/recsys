package com.recsys.gateway.security;

import com.recsys.platform.security.AuthProperties;
import com.recsys.platform.security.InternalToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropagationFilterTest {

    private final AuthProperties properties = new AuthProperties();
    private final IdentityPropagationFilter filter = new IdentityPropagationFilter(properties);

    @Test
    void stripsLargeExternalAndSpoofedInternalHeadersWithoutAuthentication() {
        String oversized = "x".repeat(12 * 1024);
        MockServerWebExchange incoming = MockServerWebExchange.from(MockServerHttpRequest.get("/api/console/system/overview")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + oversized)
                .header(properties.getInternalHeader(), oversized));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(incoming, exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(forwarded.get().getRequest().getHeaders().containsKey(properties.getInternalHeader())).isFalse();
    }

    @Test
    void replacesExternalAuthorizationWithCompactInternalIdentity() {
        MockServerWebExchange incoming = MockServerWebExchange.from(MockServerHttpRequest.get("/api/advertiser/campaigns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer external-token"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "alice", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        filter.filter(incoming, exchange -> {
                    forwarded.set(exchange);
                    return Mono.empty();
                })
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
        String internal = headers.getFirst(properties.getInternalHeader());
        InternalToken.Claims claims = InternalToken.verify(
                internal, Instant.now().getEpochSecond(), properties.getInternalSecret());
        assertThat(claims).isNotNull();
        assertThat(claims.subject()).isEqualTo("alice");
        assertThat(claims.roles()).isEqualTo("ADMIN");
        assertThat(claims.scene()).isEqualTo("edge");
    }
}
