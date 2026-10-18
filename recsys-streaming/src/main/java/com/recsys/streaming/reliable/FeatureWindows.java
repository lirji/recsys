package com.recsys.streaming.reliable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.streaming.persistence.ItemCategoryLoader;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.TimeDomain;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import java.util.Map;
import java.util.TreeMap;

/** 单并行度有界事件集合；整窗发布才能删除不再出现的item、用户和类目。 */
public final class FeatureWindows extends KeyedProcessFunction<Integer, FeatureWire.Event, String> {
    private final FeatureSettings settings;
    private transient MapState<String, String> events;
    private transient ValueState<String> identity;
    private transient ValueState<String> categories;
    private transient ValueState<String> latest;
    private transient ValueState<Long> finalized;
    private transient ValueState<Integer> size;
    private transient ValueState<Boolean> ticking;
    private transient ObjectMapper json;
    private transient Counter late;
    private transient Counter duplicate;

    public FeatureWindows(FeatureSettings settings) { this.settings = settings; }

    @Override public void open(Configuration ignored) {
        events = getRuntimeContext().getMapState(new MapStateDescriptor<>("events-v1", String.class, String.class));
        identity = getRuntimeContext().getState(new ValueStateDescriptor<>("identity-v1", String.class));
        categories = getRuntimeContext().getState(new ValueStateDescriptor<>("categories-v1", String.class));
        latest = getRuntimeContext().getState(new ValueStateDescriptor<>("latest-v1", String.class));
        finalized = getRuntimeContext().getState(new ValueStateDescriptor<>("finalized-v1", Long.class));
        size = getRuntimeContext().getState(new ValueStateDescriptor<>("size-v1", Integer.class));
        ticking = getRuntimeContext().getState(new ValueStateDescriptor<>("ticking-v1", Boolean.class));
        json = FeatureWire.mapper();
        late = getRuntimeContext().getMetricGroup().counter("late");
        duplicate = getRuntimeContext().getMetricGroup().counter("duplicate");
    }

    private void initialize() throws Exception {
        if (identity.value() != null) {
            if (!identity.value().equals(settings.fingerprint())) throw new IllegalStateException(FeatureSettings.CONFLICT);
            return;
        }
        // 首次目录读在同一算子内完成并进入checkpoint，恢复不能用新目录解释旧事件。
        Map<Long, String> snapshot = new TreeMap<>() {
            @Override public String put(Long key, String value) {
                if (size() >= FeatureSettings.MAX_CATEGORIES && !containsKey(key)) throw new IllegalStateException(FeatureSettings.CAPACITY);
                return super.put(key, value);
            }
        };
        ItemCategoryLoader.loadInto(settings.pgUrl(), settings.pgUser(), settings.pgPassword(), snapshot);
        categories.update(json.writeValueAsString(snapshot));
        identity.update(settings.fingerprint()); size.update(0);
    }

    @Override public void processElement(FeatureWire.Event event, Context ctx, Collector<String> out) throws Exception {
        initialize();
        if (ticking.value() == null) {
            ctx.timerService().registerProcessingTimeTimer(ctx.timerService().currentProcessingTime() + settings.slideMs());
            ticking.update(true);
        }
        long watermark = Math.max(ctx.timerService().currentWatermark(), finalized.value() == null ? Long.MIN_VALUE : finalized.value() - 1);
        String existing = events.get(event.id);
        if (existing != null) {
            if (!json.readTree(existing).path("digest").asText().equals(event.digest)) throw new IllegalStateException(FeatureSettings.CONFLICT);
            duplicate.inc(); return;
        }
        if (event.ts <= watermark) { late.inc(); return; }
        if (!event.positive) return;
        if (size.value() >= settings.maxEvents()) throw new IllegalStateException(FeatureSettings.CAPACITY);
        events.put(event.id, json.writeValueAsString(event)); size.update(size.value() + 1);
        long first = Math.floorDiv(event.ts, settings.slideMs()) * settings.slideMs() + settings.slideMs();
        // 最后一个空窗负责删除旧成员；watermark停滞时由绝对到期保证不永久残留。
        for (long end = first; end <= first + settings.windowMs(); end += settings.slideMs())
            ctx.timerService().registerEventTimeTimer(end - 1);
    }

    @Override public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
        initialize();
        if (ctx.timeDomain() == TimeDomain.PROCESSING_TIME) {
            if (latest.value() != null) out.collect(latest.value());
            // 长停机后只恢复一次当前心跳，不能补跑每一个错过的处理时间tick。
            ctx.timerService().registerProcessingTimeTimer(ctx.timerService().currentProcessingTime() + settings.slideMs());
            return;
        }
        long end = timestamp + 1;
        Map<String, Double> hot = new TreeMap<>();
        Map<String, Map<String, Long>> users = new TreeMap<>();
        Map<String, String> cats = json.readValue(categories.value(), new TypeReference<>() { });
        int count = size.value();
        Map<String, String> ordered = new TreeMap<>();
        for (var entry : events.entries()) ordered.put(entry.getKey(), entry.getValue());
        for (var entry : ordered.entrySet()) {
            var event = json.readValue(entry.getValue(), FeatureWire.Event.class);
            if (event.ts < end - settings.windowMs()) { events.remove(entry.getKey()); count--; continue; }
            if (event.ts >= end) continue;
            hot.merge(Long.toString(event.itemId), event.weight, Double::sum);
            String category = cats.get(Long.toString(event.itemId));
            if (category != null && !category.isBlank()) users.computeIfAbsent(Long.toString(event.userId), unused -> new TreeMap<>()).merge(category, 1L, Long::sum);
        }
        for (double score : hot.values()) if (!Double.isFinite(score)) throw new IllegalStateException(FeatureSettings.CAPACITY);
        size.update(count); finalized.update(end);
        String payload = json.writeValueAsString(new TreeMap<>(Map.of("end", end, "hot", hot, "users", users)));
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > FeatureSettings.MAX_BYTES) throw new IllegalStateException(FeatureSettings.CAPACITY);
        latest.update(payload); out.collect(payload);
    }
}
