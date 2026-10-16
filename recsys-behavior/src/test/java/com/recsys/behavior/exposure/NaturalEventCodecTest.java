package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper.Pending;
import com.recsys.common.exposure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 严格消息边界；历史已接受事件不因现在超过资格期限被丢弃。 */
@Tag("natural-exposure")
class NaturalEventCodecTest {
    final NaturalEventCodec codec = new NaturalEventCodec(NaturalEventsFixture.JSON);
    static String payload(NaturalQualification.Kind kind) throws Exception {
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        var q = new NaturalQualification(UUID.randomUUID(), UUID.randomUUID(), "never-published-subject", Long.MAX_VALUE, Long.MAX_VALUE - 1,
                50, kind, NaturalQualification.Traffic.SANDBOX, "scene", "", "a".repeat(64),
                new NaturalQualification.Policy("historical-test-only", 5000, 1000, 300000), issued, issued.plusSeconds(300));
        return NaturalEventsFixture.JSON.writeValueAsString(NaturalReceipt.Event.accepted(q, UUID.randomUUID(), issued.plusSeconds(1).plusNanos(123000)));
    }

    @ParameterizedTest @EnumSource(NaturalQualification.Kind.class)
    void preservesEveryKindLargeIdsHistoricalPolicyAndMicroseconds(NaturalQualification.Kind kind) throws Exception {
        String original = payload(kind);
        var e = codec.decode(Long.toString(Long.MAX_VALUE), original);
        assertEquals(kind, e.kind()); assertEquals(123000, e.acceptedAt().getNano());
        assertEquals("historical-test-only", e.policy().version());
        var row = new Pending(UUID.fromString(e.eventId()), e.eventType(), 1, NaturalReceipt.Event.TOPIC, e.userId(), original, 1, 1);
        assertEquals(e, codec.validate(row)); assertEquals(original, row.payload());
        assertFalse(original.contains("never-published-subject"));
    }

    @Test void rejectsMalformedTypesIdentityTimeAndSecretFields() throws Exception {
        String original = payload(NaturalQualification.Kind.FEED);
        java.util.List<Consumer<ObjectNode>> mutations = java.util.List.of(
                n -> n.put("schemaVersion", 2), n -> n.put("schemaVersion", 4294967297L),
                n -> n.put("slot", 1.0), n -> n.put("slot", 51), n -> n.putNull("kind"), n -> n.put("kind", "AD"),
                n -> n.put("trafficClass", "PRODUCTION"), n -> n.put("eventType", "OTHER"), n -> n.put("qualificationId", UUID.randomUUID().toString()),
                n -> n.put("eventId", "1-1-1-1-1"), n -> n.put("userId", 123), n -> n.put("itemId", "01"), n -> n.put("itemId", "9223372036854775808"),
                n -> n.put("scene", " "), n -> n.putNull("bucket"), n -> n.put("contentHash", "bad"), n -> n.put("token", "secret"),
                n -> n.remove("receiptId"), n -> n.put("acceptedAt", "2026-01-01T00:00:00Z"),
                n -> n.put("acceptedAt", "2026-01-01T00:05:00Z"), n -> n.put("acceptedAt", "2026-01-01T00:00:01.123456789Z"),
                n -> n.put("issuedAt", "2026-01-01T00:00:00+00:00"), n -> n.put("expiresAt", "2026-01-01T00:05:01Z"),
                n -> ((ObjectNode)n.get("policy")).put("ttlMs", "300000"),
                n -> ((ObjectNode)n.get("policy")).put("minDurationMs", 0),
                n -> ((ObjectNode)n.get("policy")).put("minRatioBps", 10001),
                n -> ((ObjectNode)n.get("policy")).put("privateKey", "secret"));
        for (var mutation : mutations) {
            ObjectNode n = (ObjectNode)NaturalEventsFixture.JSON.readTree(original); mutation.accept(n);
            var ex = assertThrows(NaturalEventCodec.InvalidEvent.class, () -> codec.decode(Long.toString(Long.MAX_VALUE), n.toString()));
            assertFalse(ex.getMessage().contains("secret")); assertNull(ex.getCause());
        }
        for (String invalid : java.util.List.of(original + " {}", original.replaceFirst("\\{", "{\"slot\":1,"), " ".repeat(32769), "[]")) {
            assertThrows(NaturalEventCodec.InvalidEvent.class, () -> codec.decode(Long.toString(Long.MAX_VALUE), invalid));
        }
        assertThrows(NaturalEventCodec.InvalidEvent.class, () -> codec.decode("123", original));
    }

    @Test void rejectsRowMetadataMismatch() throws Exception {
        String payload = payload(NaturalQualification.Kind.SEARCH);
        var e = codec.decode(Long.toString(Long.MAX_VALUE), payload);
        for (var p : java.util.List.of(
                new Pending(UUID.randomUUID(), e.eventType(), 1, NaturalReceipt.Event.TOPIC, e.userId(), payload, 1, 1),
                new Pending(UUID.fromString(e.eventId()), "OTHER", 1, NaturalReceipt.Event.TOPIC, e.userId(), payload, 1, 1),
                new Pending(UUID.fromString(e.eventId()), e.eventType(), 2, NaturalReceipt.Event.TOPIC, e.userId(), payload, 1, 1),
                new Pending(UUID.fromString(e.eventId()), e.eventType(), 1, "behavior-events", e.userId(), payload, 1, 1))) {
            assertThrows(NaturalEventCodec.InvalidEvent.class, () -> codec.validate(p));
        }
    }
}
