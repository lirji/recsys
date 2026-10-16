package com.recsys.adserving.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.RemoteAuthzEngine;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.billing.*;
import com.recsys.proto.grpc.GrpcDeadlineClientInterceptor;
import com.recsys.proto.reconciliation.v1.SandboxReconciliationServiceGrpc;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独占浏览器宿主：真实HTTP→可信TCP RPC→同一PG三族资金。两种engine部署方式均不改变这条只读路径。 */
@EnabledIfSystemProperty(named = "recsys.reconciliation.fixture", matches = "true")
class ReconciliationBrowserFixtureTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static State state;
    @Test void serveUntilBrowserFinishes() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (var owned = new State(pg)) {
                state = owned;
                var before = state.money();
                try (var context = (ServletWebServerApplicationContext) SpringApplication.run(Web.class,
                        "--spring.config.location=optional:classpath:/browser-reconciliation-isolated.yml",
                        "--spring.cloud.nacos.config.import-check.enabled=false", "--server.address=127.0.0.1", "--server.port=0",
                        "--server.tomcat.threads.max=8", "--recsys.ad.billing.sandbox-enabled=true",
                        "--recsys.advertiser.sandbox-reconciliation-enabled=true")) {
                    Path output = Path.of(System.getProperty("recsys.browser.fixture-info"));
                    JSON.writeValue(output.toFile(), Map.of("url", "http://127.0.0.1:" + context.getWebServer().getPort(),
                            "token", state.token("report-viewer"), "otherToken", state.token("other-admin"),
                            "day", state.f.day.toString(), "advertiserId", Long.toString(Long.MAX_VALUE)));
                    assertTrue(state.done.await(240, TimeUnit.SECONDS), "浏览器必须在截止前结束");
                    assertEquals(before, state.money(), "所有读取/投影核查不能修改账户、账本、预占、资格或Outbox");
                }
            }
        }
    }
    @Configuration(proxyBeanMethods = false) @EnableWebSecurity
    @Import({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, SandboxReconciliationController.class, FixtureController.class})
    static class Web {
        @Bean AdvertiserAuthz advertiserAuthz() { return new AdvertiserAuthz("disabled", ConversionOnlineFixture.provider(state.authz)); }
        @Bean GrpcSandboxReconciliationClient client() {
            var client = new GrpcSandboxReconciliationClient();
            // 每次调用独立deadline，不能把宿主启动时的绝对截止时间复用到后续刷新。
            var stub = SandboxReconciliationServiceGrpc.newBlockingStub(state.f.channel).withInterceptors(
                    new GrpcDeadlineClientInterceptor(20000),
                    new InternalAuthGrpcClientInterceptor("x-internal-token", "recsys-advertiser", "SERVICE", 60, ReconciliationOnlineFixture.SECRET));
            ReflectionTestUtils.setField(client, "stub", stub); return client;
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .addFilterBefore(new OncePerRequestFilter() {
                        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                                throws java.io.IOException, ServletException {
                            if (!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request, response); return; }
                            String subject = state.subject(request.getHeader("Authorization"));
                            if (subject == null) {
                                response.setStatus(401); response.setContentType("application/json");
                                JSON.writeValue(response.getOutputStream(), Map.of("code", "UNAUTHORIZED")); return;
                            }
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(subject, "",
                                    List.of(new SimpleGrantedAuthority(subject.equals("report-viewer") ? "ROLE_ADVERTISER" : "ROLE_ADMIN"))));
                            try { chain.doFilter(request, response); } finally { SecurityContextHolder.clearContext(); }
                        }
                    }, AnonymousAuthenticationFilter.class).build();
        }
    }
    /** 仅测试classpath且loopback，控制投影落后/追平及独占授权故障；不是产品接口。 */
    @RestController static class FixtureController {
        @PostMapping("/fixture/project") void project() { state.f.projectAll(); }
        @PostMapping("/fixture/authz/{mode}") void permission(@PathVariable String mode) {
            if (System.getProperty("recsys.reconciliation.authz-url") != null) throw new IllegalStateException("真实权限模式不可注入替身");
            if (!Set.of("allow", "deny", "failure").contains(mode)) throw new IllegalArgumentException();
            state.authzMode.set(mode);
        }
        @GetMapping("/fixture/money") Map<String, Object> money() { return state.money(); }
        @PostMapping("/fixture/finish") void finish() { state.done.countDown(); }
    }
    static class State implements AutoCloseable {
        final ReconciliationOnlineFixture f;
        final AuthzEngine authz;
        final AtomicReference<String> authzMode = new AtomicReference<>("allow");
        final CountDownLatch done = new CountDownLatch(1);
        final byte[] key = new byte[32];
        State(PostgreSQLContainer<?> pg) throws Exception {
            new java.security.SecureRandom().nextBytes(key);
            f = new ReconciliationOnlineFixture(pg); f.seedThreeFamilies(); f.start(false);
            String url = System.getProperty("recsys.reconciliation.authz-url");
            if (url != null) authz = new RemoteAuthzEngine(url, System.getenv("ACTIONS_AUTHZ_TOKEN"));
            else {
                authz = mock(AuthzEngine.class);
                when(authz.check(any(), anyString(), any(), any())).thenAnswer(i -> {
                    assertEquals("view", i.getArgument(1)); assertEquals(Consistency.fullyConsistent(), i.getArgument(3));
                    if (authzMode.get().equals("failure")) throw new IllegalStateException("isolated authz unavailable");
                    return authzMode.get().equals("allow") && SubjectRef.user("report-viewer").equals(i.getArgument(0))
                            && ResourceRef.of("advertiser", Long.toString(Long.MAX_VALUE)).equals(i.getArgument(2));
                });
            }
        }
        /** 全行快照校验资金表完全未改；投影表由专用测试控制器显式推进，独立于读取。 */
        Map<String, Object> money() {
            var values = new LinkedHashMap<String, Object>();
            for (String table : List.of("ad_budget_account", "ad_budget_reservation", "ad_billing_ledger", "ad_billing_outbox",
                    "ad_click_settlement", "ad_click_ledger", "ad_click_outbox", "ad_conversion_settlement", "ad_conversion_ledger", "ad_conversion_outbox")) {
                values.put(table, f.jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]')::text FROM " + table + " t", String.class));
            }
            return values;
        }
        @Override public void close() throws Exception { f.close(); }
        String token(String subject) throws Exception {
            String content = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + encode(JSON.writeValueAsString(
                    Map.of("iss", "reconciliation-fixture", "sub", subject, "exp", Instant.now().plusSeconds(3600).getEpochSecond())));
            return content + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(content));
        }
        String subject(String authorization) {
            try {
                if (authorization == null || !authorization.startsWith("Bearer ")) return null;
                String[] parts = authorization.substring(7).split("\\.");
                if (parts.length != 3 || !java.security.MessageDigest.isEqual(mac(parts[0] + "." + parts[1]), Base64.getUrlDecoder().decode(parts[2]))) return null;
                var payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
                if (payload.path("exp").asLong() <= Instant.now().getEpochSecond()) return null;
                String subject = payload.path("sub").asText();
                return Set.of("report-viewer", "other-admin").contains(subject) ? subject : null;
            } catch (Exception ex) { return null; }
        }
        byte[] mac(String text) throws Exception { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(text.getBytes(StandardCharsets.UTF_8)); }
        String encode(String text) { return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
