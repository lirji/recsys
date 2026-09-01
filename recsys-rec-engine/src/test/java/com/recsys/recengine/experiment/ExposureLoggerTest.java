package com.recsys.recengine.experiment;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.RecommendItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis 精确归因必须在 log 返回前可见，避免快速点击竞态。 */
class ExposureLoggerTest {

    @Test
    @SuppressWarnings("unchecked")
    void writesExactAttributionSynchronously() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ExposureLogger logger = new ExposureLogger(jdbc, redis, new SimpleMeterRegistry());

        logger.log(7, "home", "recall:plus;rank:v1",
                List.of(new RecommendItem(42, 1, List.of("HOT"), "reason", "exp-42")));

        verify(values).set(eq(RedisKeys.latestExposure(7, 42)), eq("exp-42"), any(Duration.class));
        verify(values).set(eq(RedisKeys.exposure("exp-42")), any(String.class), any(Duration.class));
        logger.shutdown();
    }
}
