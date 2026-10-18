package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.user.UserProfileReader;
import com.recsys.recall.RecallProperties;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 新旧实时投影读取显式选择；默认兼容与过期后的现有降级路径一并验证。 */
@Testcontainers
class RealtimeProjectionRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    @BeforeAll static void start() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
    }
    @AfterAll static void close() { factory.destroy(); }
    @Test void hotOptInUsesOnlySelectedSnapshotAndMissingSnapshotFallsBackToOffline() {
        redis.opsForZSet().add(RedisKeys.RT_HOT_RECALL, "1", 1);
        redis.opsForZSet().add(RedisKeys.realtimeHot("selected"), "2", 2);
        redis.opsForZSet().add(RedisKeys.HOT_RECALL, "3", 3);
        var catalog = mock(ItemCatalogReader.class); var props = new RecallProperties(); var ctx = new RecallContext(1, 10, "test");
        assertEquals(1, new HotRecaller(redis, catalog, props).recall(ctx).getFirst().itemId());
        var selected = new HotRecaller(redis, catalog, props, "selected"); assertEquals(2, selected.recall(ctx).getFirst().itemId());
        redis.delete(RedisKeys.realtimeHot("selected")); assertEquals(3, selected.recall(ctx).getFirst().itemId());
        verifyNoInteractions(catalog);
    }
    @Test void tagOptInDoesNotMixOldRealtimeCategories() {
        redis.opsForHash().put(RedisKeys.rtUser(1), "old", "1");
        redis.opsForHash().put(RedisKeys.realtimeUser("selected", 1), "new", "2");
        var catalog = mock(ItemCatalogReader.class); var profile = mock(UserProfileReader.class);
        var selected = new TagRecaller(catalog, redis, new RecallProperties(), profile, "selected");
        selected.recall(new RecallContext(1, 10, "test"));
        verify(catalog).byCategories(eq(List.of("new")), anyInt());
    }
}
