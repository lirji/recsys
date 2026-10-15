package com.recsys.console.system;

import com.recsys.common.error.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.recsys.console.system.SystemOverview.ServiceHealth;

/** 探测服务状态；远端状态和错误描述不直接进入控制台。 */
@Service
public class SystemHealthService {

    /** 主动探测只接受已知状态；稳定code用于响应，不使用ordinal或枚举名隐式持久化。 */
    private enum ProbeStatus {
        UP("UP", null),
        DOWN("DOWN", ErrorCode.CONSOLE_HEALTH_UNHEALTHY),
        OUT_OF_SERVICE("OUT_OF_SERVICE", ErrorCode.CONSOLE_HEALTH_UNHEALTHY),
        UNKNOWN("UNKNOWN", ErrorCode.CONSOLE_HEALTH_STATUS_UNKNOWN);

        private final String code;
        private final ErrorCode error;
        ProbeStatus(String code, ErrorCode error) { this.code = code; this.error = error; }

        static ProbeStatus from(Object value) {
            if (value instanceof String text) {
                for (ProbeStatus status : values()) if (status.code.equalsIgnoreCase(text)) return status;
            }
            return UNKNOWN;
        }

        String message() { return error == null ? "health endpoint reachable" : error.displayMessage(); }
    }

    private final SystemHealthProperties properties;
    private final RestClient restClient;

    @Autowired
    public SystemHealthService(SystemHealthProperties properties) {
        this(properties, buildClient(properties));
    }

    SystemHealthService(SystemHealthProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    /** 各目标独立探测，失败仍保留在结果中，不能成为缺失的健康项。 */
    public List<ServiceHealth> health() {
        long checkedAt = Instant.now().toEpochMilli();
        return properties.getTargets()
                .parallelStream()
                .map(target -> health(target, checkedAt))
                .toList();
    }

    private ServiceHealth health(SystemHealthProperties.Target target, long checkedAt) {
        if (target.getPassiveStatus() != null && !target.getPassiveStatus().isBlank()) {
            return passive(target, checkedAt);
        }
        if (target.getUrl() == null || target.getUrl().isBlank()) {
            return new ServiceHealth(
                    target.getService(),
                    target.getName(),
                    target.getKind(),
                    null,
                    ProbeStatus.UNKNOWN.code,
                    ErrorCode.CONSOLE_HEALTH_TARGET_MISSING.displayMessage(),
                    checkedAt
            );
        }
        return probe(target, checkedAt);
    }

    private ServiceHealth probe(SystemHealthProperties.Target target, long checkedAt) {
        try {
            ResponseEntity<Map> response = restClient.get()
                    .uri(target.getUrl() + "/actuator/health")
                    .retrieve()
                    .toEntity(Map.class);
            Object value = response.getBody() == null ? null : response.getBody().get("status");
            ProbeStatus status = ProbeStatus.from(value);
            return new ServiceHealth(
                    target.getService(),
                    target.getName(),
                    target.getKind(),
                    publicUrl(target.getUrl()),
                    status.code,
                    status.message(),
                    checkedAt
            );
        } catch (RestClientResponseException e) {
            return new ServiceHealth(
                    target.getService(),
                    target.getName(),
                    target.getKind(),
                    publicUrl(target.getUrl()),
                    ProbeStatus.DOWN.code,
                    ErrorCode.CONSOLE_HEALTH_HTTP_FAILED.displayMessage() + " (HTTP " + e.getStatusCode().value() + ")",
                    checkedAt
            );
        } catch (Exception e) {
            return new ServiceHealth(
                    target.getService(),
                    target.getName(),
                    target.getKind(),
                    publicUrl(target.getUrl()),
                    ProbeStatus.DOWN.code,
                    ErrorCode.CONSOLE_HEALTH_UNAVAILABLE.displayMessage(),
                    checkedAt
            );
        }
    }

    private ServiceHealth passive(SystemHealthProperties.Target target, long checkedAt) {
        String message = target.getPassiveMessage() == null || target.getPassiveMessage().isBlank()
                ? "passive target"
                : target.getPassiveMessage();
        return new ServiceHealth(
                target.getService(),
                target.getName(),
                target.getKind(),
                null,
                target.getPassiveStatus(),
                message,
                checkedAt
        );
    }

    /** URL用于定位服务，凭据及查询参数无需进入公开响应；不改变实际探测目标。 */
    private static String publicUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) return null;
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (IllegalArgumentException | URISyntaxException failure) {
            return null;
        }
    }

    private static RestClient buildClient(SystemHealthProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
