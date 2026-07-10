package com.recsys.console.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 查 Prometheus HTTP API(/api/v1/query)求推荐/广告链路的真实延迟与 QPS。
 * 全程优雅降级:Prometheus 不可达 → available=false;可达但近窗口无流量 → 对应字段 null。任何异常都不外抛。
 */
@Service
public class SystemMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsService.class);

    private final SystemMetricsProperties props;
    private final RestClient restClient;

    // 两个构造器 → 必须显式标注 Spring 用哪个,否则 "No default constructor found"(与 SystemHealthService 同坑)。
    @Autowired
    public SystemMetricsService(SystemMetricsProperties props) {
        this(props, buildClient(props));
    }

    SystemMetricsService(SystemMetricsProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    public SystemMetrics metrics() {
        long now = Instant.now().toEpochMilli();
        if (!props.isEnabled()) {
            return SystemMetrics.unavailable("系统指标查询已关闭(recsys.console.metrics.enabled=false)。", now);
        }
        try {
            // 第一条同时充当 Prometheus 可达性探针:传输/HTTP 错误会抛出 → 落到 catch 标 unavailable。
            Double p99s = queryScalar(props.getRecommendP99Query());
            Double avgs = queryScalar(props.getRecommendAvgQuery());
            Double qps = queryScalar(props.getRecommendQpsQuery());
            Double adP99s = queryScalar(props.getAdP99Query());

            boolean hasTraffic = p99s != null || qps != null;
            String message = hasTraffic
                    ? "Prometheus 指标就绪。"
                    : "Prometheus 可达,但近窗口暂无推荐流量(先发起几次 /recommend 再看)。";
            return new SystemMetrics(true, "prometheus", message,
                    toMs(p99s), toMs(avgs), round2(qps), toMs(adP99s), now);
        } catch (Exception e) {
            log.debug("Prometheus 指标查询失败: {}", e.toString());
            return SystemMetrics.unavailable(
                    "Prometheus 不可达(先 docker compose --profile obs up -d 启动观测栈):" + rootMessage(e), now);
        }
    }

    /** 求一个标量:成功且有限 → Double;成功但结果为空 / NaN / Inf → null;传输或 HTTP 错误 → 抛出。 */
    private Double queryScalar(String promql) {
        String base = props.getPrometheusUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + "/api/v1/query?query="
                + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        Map<?, ?> body = restClient.get().uri(uri).retrieve().body(Map.class);
        if (body == null || !"success".equals(body.get("status"))) {
            return null;
        }
        if (!(body.get("data") instanceof Map<?, ?> data)) {
            return null;
        }
        if (!(data.get("result") instanceof List<?> result) || result.isEmpty()) {
            return null; // 无数据(例如近窗口无流量)
        }
        if (!(result.get(0) instanceof Map<?, ?> series)
                || !(series.get("value") instanceof List<?> value)
                || value.size() < 2) {
            return null;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value.get(1)));
            return (Double.isNaN(d) || Double.isInfinite(d)) ? null : d;
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /** 秒 → 毫秒,保留 1 位小数。 */
    private static Double toMs(Double seconds) {
        return seconds == null ? null : Math.round(seconds * 1000.0 * 10.0) / 10.0;
    }

    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    private static RestClient buildClient(SystemMetricsProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
