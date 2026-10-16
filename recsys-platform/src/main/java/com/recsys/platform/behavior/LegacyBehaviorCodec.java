package com.recsys.platform.behavior;

import com.recsys.common.behavior.LegacyBehaviorAccepted;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.recsys.common.behavior.LegacyBehaviorAccepted.*;
import com.recsys.common.constant.ActionType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/** 严格wire边界；发送原字节，不把历史TIMESTAMP伪装为UTC，也不接受宽松类型转换。 */
public class LegacyBehaviorCodec {
    public static final int MAX_BYTES = 32768;
    private static final Set<String> ROOT = Set.of("eventType","schemaVersion","sourceId","behaviorId","eventId",
            "origin","priorPublication","capturedAt","behavior","sourceTsLocal");
    private static final Set<String> BODY = Set.of("userId","itemId","action","value","scene","bucket","exposureId","ts");
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 只处理已受SQL长度上限保护的源快照；过大源必须在DB内保存并隔离。 */
    public String capture(String sourceId, String snapshot, String capturedAt, Origin origin, Long normalizedTs) {
        try {
            if (snapshot == null) throw new InvalidEvent();
            JsonNode s = json.readTree(snapshot);
            for (String field : List.of("id","user_id","item_id")) {
                if (!s.path(field).isIntegralNumber() || !s.path(field).canConvertToLong()) throw new InvalidEvent();
                positive(s.path(field).asText());
            }
            if (!s.path("value").isNumber() || !Double.isFinite(s.path("value").doubleValue()) || !s.path("action").isTextual()) throw new InvalidEvent();
            var b = new Behavior(s.path("user_id").asText(), s.path("item_id").asText(), ActionType.valueOf(s.path("action").textValue()),
                    s.path("value").doubleValue(), nullable(s,"scene"), nullable(s,"bucket"), nullable(s,"exposure_id"),
                    origin == Origin.BEHAVIOR_POST ? normalizedTs : null);
            var event = new LegacyBehaviorAccepted(LegacyBehaviorAccepted.TYPE, LegacyBehaviorAccepted.VERSION, sourceId,
                    s.path("id").asText(), LegacyBehaviorAccepted.identity(sourceId, s.path("id").asText()), origin,
                    origin == Origin.BEHAVIOR_POST ? PriorPublication.NOT_ATTEMPTED : PriorPublication.UNKNOWN,
                    capturedAt, b, origin == Origin.BEHAVIOR_POST ? null : nullable(s,"ts"));
            return encode(event);
        } catch (InvalidEvent ex) { throw ex; }
        catch (JsonProcessingException | IllegalArgumentException ex) { throw new InvalidEvent(); }
    }

    /** 序列化设施异常必须使接受事务失败；仅可分类的非法数据由调用方持久隔离。 */
    public String encode(LegacyBehaviorAccepted event) {
        final String payload;
        try { payload = json.writeValueAsString(event); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("行为快照序列化失败"); }
        decode(event.behavior().userId(), payload);
        return payload;
    }

    /** 编码后提取原分区键仍通过严格解码，避免不同调用方另造键规则。 */
    public String partitionKey(String payload) {
        try {
            String key=json.readTree(payload).path("behavior").path("userId").textValue();
            return decode(key,payload).behavior().userId();
        } catch (JsonProcessingException ex) { throw new InvalidEvent(); }
    }

    /** 严格校验键、来源、时间及身份；未知版本和重复字段均拒绝。 */
    public LegacyBehaviorAccepted decode(String key, String payload) {
        try {
            if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new InvalidEvent();
            JsonNode n = json.readTree(payload), b = n.path("behavior");
            exact(n,ROOT); exact(b,BODY);
            for (String f : List.of("eventType","sourceId","behaviorId","eventId","origin","priorPublication","capturedAt")) text(n,f);
            for (String f : List.of("userId","itemId","action")) text(b,f);
            for (String f : List.of("scene","bucket","exposureId")) nullable(b,f);
            nullable(n,"sourceTsLocal");
            if (!n.path("schemaVersion").isIntegralNumber() || !n.path("schemaVersion").canConvertToInt()
                    || !b.path("value").isNumber() || !Double.isFinite(b.path("value").doubleValue())
                    || !(b.path("ts").isNull() || (b.path("ts").isIntegralNumber() && b.path("ts").canConvertToLong()))) throw new InvalidEvent();
            var e = json.treeToValue(n,LegacyBehaviorAccepted.class);
            if (!LegacyBehaviorAccepted.TYPE.equals(e.eventType()) || e.schemaVersion()!=1
                    || !UUID.fromString(e.sourceId()).toString().equals(e.sourceId())
                    || !LegacyBehaviorAccepted.identity(e.sourceId(),e.behaviorId()).equals(e.eventId())) throw new InvalidEvent();
            positive(e.behaviorId()); positive(e.behavior().userId()); positive(e.behavior().itemId());
            if (!e.behavior().userId().equals(key) || e.behavior().action()==null) throw new InvalidEvent();
            if (!e.capturedAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z")) throw new InvalidEvent();
            Instant.parse(e.capturedAt());
            if (e.origin()==Origin.BEHAVIOR_POST) {
                if (e.priorPublication()!=PriorPublication.NOT_ATTEMPTED || e.sourceTsLocal()!=null
                        || e.behavior().ts()==null || e.behavior().ts()<=0) throw new InvalidEvent();
            } else if (e.origin()==Origin.LEGACY_ROW) {
                if (e.priorPublication()!=PriorPublication.UNKNOWN || e.behavior().ts()!=null) throw new InvalidEvent();
                if (e.sourceTsLocal()!=null) {
                    if (!e.sourceTsLocal().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}")) throw new InvalidEvent();
                    LocalDateTime.parse(e.sourceTsLocal());
                }
            } else throw new InvalidEvent();
            return e;
        } catch (Exception ex) { throw new InvalidEvent(); }
    }
    private static void positive(String id) {
        long value=Long.parseLong(id);
        if (value<=0 || !Long.toString(value).equals(id)) throw new InvalidEvent();
    }
    private static void exact(JsonNode n, Set<String> fields) {
        if (!n.isObject()) throw new InvalidEvent();
        var actual = new HashSet<String>(); n.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw new InvalidEvent();
    }
    private static String text(JsonNode n,String f) { if (!n.path(f).isTextual()) throw new InvalidEvent(); return n.path(f).textValue(); }
    private static String nullable(JsonNode n,String f) { return n.path(f).isNull() ? null : text(n,f); }
    /** 脱敏闭合错误；不保留解析器可能包含原负载的异常cause。 */
    public static class InvalidEvent extends RuntimeException {
        public InvalidEvent() { super("行为事件版本、来源或数据非法"); }
    }
}
