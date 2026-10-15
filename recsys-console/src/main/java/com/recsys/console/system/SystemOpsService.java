package com.recsys.console.system;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.job.JobStatusRecord;
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
 * 只读运维面:读 Redis 热配置与离线作业状态。故障返回安全的不可用状态，不把空集合当作真实无配置/无作业。
 */
@Service
public class SystemOpsService {

    private static final Logger log = LoggerFactory.getLogger(SystemOpsService.class);

    @Nullable
    private final StringRedisTemplate redis;

    public SystemOpsService(@Nullable StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Redis快照不可读时只报告未知，不公开连接详情或任意历史异常。 */
    public SystemOps snapshot() {
        if (redis == null) {
            return unavailable();
        }
        try {
            return new SystemOps(true, null, loadTuning(), loadJobs(), null);
        } catch (RuntimeException failure) {
            log.debug("{} failureType={}", ErrorCode.CONSOLE_OPS_UNAVAILABLE.displayMessage(), failure.getClass().getSimpleName());
            return unavailable();
        }
    }

    private static SystemOps unavailable() {
        return new SystemOps(false, ErrorCode.CONSOLE_OPS_UNAVAILABLE.message(), Map.of(), List.of(),
                ErrorCode.CONSOLE_OPS_UNAVAILABLE.code());
    }

    private Map<String, String> loadTuning() {
        Map<Object, Object> raw = java.util.Objects.requireNonNull(redis.opsForHash().entries(RedisKeys.TUNING));
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    private List<SystemOps.JobStatus> loadJobs() {
        Set<String> keys = java.util.Objects.requireNonNull(redis.keys(RedisKeys.JOB_STATUS_PREFIX + "*"));
        if (keys.isEmpty()) {
            return List.of();
        }
        List<SystemOps.JobStatus> out = new ArrayList<>();
        List<String> sorted = keys.stream().sorted().toList();
        for (String key : sorted) {
            String raw = redis.opsForValue().get(key);
            String name = key.startsWith(RedisKeys.JOB_STATUS_PREFIX) ? key.substring(RedisKeys.JOB_STATUS_PREFIX.length()) : key;
            out.add(parseJob(name, raw));
        }
        return out;
    }

    /** 旧自由详情也须在消费端过滤，不能只依赖新生产者不再写入原文。 */
    static SystemOps.JobStatus parseJob(String name, String raw) {
        var status = JobStatusRecord.parse(raw);
        var error = status.errorCode();
        return new SystemOps.JobStatus(name, status.state().code(), status.updatedAt(),
                error == null ? null : error.message(), status.encoded(), error == null ? null : error.code());
    }
}
