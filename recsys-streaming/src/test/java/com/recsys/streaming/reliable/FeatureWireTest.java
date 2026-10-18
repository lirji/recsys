package com.recsys.streaming.reliable;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.common.constant.ActionType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.recsys.streaming.reliable.FeatureFixture.*;

/** 时间/来源/坐标边界；没有Kafka/Redis副作用的坏参数必须先拒绝。 */
@Tag("streaming-reliability")
class FeatureWireTest {
    FeatureSettings settings(boolean live) {
        return new FeatureSettings("local", live ? LegacyBehaviorAccepted.LIVE_TOPIC : "legacy", "group", UUID.randomUUID().toString(),
                "test", live ? SOURCE : null, live, "file:///tmp/checkpoints", null, "local", 6379, "unused", "unused", "unused",
                4000, 1000, 100, 60_000, 1000, 100, "source");
    }
    static ConsumerRecord<byte[], byte[]> record(byte[] key, byte[] value, long offset, long timestamp) {
        return new ConsumerRecord<>("topic", 0, offset, timestamp, TimestampType.CREATE_TIME, key.length, value.length, key, value,
                new org.apache.kafka.common.header.internals.RecordHeaders(), Optional.empty());
    }
    @Test void liveUsesStableEventIdentityWhileLegacyUsesFullCoordinate() {
        long now = System.currentTimeMillis(); var live = new FeatureWire(settings(true)); byte[] bytes = event(1, 2, now, ActionType.LIKE);
        assertEquals(live.decode(record("1".getBytes(), bytes, 1, now)).id, live.decode(record("1".getBytes(), bytes, 2, now)).id);
        assertEquals(2d, live.decode(record("1".getBytes(), bytes, 1, now)).weight);
        var legacy = new FeatureWire(settings(false)); byte[] old = ("{\"userId\":1,\"itemId\":9223372036854775807,\"ts\":" + now + ",\"action\":\"CLICK\",\"value\":0}").getBytes(StandardCharsets.UTF_8);
        assertNotEquals(legacy.decode(record(new byte[0], old, 1, now)).id, legacy.decode(record(new byte[0], old, 2, now)).id);
        assertEquals(Long.MAX_VALUE, legacy.decode(record(new byte[0], old, 1, now)).itemId);
    }
    @Test void poisonSourceKeyHistoryUnknownVersionAndFutureSkewFailClosed() {
        long now = System.currentTimeMillis(); var wire = new FeatureWire(settings(true)); byte[] bytes = event(1, 2, now, ActionType.CLICK);
        assertThrows(IllegalArgumentException.class, () -> wire.decode(record("2".getBytes(), bytes, 1, now)));
        for (String bad : new String[]{new String(bytes).replace(SOURCE, UUID.randomUUID().toString()),
                new String(bytes).replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "{}", "[]", new String(bytes) + "{}"})
            assertThrows(IllegalArgumentException.class, () -> wire.decode(record("1".getBytes(), bad.getBytes(StandardCharsets.UTF_8), 1, now)));
        var legacy = new FeatureWire(settings(false));
        assertThrows(IllegalArgumentException.class, () -> legacy.decode(record(new byte[0],
                ("{\"userId\":1,\"itemId\":2,\"ts\":" + (now + 30_001) + ",\"action\":\"CLICK\",\"value\":0}").getBytes(), 1, now)));
    }
    @Test void missingMandatoryIdentityAndReservedHistoryFailBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> ReliableFeatureJob.run(java.util.Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ReliableFeatureJob.run(java.util.Map.of("topic", LegacyBehaviorAccepted.HISTORY_TOPIC)));
        assertEquals(com.recsys.common.constant.RedisKeys.RT_HOT_RECALL, com.recsys.common.constant.RedisKeys.realtimeHot(""));
        assertEquals("isolated:hot", com.recsys.common.constant.RedisKeys.realtimeHot("isolated"));
    }
}
