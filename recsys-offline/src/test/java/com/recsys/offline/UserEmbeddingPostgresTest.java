package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;


import com.pgvector.PGvector;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.mapper.derived.ItemEmbeddingMapper;
import com.recsys.offline.persistence.mapper.derived.UserEmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实双源验证用户向量权重、时间边界、重建与逐条失败，不用Mock证明事务。 */
@Testcontainers
class UserEmbeddingPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate primary, derived;
    static TrackedDriverDataSource primarySource, derivedSource;
    static PositiveFeedbackMapper feedback;
    static UserEmbeddingJob job;
    static SqlSessionTemplate derivedSessions;

    @BeforeAll static void prepare() throws Exception {
        primary = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        primary.execute("CREATE EXTENSION vector; CREATE SCHEMA vectors");
        primarySource = source("public", "user-vector-primary");
        derivedSource = source("vectors,public", "user-vector-derived");
        derived = new JdbcTemplate(derivedSource);
        for (String table : List.of("user_behavior", "behavior_log")) {
            create(primary, table, "user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION,ts TIMESTAMP");
        }
        create(derived, "item_embedding", "item_id BIGINT PRIMARY KEY,embedding vector(3)");
        create(derived, "user_embedding", "user_id BIGINT PRIMARY KEY,embedding vector(3)");
        create(primary, "user_embedding", "user_id BIGINT PRIMARY KEY,embedding vector(3)");
        primary.execute("INSERT INTO user_embedding VALUES(777,'[9,9,9]')");
        feedback = new SqlSessionTemplate(SpringSqlSessions.create(primarySource,
                "classpath*:mapper/offline/primary/**/*.xml")).getMapper(PositiveFeedbackMapper.class);
        derivedSessions = new SqlSessionTemplate(SpringSqlSessions.create(derivedSource,
                "classpath*:mapper/offline/derived/**/*.xml"));
        job = new UserEmbeddingJob(feedback, derivedSessions.getMapper(ItemEmbeddingMapper.class),
                derivedSessions.getMapper(UserEmbeddingMapper.class));
    }

    // 原表是TIMESTAMP；用无时区字面量冻结extract(epoch)输入，避免to_timestamp经会话时区转换。
    @BeforeEach void reset() {
        primary.execute("TRUNCATE user_behavior,behavior_log");
        derived.execute("ALTER TABLE user_embedding DROP CONSTRAINT IF EXISTS reject_user; TRUNCATE item_embedding,user_embedding");
        derived.execute("INSERT INTO item_embedding VALUES(0,'[0,0,1]'),(10,'[1,0,0]'),(11,'[0,1,0]'),(12,'[0,0,1]'),(13,'[-1,0,0]'),(14,NULL); INSERT INTO user_embedding VALUES(99,'[1,1,1]')");
        primary.execute("""
                INSERT INTO user_behavior VALUES
                (1,10,'RATING',4,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),(1,11,'LIKE',NULL,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),
                (1,12,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),(1,12,'IMPRESSION',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),
                (1,10,'RATING',3.9,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),(2,10,'PLAY',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),
                (2,13,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),(3,14,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),
                (NULL,NULL,'CLICK',NULL,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second'));
                INSERT INTO behavior_log VALUES(7,12,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second'));
                """);
    }

    @Test void defaultRebuildKeepsWeightsZeroVectorsAndNullableIdentities() throws Exception {
        job.run(new DefaultApplicationArguments("--half-life-days=0"));
        assertEquals(List.of(0L, 1L, 2L), users());
        assertVector(0, 0, 0, 1);
        assertVector(1, 0.87287156f, 0.43643578f, 0.21821789f);
        assertVector(2, 0, 0, 0);
        assertEquals("[9,9,9]", primary.queryForObject("SELECT embedding::text FROM user_embedding WHERE user_id=777", String.class));
        assertClosed();
    }

    @Test void incrementalUpsertPreservesUnrelatedUsers() throws Exception {
        derived.execute("INSERT INTO user_embedding VALUES(1,'[9,9,9]')");
        job.run(new DefaultApplicationArguments("--no-rebuild", "--half-life-days=0"));
        assertEquals(List.of(0L, 1L, 2L, 99L), users());
        assertVector(1, 0.87287156f, 0.43643578f, 0.21821789f);
        assertVector(99, 1, 1, 1);
        assertClosed();
    }

    @Test void halfLifeCutoffThresholdAndReplicaSourceRemainExact() throws Exception {
        primary.execute("TRUNCATE user_behavior; INSERT INTO user_behavior VALUES(1,10,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 0 * interval '1 second')),(1,11,'LIKE',NULL,(TIMESTAMP '1970-01-01 00:00:00' + 86400 * interval '1 second')),(1,12,'RATING',3.9,(TIMESTAMP '1970-01-01 00:00:00' + 86400 * interval '1 second')),(1,12,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 86400.000001 * interval '1 second')),(1,12,'PLAY',1,NULL)");
        job.run(new DefaultApplicationArguments("--max-ts=86400", "--half-life-days=1"));
        assertEquals(List.of(1L), users()); assertVector(1, 0.24253562f, 0.9701425f, 0);
        job.run(new DefaultApplicationArguments("--behavior-table=behavior_log", "--max-ts=100", "--half-life-days=1"));
        assertEquals(List.of(7L), users()); assertVector(7, 0, 0, 1);
        assertClosed();
    }

    @Test void noUsableItemVectorsOrFeedbackFailsBeforeClearingOldUsers() throws Exception {
        derived.execute("TRUNCATE item_embedding");
        assertEquals(ErrorCode.JOB_NO_ITEM_VECTORS, assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments())).errorCode()); assertEquals(List.of(99L), users());
        derived.execute("INSERT INTO item_embedding VALUES(10,NULL)");
        assertEquals(ErrorCode.JOB_NO_ITEM_VECTORS, assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments())).errorCode()); assertEquals(List.of(99L), users());
        derived.execute("UPDATE item_embedding SET embedding='[1,0,0]'");
        primary.execute("TRUNCATE user_behavior");
        assertEquals(ErrorCode.JOB_NO_USER_VECTORS, assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments())).errorCode()); assertEquals(List.of(99L), users());
        assertClosed();
    }

    @Test void lateWriteFailureKeepsTruncateAndEarlierAutocommitWrite() {
        rejectSecondUser();
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--half-life-days=0")));
        assertEquals(List.of(1L), users()); assertVector(1, 1, 0, 0);
        assertClosed();
    }

    @Test void outerTransactionRestoresTruncatedRowsAfterWriteFailure() {
        rejectSecondUser();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(derivedSource));
        assertThrows(DataIntegrityViolationException.class, () -> transaction.executeWithoutResult(status ->
                runJobUnchecked()));
        assertEquals(List.of(99L), users()); assertVector(99, 1, 1, 1);
        assertClosed();
    }

    @Test void outerRollbackRestoresOriginalUserVectorsAfterSuccessfulRebuild() {
        new TransactionTemplate(new DataSourceTransactionManager(derivedSource)).executeWithoutResult(status -> {
            assertEquals("vectors", derivedSessions.selectOne("offline.probe.derived.schema"));
            assertDoesNotThrow(() -> job.run(new DefaultApplicationArguments("--half-life-days=0")));
            assertEquals("vectors", derivedSessions.selectOne("offline.probe.derived.schema"));
            assertEquals(List.of(0L, 1L, 2L), users());
            status.setRollbackOnly();
        });
        assertEquals(List.of(99L), users()); assertVector(99, 1, 1, 1);
        assertClosed();
    }

    /** 仅桥接OfflineJob的checked签名，运行时数据库异常必须原样向上传播触发回滚。 */
    private static void runJobUnchecked() {
        try { job.run(new DefaultApplicationArguments("--half-life-days=0")); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception unexpected) { throw new AssertionError(unexpected); }
    }
    private static void rejectSecondUser() {
        primary.execute("TRUNCATE user_behavior; INSERT INTO user_behavior VALUES(1,10,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second')),(2,11,'CLICK',1,(TIMESTAMP '1970-01-01 00:00:00' + 100 * interval '1 second'))");
        derived.execute("ALTER TABLE user_embedding ADD CONSTRAINT reject_user CHECK(user_id<>2)");
    }
    private static List<Long> users() { return derived.queryForList("SELECT user_id FROM user_embedding ORDER BY user_id", Long.class); }
    private static void assertVector(long userId, float... expected) {
        String text = derived.queryForObject("SELECT embedding::text FROM user_embedding WHERE user_id=?", String.class, userId);
        assertArrayEquals(expected, assertDoesNotThrow(() -> new PGvector(text)).toArray(), 0.000001f);
    }
    private static void assertClosed() {
        primarySource.assertClosed(primary, "user-vector-primary"); derivedSource.assertClosed(primary, "user-vector-derived");
    }
    private static TrackedDriverDataSource source(String schema, String name) {
        return new TrackedDriverDataSource(PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema + "&ApplicationName=" + name, PG.getUsername(), PG.getPassword());
    }
    /** 测试DDL仅作用于独占容器并补齐表与每列注释。 */
    private static void create(JdbcTemplate db, String table, String columns) {
        db.execute("CREATE TABLE " + table + "(" + columns + ")");
        db.execute("COMMENT ON TABLE " + table + " IS '用户向量迁移独占测试'");
        for (String column : columns.split(",")) db.execute("COMMENT ON COLUMN " + table + "." + column.strip().split(" ")[0] + " IS '测试字段'");
    }
}
