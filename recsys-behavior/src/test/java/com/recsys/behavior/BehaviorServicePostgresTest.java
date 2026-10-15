package com.recsys.behavior;

import com.recsys.common.constant.ActionType;
import com.recsys.common.dto.BehaviorEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.recsys.behavior.persistence.config.BehaviorPersistenceConfiguration;
import com.recsys.behavior.persistence.mapper.primary.BehaviorMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
    static AnnotationConfigApplicationContext context;

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
        jdbc.execute("COMMENT ON TABLE user_behavior IS '行为迁移隔离测试表'");
        for (String column : List.of("id","user_id","item_id","action","value","scene","bucket","ts"))
            jdbc.execute("COMMENT ON COLUMN user_behavior." + column + " IS '行为测试字段'");
        // 模拟存量 schema 升级：先 ADD nullable 列，再建 partial unique。
        jdbc.execute("ALTER TABLE user_behavior ADD COLUMN IF NOT EXISTS exposure_id TEXT");
        jdbc.execute("COMMENT ON COLUMN user_behavior.exposure_id IS '服务端校验过的曝光标识'");
        jdbc.execute("""
                CREATE UNIQUE INDEX uq_behavior_click_exposure ON user_behavior(exposure_id)
                WHERE action='CLICK' AND exposure_id IS NOT NULL
                """);
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", jdbc.getDataSource());
        context.register(BehaviorPersistenceConfiguration.class); context.refresh();
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentClicksAreIdempotentButLikesAreNotCollapsed() throws Exception {
        jdbc.execute("TRUNCATE user_behavior");
        ObjectProvider<KafkaTemplate<String, String>> kafka = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BehaviorService service = new BehaviorService(context.getBean(BehaviorMapper.class), kafka, redis, registry);
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

    @BeforeEach
    void resetExclusiveFixture() { jdbc.execute("TRUNCATE user_behavior"); }

    @Test
    void nullBucketStillVerifiesExposureButWrongOwnerDoesNot() {
        var service = service();
        jdbc.update("INSERT INTO user_behavior(user_id,item_id,action,bucket,exposure_id) VALUES"
                + "(7,42,'IMPRESSION','old','known'),(7,42,'IMPRESSION',NULL,'known')");
        service.record(new BehaviorEvent(7, 42, ActionType.CLICK, 1, "home", "forged", 1, "known"));
        service.record(new BehaviorEvent(8, 42, ActionType.CLICK, 1, "home", "forged", 1, "known"));
        var valid = jdbc.queryForMap("SELECT bucket,exposure_id FROM user_behavior WHERE action='CLICK' AND user_id=7");
        assertThat(valid).containsEntry("bucket", null).containsEntry("exposure_id", "known");
        var invalid = jdbc.queryForMap("SELECT bucket,exposure_id FROM user_behavior WHERE action='CLICK' AND user_id=8");
        assertThat(invalid).containsEntry("bucket", null).containsEntry("exposure_id", null);
    }

    @Test
    void ratingKeepsReportedTimestampValueAndBucket() {
        long timestamp = 1_700_000_012_345L;
        service().record(new BehaviorEvent(7, 42, ActionType.RATING, 4.5, "profile", "reported", timestamp));
        var row = jdbc.queryForMap("SELECT * FROM user_behavior");
        assertThat(row).containsEntry("action", "RATING").containsEntry("value", 4.5).containsEntry("bucket", "reported");
        assertThat(((java.sql.Timestamp) row.get("ts")).getTime()).isEqualTo(timestamp);
    }

    @SuppressWarnings("unchecked")
    private BehaviorService service() {
        ObjectProvider<KafkaTemplate<String, String>> kafka = mock(ObjectProvider.class);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return new BehaviorService(context.getBean(BehaviorMapper.class), kafka, redis, new SimpleMeterRegistry());
    }
}
