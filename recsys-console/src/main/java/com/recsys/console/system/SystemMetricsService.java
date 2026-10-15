package com.recsys.console.system;

import com.recsys.common.error.ErrorCode;
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

    /** 可用仅表示本次四项查询均获得合法响应，缺数据不会推断为流量为零。 */
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

            boolean hasRecommendMetrics = p99s != null || avgs != null || qps != null;
            String message = hasRecommendMetrics
                    ? "Prometheus 指标就绪。"
                    : "指标接口可达，但当前窗口没有可用推荐指标。";
            return new SystemMetrics(true, "prometheus", message,
                    toMs(p99s), toMs(avgs), round2(qps), toMs(adP99s), now);
        } catch (Exception e) {
            log.debug("{} failureType={}", ErrorCode.CONSOLE_METRICS_UNAVAILABLE.displayMessage(), e.getClass().getSimpleName());
            return SystemMetrics.unavailable(
                    ErrorCode.CONSOLE_METRICS_UNAVAILABLE.displayMessage(), now);
        }
    }

    /** 只接受当前四个聚合查询的单值vector；协议失败不能被当作“没有流量”。 */
    private Double queryScalar(String promql) {
        String base = props.getPrometheusUrl().replaceAll("/+$", "");
        URI uri = URI.create(base + "/api/v1/query?query="
                + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        Map<?, ?> body = restClient.get().uri(uri).retrieve().body(Map.class);
        if (body == null || !"success".equals(body.get("status"))
                || !(body.get("data") instanceof Map<?, ?> data)
                || !"vector".equals(data.get("resultType"))
                || !(data.get("result") instanceof List<?> result)
                || result.size() > 1) {
            throw invalidReply();
        }
        // warnings可能表示仅返回部分可收集数据；当前视图没有部分结果字段，不能显示完全就绪。
        Object warnings = body.get("warnings");
        if (warnings != null && (!(warnings instanceof List<?> list) || !list.isEmpty())) throw invalidReply();
        if (result.isEmpty()) return null;
        if (!(result.get(0) instanceof Map<?, ?> series)
                || !(series.get("value") instanceof List<?> value) || value.size() != 2
                || !(value.get(0) instanceof Number) || !(value.get(1) instanceof String raw)) {
            throw invalidReply();
        }
        // Prometheus的非有限观测值表示当前无法求值，保留原null语义；格式错误不能混同。
        if ("NaN".equals(raw) || "+Inf".equals(raw) || "-Inf".equals(raw)) return null;
        double number = Double.parseDouble(raw);
        if (!Double.isFinite(number)) return null;
        if (number < 0) throw invalidReply(); // 本入口只读延迟和QPS，负数不是有效观测值。
        return number;
    }

    private static IllegalStateException invalidReply() {
        return new IllegalStateException(ErrorCode.CONSOLE_METRICS_UNAVAILABLE.code());
    }

    /** 秒 → 毫秒,保留 1 位小数。 */
    private static Double toMs(Double seconds) {
        return seconds == null ? null : Math.round(seconds * 1000.0 * 10.0) / 10.0;
    }

    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }

    private static RestClient buildClient(SystemMetricsProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
