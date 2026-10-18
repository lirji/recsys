package com.recsys.streaming.reliable;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** C19显式运行边界；所有影响恢复结果的参数均进入状态身份。 */
public record FeatureSettings(String kafka, String topic, String group, String projection,
        String namespace, String sourceId, boolean live, String checkpointDir, String restore,
        String redisHost, int redisPort, String pgUrl, String pgUser, String pgPassword,
        long windowMs, long slideMs, long disorderMs, long freshnessMs, long checkpointMs,
        int maxEvents, String sourceIdentity) implements Serializable {
    public static final long FUTURE_SKEW_MS = 30_000;
    public static final long LEASE_MS = 60_000;
    public static final int IO_TIMEOUT_MS = 2_000;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_CATEGORIES = 100_000;
    public static final String INVALID = com.recsys.common.error.ErrorCode.VALIDATION_FAILED.displayMessage();
    public static final String CONFLICT = com.recsys.common.error.ErrorCode.CONFLICT.displayMessage();
    public static final String CAPACITY = com.recsys.common.error.ErrorCode.SERVICE_UNAVAILABLE.displayMessage();

    public FeatureSettings {
        if (kafka == null || kafka.isBlank() || topic == null || topic.isBlank()
                || group == null || group.isBlank() || namespace == null || !namespace.matches("[a-zA-Z0-9:_-]{1,80}")
                || projection == null || !UUID.fromString(projection).toString().equals(projection)
                || checkpointDir == null || !URI.create(checkpointDir).isAbsolute()
                || windowMs < 100 || windowMs > 86_400_000 || slideMs < 100 || slideMs >= LEASE_MS / 2
                || windowMs % slideMs != 0 || windowMs / slideMs > 100
                || disorderMs < 0 || freshnessMs <= disorderMs + slideMs || freshnessMs > 86_400_000
                || checkpointMs < 100 || maxEvents < 1 || maxEvents > 100_000
                || redisPort < 1 || redisPort > 65535 || sourceIdentity == null) throw new IllegalArgumentException(INVALID);
        if (live) {
            if (!(topic.equals(LegacyBehaviorAccepted.LIVE_TOPIC) || topic.startsWith(LegacyBehaviorAccepted.LIVE_TOPIC + "."))
                    || sourceId == null || !UUID.fromString(sourceId).toString().equals(sourceId)) throw new IllegalArgumentException(INVALID);
        } else LegacyBehaviorAccepted.requireLegacyTopic(topic);
    }

    /** 凭据不进入状态摘要；源集群/Topic ID由启动时只读Admin查询绑定。 */
    public String fingerprint() {
        return digest(String.join("|", topic, group, projection, namespace, String.valueOf(sourceId),
                Boolean.toString(live), Long.toString(windowMs), Long.toString(slideMs), Long.toString(disorderMs),
                Long.toString(freshnessMs), Integer.toString(maxEvents), sourceIdentity));
    }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(INVALID); }
    }

    /** CLI优先于环境变量；必需身份不提供共享环境默认值。 */
    public static String value(Map<String, String> args, String key, String env, String fallback) {
        return args.getOrDefault(key, System.getenv().getOrDefault(env, fallback));
    }
}
