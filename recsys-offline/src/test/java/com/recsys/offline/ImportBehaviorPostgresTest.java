package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.recsys.offline.persistence.mapper.primary.BehaviorImportMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 独占PG验证导入的原有批次提交、重复执行、来源隔离及外层回滚边界。 */
@Testcontainers
class ImportBehaviorPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @TempDir Path directory;
    JdbcTemplate jdbc;
    ImportBehaviorJob job;

    @BeforeEach void prepare() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS user_behavior");
        jdbc.execute("""
                CREATE TABLE user_behavior(user_id bigint NOT NULL, item_id bigint NOT NULL,
                    action text NOT NULL, value double precision CHECK(value >= 0), scene text,
                    bucket text, ts timestamptz);
                COMMENT ON TABLE user_behavior IS '独占导入测试，检查约束用于第二批失败注入';
                COMMENT ON COLUMN user_behavior.user_id IS '用户身份';
                COMMENT ON COLUMN user_behavior.item_id IS '物品身份';
                COMMENT ON COLUMN user_behavior.action IS '原行为类型';
                COMMENT ON COLUMN user_behavior.value IS '评分，非负约束仅用于注入失败';
                COMMENT ON COLUMN user_behavior.scene IS '导入来源与线上来源隔离';
                COMMENT ON COLUMN user_behavior.bucket IS '导入数据不携带实验桶';
                COMMENT ON COLUMN user_behavior.ts IS '原CSV秒时间转换';
                INSERT INTO user_behavior VALUES(99,99,'CLICK',1,'online','control',to_timestamp(20));
                INSERT INTO user_behavior VALUES(9800,98,'RATING',5,'ml-import',NULL,to_timestamp(10));
                """);
        var sessions = new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),
                "classpath*:mapper/offline/primary/**/*.xml"));
        job = new ImportBehaviorJob(sessions.getMapper(BehaviorImportMapper.class));
        ReflectionTestUtils.setField(job, "movielensPath", directory.toString());
    }

    @Test void repeatedImportKeepsDuplicatesNullBucketAndOtherScenes() throws Exception {
        csv("1,10,4.5,100\n1,10,4.5,100\nmalformed\n2,11,3,101\n");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(ErrorCode.JOB_IMPORT_PARTIAL_FAILED, assertThrows(PublicErrorException.class,
                    () -> job.run(new DefaultApplicationArguments())).errorCode());
        }
        assertEquals(4, count());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE user_id=1 AND item_id=10 AND action='RATING' AND value=4.5 AND bucket IS NULL AND ts=to_timestamp(100)", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='online' AND bucket='control'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE user_id=9800", Integer.class));
    }

    @Test void missingFileDoesNotClearExistingRows() throws Exception {
        assertEquals(ErrorCode.JOB_INPUT_UNAVAILABLE, assertThrows(PublicErrorException.class,
                () -> job.run(new DefaultApplicationArguments())).errorCode());
        assertEquals(2, count());
    }

    @Test void failedSecondBatchPreservesFirstBatchAndRejectsWholeFailedBatch() throws Exception {
        var rows = new StringBuilder();
        for (int i = 1; i <= 1001; i++) rows.append(i).append(",10,4,100\n");
        rows.append("1002,10,-1,100\n");
        csv(rows.toString());
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments()));
        assertEquals(1001, count()); // 已提交首批1000条及原线上记录，失败批两条均未提交。
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE user_id IN (1001,1002)", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE user_id=9800", Integer.class));
    }

    @Test void callerTransactionRollsBackDeleteAndEveryInsertTogether() throws Exception {
        csv("1,10,4.5,100\n");
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            try { job.run(new DefaultApplicationArguments()); }
            catch (Exception e) { throw new IllegalStateException(e); }
            assertEquals(2, count());
            status.setRollbackOnly();
        });
        assertEquals(2, count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE user_id=9800", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE user_id=1", Integer.class));
    }

    @Test void malformedNumbersAndOverflowUseSafeCodeWithoutTouchingOtherScenes() throws Exception {
        for (String row : java.util.List.of("private,10,1,100", "1,10,NaN,100", "1,10,1,9223372036854775807")) {
            csv(row + "\n");
            var failure = assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments()));
            assertEquals(ErrorCode.JOB_INPUT_INVALID, failure.errorCode());
            assertFalse(failure.getMessage().contains("private"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='online'", Integer.class));
        }
    }

    private void csv(String rows) throws Exception {
        Files.writeString(directory.resolve("ratings.csv"), "userId,movieId,rating,timestamp\n" + rows);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM user_behavior", Integer.class);
    }
}
