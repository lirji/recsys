package com.recsys.streaming.reliable;

import org.apache.flink.configuration.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Redis证明整窗删除、重放幂等、租约、防旧版本和固定有效期。 */
@Tag("streaming-reliability") @Testcontainers
class FeatureRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @TempDir Path directory;
    FeatureSettings settings;
    FeatureRedisSink sink;
    Jedis redis;
    @BeforeEach void start() throws Exception {
        settings = settings(); redis = new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379));
        sink = new FeatureRedisSink(settings); sink.open(new Configuration());
    }
    @AfterEach void close() { sink.close(); redis.close(); }
    FeatureSettings settings() {
        return new FeatureSettings("unused", "legacy-test", "group", UUID.randomUUID().toString(), "test:" + UUID.randomUUID(), null, false,
                directory.toUri().toString(), null, REDIS.getHost(), REDIS.getMappedPort(6379), "unused", "unused", "unused",
                4000, 1000, 100, 10_000, 1000, 100, "source");
    }
    static String snapshot(long end, Map<String, Double> hot, Map<String, Map<String, Long>> users) throws Exception {
        return FeatureWire.mapper().writeValueAsString(new TreeMap<>(Map.of("end", end, "hot", hot, "users", users)));
    }
    @Test void replacesWholeWindowAndDeletesMissingItemsUsersAndCategories() throws Exception {
        long now = System.currentTimeMillis();
        sink.publish(snapshot(now, Map.of("1", 2d, "2", 1d), Map.of("1", Map.of("old", 2L, "keep", 1L), "2", Map.of("gone", 1L))));
        sink.publish(snapshot(now + 1, Map.of("2", 3d), Map.of("1", Map.of("keep", 2L))));
        assertEquals(Map.of("keep", "2"), redis.hgetAll(settings.namespace() + ":user:1"));
        assertFalse(redis.exists(settings.namespace() + ":user:2"));
        assertEquals(1, redis.zcard(settings.namespace() + ":hot"));
        assertEquals(3d, redis.zscore(settings.namespace() + ":hot", "2"));
        sink.publish(snapshot(now + 2, Map.of(), Map.of()));
        assertFalse(redis.exists(settings.namespace() + ":hot"));
        assertFalse(redis.exists(settings.namespace() + ":user:1"));
    }
    @Test void duplicateRestoresMissingProjectionWithoutExtendingExpiryAndOldCannotOverwrite() throws Exception {
        long now = System.currentTimeMillis(); String original = snapshot(now, Map.of("1", 2d), Map.of("1", Map.of("cat", 1L)));
        sink.publish(original); long expires = redis.pexpireTime(settings.namespace() + ":hot");
        redis.del(settings.namespace() + ":hot", settings.namespace() + ":user:1"); sink.publish(original);
        assertEquals(2d, redis.zscore(settings.namespace() + ":hot", "1"));
        assertEquals(expires, redis.pexpireTime(settings.namespace() + ":hot"));
        sink.publish(snapshot(now - 1, Map.of("2", 99d), Map.of()));
        assertEquals(2d, redis.zscore(settings.namespace() + ":hot", "1"));
        assertThrows(IllegalStateException.class, () -> sink.publish(snapshot(now, Map.of("1", 3d), Map.of())));
        assertEquals(2d, redis.zscore(settings.namespace() + ":hot", "1"));
    }
    @Test void expiredReplayCannotReviveFeatures() throws Exception {
        sink.publish(snapshot(System.currentTimeMillis() - 20_000, Map.of("1", 2d), Map.of("1", Map.of("cat", 1L))));
        assertFalse(redis.exists(settings.namespace() + ":hot")); assertFalse(redis.exists(settings.namespace() + ":user:1"));
        assertTrue(redis.exists(settings.namespace() + ":control"));
    }
    @Test void liveOwnerAndExpiredWriterCannotStealLease() throws Exception {
        String snapshot = snapshot(System.currentTimeMillis(), Map.of("1", 1d), Map.of()); sink.publish(snapshot);
        var other = new FeatureRedisSink(settings); other.open(new Configuration());
        try { assertThrows(IllegalStateException.class, () -> other.publish(snapshot));
            redis.set(settings.namespace() + ":lease", "replacement-owner");
            assertThrows(IllegalStateException.class, () -> sink.publish(snapshot));
        } finally { other.close(); }
        assertEquals("replacement-owner", redis.get(settings.namespace() + ":lease"));
    }
    @Test void wrongTypeFailsBeforeDeletingAnyPreviousOutput() throws Exception {
        String snapshot = snapshot(System.currentTimeMillis(), Map.of("1", 1d), Map.of()); sink.publish(snapshot);
        redis.set(settings.namespace() + ":user:1", "invalid");
        assertThrows(IllegalStateException.class, () -> sink.publish(snapshot(System.currentTimeMillis() + 1, Map.of("2", 2d), Map.of("1", Map.of("x", 1L)))));
        assertEquals(1d, redis.zscore(settings.namespace() + ":hot", "1"));
    }
    @Test void realRedisOutagePropagatesAndDoesNotBecomeSuccessfulDelivery() throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try { assertThrows(redis.clients.jedis.exceptions.JedisException.class,
                () -> sink.publish(snapshot(System.currentTimeMillis(), Map.of(), Map.of()))); }
        finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
    }
}
