package com.recsys.gateway.security;

import com.recsys.platform.security.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.gateway.web.GatewayErrorResponses;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;

/** 实际 WebFlux 安全链拦截新 namespace，而非仅断言配置字符串存在。 */
@Tag("money-chain")
@Tag("natural-exposure")
class SandboxGatewaySecurityTest {
    @Test void sandboxGetAndPutRequireAdminAtEdge() {
        var props=new AuthProperties(); props.setEnabled(true);
        var casdoor=new EdgeCasdoorProperties(); casdoor.setEnabled(false);
        var decoder=mock(ReactiveJwtDecoder.class);
        for(String role:List.of("USER","ADMIN")) {
            when(decoder.decode(role)).thenReturn(Mono.just(Jwt.withTokenValue(role).header("alg","HS256").subject("user")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("roles",List.of(role)).build()));
        }
        var errors=new GatewaySecurityErrorHandler(new GatewayErrorResponses(new ObjectMapper()));
        var chain=new GatewaySecurityConfig().securityWebFilterChain(ServerHttpSecurity.http(),props,casdoor,decoder,errors);
        var client=WebTestClient.bindToWebHandler(exchange->exchange.getResponse().setComplete()).webFilter(new WebFilterChainProxy(chain)).build();
        for(String path:List.of("/api/sandbox/search-ad-deliveries/id","/api/sandbox/ad-impressions/id","/api/sandbox/natural-deliveries/id","/api/sandbox/natural-impressions/id")) {
            client.get().uri(path).exchange().expectStatus().isUnauthorized();
            client.put().uri(path).headers(h->h.setBearerAuth("USER")).exchange().expectStatus().isForbidden();
            client.get().uri(path).headers(h->h.setBearerAuth("ADMIN")).exchange().expectStatus().isOk();
            client.put().uri(path).headers(h->h.setBearerAuth("ADMIN")).exchange().expectStatus().isOk();
        }
    }
}
