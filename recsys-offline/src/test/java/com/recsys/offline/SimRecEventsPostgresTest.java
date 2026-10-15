package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;


import org.junit.jupiter.api.*;
import com.recsys.offline.persistence.RecSimulationWriter;
import com.recsys.offline.persistence.mapper.primary.RecSimulationMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
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

/** 用真实PG冻结推荐模拟的热度输入、事件关系与原单次大批处理失败进度。 */
@Testcontainers
class SimRecEventsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate db, writer;
    static TrackedDriverDataSource writerSource;
    static SimRecEventsJob job;
    static SqlSessionTemplate sessions;

    @BeforeAll static void prepare() throws Exception {
        db = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "ApplicationName=rec-simulation-writer";
        writerSource = new TrackedDriverDataSource(url, PG.getUsername(), PG.getPassword());
        writer = new JdbcTemplate(writerSource);
        create("item", "item_id BIGINT PRIMARY KEY");
        create("item_local", "item_id BIGINT PRIMARY KEY");
        create("user_behavior", "id BIGSERIAL PRIMARY KEY,user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION,scene TEXT,bucket TEXT,position INT,exposure_id TEXT,ts TIMESTAMP");
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(writerSource, "classpath*:mapper/offline/primary/**/*.xml"));
        job = new SimRecEventsJob(sessions.getMapper(RecSimulationMapper.class), new RecSimulationWriter(sessions));
    }

    @BeforeEach void reset() {
        db.execute("ALTER TABLE user_behavior DROP CONSTRAINT IF EXISTS reject_late; TRUNCATE item,item_local,user_behavior RESTART IDENTITY");
        db.execute("INSERT INTO item VALUES(0),(1),(2); INSERT INTO item_local VALUES(70),(80)");
        // NULL分组按原getLong读取为0，item 0必须使用这组热度；不得静默跳过可空ID。
        db.execute("INSERT INTO user_behavior(user_id,item_id,action,value,scene,ts) SELECT 1,NULL,'RATING',5,'rating',to_timestamp(1) FROM generate_series(1,27)");
        db.execute("INSERT INTO user_behavior(user_id,item_id,action,value,scene,ts) SELECT 1,1,'RATING',5,'rating',to_timestamp(1) FROM generate_series(1,9)");
        db.execute("INSERT INTO user_behavior(user_id,item_id,action,value,scene,ts) VALUES(1,2,'RATING',5,'rating',to_timestamp(1)),(9,99,'CLICK',1,'online',to_timestamp(2)),(9,98,'CLICK',1,'sim-rec',to_timestamp(3))");
        db.execute("ALTER SEQUENCE user_behavior_id_seq RESTART WITH 100");
    }

    @Test void defaultFiftyThousandImpressionsKeepPopularityPositionsAndEventRelationships() {
        long start = System.currentTimeMillis();
        job.run(new DefaultApplicationArguments("--clear"));
        long end = System.currentTimeMillis();
        assertEquals(50000, count("IMPRESSION"));
        assertEquals(11285, count("CLICK")); assertEquals(3342, count("LIKE"));
        assertEquals("fe01f2a1fba32dd8bb9b8e643d5b61e2", generatedFingerprint());
        // 仅刷新断言查询的统计信息，避免新灌入的大样本走二次方关联扫描。
        db.execute("ANALYZE user_behavior");
        System.out.println("SIM_REC_DEFAULT_CLICK=" + count("CLICK") + ";LIKE=" + count("LIKE") + ";HASH=" + generatedFingerprint());
        assertEquals(38, db.queryForObject("SELECT count(*) FROM user_behavior WHERE scene<>'sim-rec'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='sim-rec' AND (bucket<>'sim' OR exposure_id IS NOT NULL OR user_id NOT BETWEEN 1 AND 610 OR item_id NOT IN(0,1,2) OR position NOT BETWEEN 1 AND 10)", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='sim-rec' AND ((action='IMPRESSION' AND value<>position) OR (action<>'IMPRESSION' AND value<>0))", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior c WHERE c.scene='sim-rec' AND c.action='CLICK' AND NOT EXISTS(SELECT 1 FROM user_behavior i WHERE i.scene='sim-rec' AND i.action='IMPRESSION' AND i.user_id=c.user_id AND i.item_id=c.item_id AND i.position=c.position AND c.ts>=i.ts+interval '30 seconds' AND c.ts<i.ts+interval '90 seconds')", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior l WHERE l.scene='sim-rec' AND l.action='LIKE' AND NOT EXISTS(SELECT 1 FROM user_behavior c WHERE c.scene='sim-rec' AND c.action='CLICK' AND c.user_id=l.user_id AND c.item_id=l.item_id AND c.position=l.position AND l.ts=c.ts+interval '5 seconds')", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='sim-rec' AND action='IMPRESSION' AND (ts<CAST(? AS TIMESTAMP)-interval '23 hours' OR ts>CAST(? AS TIMESTAMP))", Integer.class, new java.sql.Timestamp(start), new java.sql.Timestamp(end)));
        assertWriterClosed();
    }

    @Test void replicaCatalogAndExplicitParametersKeepSourceIsolation() {
        job.run(new DefaultApplicationArguments("--clear", "--item-table=item_local", "--sessions=100", "--slate=3", "--users=1", "--like-rate=1", "--decay=1", "--seed=7"));
        assertEquals(300, count("IMPRESSION")); assertTrue(count("CLICK") > 0); assertEquals(count("CLICK"), count("LIKE"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='sim-rec' AND (item_id NOT IN(70,80) OR user_id<>1 OR position NOT BETWEEN 1 AND 3)", Integer.class));
        assertWriterClosed();
    }

    @Test void emptyCatalogCanFollowClearButInvalidSourceCannotClear() {
        db.execute("TRUNCATE item");
        assertEquals(ErrorCode.JOB_GENERATION_INPUT_EMPTY, assertThrows(PublicErrorException.class, () ->
                job.run(new DefaultApplicationArguments("--clear"))).errorCode());
        assertEquals(0, simulationRows());
        assertEquals(38, db.queryForObject("SELECT count(*) FROM user_behavior", Integer.class));
        db.execute("INSERT INTO user_behavior(scene) VALUES('sim-rec')");
        assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments("--clear", "--item-table=item;DROP TABLE item_local")));
        assertEquals(1, simulationRows());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM item_local", Integer.class));
        assertWriterClosed();
    }

    @Test void invalidParametersIncludingZeroSessionsCannotClearPriorRows() {
        for (String option : new String[]{"--sessions=0", "--sessions=-1", "--slate=0", "--users=0",
                "--like-rate=1.1", "--decay=NaN", "--seed=private"}) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class, () ->
                    job.run(new DefaultApplicationArguments(option, "--clear"))).errorCode());
            assertEquals(1, simulationRows());
            assertEquals(98L, db.queryForObject("SELECT item_id FROM user_behavior WHERE scene='sim-rec'", Long.class));
        }
        assertWriterClosed();
    }

    @Test void lateFailureKeepsActualDriverAutocommitProgress() {
        db.execute("ALTER TABLE user_behavior ADD CONSTRAINT reject_late CHECK(id<>15099)");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--clear")));
        int committed = simulationRows();
        System.out.println("SIM_REC_LATE_FAILURE_COMMITTED=" + committed);
        // 旧驱动/该语句的真实测量值，不是新增业务批大小。
        assertEquals(14847, committed);
        assertEquals(38, db.queryForObject("SELECT count(*) FROM user_behavior WHERE scene<>'sim-rec'", Integer.class));
        assertWriterClosed();
    }

    @Test void outerRollbackRestoresClearedRowsAndRemovesGeneratedBatch() {
        new TransactionTemplate(new DataSourceTransactionManager(writer.getDataSource())).executeWithoutResult(status -> {
            assertEquals("public", sessions.selectOne("offline.probe.primary.schema"));
            job.run(new DefaultApplicationArguments("--clear", "--sessions=100", "--slate=3"));
            assertEquals("public", sessions.selectOne("offline.probe.primary.schema"));
            assertEquals(300, count("IMPRESSION"));
            status.setRollbackOnly();
        });
        assertEquals(1, simulationRows());
        assertEquals(98L, db.queryForObject("SELECT item_id FROM user_behavior WHERE scene='sim-rec'", Long.class));
        assertWriterClosed();
    }

    @Test void outerCommitKeepsExistingSimpleSessionUsableAfterBatchClose() {
        new TransactionTemplate(new DataSourceTransactionManager(writerSource)).executeWithoutResult(status -> {
            assertEquals("public", sessions.selectOne("offline.probe.primary.schema"));
            job.run(new DefaultApplicationArguments("--clear", "--sessions=100", "--slate=3"));
            assertEquals("public", sessions.selectOne("offline.probe.primary.schema"));
            assertEquals(300, count("IMPRESSION"));
        });
        assertEquals(300, count("IMPRESSION"));
        assertWriterClosed();
    }

    /** 去掉统一now偏移，对旧路径真实产出的有序事件建立行为摘要。 */
    private static String generatedFingerprint() {
        return db.queryForObject("SELECT md5(string_agg(concat_ws(':',user_id,item_id,action,value,position,extract(epoch FROM (ts-origin))),',' ORDER BY id)) FROM (SELECT *,min(ts) OVER() origin FROM user_behavior WHERE scene='sim-rec') r", String.class);
    }
    private static int count(String action) {
        // 事务中也必须从相同资源读取，独立admin连接看不到尚未提交的批次。
        return writer.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='sim-rec' AND action=?", Integer.class, action);
    }
    private static int simulationRows() { return db.queryForObject("SELECT count(*) FROM user_behavior WHERE scene='sim-rec'", Integer.class); }
    private static void assertWriterClosed() {
        writerSource.assertClosed(db, "rec-simulation-writer");
    }
    /** 所有DDL仅用于独占容器，字段注释与生产规范一致。 */
    private static void create(String table, String columns) {
        db.execute("CREATE TABLE " + table + "(" + columns + ")");
        db.execute("COMMENT ON TABLE " + table + " IS '推荐模拟迁移独占测试'");
        for (String column : columns.split(",")) db.execute("COMMENT ON COLUMN " + table + "." + column.strip().split(" ")[0] + " IS '测试字段'");
    }
}
