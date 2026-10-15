package com.recsys.console.system;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.job.JobState;
import com.recsys.common.job.JobStatusRecord;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独占Redis验证旧/新状态公开出口与真实超时，禁止共享环境故障注入。 */
@Testcontainers
class SystemOpsRedisTest {
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

    @Test void realEmptyIsAvailableAndTuningValuesArePreserved() {
        var empty = new SystemOpsService(redis).snapshot();
        assertThat(empty.redisAvailable()).isTrue(); assertThat(empty.jobs()).isEmpty(); assertThat(empty.code()).isNull();
        redis.opsForHash().put(RedisKeys.TUNING, "recallSize", "200");
        assertThat(new SystemOpsService(redis).snapshot().tuning()).containsEntry("recallSize", "200");
    }
    @Test void historicalSecretsAndMalformedRecordsAreFilteredAtHttpBoundary() throws Exception {
        redis.opsForValue().set(RedisKeys.jobStatus("bad"), "fail@2026-10-16 05:00:00:private jdbc:postgresql SQL token");
        redis.opsForValue().set(RedisKeys.jobStatus("broken"), "private@malformed");
        redis.opsForValue().set(RedisKeys.jobStatus("new"), JobStatusRecord.now(JobState.FAIL, ErrorCode.JOB_STATUS_WRITE_FAILED).encoded());
        var mvc = MockMvcBuilders.standaloneSetup(new SystemController(null, null, null, new SystemOpsService(redis))).build();
        String body = mvc.perform(get("/api/console/system/ops")).andExpect(status().isOk())
                .andExpect(jsonPath("$.redisAvailable").value(true))
                .andExpect(jsonPath("$.jobs[0].status").value("fail"))
                .andExpect(jsonPath("$.jobs[0].code").value("JOB_EXECUTION_FAILED"))
                .andExpect(jsonPath("$.jobs[1].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.jobs[2].code").value("JOB_STATUS_WRITE_FAILED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("private", "jdbc:", "SQL", "malformed");
    }
    @Test void timeoutReturnsUnknownAndRecoversToActualEmpty() throws Exception {
        assertThat(REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "1200", "ALL").getExitCode()).isZero();
        var service = new SystemOpsService(redis);
        try {
            var unavailable = service.snapshot();
            assertThat(unavailable.redisAvailable()).isFalse();
            assertThat(unavailable.code()).isEqualTo("CONSOLE_OPS_UNAVAILABLE");
            assertThat(unavailable.message()).isEqualTo(ErrorCode.CONSOLE_OPS_UNAVAILABLE.message());
            assertThat(unavailable.toString()).doesNotContain("RedisCommand", "localhost", "Exception");
        } finally {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!service.snapshot().redisAvailable() && System.nanoTime() < deadline) Thread.sleep(100);
        }
        var recovered = service.snapshot();
        assertThat(recovered.redisAvailable()).isTrue(); assertThat(recovered.code()).isNull();
        assertThat(recovered.jobs()).isEmpty();
    }
}
