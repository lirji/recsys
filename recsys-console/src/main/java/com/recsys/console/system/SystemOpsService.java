package com.recsys.console.system;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读运维面:读 Redis 热配置与离线作业状态。任一异常 fail-open,不拖垮控制台。
 */
@Service
public class SystemOpsService {

    private static final Logger log = LoggerFactory.getLogger(SystemOpsService.class);
    private static final String JOB_STATUS_PREFIX = "job:status:";

    @Nullable
    private final StringRedisTemplate redis;

    public SystemOpsService(@Nullable StringRedisTemplate redis) {
        this.redis = redis;
    }

    public SystemOps snapshot() {
        if (redis == null) {
            return new SystemOps(false, "Redis 未装配", Map.of(), List.of());
        }
        try {
            return new SystemOps(true, null, loadTuning(), loadJobs());
        } catch (Exception e) {
            log.debug("读取运维快照失败: {}", e.getMessage());
            return new SystemOps(false, "Redis 不可达: " + e.getMessage(), Map.of(), List.of());
        }
    }

    private Map<String, String> loadTuning() {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.TUNING);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    private List<SystemOps.JobStatus> loadJobs() {
        Set<String> keys = redis.keys(JOB_STATUS_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<SystemOps.JobStatus> out = new ArrayList<>();
        List<String> sorted = keys.stream().sorted().toList();
        for (String key : sorted) {
            String raw = redis.opsForValue().get(key);
            String name = key.startsWith(JOB_STATUS_PREFIX) ? key.substring(JOB_STATUS_PREFIX.length()) : key;
            out.add(parseJob(name, raw));
        }
        return out;
    }

    /** 解析 {@code status@yyyy-MM-dd HH:mm:ss[:detail]}(DagJob.recordStatus 口径)。 */
    static SystemOps.JobStatus parseJob(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return new SystemOps.JobStatus(name, "UNKNOWN", null, null, raw);
        }
        int at = raw.indexOf('@');
        if (at <= 0) {
            return new SystemOps.JobStatus(name, raw, null, null, raw);
        }
        String status = raw.substring(0, at);
        String rest = raw.substring(at + 1);
        if (rest.length() >= 19) {
            String updatedAt = rest.substring(0, 19);
            String detail = rest.length() > 20 && rest.charAt(19) == ':' ? rest.substring(20) : null;
            return new SystemOps.JobStatus(name, status, updatedAt, detail, raw);
        }
        return new SystemOps.JobStatus(name, status, rest, null, raw);
    }
}
