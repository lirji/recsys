package com.recsys.streaming.reliable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.common.constant.ActionType;
import com.recsys.platform.behavior.LegacyBehaviorCodec;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

/** 完整消费坐标参与旧流身份；新版只使用既有严格codec，不另造事件语言。 */
public final class FeatureWire implements KafkaRecordDeserializationSchema<FeatureWire.Event> {
    private final FeatureSettings settings;
    public FeatureWire(FeatureSettings settings) { this.settings = settings; }

    /** Flink POJO，避免Map/record的Kryo反射在不同JDK下改变恢复格式。 */
    public static class Event {
        public String id;
        public String digest;
        public long userId;
        public long itemId;
        public long ts;
        public double weight;
        public boolean positive;
    }

    @Override public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<Event> out) {
        out.collect(decode(record));
    }

    /** 无法解释的记录使作业失败，offset不越过坏记录；不把解析异常的原文写日志。 */
    public Event decode(ConsumerRecord<byte[], byte[]> record) {
        try {
            if (record.value() == null || record.value().length > LegacyBehaviorCodec.MAX_BYTES
                    || (record.key() != null && record.key().length > 1024)) throw new IllegalArgumentException();
            var utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String payload = utf8.decode(ByteBuffer.wrap(record.value())).toString();
            Event event = new Event();
            ActionType action;
            double value;
            long accepted;
            if (settings.live()) {
                if (record.key() == null) throw new IllegalArgumentException();
                var source = new LegacyBehaviorCodec().decode(utf8.decode(ByteBuffer.wrap(record.key())).toString(), payload);
                if (source.origin() != LegacyBehaviorAccepted.Origin.BEHAVIOR_POST
                        || !settings.sourceId().equals(source.sourceId())) throw new IllegalArgumentException();
                event.id = source.eventId(); event.userId = Long.parseLong(source.behavior().userId());
                event.itemId = Long.parseLong(source.behavior().itemId()); event.ts = source.behavior().ts();
                action = source.behavior().action(); value = source.behavior().value();
                accepted = Instant.parse(source.capturedAt()).toEpochMilli();
            } else {
                var json = mapper().readTree(payload);
                var fields = Set.of("userId", "itemId", "action", "value", "scene", "bucket", "ts", "exposureId");
                var names = json.fieldNames();
                while (names.hasNext()) if (!fields.contains(names.next())) throw new IllegalArgumentException();
                for (String field : new String[]{"userId", "itemId", "ts"})
                    if (!json.path(field).isIntegralNumber() || !json.path(field).canConvertToLong()
                            || json.path(field).longValue() <= 0) throw new IllegalArgumentException();
                if (!json.path("value").isNumber() || !json.path("action").isTextual()) throw new IllegalArgumentException();
                event.id = record.topic() + ":" + record.partition() + ":" + record.offset();
                event.userId = json.path("userId").longValue(); event.itemId = json.path("itemId").longValue();
                event.ts = json.path("ts").longValue(); value = json.path("value").doubleValue();
                action = ActionType.valueOf(json.path("action").textValue()); accepted = record.timestamp();
            }
            if (!Double.isFinite(value) || accepted <= 0 || accepted > 253402300799999L
                    || event.ts > accepted + FeatureSettings.FUTURE_SKEW_MS)
                throw new IllegalArgumentException();
            event.positive = action != ActionType.IMPRESSION;
            event.weight = action == ActionType.LIKE ? 2 : action == ActionType.RATING && value > 0 ? value : 1;
            event.digest = FeatureSettings.digest(payload);
            return event;
        } catch (Exception invalid) { throw new IllegalArgumentException(FeatureSettings.INVALID); }
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    @Override public TypeInformation<Event> getProducedType() { return TypeInformation.of(Event.class); }
}
