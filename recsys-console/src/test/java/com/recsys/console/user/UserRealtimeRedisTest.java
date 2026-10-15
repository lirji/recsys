package com.recsys.console.user;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.console.persistence.mapper.primary.UserProfileMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独占Redis验证损坏值、无数据、暂停超时与恢复；不会读写共享dev_infra。 */
@Testcontainers
class UserRealtimeRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    UserProfileService service;

    @BeforeAll static void connect() {
        var standalone = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).shutdownTimeout(Duration.ZERO).build();
        factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
    }
    @AfterAll static void close() { if (factory != null) factory.destroy(); }
    @BeforeEach void prepare() {
        try (var connection = factory.getConnection()) { connection.serverCommands().flushDb(); }
        var mapper = mock(UserProfileMapper.class);
        when(mapper.profile(7)).thenReturn(new UserProfileMapper.Profile(7, "{}", null));
        when(mapper.buckets(7)).thenReturn(List.of()); when(mapper.recent(7, 50)).thenReturn(List.of());
        service = new UserProfileService(mapper, redis);
    }

    @Test void missingKeysAndValidValuesAreRealDataNotErrors() {
        var empty = service.get(7);
        assertTrue(empty.realtime().available());
        assertEquals(0L, empty.realtime().seenCount()); assertEquals(Boolean.FALSE, empty.realtime().recCached());
        assertTrue(empty.errors().isEmpty());
        redis.opsForHash().put(RedisKeys.rtUser(7), "Drama", "3.75");
        redis.opsForZSet().add(RedisKeys.userSeq(7), "42", 1);
        redis.opsForSet().add(RedisKeys.seenItems(7), "a", "b");
        redis.opsForValue().set(RedisKeys.recCache(7), "cached");
        var view = service.get(7);
        assertTrue(view.realtime().available());
        assertEquals(3L, view.realtime().rtCategoryPrefs().get("Drama"));
        assertEquals(List.of(42L), view.realtime().recentSeqItems());
        assertEquals(2L, view.realtime().seenCount()); assertEquals(Boolean.TRUE, view.realtime().recCached());
    }

    @ParameterizedTest
    @ValueSource(strings = {"private SQL token", "NaN", "Infinity", "-1", "9223372036854775808", "1e1000000000"})
    void malformedOrOutOfRangeCountsRemainUnknownUntilRepaired(String value) {
        redis.opsForHash().put(RedisKeys.rtUser(7), "Drama", value);
        assertUnknown(service.get(7));
        redis.opsForHash().put(RedisKeys.rtUser(7), "Drama", "2.5");
        var repaired = service.get(7);
        assertTrue(repaired.realtime().available());
        assertEquals(2L, repaired.realtime().rtCategoryPrefs().get("Drama"));
    }

    @Test void malformedSequenceDoesNotBecomeAnEmptySuccessfulSequence() {
        redis.opsForZSet().add(RedisKeys.userSeq(7), "private malformed member", 1);
        assertUnknown(service.get(7));
        redis.delete(RedisKeys.userSeq(7));
        assertTrue(service.get(7).realtime().available());
    }

    @Test void redisPauseTimeoutKeepsDatabaseSectionsAndRecovers() throws Exception {
        var result = REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "1200", "ALL");
        assertEquals(0, result.getExitCode());
        assertUnknown(service.get(7));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        UserProfileView latest;
        do {
            Thread.sleep(100);
            latest = service.get(7);
        } while (!latest.realtime().available() && System.nanoTime() < deadline);
        assertTrue(latest.realtime().available(), "暂停结束后真实连接恢复，错误标记不能残留");
        assertFalse(latest.errors().containsKey(UserProfileView.Section.REALTIME));
    }

    private static void assertUnknown(UserProfileView view) {
        assertEquals(Boolean.TRUE, view.exists()); assertEquals("{}", view.profileJson());
        assertFalse(view.realtime().available());
        assertNull(view.realtime().seenCount()); assertNull(view.realtime().recCached());
        assertTrue(view.realtime().rtCategoryPrefs().isEmpty()); assertTrue(view.realtime().recentSeqItems().isEmpty());
        assertEquals(ErrorCode.CONSOLE_USER_REALTIME_UNAVAILABLE.code(), view.errors().get(UserProfileView.Section.REALTIME));
        assertFalse(view.toString().contains("private"));
    }
}
