package com.recsys.offline;

import org.junit.jupiter.api.*;
import com.recsys.offline.persistence.UpliftSimulationWriter;
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

/** 冻结uplift分配/转化两个独立大批次的可观察行为与失败进度。 */
@Testcontainers
class SimAdUpliftDataPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate db, writer;
    static TrackedDriverDataSource source;
    static SimAdUpliftDataJob job;
    static SqlSessionTemplate sessions;

    @BeforeAll static void prepare() throws Exception {
        db = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "ApplicationName=uplift-simulation-writer";
        source = new TrackedDriverDataSource(url, PG.getUsername(), PG.getPassword());
        writer = new JdbcTemplate(source);
        create("ad_uplift_assignment", "assignment_id TEXT PRIMARY KEY,request_id TEXT NOT NULL,user_id BIGINT NOT NULL,ad_id BIGINT NOT NULL,advertiser_id BIGINT NOT NULL,item_id BIGINT NOT NULL,treatment BOOLEAN NOT NULL,propensity DOUBLE PRECISION NOT NULL CHECK(propensity>0 AND propensity<1),pctr DOUBLE PRECISION NOT NULL,pcvr DOUBLE PRECISION NOT NULL,quality DOUBLE PRECISION NOT NULL,relevance DOUBLE PRECISION NOT NULL,bid DOUBLE PRECISION NOT NULL,ad_bucket TEXT,model_version TEXT,assigned_at TIMESTAMP NOT NULL DEFAULT now(),outcome_due_at TIMESTAMP NOT NULL");
        db.execute("ALTER TABLE ad_uplift_assignment ADD UNIQUE(request_id,ad_id)");
        create("ad_conversion_fact", "event_id TEXT PRIMARY KEY,advertiser_id BIGINT NOT NULL,user_id BIGINT NOT NULL,objective TEXT NOT NULL,conversion_value DOUBLE PRECISION NOT NULL DEFAULT 0,occurred_at TIMESTAMP NOT NULL,created_at TIMESTAMP NOT NULL DEFAULT now()");
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(source, "classpath*:mapper/offline/addb/**/*.xml"));
        job = new SimAdUpliftDataJob(new UpliftSimulationWriter(sessions));
    }

    @BeforeEach void reset() {
        db.execute("ALTER TABLE ad_uplift_assignment DROP CONSTRAINT IF EXISTS reject_late; ALTER TABLE ad_conversion_fact DROP CONSTRAINT IF EXISTS reject_fact; TRUNCATE ad_uplift_assignment,ad_conversion_fact");
    }

    @Test void defaultFiveThousandAssignmentsPreserveAllColumnsAndDelayedFacts() {
        job.run(new DefaultApplicationArguments("--run-id=qa-test"));
        assertEquals(5000, assignments()); assertEquals(602, facts()); assertEquals(2539, treated());
        assertEquals("1d9739b75813cc9694ce663c8b2ab7f0", fingerprint());
        System.out.println("UPLIFT_DEFAULT_TREATED=" + treated() + ";FACTS=" + facts() + ";HASH=" + fingerprint());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ad_uplift_assignment WHERE assignment_id<>request_id OR assignment_id<>'qa-test:'||(user_id-1000000) OR item_id<>920000+(user_id-1000000)%20 OR propensity<>0.5 OR pctr<>0.1 OR pcvr<>0.2 OR relevance<>0.7 OR bid<>2 OR ad_bucket<>'uplift-qa' OR model_version<>'collector' OR outcome_due_at<>assigned_at+interval '3 days' OR ((user_id%2=0) AND (ad_id<>900001 OR advertiser_id<>910001 OR quality<>1.2)) OR ((user_id%2=1) AND (ad_id<>900002 OR advertiser_id<>910002 OR quality<>0.8))", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ad_conversion_fact f WHERE objective<>'purchase' OR conversion_value<>100 OR NOT EXISTS(SELECT 1 FROM ad_uplift_assignment a WHERE a.user_id=f.user_id AND a.advertiser_id=f.advertiser_id AND f.event_id='qa-test:outcome:'||(a.user_id-1000000) AND f.occurred_at=a.assigned_at+interval '1 day')", Integer.class));
        assertTrue(db.queryForObject("SELECT bool_and(assigned_at<current_timestamp-interval '9 days') FROM ad_uplift_assignment", Boolean.class));
        source.assertClosed(db, "uplift-simulation-writer");
    }

    @Test void sameRunIsIdempotentAndAnyUniqueConflictKeepsExistingAssignment() {
        var args = new DefaultApplicationArguments("--run-id=quoted'run", "--users=200");
        job.run(args);
        var beforeAssignments = db.queryForList("SELECT * FROM ad_uplift_assignment ORDER BY assignment_id");
        var beforeFacts = db.queryForList("SELECT * FROM ad_conversion_fact ORDER BY event_id");
        job.run(args);
        assertEquals(beforeAssignments, db.queryForList("SELECT * FROM ad_uplift_assignment ORDER BY assignment_id"));
        assertEquals(beforeFacts, db.queryForList("SELECT * FROM ad_conversion_fact ORDER BY event_id"));
        // 唯一约束冲突不只限于主键；请求/广告已存在时不能覆盖已有分配。
        db.update("UPDATE ad_uplift_assignment SET assignment_id='protected',quality=9 WHERE request_id=?", "quoted'run:0");
        job.run(args);
        assertEquals(200, assignments());
        assertEquals(9.0, db.queryForObject("SELECT quality FROM ad_uplift_assignment WHERE assignment_id='protected'", Double.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ad_uplift_assignment WHERE assignment_id=?", Integer.class, "quoted'run:0"));
        source.assertClosed(db, "uplift-simulation-writer");
    }

    @Test void lateAssignmentFailurePreservesOriginalPartialCommitBoundary() {
        db.execute("ALTER TABLE ad_uplift_assignment ADD CONSTRAINT reject_late CHECK(user_id<>1004000)");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--run-id=qa-test")));
        System.out.println("UPLIFT_LATE_FAILURE_COMMITTED=" + assignments());
        // 冻结17绑定原批次在第4001行失败时的实际驱动提交进度。
        assertEquals(3839, assignments());
        assertEquals(0, facts());
        source.assertClosed(db, "uplift-simulation-writer");
    }

    @Test void failedFactBatchKeepsAlreadyCommittedAssignments() {
        db.execute("ALTER TABLE ad_conversion_fact ADD CONSTRAINT reject_fact CHECK(objective<>'purchase')");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--run-id=qa-test")));
        assertEquals(5000, assignments()); assertEquals(0, facts());
        source.assertClosed(db, "uplift-simulation-writer");
    }

    @Test void outerTransactionRollsBackBothBatches() {
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            assertEquals("public", sessions.selectOne("offline.probe.addb.schema"));
            job.run(new DefaultApplicationArguments("--run-id=qa-test", "--users=200"));
            assertEquals("public", sessions.selectOne("offline.probe.addb.schema"));
            assertEquals(200, assignments()); assertTrue(facts() > 0);
            status.setRollbackOnly();
        });
        assertEquals(0, assignments()); assertEquals(0, facts());
        source.assertClosed(db, "uplift-simulation-writer");
    }

    @Test void emptyAndInvalidCountsDoNotWriteData() {
        job.run(new DefaultApplicationArguments("--users=0"));
        assertEquals(0, assignments()); assertEquals(0, facts());
        assertThrows(IllegalArgumentException.class, () -> job.run(new DefaultApplicationArguments("--users=-1")));
        assertEquals(0, assignments()); assertEquals(0, facts());
        source.assertClosed(db, "uplift-simulation-writer");
    }

    @Test void outerCommitKeepsSimpleSessionUsableAcrossBothBatches() {
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            assertEquals("public", sessions.selectOne("offline.probe.addb.schema"));
            job.run(new DefaultApplicationArguments("--run-id=qa-test", "--users=200"));
            assertEquals("public", sessions.selectOne("offline.probe.addb.schema"));
            assertEquals(200, assignments()); assertTrue(facts() > 0);
        });
        assertEquals(200, assignments()); assertTrue(facts() > 0);
        source.assertClosed(db, "uplift-simulation-writer");
    }

    private static int assignments() { return writer.queryForObject("SELECT count(*) FROM ad_uplift_assignment", Integer.class); }
    private static int facts() { return writer.queryForObject("SELECT count(*) FROM ad_conversion_fact", Integer.class); }
    private static int treated() { return writer.queryForObject("SELECT count(*) FROM ad_uplift_assignment WHERE treatment", Integer.class); }
    /** 原now偏移剔除后冻结分配及转化身份，避免用新实现反推期望。 */
    private static String fingerprint() {
        return db.queryForObject("SELECT md5(string_agg(concat_ws(':',a.assignment_id,a.request_id,a.user_id,a.ad_id,a.advertiser_id,a.item_id,a.treatment,a.propensity,a.pctr,a.pcvr,a.quality,a.relevance,a.bid,a.ad_bucket,a.model_version,extract(epoch FROM (a.assigned_at-a.origin)),f.event_id),',' ORDER BY a.user_id)) FROM (SELECT *,min(assigned_at) OVER() origin FROM ad_uplift_assignment) a LEFT JOIN ad_conversion_fact f ON f.user_id=a.user_id AND f.advertiser_id=a.advertiser_id", String.class);
    }
    /** 独占测试表逐列注释，不修改历史初始化资源。 */
    private static void create(String table, String columns) {
        db.execute("CREATE TABLE " + table + "(" + columns + ")");
        db.execute("COMMENT ON TABLE " + table + " IS 'uplift模拟独占测试'");
        for (String column : columns.split(",")) db.execute("COMMENT ON COLUMN " + table + "." + column.strip().split(" ")[0] + " IS '测试字段'");
    }
}
