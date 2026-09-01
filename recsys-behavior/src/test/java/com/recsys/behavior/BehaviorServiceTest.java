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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** 点击分桶归因契约:服务端回查的 bucket 必须真正进入持久化事件,供 ab-report/CUPED 使用。 */
class BehaviorServiceTest {

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectProvider<KafkaTemplate<String, String>> kafka = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(jdbc.update(anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BehaviorService service = new BehaviorService(jdbc, kafka, redis, registry);
        return new Fixture(service, jdbc, values, registry);
    }

    @Test
    void clickWithoutBucket_persistsRedisAttributedBucket() {
        Fixture f = fixture();
        String attributed = "recall:plus;rank:onnx;rerank:mmr";
        when(f.values.get(RedisKeys.exposureBucket(7, 42))).thenReturn(attributed);

        f.service.record(new BehaviorEvent(7, 42, ActionType.CLICK, 1.0, "home", null, 1234));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(f.jdbc).update(anyString(), args.capture());
        assertEquals(attributed, args.getValue()[5]);
    }

    @Test
    void clientBucket_isIgnoredAndServerAttributionWins() {
        Fixture f = fixture();
        String clientBucket = "recall:base;rank:v1;rerank:diversity";
        String attributed = "recall:plus;rank:onnx;rerank:mmr";
        when(f.values.get(RedisKeys.exposureBucket(7, 42))).thenReturn(attributed);

        f.service.record(new BehaviorEvent(7, 42, ActionType.LIKE, 1.0, "home", clientBucket, 1234));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(f.jdbc).update(anyString(), args.capture());
        assertEquals(attributed, args.getValue()[5]);
        verify(f.values).get(RedisKeys.exposureBucket(7, 42));
    }

    @Test
    void exactExposureIdIsPersistedAndDuplicateDoesNotDoubleCountMetric() {
        Fixture f = fixture();
        String exposureId = "exp-42";
        String bucket = "recall:plus;rank:onnx;rerank:mmr";
        when(f.values.get(RedisKeys.exposure(exposureId)))
                .thenReturn(new ExposureAttribution(7, 42, bucket).encode());
        when(f.jdbc.update(anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(1, 0);

        BehaviorEvent click = new BehaviorEvent(
                7, 42, ActionType.CLICK, 1.0, "home", "forged", 1234, exposureId);
        f.service.record(click);
        f.service.record(click);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(f.jdbc, times(2)).update(anyString(), args.capture());
        assertEquals(bucket, args.getAllValues().getFirst()[5]);
        assertEquals(exposureId, args.getAllValues().getFirst()[6]);
        assertEquals(1.0, f.registry.get("recsys.click").counter().count());
    }

    @Test
    void unverifiedClientExposureIdIsNotUsedAsGlobalDedupeKey() {
        Fixture f = fixture();

        f.service.record(new BehaviorEvent(
                7, 42, ActionType.CLICK, 1.0, "home", "forged", 1234, "client-fixed-id"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(f.jdbc).update(anyString(), args.capture());
        assertEquals(null, args.getValue()[5]);
        assertEquals(null, args.getValue()[6]);
    }

    private record Fixture(BehaviorService service, JdbcTemplate jdbc,
                           ValueOperations<String, String> values, SimpleMeterRegistry registry) {
    }
}
