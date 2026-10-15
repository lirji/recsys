package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** 自有schema模拟独立源库和目标库，保留原水位、批次与来源连接释放边界。 */
@Testcontainers
class SyncBehaviorLogPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate target, source, admin;
    SyncBehaviorLogJob job;

    @BeforeEach void prepare() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        admin.execute("DROP SCHEMA IF EXISTS target_store CASCADE; DROP SCHEMA IF EXISTS source_store CASCADE; CREATE SCHEMA target_store; CREATE SCHEMA source_store");
        target = jdbc("target_store"); source = jdbc("source_store");
        for (var db : new JdbcTemplate[]{target, source}) {
            db.execute("""
                    CREATE TABLE user_behavior(id bigint PRIMARY KEY, user_id bigint, item_id bigint,
                        action text, value double precision, scene text, bucket text, ts timestamp,
                        position integer, exposure_id text);
                    COMMENT ON TABLE user_behavior IS '独占行为同步源';
                    COMMENT ON COLUMN user_behavior.id IS '原递增水位';
                    COMMENT ON COLUMN user_behavior.user_id IS '可空用户身份';
                    COMMENT ON COLUMN user_behavior.item_id IS '可空物品身份';
                    COMMENT ON COLUMN user_behavior.action IS '行为类型';
                    COMMENT ON COLUMN user_behavior.value IS '可空行为值';
                    COMMENT ON COLUMN user_behavior.scene IS '原来源';
                    COMMENT ON COLUMN user_behavior.bucket IS '可空实验桶';
                    COMMENT ON COLUMN user_behavior.ts IS '原发生时间，精确到微秒';
                    COMMENT ON COLUMN user_behavior.position IS '可空位置';
                    COMMENT ON COLUMN user_behavior.exposure_id IS '可空原曝光标识';
                    """);
        }
        var targetSessions = new SqlSessionTemplate(SpringSqlSessions.create(target.getDataSource(),
                "classpath*:mapper/offline/primary/**/*.xml"));
        var sourceSessions = new SqlSessionTemplate(SpringSqlSessions.create(source.getDataSource(),
                "classpath*:mapper/offline/source/BehaviorCopySourceMapper.xml"));
        assertFalse(targetSessions.getConfiguration().hasStatement(
                "com.recsys.offline.persistence.mapper.source.BehaviorCopySourceMapper.since"));
        job = new SyncBehaviorLogJob(targetSessions) {
            @Override SqlSessionTemplate buildSourceSessions(String name) {
                assertEquals("isolated", name);
                return sourceSessions;
            }
        };
    }

    @Test void sameDatabaseIncrementalWatermarkAndFullReloadKeepOriginalMeaning() {
        target.execute("INSERT INTO user_behavior VALUES(1,NULL,NULL,'CLICK',NULL,NULL,NULL,'2024-01-01 00:00:00.123456',NULL,NULL),(3,3,30,'RATING',4,'s','b',NULL,0,'exposure')");
        job.run(new DefaultApplicationArguments());
        job.run(new DefaultApplicationArguments());
        assertEquals(2, count());
        assertEquals("2024-01-01 00:00:00.123456", target.queryForObject("SELECT ts::text FROM behavior_log WHERE id=1", String.class));
        assertNull(target.queryForObject("SELECT value FROM behavior_log WHERE id=1", Double.class));
        target.execute("UPDATE user_behavior SET action='LIKE' WHERE id=1; INSERT INTO user_behavior(id,action) VALUES(2,'PLAY')");
        job.run(new DefaultApplicationArguments());
        assertEquals(2, count());
        assertEquals("CLICK", target.queryForObject("SELECT action FROM behavior_log WHERE id=1", String.class));
        job.run(new DefaultApplicationArguments("--full"));
        assertEquals(3, count());
        assertEquals("LIKE", target.queryForObject("SELECT action FROM behavior_log WHERE id=1", String.class));
    }

    @Test void crossDatabaseCopiesNullableColumnsAndClosesSourceConnection() {
        source.execute("INSERT INTO user_behavior(id,action,ts) SELECT n,'CLICK','2024-01-01 00:00:00.123456'::timestamp FROM generate_series(1,1002) n");
        job.run(new DefaultApplicationArguments("--source-db=isolated"));
        job.run(new DefaultApplicationArguments("--source-db=isolated"));
        assertEquals(1002, count());
        assertEquals(0, target.queryForObject("SELECT count(*) FROM user_behavior", Integer.class));
        assertEquals(1002, target.queryForObject("SELECT count(*) FROM behavior_log WHERE user_id IS NULL AND item_id IS NULL AND value IS NULL AND position IS NULL AND exposure_id IS NULL AND ts='2024-01-01 00:00:00.123456'", Integer.class));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='sync-source_store'", Integer.class));
    }

    @Test void crossDatabaseSecondBatchFailureKeepsOnlyCommittedFirstBatch() {
        job.run(new DefaultApplicationArguments());
        target.execute("ALTER TABLE behavior_log ADD CHECK(value >= 0)");
        source.execute("INSERT INTO user_behavior(id,value) SELECT n,CASE WHEN n=1002 THEN -1 ELSE 1 END FROM generate_series(1,1002) n");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--source-db=isolated")));
        assertEquals(1000, count());
        assertEquals(0, target.queryForObject("SELECT count(*) FROM behavior_log WHERE id IN(1001,1002)", Integer.class));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='sync-source_store'", Integer.class));
    }

    @Test void outerTransactionCanRollBackFullReloadAndCopiedRows() {
        target.execute("INSERT INTO user_behavior(id,action) VALUES(1,'CLICK')");
        job.run(new DefaultApplicationArguments());
        target.execute("UPDATE user_behavior SET action='LIKE'; INSERT INTO user_behavior(id,action) VALUES(2,'PLAY')");
        new TransactionTemplate(new DataSourceTransactionManager(target.getDataSource())).executeWithoutResult(status -> {
            job.run(new DefaultApplicationArguments("--full"));
            assertEquals(2, count());
            status.setRollbackOnly();
        });
        assertEquals(1, count());
        assertEquals("CLICK", target.queryForObject("SELECT action FROM behavior_log WHERE id=1", String.class));
    }

    @Test void bootstrapUpgradesLegacyExposureColumnAndKeepsBothIndexes() {
        job.run(new DefaultApplicationArguments());
        target.execute("ALTER TABLE behavior_log DROP COLUMN exposure_id");
        target.execute("INSERT INTO user_behavior(id,exposure_id) VALUES(1,'legacy-upgrade')");
        job.run(new DefaultApplicationArguments());
        assertEquals(10, target.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='behavior_log'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NOT NULL", Integer.class));
        assertEquals("legacy-upgrade", target.queryForObject("SELECT exposure_id FROM behavior_log WHERE id=1", String.class));
        assertEquals(2, target.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='target_store' AND indexname IN('idx_behavior_log_user_action','idx_behavior_log_item')", Integer.class));
    }

    @Test void invalidSourceOptionCannotCreateOrClearReadModelEvenWithFull() {
        for (String[] options : new String[][]{{"--full", "--source-db"}, {"--full", "--source-db= "},
                {"--full", "--source-db=one", "--source-db=two"}}) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                    () -> job.run(new DefaultApplicationArguments(options))).errorCode());
            assertNull(target.queryForObject("SELECT to_regclass('behavior_log')::text", String.class));
        }
        target.execute("INSERT INTO user_behavior(id) VALUES(1)");
        job.run(new DefaultApplicationArguments());
        var original = target.queryForList("SELECT * FROM behavior_log");
        target.execute("INSERT INTO user_behavior(id) VALUES(2)");
        for (String[] options : new String[][]{{"--full", "--source-db"}, {"--full", "--source-db= "},
                {"--full", "--source-db=one", "--source-db=two"}}) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                    () -> job.run(new DefaultApplicationArguments(options))).errorCode());
            assertEquals(original, target.queryForList("SELECT * FROM behavior_log"));
        }
        job.run(new DefaultApplicationArguments("--full"));assertEquals(2, count());
    }

    private JdbcTemplate jdbc(String schema) {
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=sync-" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
    }

    private int count() {
        return target.queryForObject("SELECT count(*) FROM behavior_log", Integer.class);
    }
}
