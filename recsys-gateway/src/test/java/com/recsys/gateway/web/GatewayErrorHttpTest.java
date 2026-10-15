package com.recsys.gateway.web;

import com.nimbusds.jwt.SignedJWT;
import com.recsys.gateway.GatewayApplication;
import com.recsys.platform.security.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 启动实际Gateway/Netty：验证安全、控制器、框架异常与错误序列化在真实HTTP上协同。 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "management.tracing.enabled=false", "recsys.security.enabled=true",
                "recsys.security.casdoor.enabled=false", "server.port=0"})
@Import(GatewayErrorHttpTest.Probe.class)
class GatewayErrorHttpTest {
    @LocalServerPort int port;
    @Autowired GatewayExceptionHandler handler;
    @Autowired AuthProperties properties;

    private WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

    @Test
    void loginAndRealJwtAuthorizationKeepSuccessAndErrorContracts() throws Exception {
        client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "bad"))
                .exchange().expectStatus().isUnauthorized().expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_INVALID_CREDENTIALS")
                .jsonPath("$.message").isEqualTo("用户名或密码错误");
        var result = client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "user", "password", "user"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(result).containsEntry("tokenType", "Bearer");
        String token = (String) result.get("token");
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getSubject()).isEqualTo("user");
        client().get().uri("/api/sandbox/action").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client().get().uri("/api/sandbox/action").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void framework404405415AndMalformedJson400HaveSafeCodes() {
        client().get().uri("/api/auth/missing").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        client().get().uri("/api/auth/login").exchange().expectStatus().isEqualTo(405)
                .expectHeader().value(HttpHeaders.ALLOW, value -> assertThat(value).contains("POST"))
                .expectBody().jsonPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
        client().post().uri("/api/auth/login").contentType(MediaType.TEXT_PLAIN).bodyValue("private")
                .exchange().expectStatus().isEqualTo(415).expectBody()
                .jsonPath("$.code").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{private-broken")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST");
    }

    @Test
    void signingConfigurationFailureIs500WithSafeCodeRatherThanBadCredentials() {
        String original = properties.getEdgeSecret();
        try {
            // 只注入签发器故障，真实HTTP响应必须保留500并隐藏密钥/底层校验详情。
            properties.setEdgeSecret("short-secret");
            client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("username", "admin", "password", "admin"))
                    .exchange().expectStatus().isEqualTo(500).expectBody()
                    .jsonPath("$.code").isEqualTo("AUTH_TOKEN_ISSUANCE_FAILED")
                    .jsonPath("$.message").isEqualTo("登录服务暂时不可用，请稍后重试")
                    .consumeWith(result -> assertThat(new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("short-secret", "MACSigner", "256 bits"));
        } finally {
            properties.setEdgeSecret(original);
        }
    }

    @Test
    void serverErrorsKeepStatusAndHeadersWithoutInternalDetails() {
        for (int status : new int[]{429, 502, 503, 504}) {
            client().get().uri("/api/auth/probe/status/" + status).exchange().expectStatus().isEqualTo(status)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                    .expectBody().jsonPath("$.code").isNotEmpty()
                    .consumeWith(result -> assertThat(new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
                            .doesNotContain("private", "SQL"));
        }
        client().get().uri("/api/auth/probe/unexpected").exchange().expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.message").isEqualTo("服务器内部错误");
        client().head().uri("/api/auth/missing").exchange().expectStatus().isNotFound().expectBody().isEmpty();
    }

    @Test
    void existingBusinessBodySurvivesWholeGatewayChain() {
        client().get().uri("/api/auth/probe/business").exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("DELIVERY_409")
                .jsonPath("$.custom").isEqualTo("retained");
    }

    @RestController
    static class Probe {
        @GetMapping("/api/auth/probe/status/{status}")
        String status(@PathVariable int status) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(status), "private SQL detail") {
                @Override public HttpHeaders getHeaders() {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set(HttpHeaders.RETRY_AFTER, "30");
                    return headers;
                }
            };
        }
        @GetMapping("/api/auth/probe/unexpected")
        String unexpected() { throw new IllegalStateException("private SQL detail"); }
        @GetMapping("/api/auth/probe/business")
        ResponseEntity<?> business() {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "DELIVERY_409", "custom", "retained"));
        }
    }
}
