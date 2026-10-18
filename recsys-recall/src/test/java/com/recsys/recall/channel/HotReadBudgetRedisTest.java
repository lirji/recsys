package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.recall.*;
import com.recsys.recall.RecallProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实Redis暂停+并发热点证明DB单飞，不以Future取消冒充数据库已退出。 */
@Tag("recall-resilience") @Testcontainers
class HotReadBudgetRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Test void redisFailureMergesDatabaseLoadsAndResumesNormalTraffic() throws Exception {
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)), client);
        factory.afterPropertiesSet(); factory.start(); var redis = new StringRedisTemplate(factory);
        redis.opsForZSet().add(RedisKeys.RT_HOT_RECALL, "11", 5);
        var properties = new RecallProperties(); properties.getCacheBudget().setRedisWaitMs(1000); properties.getCacheBudget().setDatabaseWaitMs(30);
        var catalog = mock(ItemCatalogReader.class); var loads = new AtomicInteger(); var active = new AtomicInteger(); var peak = new AtomicInteger();
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        when(catalog.hotByPopularity(anyInt())).thenAnswer(invocation -> {
            loads.incrementAndGet(); peak.accumulateAndGet(active.incrementAndGet(), Math::max); entered.countDown();
            try {
                boolean interrupted = false;
                while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
                return List.of(new ItemCatalogReader.ScoredId(22, 3));
            } finally { active.decrementAndGet(); }
        });
        var meters = new SimpleMeterRegistry(); var hot = new HotRecaller(redis, catalog, properties, "", meters);
        var callers = Executors.newFixedThreadPool(16);
        try {
            assertEquals(11, hot.recall(new RecallContext(1, 10, "normal")).getFirst().itemId());
            // 先以启动预算完成真实读/类加载，再测30ms故障预算；不把CI首次类加载当作Redis不可用。
            properties.getCacheBudget().setRedisWaitMs(30);
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                var latencies = new ArrayList<Future<Long>>();
                for (int i = 0; i < 32; i++) latencies.add(callers.submit(() -> {
                    long start = System.nanoTime(); hot.recall(new RecallContext(1, 10, "hotspot")); return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (var future : latencies) assertTrue(future.get(2, TimeUnit.SECONDS) < 350);
                assertEquals(1, loads.get()); assertEquals(1, peak.get()); assertEquals(1, active.get());
                release.countDown();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (active.get() > 0 && System.nanoTime() < end) Thread.sleep(5);
                for (int user = 2; user <= 21; user++) assertEquals(22, hot.recall(new RecallContext(user, 10, "normal")).getFirst().itemId());
                assertEquals(1, loads.get());
                org.slf4j.LoggerFactory.getLogger(getClass()).info("C20 real-Redis outage requests=32 normal=20 dbLoads={} peakDb={}", loads.get(), peak.get());
            } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); boolean recovered = false;
            while (System.nanoTime() < end) {
                var results = hot.recall(new RecallContext(2, 10, "recover"));
                if (!results.isEmpty() && results.getFirst().itemId() == 11) { recovered = true; break; }
                Thread.sleep(30);
            }
            assertTrue(recovered);
        } finally { release.countDown(); callers.shutdownNow(); hot.close(); factory.destroy(); meters.close(); }
    }
}
