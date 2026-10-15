package com.recsys.offline;

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

/** 广告事件读仓保护全部16列、水位/重灌、1000条批次及原数据源事务。 */
@Testcontainers
class SyncAdEventLogPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate target, source, admin;
    SyncAdEventLogJob job;

    @BeforeEach void prepare() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        admin.execute("DROP SCHEMA IF EXISTS target_store CASCADE; DROP SCHEMA IF EXISTS source_store CASCADE; CREATE SCHEMA target_store; CREATE SCHEMA source_store");
        target = jdbc("target_store"); source = jdbc("source_store");
        for (var db : new JdbcTemplate[]{target, source}) {
            db.execute("""
                    CREATE TABLE ad_event(id bigint PRIMARY KEY, request_id text, query text, user_id bigint,
                        ad_id bigint, bidword_id bigint, position integer, event_type text, pctr double precision,
                        pctr_calib double precision, ecpm double precision, charged_price double precision,
                        relevance double precision, ad_bucket text, ts timestamp, creative_id bigint);
                    COMMENT ON TABLE ad_event IS '独占广告事件同步源';
                    COMMENT ON COLUMN ad_event.id IS '源事件ID及水位';
                    COMMENT ON COLUMN ad_event.request_id IS '原请求标识';
                    COMMENT ON COLUMN ad_event.query IS '原搜索词';
                    COMMENT ON COLUMN ad_event.user_id IS '原用户身份';
                    COMMENT ON COLUMN ad_event.ad_id IS '原广告身份';
                    COMMENT ON COLUMN ad_event.bidword_id IS '原竞价词身份';
                    COMMENT ON COLUMN ad_event.position IS '原展示位置';
                    COMMENT ON COLUMN ad_event.event_type IS '原事件类型';
                    COMMENT ON COLUMN ad_event.pctr IS '原预估CTR';
                    COMMENT ON COLUMN ad_event.pctr_calib IS '原校准CTR';
                    COMMENT ON COLUMN ad_event.ecpm IS '原排序收益';
                    COMMENT ON COLUMN ad_event.charged_price IS '原事件记录价格';
                    COMMENT ON COLUMN ad_event.relevance IS '原相关性';
                    COMMENT ON COLUMN ad_event.ad_bucket IS '原实验桶';
                    COMMENT ON COLUMN ad_event.ts IS '原微秒事件时间';
                    COMMENT ON COLUMN ad_event.creative_id IS '原创意身份';
                    """);
        }
        var targetSessions = new SqlSessionTemplate(SpringSqlSessions.create(target.getDataSource(),
                "classpath*:mapper/offline/primary/**/*.xml"));
        var sourceSessions = new SqlSessionTemplate(SpringSqlSessions.create(source.getDataSource(),
                "classpath*:mapper/offline/source/AdEventCopySourceMapper.xml"));
        assertFalse(targetSessions.getConfiguration().hasStatement(
                "com.recsys.offline.persistence.mapper.source.AdEventCopySourceMapper.since"));
        job = new SyncAdEventLogJob(targetSessions) {
            @Override SqlSessionTemplate buildSourceSessions(String name) {
                assertEquals("isolated", name);
                return sourceSessions;
            }
        };
    }

    @Test void localIncrementalFullAndEveryColumnKeepOriginalValues() {
        seed(target);
        job.run(new DefaultApplicationArguments());
        job.run(new DefaultApplicationArguments());
        assertEquals(target.queryForList("SELECT * FROM ad_event ORDER BY id"), target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
        target.execute("UPDATE ad_event SET event_type='CLICK' WHERE id=3; INSERT INTO ad_event(id,event_type) VALUES(2,'CONVERSION')");
        job.run(new DefaultApplicationArguments());
        assertEquals(2, count());
        assertEquals("IMPRESSION", target.queryForObject("SELECT event_type FROM ad_event_log WHERE id=3", String.class));
        job.run(new DefaultApplicationArguments("--full"));
        assertEquals(3, count());
        assertEquals(target.queryForList("SELECT * FROM ad_event ORDER BY id"), target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
        assertEquals(16, target.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='ad_event_log'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NOT NULL", Integer.class));
        assertEquals(3, target.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='target_store' AND indexname IN('idx_ad_event_log_type_ts','idx_ad_event_log_ad','idx_ad_event_log_req')", Integer.class));
    }

    @Test void crossDatabaseCopiesAllFieldsAndClosesSourceConnection() {
        seed(source);
        source.execute("INSERT INTO ad_event(id) SELECT n FROM generate_series(4,1003) n");
        job.run(new DefaultApplicationArguments("--source-db=isolated"));
        job.run(new DefaultApplicationArguments("--source-db=isolated"));
        assertEquals(1002, count());
        assertEquals(source.queryForList("SELECT * FROM ad_event ORDER BY id"), target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
        assertEquals(0, target.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
        assertSourceClosed();
    }

    @Test void secondBatchFailureLeavesFirstBatchAndNoPartialFailedBatch() {
        job.run(new DefaultApplicationArguments());
        target.execute("ALTER TABLE ad_event_log ADD CHECK(charged_price >= 0)");
        source.execute("INSERT INTO ad_event(id,charged_price) SELECT n,CASE WHEN n=1002 THEN -1 ELSE 1 END FROM generate_series(1,1002) n");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--source-db=isolated")));
        assertEquals(1000, count());
        assertEquals(0, target.queryForObject("SELECT count(*) FROM ad_event_log WHERE id IN(1001,1002)", Integer.class));
        assertSourceClosed();
    }

    @Test void outerTransactionRollsBackFullReloadAndNewRows() {
        seed(target);
        job.run(new DefaultApplicationArguments());
        target.execute("UPDATE ad_event SET event_type='CLICK' WHERE id=3; INSERT INTO ad_event(id) VALUES(2)");
        new TransactionTemplate(new DataSourceTransactionManager(target.getDataSource())).executeWithoutResult(status -> {
            job.run(new DefaultApplicationArguments("--full"));
            assertEquals(3, count());
            status.setRollbackOnly();
        });
        assertEquals(2, count());
        assertEquals("IMPRESSION", target.queryForObject("SELECT event_type FROM ad_event_log WHERE id=3", String.class));
    }

    private void seed(JdbcTemplate db) {
        db.execute("INSERT INTO ad_event(id) VALUES(1)");
        db.update("INSERT INTO ad_event VALUES(3,?,?,9223372036854775807,7,8,0,'IMPRESSION',0.00000001,0.123456789,3.14,1.234567,0.5,'bucket','2024-01-01 00:00:00.123456',9223372036854775807)", "request'中文", "q';SELECT 1");
    }

    private void assertSourceClosed() {
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='adcopy-source_store'", Integer.class));
    }

    private JdbcTemplate jdbc(String schema) {
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=adcopy-" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
    }

    private int count() {
        return target.queryForObject("SELECT count(*) FROM ad_event_log", Integer.class);
    }
}
