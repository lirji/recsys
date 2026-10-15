package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.common.job.JobState;
import com.recsys.common.job.JobStatusRecord;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.recsys.offline.JobRunnerErrorTest.*;

/** 独占Redis验证状态发布/锁/超时，不把mock发送成功当作真实状态写入。 */
@Testcontainers
class DagJobRedisTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    @BeforeAll static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
    }
    @BeforeEach void reset() { try (var c = factory.getConnection()) { c.serverCommands().flushDb(); } }
    @AfterAll static void close() { if (factory != null) factory.destroy(); }

    @Test void successfulDagRecordsSafeStatesAndReleasesLock() throws Exception {
        var calls = new AtomicInteger();
        dag(redis, job("first", calls::incrementAndGet), job("later", calls::incrementAndGet))
                .run(args("--targets=first,later"));
        assertEquals(2, calls.get());
        assertEquals(JobState.OK, JobStatusRecord.parse(redis.opsForValue().get(RedisKeys.jobStatus("first"))).state());
        assertEquals(JobState.OK, JobStatusRecord.parse(redis.opsForValue().get(RedisKeys.jobStatus("later"))).state());
        assertFalse(Boolean.TRUE.equals(redis.hasKey("job:dag:lock")));
    }
    @Test void failedJobIsPropagatedAndDownstreamIsSkippedWithoutPrivateText() {
        var later = new AtomicInteger();
        var work = dag(redis, job("first", () -> { throw new IllegalStateException("private SQL token"); }), job("later", later::incrementAndGet));
        var failure = assertThrows(PublicErrorException.class, () -> work.run(args("--targets=first,later")));
        assertEquals(ErrorCode.JOB_EXECUTION_FAILED, failure.errorCode()); assertEquals(0, later.get());
        String raw = redis.opsForValue().get(RedisKeys.jobStatus("first"));
        assertFalse(raw.contains("private")); assertEquals(JobState.FAIL, JobStatusRecord.parse(raw).state());
        var skipped = JobStatusRecord.parse(redis.opsForValue().get(RedisKeys.jobStatus("later")));
        assertEquals(JobState.SKIPPED, skipped.state()); assertEquals(ErrorCode.JOB_DEPENDENCY_FAILED, skipped.errorCode());
    }
    @Test void occupiedOrMissingLockPreventsWorkAndDoesNotDeleteAnotherLock() {
        var calls = new AtomicInteger();
        redis.opsForValue().set("job:dag:lock", "other owner");
        var work = dag(redis, job("first", calls::incrementAndGet));
        assertEquals(ErrorCode.JOB_ALREADY_RUNNING, assertThrows(PublicErrorException.class, () -> work.run(args("--targets=first"))).errorCode());
        assertEquals("other owner", redis.opsForValue().get("job:dag:lock"));
        assertEquals(ErrorCode.JOB_LOCK_UNAVAILABLE, assertThrows(PublicErrorException.class,
                () -> dag(null, job("first", calls::incrementAndGet)).run(args("--targets=first"))).errorCode());
        assertEquals(0, calls.get());
    }
    @Test void invalidPlanAndDryRunDoNotTouchRedisOrExecuteJobs() throws Exception {
        var calls = new AtomicInteger(); var work = dag(null, job("first", calls::incrementAndGet));
        work.run(args("--targets=first", "--dry-run"));
        for (String input : List.of("--targets", "--pipeline=private", "--targets=,")) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class, () -> work.run(args(input))).errorCode());
        }
        assertEquals(ErrorCode.JOB_UNKNOWN, assertThrows(PublicErrorException.class,
                () -> work.run(args("--targets=private", "--dry-run"))).errorCode());
        assertEquals(0, calls.get());
    }
    @Test void lockTimeoutIsFailureBeforeAnyWork() throws Exception {
        var calls = new AtomicInteger(); pause();
        try {
            assertEquals(ErrorCode.JOB_LOCK_UNAVAILABLE, assertThrows(PublicErrorException.class,
                    () -> dag(redis, job("first", calls::incrementAndGet)).run(args("--targets=first"))).errorCode());
            assertEquals(0, calls.get());
        } finally { awaitRedis(); }
    }
    @Test void statusTimeoutDoesNotUndoEffectsOrRunDownstream() throws Exception {
        var executed = new AtomicInteger(); var later = new AtomicInteger();
        var work = dag(redis, job("first", () -> { executed.incrementAndGet(); pause(); }), job("later", later::incrementAndGet));
        try {
            assertEquals(ErrorCode.JOB_STATUS_WRITE_FAILED, assertThrows(PublicErrorException.class,
                    () -> work.run(args("--targets=first,later"))).errorCode());
            assertEquals(1, executed.get()); assertEquals(0, later.get());
            // 超时写入可能稍后落库，不能断言没有状态或业务效果。
        } finally { awaitRedis(); }
    }
    private static void pause() {
        try { assertEquals(0, REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "1200", "ALL").getExitCode()); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void awaitRedis() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            try { redis.hasKey("health"); return; }
            catch (RuntimeException waiting) { Thread.sleep(100); }
        } while (System.nanoTime() < deadline);
        fail("独占Redis暂停结束后应恢复");
    }
    @SuppressWarnings("unchecked")
    private static DagJob dag(StringRedisTemplate template, OfflineJob... jobs) {
        ObjectProvider<List<OfflineJob>> jp = mock(ObjectProvider.class);
        ObjectProvider<StringRedisTemplate> rp = mock(ObjectProvider.class);
        when(jp.getObject()).thenReturn(List.of(jobs)); when(rp.getIfAvailable()).thenReturn(template);
        return new DagJob(jp, rp);
    }
}
