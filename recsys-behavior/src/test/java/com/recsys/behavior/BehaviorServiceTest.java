package com.recsys.behavior;

import com.recsys.common.constant.ActionType;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.BehaviorEvent;
import com.recsys.common.experiment.ExposureAttribution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.recsys.behavior.persistence.mapper.primary.BehaviorMapper;
import com.recsys.behavior.persistence.model.BehaviorWrite;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** 点击分桶归因契约:服务端回查的 bucket 必须真正进入持久化事件,供 ab-report/CUPED 使用。 */
class BehaviorServiceTest {

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        BehaviorMapper behavior = mock(BehaviorMapper.class);
        ObjectProvider<KafkaTemplate<String, String>> kafka = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(behavior.insert(org.mockito.ArgumentMatchers.any(BehaviorWrite.class))).thenReturn(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BehaviorService service = new BehaviorService(behavior, kafka, redis, registry);
        return new Fixture(service, behavior, values, registry);
    }

    @Test
    void clickWithoutBucket_persistsRedisAttributedBucket() {
        Fixture f = fixture();
        String attributed = "recall:plus;rank:onnx;rerank:mmr";
        when(f.values.get(RedisKeys.exposureBucket(7, 42))).thenReturn(attributed);

        f.service.record(new BehaviorEvent(7, 42, ActionType.CLICK, 1.0, "home", null, 1234));

        ArgumentCaptor<BehaviorWrite> args = ArgumentCaptor.forClass(BehaviorWrite.class);
        verify(f.behavior).insert(args.capture());
        assertEquals(attributed, args.getValue().bucket());
    }

    @Test
    void clientBucket_isIgnoredAndServerAttributionWins() {
        Fixture f = fixture();
        String clientBucket = "recall:base;rank:v1;rerank:diversity";
        String attributed = "recall:plus;rank:onnx;rerank:mmr";
        when(f.values.get(RedisKeys.exposureBucket(7, 42))).thenReturn(attributed);

        f.service.record(new BehaviorEvent(7, 42, ActionType.LIKE, 1.0, "home", clientBucket, 1234));

        ArgumentCaptor<BehaviorWrite> args = ArgumentCaptor.forClass(BehaviorWrite.class);
        verify(f.behavior).insert(args.capture());
        assertEquals(attributed, args.getValue().bucket());
        verify(f.values).get(RedisKeys.exposureBucket(7, 42));
    }

    @Test
    void exactExposureIdIsPersistedAndDuplicateDoesNotDoubleCountMetric() {
        Fixture f = fixture();
        String exposureId = "exp-42";
        String bucket = "recall:plus;rank:onnx;rerank:mmr";
        when(f.values.get(RedisKeys.exposure(exposureId)))
                .thenReturn(new ExposureAttribution(7, 42, bucket).encode());
        when(f.behavior.insert(org.mockito.ArgumentMatchers.any(BehaviorWrite.class))).thenReturn(1, 0);

        BehaviorEvent click = new BehaviorEvent(
                7, 42, ActionType.CLICK, 1.0, "home", "forged", 1234, exposureId);
        f.service.record(click);
        f.service.record(click);

        ArgumentCaptor<BehaviorWrite> args = ArgumentCaptor.forClass(BehaviorWrite.class);
        verify(f.behavior, times(2)).insert(args.capture());
        assertEquals(bucket, args.getAllValues().getFirst().bucket());
        assertEquals(exposureId, args.getAllValues().getFirst().exposureId());
        assertEquals(1.0, f.registry.get("recsys.click").counter().count());
    }

    @Test
    void unverifiedClientExposureIdIsNotUsedAsGlobalDedupeKey() {
        Fixture f = fixture();

        f.service.record(new BehaviorEvent(
                7, 42, ActionType.CLICK, 1.0, "home", "forged", 1234, "client-fixed-id"));

        ArgumentCaptor<BehaviorWrite> args = ArgumentCaptor.forClass(BehaviorWrite.class);
        verify(f.behavior).insert(args.capture());
        assertEquals(null, args.getValue().bucket());
        assertEquals(null, args.getValue().exposureId());
    }

    private record Fixture(BehaviorService service, BehaviorMapper behavior,
                           ValueOperations<String, String> values, SimpleMeterRegistry registry) {
    }
}
