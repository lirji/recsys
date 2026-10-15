package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;


import com.zaxxer.hikari.HikariDataSource;
import com.recsys.offline.persistence.AdSimulationWriter;
import com.recsys.offline.persistence.mapper.sharded.AdSimulationCatalogMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** 真实分片目录与独占事件库验证大JDBC批次的实际失败边界，不推断其天然原子性。 */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Testcontainers
class SimAdEventsPostgresTest {
    @Container static final PostgreSQLContainer<?> ZERO = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final PostgreSQLContainer<?> ONE = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate zero, one, events;
    static TrackedDriverDataSource eventSource;
    static DataSource sharded;
    static SimAdEventsJob job;
    static SqlSessionTemplate eventSessions;

    @BeforeAll static void prepare() throws Exception {
        HikariDataSource ds0 = shardSource(ZERO), ds1 = shardSource(ONE);
        zero = new JdbcTemplate(ds0); one = new JdbcTemplate(ds1);
        for (var db : List.of(zero, one)) {
            create(db, "advertiser", "advertiser_id BIGINT PRIMARY KEY");
            create(db, "ad", "ad_id BIGINT PRIMARY KEY,advertiser_id BIGINT");
            create(db, "bidword", "id BIGINT PRIMARY KEY,ad_id BIGINT");
            create(db, "ad_creative", "creative_id BIGINT PRIMARY KEY,ad_id BIGINT,status TEXT");
        }
        zero.execute("CREATE SCHEMA simulation");
        String url = ZERO.getJdbcUrl() + (ZERO.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=simulation&ApplicationName=simulation-writer";
        eventSource = new TrackedDriverDataSource(url, ZERO.getUsername(), ZERO.getPassword());
        events = new JdbcTemplate(eventSource);
        create(events, "ad_event", "id BIGSERIAL PRIMARY KEY,request_id TEXT,query TEXT,user_id BIGINT,ad_id BIGINT,position INT,event_type TEXT,pctr DOUBLE PRECISION,pctr_calib DOUBLE PRECISION,charged_price DOUBLE PRECISION,ts TIMESTAMP,creative_id BIGINT");
        String yaml;
        try (var in = SimAdEventsPostgresTest.class.getResourceAsStream("/sharding.yaml")) {
            yaml = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        sharded = YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0", ds0, "ds_1", ds1),
                yaml.substring(yaml.indexOf("\nrules:") + 1).getBytes(StandardCharsets.UTF_8));
        eventSessions = new SqlSessionTemplate(SpringSqlSessions.create(events.getDataSource(),
                "classpath*:mapper/offline/addb/**/*.xml"));
        var shardSessions = new SqlSessionTemplate(SpringSqlSessions.create(sharded,
                "classpath*:mapper/offline/sharded/**/*.xml"));
        job = new SimAdEventsJob(new AdSimulationWriter(eventSessions), shardSessions.getMapper(AdSimulationCatalogMapper.class));
    }

    @AfterAll static void close() throws Exception {
        if (sharded instanceof AutoCloseable closeable) closeable.close();
    }

    @BeforeEach void reset() {
        for (var db : List.of(zero, one)) db.execute("TRUNCATE advertiser,ad,bidword,ad_creative");
        events.execute("ALTER TABLE ad_event DROP CONSTRAINT IF EXISTS reject_late; ALTER TABLE ad_event DROP CONSTRAINT IF EXISTS reject_click; TRUNCATE ad_event RESTART IDENTITY");
        zero.execute("INSERT INTO ad VALUES(2,2),(6,2); INSERT INTO ad_creative VALUES(21,2,'active'),(22,2,'active'),(61,6,'active')");
        one.execute("INSERT INTO ad VALUES(1,1),(5,1); INSERT INTO ad_creative VALUES(11,1,'active'),(12,1,'active'),(51,5,'active'),(52,5,'inactive')");
    }

    @Test void defaultTwentyThousandRequestsPreserveRelationsNullColumnsAndDelayedConversions() {
        job.run(new DefaultApplicationArguments("--days=0", "--seed=7"));
        assertEquals(20000, count("IMPRESSION"));
        assertTrue(count("CLICK") > 0); assertTrue(count("CONVERSION") > 0);
        assertEquals(20000, events.queryForObject("SELECT count(*) FROM ad_event WHERE event_type='IMPRESSION' AND query='sim' AND position BETWEEN 1 AND 3 AND pctr=pctr_calib AND charged_price>0 AND user_id BETWEEN 1 AND 600 AND ad_id BETWEEN 1 AND 6", Integer.class));
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event WHERE event_type<>'IMPRESSION' AND (query IS NOT NULL OR position IS NOT NULL OR pctr IS NOT NULL OR pctr_calib IS NOT NULL OR charged_price IS NOT NULL OR creative_id IS NOT NULL)", Integer.class));
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event c WHERE c.event_type='CLICK' AND NOT EXISTS(SELECT 1 FROM ad_event i WHERE i.event_type='IMPRESSION' AND i.request_id=c.request_id AND i.user_id=c.user_id AND i.ad_id=c.ad_id AND i.ts=c.ts)", Integer.class));
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event c WHERE c.event_type='CONVERSION' AND NOT EXISTS(SELECT 1 FROM ad_event k WHERE k.event_type='CLICK' AND k.request_id=c.request_id AND k.user_id=c.user_id AND k.ad_id=c.ad_id AND k.ts<=c.ts)", Integer.class));
        assertTrue(events.queryForObject("SELECT max(ts)>current_timestamp+interval '1 day' FROM ad_event WHERE event_type='CONVERSION'", Boolean.class));
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event WHERE creative_id IS NOT NULL AND NOT ((ad_id=1 AND creative_id IN(11,12)) OR (ad_id=2 AND creative_id IN(21,22)) OR (ad_id=5 AND creative_id=51) OR (ad_id=6 AND creative_id=61))", Integer.class));
        assertTrue(events.queryForObject("SELECT count(*) FROM ad_event WHERE event_type='IMPRESSION' AND ad_id IN(3,4) AND creative_id IS NULL", Integer.class) > 0);
        assertWriterClosed();
    }

    @Test void lateImpressionFailureKeepsOriginalAutocommitProgress() {
        events.execute("ALTER TABLE ad_event ADD CONSTRAINT reject_late CHECK(id<>15000)");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--requests=20000", "--seed=7")));
        int committed = count("IMPRESSION");
        System.out.println("SIM_AD_LATE_FAILURE_COMMITTED=" + committed);
        // 冻结驱动42.7.3旧路径实测值，不是业务批大小；防止迁移偷偷改变autocommit边界。
        assertEquals(14847, committed);
        assertEquals(0, count("CLICK")); assertEquals(0, count("CONVERSION"));
        assertWriterClosed();
    }

    @Test void clickBatchFailureKeepsAlreadyCommittedImpressions() {
        events.execute("ALTER TABLE ad_event ADD CONSTRAINT reject_click CHECK(event_type<>'CLICK')");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--requests=5000", "--seed=7")));
        assertEquals(5000, count("IMPRESSION"));
        assertEquals(0, count("CLICK")); assertEquals(0, count("CONVERSION"));
        assertWriterClosed();
    }

    @Test void outerTransactionRollsBackAllThreeBatches() {
        new TransactionTemplate(new DataSourceTransactionManager(events.getDataSource())).executeWithoutResult(status -> {
            assertEquals("simulation", eventSessions.selectOne("offline.probe.addb.schema"));
            job.run(new DefaultApplicationArguments("--requests=5000", "--seed=7"));
            assertEquals("simulation", eventSessions.selectOne("offline.probe.addb.schema"));
            assertEquals(5000, count("IMPRESSION")); assertTrue(count("CLICK") > 0);
            status.setRollbackOnly();
        });
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
        assertWriterClosed();
    }

    @Test void emptyShardsAndZeroRequestsDoNotWriteEvents() {
        zero.execute("TRUNCATE ad"); one.execute("TRUNCATE ad");
        assertEquals(ErrorCode.JOB_NO_ADS, assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments())).errorCode());
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
        one.execute("INSERT INTO ad VALUES(9,1)");
        assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments("--requests=0"))).errorCode());
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
    }

    @Test void missingCreativeTablesFailBeforeGeneratingAnyEvents() {
        for (var db : List.of(zero, one)) db.execute("ALTER TABLE ad_creative RENAME TO ad_creative_missing");
        try {
            assertEquals(ErrorCode.JOB_CREATIVE_INPUT_UNAVAILABLE, assertThrows(PublicErrorException.class, () ->
                    job.run(new DefaultApplicationArguments("--requests=100"))).errorCode());
            assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
            assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event WHERE creative_id IS NOT NULL", Integer.class));
        } finally {
            for (var db : List.of(zero, one)) db.execute("ALTER TABLE ad_creative_missing RENAME TO ad_creative");
        }
    }

    @Test void genuinelyEmptyCreativesKeepNullAttributionWithExplicitWarning(org.springframework.boot.test.system.CapturedOutput output) {
        for (var db : List.of(zero, one)) db.execute("TRUNCATE ad_creative");
        job.run(new DefaultApplicationArguments("--requests=100"));
        assertEquals(100, count("IMPRESSION"));
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event WHERE creative_id IS NOT NULL", Integer.class));
        assertTrue(output.getAll().contains("[JOB_CREATIVE_ATTRIBUTION_UNAVAILABLE]"));
        assertWriterClosed();
    }
    @Test void invalidOptionsCannotCreatePartialEvents() {
        for (String option : List.of("--requests=-1", "--days=-1", "--conv-delay-days=-1", "--conv-delay-days=NaN", "--seed=private")) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class, () ->
                    job.run(new DefaultApplicationArguments(option))).errorCode());
        }
        assertEquals(0, events.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
    }

    @Test void outerCommitKeepsExistingSimpleSessionUsableAfterBatchClose() {
        new TransactionTemplate(new DataSourceTransactionManager(events.getDataSource())).executeWithoutResult(status -> {
            assertEquals("simulation", eventSessions.selectOne("offline.probe.addb.schema"));
            job.run(new DefaultApplicationArguments("--requests=100", "--seed=7"));
            assertEquals("simulation", eventSessions.selectOne("offline.probe.addb.schema"));
            assertEquals(100, count("IMPRESSION"));
        });
        assertEquals(100, count("IMPRESSION"));
        assertWriterClosed();
    }

    @Test void connectionProbeStillRejectsAnActuallyOpenJdbcConnection() throws Exception {
        try (var connection = eventSource.getConnection()) {
            assertFalse(connection.isClosed());
            assertThrows(AssertionError.class, () -> eventSource.assertClosed(zero, "simulation-writer"));
        }
        assertWriterClosed();
    }

    private static int count(String type) {
        return events.queryForObject("SELECT count(*) FROM ad_event WHERE event_type=?", Integer.class, type);
    }

    private static void assertWriterClosed() {
        eventSource.assertClosed(zero, "simulation-writer");
    }

    private static HikariDataSource shardSource(PostgreSQLContainer<?> pg) {
        var ds = new HikariDataSource(); ds.setJdbcUrl(pg.getJdbcUrl()); ds.setUsername(pg.getUsername()); ds.setPassword(pg.getPassword()); ds.setMaximumPoolSize(3); return ds;
    }

    /** 测试DDL仅作用于独占容器并补齐每个字段注释。 */
    private static void create(JdbcTemplate db, String table, String columns) {
        db.execute("CREATE TABLE " + table + "(" + columns + ")");
        db.execute("COMMENT ON TABLE " + table + " IS '广告模拟迁移独占测试'");
        for (String column : columns.split(",")) db.execute("COMMENT ON COLUMN " + table + "." + column.strip().split(" ")[0] + " IS '测试字段'");
    }
}
