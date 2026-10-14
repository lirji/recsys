package com.recsys.advertiser;

import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.dto.AdvertiserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 保护 Redis 分计数到 API 元的边界，并区分已知零消费与无法确认消费。 */
class AdvertiserBudgetViewTest {
    private final AdvertiserRepository repo = mock(AdvertiserRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AdvertiserService service = new AdvertiserService(
            repo, mock(AdIndexSyncService.class), redis, mock(AdvertiserAuthz.class));

    private AdvertiserView read(String counter) {
        when(repo.findAdvertiserRaw(1)).thenReturn(new AdvertiserView(1, "测试账户", 100, "active", null, null));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(counter);
        return service.getAdvertiser(1);
    }

    @Test
    void convertsIntegerCentsToYuan() {
        AdvertiserView view = read("125");
        assertThat(view.spentToday()).isEqualTo(1.25);
        assertThat(view.remainingBudget()).isEqualTo(98.75);
    }

    @Test
    void retainsKnownZeroAndClampsOverspendRemainder() {
        assertThat(read("0").spentToday()).isZero();
        assertThat(read("10001").remainingBudget()).isZero();
    }

    @Test
    void missingCounterDoesNotInventZeroSpend() {
        AdvertiserView view = read(null);
        assertThat(view.spentToday()).isNull();
        assertThat(view.remainingBudget()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "1.25", "-1", "not-a-number", "9223372036854775808"})
    void invalidCounterRemainsUnknown(String counter) {
        AdvertiserView view = read(counter);
        assertThat(view.spentToday()).isNull();
        assertThat(view.remainingBudget()).isNull();
    }

    @Test
    void redisFailureDoesNotAdvertiseAvailableBudget() {
        read("125");
        when(values.get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        AdvertiserView view = service.getAdvertiser(1);
        assertThat(view.spentToday()).isNull();
        assertThat(view.remainingBudget()).isNull();
    }
}
