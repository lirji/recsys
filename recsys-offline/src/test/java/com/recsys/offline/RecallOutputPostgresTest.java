package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 独占真实PG/Redis验证三类召回无输入/稀疏输入和正常产出，不用Mock证明发布。 */
@Testcontainers
class RecallOutputPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate db;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory factory;
    static List<OfflineJob> jobs;

    @BeforeAll static void setup() throws Exception {
        var source = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE user_behavior(user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION,ts TIMESTAMPTZ)");
        db.execute("COMMENT ON TABLE user_behavior IS '召回产出边界独占测试输入'");
        for (String column : List.of("user_id", "item_id", "action", "value", "ts")) {
            db.execute("COMMENT ON COLUMN user_behavior." + column + " IS '原正反馈输入字段'");
        }
        var mapper = new SqlSessionTemplate(SpringSqlSessions.create(source,
                "classpath*:mapper/offline/primary/**/*.xml")).getMapper(PositiveFeedbackMapper.class);
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
        jobs = List.of(new ItemCfJob(mapper, redis), new UserCfJob(mapper, redis), new SwingJob(mapper, redis));
    }
    @AfterAll static void close() { if (factory != null) factory.destroy(); }
    @BeforeEach void reset() {
        db.execute("TRUNCATE user_behavior");
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        for (String key : oldKeys()) redis.opsForZSet().add(key, "old", 7);
    }
    @Test void missingInputAndSparseInputAreDistinctFailuresAndPreserveOldLists() {
        for (OfflineJob job : jobs) failure(job, ErrorCode.JOB_NO_POSITIVE_FEEDBACK);
        assertOldOnly();
        db.execute("INSERT INTO user_behavior VALUES(1,10,'CLICK',1,now())");
        for (OfflineJob job : jobs) failure(job, ErrorCode.JOB_NO_RECALL_OUTPUT);
        assertOldOnly();
    }
    @Test void normalCooccurrencePublishesAllThreeAlgorithmsAndAllowsUsersWithNoNovelCandidates() throws Exception {
        seed(); for (OfflineJob job : jobs) job.run(new DefaultApplicationArguments());
        assertEquals(Set.of("11", "12"), members(RedisKeys.i2i(10)));
        assertEquals(Set.of("12"), members(RedisKeys.u2u(1)));
        assertTrue(members(RedisKeys.u2u(2)).isEmpty());
        assertEquals(Set.of("10"), members(RedisKeys.u2u(3)));
        assertEquals(Set.of("11"), members(RedisKeys.swing(10)));
        assertEquals(Set.of("10", "12"), members(RedisKeys.swing(11)));
        assertEquals(1.0 / 3.0, redis.opsForZSet().score(RedisKeys.swing(10), "11"), 1e-12);
        for (String key : oldKeys()) assertEquals(Set.of("old"), members(key));
    }
    @Test void invalidLimitsAndNonFiniteWeightsCannotClearExistingLists() {
        seed();
        for (OfflineJob job : jobs) {
            for (String option : List.of("--topk=0", "--topk=-1", "--max-user-items=0", "--min-rating=NaN", "--topk")) {
                failure(job, ErrorCode.JOB_INVALID_ARGUMENT, option);
            }
        }
        failure(jobs.get(1), ErrorCode.JOB_INVALID_ARGUMENT, "--sim-users=0");
        failure(jobs.get(1), ErrorCode.JOB_INVALID_ARGUMENT, "--max-item-users=0");
        failure(jobs.get(2), ErrorCode.JOB_INVALID_ARGUMENT, "--alpha=-2");
        assertOldOnly();
    }
    @Test void fixingInputAfterFailureAllowsRetryWithActualPublication() throws Exception {
        var runner = new JobRunner(jobs);
        assertEquals(ErrorCode.JOB_NO_POSITIVE_FEEDBACK, assertThrows(PublicErrorException.class,
                () -> runner.run(new DefaultApplicationArguments("--job=item-cf"))).errorCode());
        seed(); runner.run(new DefaultApplicationArguments("--job=item-cf"));
        assertEquals(Set.of("11", "12"), members(RedisKeys.i2i(10)));
    }
    private static void seed() {
        db.execute("INSERT INTO user_behavior(user_id,item_id,action,value,ts) VALUES"
                + "(1,10,'CLICK',1,now()),(1,11,'CLICK',1,now()),"
                + "(2,10,'CLICK',1,now()),(2,11,'CLICK',1,now()),(2,12,'CLICK',1,now()),"
                + "(3,11,'CLICK',1,now()),(3,12,'CLICK',1,now())");
    }
    private static void failure(OfflineJob job, ErrorCode code, String... options) {
        assertEquals(code, assertThrows(PublicErrorException.class,
                () -> job.run(new DefaultApplicationArguments(options))).errorCode());
    }
    private static Set<String> oldKeys() { return Set.of(RedisKeys.i2i(99), RedisKeys.u2u(99), RedisKeys.swing(99)); }
    private static Set<String> members(String key) { return redis.opsForZSet().range(key, 0, -1); }
    private static void assertOldOnly() {
        assertEquals(oldKeys(), redis.keys("*"));
        for (String key : oldKeys()) assertEquals(7.0, redis.opsForZSet().score(key, "old"));
    }
}
