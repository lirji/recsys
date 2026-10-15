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

/** 目录副本保护：全列覆盖、原NULL数组差异、生成列、批次与外层事务。 */
@Testcontainers
class SyncItemCatalogPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate target, source, admin;
    SyncItemCatalogJob job;

    @BeforeEach void prepare() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        admin.execute("DROP SCHEMA IF EXISTS target_store CASCADE; DROP SCHEMA IF EXISTS source_store CASCADE; CREATE SCHEMA target_store; CREATE SCHEMA source_store");
        target = jdbc("target_store"); source = jdbc("source_store");
        for (var db : new JdbcTemplate[]{target, source}) {
            db.execute("""
                    CREATE TABLE item(item_id bigint PRIMARY KEY, title text, category text, tags text[],
                        description text, popularity double precision);
                    COMMENT ON TABLE item IS '独占目录同步源';
                    COMMENT ON COLUMN item.item_id IS '原物品身份';
                    COMMENT ON COLUMN item.title IS '可空标题';
                    COMMENT ON COLUMN item.category IS '可空类目';
                    COMMENT ON COLUMN item.tags IS '可空标签，允许重复及NULL元素';
                    COMMENT ON COLUMN item.description IS '可空描述';
                    COMMENT ON COLUMN item.popularity IS '可空热度';
                    """);
        }
        var targetSessions = new SqlSessionTemplate(SpringSqlSessions.create(target.getDataSource(),
                "classpath*:mapper/offline/primary/**/*.xml"));
        var sourceSessions = new SqlSessionTemplate(SpringSqlSessions.create(source.getDataSource(),
                "classpath*:mapper/offline/source/ItemCopySourceMapper.xml"));
        assertFalse(targetSessions.getConfiguration().hasStatement(
                "com.recsys.offline.persistence.mapper.source.ItemCopySourceMapper.items"));
        job = new SyncItemCatalogJob(targetSessions) {
            @Override SqlSessionTemplate buildSourceSessions(String name) {
                assertEquals("isolated", name);
                return sourceSessions;
            }
        };
    }

    @Test void localUpsertUpdatesAllColumnsAndFullAloneRemovesDeletedItems() {
        target.execute("INSERT INTO item VALUES(1,NULL,NULL,NULL,NULL,NULL),(2,'running','Action',ARRAY['a,b',NULL,'中文','a,b'],'old',3)");
        job.run(new DefaultApplicationArguments());
        assertNull(target.queryForObject("SELECT tags FROM item_local WHERE item_id=1", Object.class));
        assertEquals(1, target.queryForObject("SELECT count(*) FROM item_local WHERE title_tsv @@ to_tsquery('english','run')", Integer.class));
        target.execute("DELETE FROM item WHERE item_id=1; UPDATE item SET title='walking',category=NULL,tags=ARRAY[]::text[],description=NULL,popularity=NULL WHERE item_id=2");
        job.run(new DefaultApplicationArguments());
        assertEquals(2, count());
        assertEquals(1, target.queryForObject("SELECT count(*) FROM item_local WHERE item_id=2 AND category IS NULL AND cardinality(tags)=0 AND description IS NULL AND popularity IS NULL AND title_tsv @@ to_tsquery('english','walk')", Integer.class));
        assertEquals(0, target.queryForObject("SELECT count(*) FROM item_local WHERE title_tsv @@ to_tsquery('english','run')", Integer.class));
        job.run(new DefaultApplicationArguments("--full"));
        assertEquals(1, count());
        assertEquals(7, target.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='item_local'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NOT NULL", Integer.class));
        assertNotNull(target.queryForObject("SELECT to_regclass('idx_item_local_title_tsv')::text", String.class));
    }

    @Test void crossDatabasePreservesArrayOrderDuplicatesAndConvertsNullArrayToEmpty() {
        source.execute("INSERT INTO item VALUES(1,NULL,NULL,NULL,NULL,NULL),(2,'中文','Action',ARRAY['a,b',NULL,'中文','a,b'],'quoted',3)");
        job.run(new DefaultApplicationArguments("--source-db=isolated"));
        job.run(new DefaultApplicationArguments("--source-db=isolated"));
        assertEquals(2, count());
        assertEquals(0, target.queryForObject("SELECT cardinality(tags) FROM item_local WHERE item_id=1", Integer.class));
        assertEquals(1, target.queryForObject("SELECT count(*) FROM item_local WHERE item_id=2 AND tags=ARRAY['a,b',NULL,'中文','a,b'] AND popularity=3 AND title='中文' AND description='quoted'", Integer.class));
        assertEquals(0, target.queryForObject("SELECT count(*) FROM item", Integer.class));
        assertSourceClosed();
    }

    @Test void secondBatchFailureKeepsFirstBatchAndNoPartOfFailedBatch() {
        job.run(new DefaultApplicationArguments());
        target.execute("ALTER TABLE item_local ADD CHECK(popularity >= 0)");
        source.execute("INSERT INTO item(item_id,popularity) SELECT n,CASE WHEN n=1002 THEN -1 ELSE 1 END FROM generate_series(1,1002) n");
        assertThrows(DataIntegrityViolationException.class, () -> job.run(new DefaultApplicationArguments("--source-db=isolated")));
        assertEquals(1000, count());
        assertEquals(0, target.queryForObject("SELECT count(*) FROM item_local WHERE item_id IN(1001,1002)", Integer.class));
        assertSourceClosed();
    }

    @Test void outerTransactionRollsBackFullReloadAndUpdatesTogether() {
        target.execute("INSERT INTO item(item_id,title) VALUES(1,'old')");
        job.run(new DefaultApplicationArguments());
        target.execute("UPDATE item SET title='new'; INSERT INTO item(item_id,title) VALUES(2,'second')");
        new TransactionTemplate(new DataSourceTransactionManager(target.getDataSource())).executeWithoutResult(status -> {
            job.run(new DefaultApplicationArguments("--full"));
            assertEquals(2, count());
            status.setRollbackOnly();
        });
        assertEquals(1, count());
        assertEquals("old", target.queryForObject("SELECT title FROM item_local WHERE item_id=1", String.class));
    }

    private void assertSourceClosed() {
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='catalog-source_store'", Integer.class));
    }

    private JdbcTemplate jdbc(String schema) {
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=catalog-" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword()));
    }

    private int count() {
        return target.queryForObject("SELECT count(*) FROM item_local", Integer.class);
    }
}
