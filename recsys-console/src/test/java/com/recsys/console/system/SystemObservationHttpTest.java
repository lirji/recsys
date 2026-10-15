package com.recsys.console.system;

import com.recsys.common.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实HTTP验证观测服务响应，不以Mock RestClient绕过状态、JSON解析和传输失败。 */
class SystemObservationHttpTest {
    @Test void metricsKeepUnitsAndRoundingThroughPublicHttp() throws Exception {
        try (var endpoint = new Endpoint(vector("0.123456"))) {
            var service = metrics(endpoint);
            var controller = new SystemController(new SystemOverviewService(), null, service, null);
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(get("/api/console/system/metrics")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.available").value(true))
                    .andExpect(jsonPath("$.recommendP99Ms").value(123.5))
                    .andExpect(jsonPath("$.recommendAvgMs").value(123.5))
                    .andExpect(jsonPath("$.recommendQps").value(0.12))
                    .andExpect(jsonPath("$.adP99Ms").value(123.5));
            assertEquals(4, endpoint.calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "NaN", "+Inf", "-Inf"})
    void validNoObservationIsDifferentFromQueryFailure(String value) throws Exception {
        try (var endpoint = new Endpoint(value.equals("empty") ? "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}" : vector(value))) {
            var result = metrics(endpoint).metrics();
            assertTrue(result.available());
            assertNull(result.recommendP99Ms());
            assertNull(result.recommendQps());
            assertEquals("指标接口可达，但当前窗口没有可用推荐指标。", result.message());
            assertEquals(4, endpoint.calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{\"status\":\"error\",\"error\":\"private SQL token\"}",
            "{\"status\":\"success\"}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\"}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\",\"result\":[1,\"2\"]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{},{}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"value\":[1,\"private\"]}]}}",
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"value\":[1,\"-2\"]}]}}",
            "private invalid JSON"
    })
    void invalidOrFailedReplyDoesNotBecomeAvailableWithNoTraffic(String body) throws Exception {
        try (var endpoint = new Endpoint(body)) {
            assertUnavailable(metrics(endpoint).metrics());
            assertEquals(1, endpoint.calls.get());
        }
    }

    @Test void partialResultWarningDoesNotClaimMetricsReady() throws Exception {
        String response = vector("1").replace("{\"status\":", "{\"warnings\":[\"private partial error\"],\"status\":");
        try (var endpoint = new Endpoint(response)) {
            assertUnavailable(metrics(endpoint).metrics());
            assertEquals(1, endpoint.calls.get());
        }
    }

    @Test void lateFailureDoesNotPublishPartiallySuccessfulMetrics() throws Exception {
        try (var endpoint = new Endpoint(vector("1"))) {
            endpoint.failAt = 4;
            assertUnavailable(metrics(endpoint).metrics());
            assertEquals(4, endpoint.calls.get());
        }
    }

    @Test void httpFailureAndDisconnectUseSafeUnavailableMessage() throws Exception {
        try (var endpoint = new Endpoint("private SQL token")) {
            endpoint.status = 503;
            var service = metrics(endpoint);
            assertUnavailable(service.metrics());
            endpoint.server.stop(0);
            assertUnavailable(service.metrics());
        }
    }

    @Test void disabledDoesNotProbe() throws Exception {
        try (var endpoint = new Endpoint(vector("1"))) {
            var props = new SystemMetricsProperties();
            props.setPrometheusUrl(endpoint.url());
            props.setEnabled(false);
            assertFalse(new SystemMetricsService(props).metrics().available());
            assertEquals(0, endpoint.calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN", "private SQL token"})
    void healthStatusIsBoundedAndPublicUrlDoesNotContainCredentials(String remoteStatus) throws Exception {
        try (var endpoint = new Endpoint("{\"status\":\"" + remoteStatus + "\"}")) {
            var props = new SystemHealthProperties();
            var target = new SystemHealthProperties.Target();
            target.setService("engine"); target.setKind("app"); target.setName("engine");
            target.setUrl(endpoint.url().replace("http://", "http://secret:private@") + "/service?token=private#secret");
            props.setTargets(List.of(target));
            var result = new SystemHealthService(props).health().get(0);
            assertEquals(endpoint.url() + "/service", result.url());
            assertFalse(result.toString().contains("private"));
            assertFalse(result.toString().contains("secret"));
            if (remoteStatus.equals("UP")) assertEquals("UP", result.status());
            else if (remoteStatus.equals("DOWN") || remoteStatus.equals("OUT_OF_SERVICE")) {
                assertEquals(remoteStatus, result.status());
                assertEquals(ErrorCode.CONSOLE_HEALTH_UNHEALTHY.displayMessage(), result.message());
            } else {
                assertEquals("UNKNOWN", result.status());
                assertEquals(ErrorCode.CONSOLE_HEALTH_STATUS_UNKNOWN.displayMessage(), result.message());
            }
        }
    }

    private static void assertUnavailable(SystemMetrics result) {
        assertFalse(result.available());
        assertEquals(ErrorCode.CONSOLE_METRICS_UNAVAILABLE.displayMessage(), result.message());
        assertNull(result.recommendP99Ms()); assertNull(result.recommendAvgMs());
        assertNull(result.recommendQps()); assertNull(result.adP99Ms());
        assertFalse(result.toString().contains("private"));
    }

    private static SystemMetricsService metrics(Endpoint endpoint) {
        var props = new SystemMetricsProperties();
        props.setPrometheusUrl(endpoint.url());
        props.setConnectTimeoutMs(200); props.setReadTimeoutMs(1000);
        return new SystemMetricsService(props);
    }

    private static String vector(String value) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1,\"" + value + "\"]}]}}";
    }

    private static class Endpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        volatile int status = 200;
        volatile int failAt = Integer.MAX_VALUE;
        Endpoint(String body) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String response = calls.incrementAndGet() >= failAt ? "{\"status\":\"error\",\"error\":\"private\"}" : body;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
