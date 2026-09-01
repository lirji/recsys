package com.recsys.common.experiment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Redis 精确曝光归因值。数字字段与 bucket 分开编码，避免 bucket 中的分号等实验标签破坏解析。
 */
public record ExposureAttribution(long userId, long itemId, String bucket) {

    private static final String VERSION = "v1";

    public String encode() {
        String rawBucket = bucket == null ? "" : bucket;
        String encodedBucket = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawBucket.getBytes(StandardCharsets.UTF_8));
        return VERSION + ":" + userId + ":" + itemId + ":" + encodedBucket;
    }

    public static Optional<ExposureAttribution> decode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = value.split(":", 4);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                return Optional.empty();
            }
            String bucket = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
            return Optional.of(new ExposureAttribution(
                    Long.parseLong(parts[1]), Long.parseLong(parts[2]), bucket.isBlank() ? null : bucket));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public boolean matches(long expectedUserId, long expectedItemId) {
        return userId == expectedUserId && itemId == expectedItemId;
    }
}
