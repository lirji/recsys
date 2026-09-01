package com.recsys.behavior;

import com.recsys.common.constant.ActionType;
import com.recsys.common.dto.BehaviorEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PostgreSQL 部分唯一索引在真实并发下保证同曝光 CLICK 只落一行。 */
@Testcontainers(disabledWithoutDocker = true)
class BehaviorServicePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("recsys").withUsername("recsys").withPassword("recsys");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("""
                CREATE TABLE user_behavior (
                    id BIGSERIAL PRIMARY KEY, user_id BIGINT, item_id BIGINT, action TEXT,
                    value DOUBLE PRECISION, scene TEXT, bucket TEXT,
                    ts TIMESTAMP DEFAULT now())
                """);
        // 模拟存量 schema 升级：先 ADD nullable 列，再建 partial unique。
        jdbc.execute("ALTER TABLE user_behavior ADD COLUMN IF NOT EXISTS exposure_id TEXT");
        jdbc.execute("""
                CREATE UNIQUE INDEX uq_behavior_click_exposure ON user_behavior(exposure_id)
                WHERE action='CLICK' AND exposure_id IS NOT NULL
                """);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentClicksAreIdempotentButLikesAreNotCollapsed() throws Exception {
        jdbc.execute("TRUNCATE user_behavior");
        ObjectProvider<KafkaTemplate<String, String>> kafka = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BehaviorService service = new BehaviorService(jdbc, kafka, redis, registry);
        jdbc.update("""
                INSERT INTO user_behavior(user_id,item_id,action,bucket,exposure_id)
                VALUES(7,42,'IMPRESSION','recall:plus;rank:v1','same-exposure')
                """);

        int workers = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    service.record(new BehaviorEvent(
                            7, 42, ActionType.CLICK, 1, "home", null, 1, "same-exposure"));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_behavior WHERE action='CLICK'", Integer.class)).isEqualTo(1);
        assertThat(registry.get("recsys.click").counter().count()).isEqualTo(1.0);

        service.record(new BehaviorEvent(7, 42, ActionType.LIKE, 1, "home", null, 1, "same-exposure"));
        service.record(new BehaviorEvent(7, 42, ActionType.LIKE, 1, "home", null, 1, "same-exposure"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_behavior WHERE action='LIKE'", Integer.class)).isEqualTo(2);
    }
}
