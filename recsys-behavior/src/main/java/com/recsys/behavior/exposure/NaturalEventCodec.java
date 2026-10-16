package com.recsys.behavior.exposure;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper.Pending;
import com.recsys.common.exposure.NaturalReceipt.Event;
import com.recsys.common.exposure.NaturalQualification;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 只验证已接受事实的原始 v1 负载，不重新验签、判断当前到期或改写原字节。 */
public class NaturalEventCodec {
    public static final int MAX_BYTES = 32768;
    private static final Set<String> FIELDS = Set.of("eventId", "eventType", "schemaVersion", "trafficClass",
            "operationId", "qualificationId", "receiptId", "userId", "itemId", "slot", "kind", "scene",
            "bucket", "contentHash", "policy", "issuedAt", "expiresAt", "acceptedAt");
    private static final Set<String> POLICY = Set.of("version", "minRatioBps", "minDurationMs", "ttlMs");
    private final ObjectMapper json;

    /** 独立 codec 避免宿主宽松反序列化策略影响消息边界。 */
    public NaturalEventCodec(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    /** 验证行元数据与消息身份一致；错误不携带原始负载。 */
    public Event validate(Pending pending) {
        Event event = decode(pending.key(), pending.payload());
        if (!Event.TOPIC.equals(pending.destination()) || !Event.TYPE.equals(pending.eventType())
                || pending.schemaVersion() != Event.VERSION || !pending.eventId().toString().equals(event.eventId())) {
            throw new InvalidEvent();
        }
        return event;
    }

    /** 保留历史已接受政策，合法积压不因当前 key/政策/时间变化而丢失。 */
    public Event decode(String key, String payload) {
        try {
            if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new InvalidEvent();
            JsonNode tree = json.readTree(payload);
            exactFields(tree, FIELDS);
            exactFields(tree.path("policy"), POLICY);
            for (String name : FIELDS) {
                if (!Set.of("schemaVersion", "slot", "policy").contains(name) && !tree.path(name).isTextual()) throw new InvalidEvent();
            }
            integer(tree, "schemaVersion"); integer(tree, "slot");
            JsonNode policy = tree.path("policy");
            if (!policy.path("version").isTextual()) throw new InvalidEvent();
            integer(policy, "minRatioBps"); integer(policy, "minDurationMs"); integer(policy, "ttlMs");
            Event e = json.treeToValue(tree, Event.class);
            if (!Event.TYPE.equals(e.eventType()) || e.schemaVersion() != Event.VERSION || !"SANDBOX".equals(e.trafficClass())
                    || e.slot() < 1 || e.slot() > NaturalQualification.MAX_SLOTS || e.kind() == null || !e.eventId().equals(e.qualificationId())) throw new InvalidEvent();
            for (String id : List.of(e.eventId(), e.qualificationId(), e.operationId(), e.receiptId())) {
                if (!UUID.fromString(id).toString().equals(id)) throw new InvalidEvent();
            }
            for (String id : List.of(e.userId(), e.itemId())) {
                long value = Long.parseLong(id);
                if (value <= 0 || !Long.toString(value).equals(id)) throw new InvalidEvent();
            }
            if (!e.userId().equals(key) || e.scene().isBlank() || e.scene().length() > 100 || e.bucket().length() > 2000
                    || !e.contentHash().matches("[0-9a-f]{64}")) throw new InvalidEvent();
            for (String time : List.of("issuedAt", "expiresAt", "acceptedAt")) {
                if (!tree.path(time).textValue().endsWith("Z")) throw new InvalidEvent();
            }
            if (e.issuedAt().getNano() % 1_000_000 != 0 || e.expiresAt().getNano() % 1_000_000 != 0
                    || e.acceptedAt().getNano() % 1000 != 0 || !e.issuedAt().plusMillis(e.policy().ttlMs()).equals(e.expiresAt())
                    || e.acceptedAt().isBefore(e.issuedAt().plusMillis(e.policy().minDurationMs()))
                    || !e.acceptedAt().isBefore(e.expiresAt())) throw new InvalidEvent();
            return e;
        } catch (Exception ex) {
            // 解析器、时间及业务值校验的错误统一脱敏；坏事件由 relay 持久隔离，绝不当成功。
            throw new InvalidEvent();
        }
    }

    private static void exactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new InvalidEvent();
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new InvalidEvent();
    }

    private static void integer(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()) throw new InvalidEvent();
    }

    /** 固定可观测分类，不输出解析器可能包含凭据的原错误。 */
    public static class InvalidEvent extends RuntimeException {
        public InvalidEvent() { super("自然事件版本、类型或身份非法"); }
    }
}
