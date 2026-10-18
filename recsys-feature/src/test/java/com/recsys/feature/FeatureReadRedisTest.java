package com.recsys.feature;

import com.recsys.common.constant.RedisKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实Redis验证批量故障没有N次重试，饱和时读资源有上限且写入错误不被吞掉。 */
@Tag("recall-resilience") @Testcontainers
class FeatureReadRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisFeatureService service;
    SimpleMeterRegistry meters;
    @BeforeEach void start() {
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)), client);
        factory.afterPropertiesSet(); factory.start(); redis = spy(new StringRedisTemplate(factory));
        redis.opsForValue().set("probe", "ready");
        var config = new FeatureReadProperties(); config.setThreads(2); config.setTimeoutMs(100); config.setMaxBatchSize(64);
        meters = new SimpleMeterRegistry(); service = new RedisFeatureService(redis, config, meters);
    }
    @AfterEach void close() { service.close(); factory.destroy(); meters.close(); }
    @Test void batchAndSinglesReadActualValuesAndOversizeDoesNotReachRedis() {
        service.writeItemFeatures(1, Map.of("score", 2d)); service.writeUserFeatures(7, Map.of("interest", 3d));
        assertEquals(Map.of("score", 2d), service.itemFeatures(1)); assertEquals(Map.of("interest", 3d), service.userFeatures(7));
        assertEquals(Map.of(1L, Map.of("score", 2d), 2L, Map.of()), service.itemFeatures(List.of(1L, 2L, 1L)));
        clearInvocations(redis);
        assertTrue(service.itemFeatures(java.util.stream.LongStream.rangeClosed(1, 65).boxed().toList()).isEmpty());
        verifyNoInteractions(redis);
    }
    @Test @SuppressWarnings("unchecked") void failedPipelineIsBoundedAndNeverRetriesEveryItem() throws Exception {
        var calls = new AtomicInteger(); var active = new AtomicInteger(); var peak = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet(); peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { return invocation.callRealMethod(); } finally { active.decrementAndGet(); }
        }).when(redis).executePipelined(any(RedisCallback.class));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        var callers = Executors.newFixedThreadPool(12);
        try {
            var ids = java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList();
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < 24; i++) results.add(callers.submit(() -> {
                long start = System.nanoTime(); assertTrue(service.itemFeatures(ids).isEmpty()); return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            }));
            for (var result : results) assertTrue(result.get(2, TimeUnit.SECONDS) < 350);
            assertTrue(calls.get() > 0); assertTrue(calls.get() <= 24); assertTrue(peak.get() <= 2);
            verify(redis, never()).opsForHash();
            org.slf4j.LoggerFactory.getLogger(getClass()).info("C20 feature Redis paused requests=24 batchSize=50 pipelines={} peakIo={}", calls.get(), peak.get());
        } finally { callers.shutdownNow(); REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        redis.opsForHash().put(RedisKeys.itemFeature(1), "score", "7");
        while (service.itemFeatures(1).isEmpty() && System.nanoTime() < end) Thread.sleep(20);
        assertEquals(Map.of("score", 7d), service.itemFeatures(1));
    }
    @Test void writeFailureStillPropagatesAndClosedReadPoolDoesNotExecute() {
        redis.opsForValue().set(RedisKeys.itemFeature(9), "wrong-type");
        assertThrows(RuntimeException.class, () -> service.writeItemFeatures(9, Map.of("x", 1d)));
        service.close(); clearInvocations(redis); assertTrue(service.itemFeatures(1).isEmpty()); verifyNoInteractions(redis);
    }
}
