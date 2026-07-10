package com.recsys.console.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.recsys.console.system.SystemOverview.ServiceHealth;

@Service
public class SystemHealthService {

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
                    "UNKNOWN",
                    "health target url is not configured",
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
            String status = value == null ? "UNKNOWN" : String.valueOf(value).toUpperCase(Locale.ROOT);
            return new ServiceHealth(
                    target.getService(),
                    target.getName(),
                    target.getKind(),
                    target.getUrl(),
                    status,
                    "health endpoint reachable",
                    checkedAt
            );
        } catch (Exception e) {
            return new ServiceHealth(
                    target.getService(),
                    target.getName(),
                    target.getKind(),
                    target.getUrl(),
                    "DOWN",
                    "health endpoint unreachable",
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

    private static RestClient buildClient(SystemHealthProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
