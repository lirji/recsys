package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.pgvector.PGvector;
import com.recsys.offline.persistence.TowerEmbeddingStore;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实pgvector冻结双塔导入的重复ID覆盖、500行边界、固定DDL维度与事务语义。 */
@Testcontainers
class ImportTowerPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate admin, db;
    static TrackedDriverDataSource source;
    static ImportTowerJob job;
    static SqlSessionTemplate sessions;
    @TempDir Path files;

    @BeforeAll static void prepare() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        source = new TrackedDriverDataSource(PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?")
                + "ApplicationName=tower-import-writer", PG.getUsername(), PG.getPassword());
        db = new JdbcTemplate(source);
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(source, "classpath*:mapper/offline/derived/**/*.xml"));
        job = new ImportTowerJob(new TowerEmbeddingStore(sessions));
    }

    @BeforeEach void reset() { admin.execute("DROP TABLE IF EXISTS item_tower_embedding"); }

    @Test void defaultDimensionDirtyRowsAndDuplicateIdsAcrossBatchesRemainExact() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(" "); lines.add("bad" + row(1, 64, 0).substring(1));
        lines.add("12,1,2"); lines.add("13,not-a-float," + IntStream.range(1,64).mapToObj(i -> "0").collect(Collectors.joining(",")));
        lines.add(row(1, 64, 0)); lines.add(row(1, 64, 1));
        for (int i=2;i<=500;i++) lines.add(row(i, 64, 0));
        lines.add(row(1, 64, 2)); lines.add(row(501, 64, 1));
        var input = args(csv("duplicates.csv", lines));
        assertEquals(ErrorCode.JOB_IMPORT_PARTIAL_FAILED, assertThrows(PublicErrorException.class, () -> job.run(input)).errorCode());
        assertEquals(501, count()); assertVector(1, 64, 2); assertVector(501, 64, 1);
        assertEquals("vector(64)", dimension());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='item_tower_embedding'::regclass AND attnum>0 AND col_description(attrelid,attnum) IS NOT NULL", Integer.class));
        assertEquals("双塔召回模型生成的物品侧向量", db.queryForObject("SELECT obj_description('item_tower_embedding'::regclass)", String.class));
        assertTrue(db.queryForObject("SELECT indexdef LIKE '%USING hnsw%vector_cosine_ops%' FROM pg_indexes WHERE indexname='idx_item_tower_hnsw'", Boolean.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='item_tower_embedding'::regclass AND contype='f'", Integer.class));
        source.assertClosed(admin, "tower-import-writer");
    }

    @Test void missingFileDoesNotBootstrapOrClearAndEmptyFileDoesClear() throws Exception {
        Path missing = files.resolve("missing.csv");
        assertEquals(ErrorCode.JOB_INPUT_UNAVAILABLE, assertThrows(PublicErrorException.class, () -> job.run(args(missing))).errorCode());
        assertNull(admin.queryForObject("SELECT to_regclass('item_tower_embedding')::text", String.class));
        Path empty = csv("empty.csv", List.of()); job.run(args(empty));
        db.update("INSERT INTO item_tower_embedding VALUES(9800,?::vector)", new PGvector(vector(64, 0)).toString());
        assertEquals(ErrorCode.JOB_INPUT_UNAVAILABLE, assertThrows(PublicErrorException.class, () -> job.run(args(missing))).errorCode()); assertEquals(1, count());
        job.run(args(empty)); assertEquals(0, count());
        source.assertClosed(admin, "tower-import-writer");
    }

    @Test void dimArgumentDoesNotAlterFixedBootstrapOrExistingTableType() throws Exception {
        Path small = csv("three.csv", List.of(row(1,3,0)));
        assertThrows(DataIntegrityViolationException.class, () -> job.run(args(small,"--dim=3")));
        assertEquals("vector(64)", dimension()); assertEquals(0, count());
        db.execute("DROP TABLE item_tower_embedding; CREATE TABLE item_tower_embedding(item_id BIGINT PRIMARY KEY,embedding vector(3))");
        db.execute("COMMENT ON TABLE item_tower_embedding IS '已有三维测试表'");
        db.execute("COMMENT ON COLUMN item_tower_embedding.item_id IS '物品标识'");
        db.execute("COMMENT ON COLUMN item_tower_embedding.embedding IS '三维测试向量'");
        job.run(args(small,"--dim=3"));
        assertEquals("vector(3)", dimension()); assertVector(1,3,0);
        source.assertClosed(admin, "tower-import-writer");
    }

    @Test void lateSecondBatchFailureKeepsOriginalCommittedProgress() throws Exception {
        initializeOldRow(); db.execute("ALTER TABLE item_tower_embedding ADD CONSTRAINT reject_item CHECK(item_id<>700)");
        Path file = csv("late.csv", thousandRows());
        assertThrows(DataIntegrityViolationException.class, () -> job.run(args(file)));
        System.out.println("TOWER_LATE_FAILURE_COMMITTED=" + count());
        // 冻结本语句/500行批次的旧PG实测，第二批失败不损失第一批。
        assertEquals(500, count());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM item_tower_embedding WHERE item_id=9800", Integer.class));
        source.assertClosed(admin, "tower-import-writer");
    }

    @Test void outerRollbackRestoresRowsRemovedByTruncate() throws Exception {
        initializeOldRow(); Path file = csv("rebuild.csv", thousandRows());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            assertEquals("public", sessions.selectOne("offline.probe.derived.schema"));
            assertDoesNotThrow(() -> job.run(args(file)));
            assertEquals("public", sessions.selectOne("offline.probe.derived.schema"));
            assertEquals(1000, count()); status.setRollbackOnly();
        });
        assertEquals(List.of(9800L), db.queryForList("SELECT item_id FROM item_tower_embedding", Long.class));
        source.assertClosed(admin, "tower-import-writer");
    }

    @Test void outerFailureRestoresPriorDataAcrossBothBatches() throws Exception {
        initializeOldRow(); db.execute("ALTER TABLE item_tower_embedding ADD CONSTRAINT reject_item CHECK(item_id<>700)");
        Path file = csv("failure.csv", thousandRows());
        assertThrows(DataIntegrityViolationException.class, () ->
                new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> runUnchecked(file)));
        assertEquals(List.of(9800L), db.queryForList("SELECT item_id FROM item_tower_embedding", Long.class));
        source.assertClosed(admin, "tower-import-writer");
    }

    @Test void outerCommitKeepsSimpleSessionUsableAcrossBootstrapAndBatches() throws Exception {
        Path file = csv("commit.csv", thousandRows());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            assertEquals("public", sessions.selectOne("offline.probe.derived.schema"));
            assertDoesNotThrow(() -> job.run(args(file)));
            assertEquals("public", sessions.selectOne("offline.probe.derived.schema"));
            assertEquals(1000, count());
        });
        assertEquals(1000, count());
        source.assertClosed(admin, "tower-import-writer");
    }

    @Test void invalidArgumentsFailBeforeRebuildingExistingProjection() throws Exception {
        initializeOldRow(); Path file = csv("valid.csv", List.of(row(1,64,0)));
        for (String option : List.of("--dim=0", "--dim=-1", "--dim=private", "--dim")) {
            var failure = assertThrows(PublicErrorException.class, () -> job.run(args(file, option)));
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, failure.errorCode());
            assertEquals(List.of(9800L), db.queryForList("SELECT item_id FROM item_tower_embedding", Long.class));
        }
        source.assertClosed(admin, "tower-import-writer");
    }

    private void initializeOldRow() throws Exception {
        job.run(args(csv("init.csv", List.of(row(9800,64,0)))));
    }
    private static void runUnchecked(Path file) {
        try { job.run(args(file)); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception unexpected) { throw new AssertionError(unexpected); }
    }
    private Path csv(String name, List<String> lines) throws Exception { return Files.write(files.resolve(name), lines); }
    private static List<String> thousandRows() { return IntStream.rangeClosed(1,1000).mapToObj(i -> row(i,64,0)).toList(); }
    private static float[] vector(int dim, int axis) { float[] result=new float[dim];result[axis]=1;return result; }
    private static String row(long id, int dim, int axis) {
        return id + "," + IntStream.range(0,dim).mapToObj(i -> i==axis?"1":"0").collect(Collectors.joining(","));
    }
    private static DefaultApplicationArguments args(Path file, String... extra) {
        List<String> values=new ArrayList<>();values.add("--file="+file);values.addAll(List.of(extra));
        return new DefaultApplicationArguments(values.toArray(String[]::new));
    }
    private static int count() { return db.queryForObject("SELECT count(*) FROM item_tower_embedding", Integer.class); }
    private static String dimension() {
        return db.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='item_tower_embedding'::regclass AND attname='embedding'", String.class);
    }
    private static void assertVector(long id, int dim, int axis) {
        String actual=db.queryForObject("SELECT embedding::text FROM item_tower_embedding WHERE item_id=?",String.class,id);
        assertArrayEquals(vector(dim,axis),assertDoesNotThrow(()->new PGvector(actual)).toArray());
    }
}
