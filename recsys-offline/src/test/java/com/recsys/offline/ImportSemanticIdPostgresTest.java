package com.recsys.offline;

import org.junit.jupiter.api.*;
import com.recsys.offline.persistence.SemanticIdStore;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG保护语义ID的宽松CSV解析、重复覆盖、原500行批次及重建事务。 */
@Testcontainers
class ImportSemanticIdPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate admin, db;
    static TrackedDriverDataSource source;
    static ImportSemanticIdJob job;
    static SqlSessionTemplate sessions;
    @TempDir Path files;

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=semantic-id-import-writer",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        sessions=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/derived/**/*.xml"));
        job=new ImportSemanticIdJob(new SemanticIdStore(sessions));
    }
    @BeforeEach void reset() { admin.execute("DROP TABLE IF EXISTS item_semantic_id"); }

    @Test void duplicateIdsDirtyRowsExtraColumnsAndNumericBoundariesArePreserved() throws Exception {
        List<String> lines=new ArrayList<>(List.of(" ","item_id,c0,c1,c2","2,1,2","3,bad,2,3","4,2147483648,2,3","1,1,2,3","1,4,5,6"));
        for(int i=2;i<=500;i++)lines.add(i+",1,2,3");
        lines.add("1,-2147483648,2147483647,-7,ignored,extra");
        lines.add("9223372036854775807,8,9,10");
        job.run(args(csv("codes.csv",lines),"--model=  model'中文  "));
        assertEquals(501,count());
        assertEquals(5,db.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='item_semantic_id'::regclass AND attnum>0 AND col_description(attrelid,attnum) IS NOT NULL",Integer.class));
        assertEquals(List.of(Integer.MIN_VALUE,Integer.MAX_VALUE,-7),db.queryForObject("SELECT c0,c1,c2 FROM item_semantic_id WHERE item_id=1",(rs,n)->List.of(rs.getInt(1),rs.getInt(2),rs.getInt(3))));
        assertEquals("model'中文",db.queryForObject("SELECT model FROM item_semantic_id WHERE item_id=?",String.class,Long.MAX_VALUE));
        assertEquals("生成式召回模型生成的物品分层语义编码",db.queryForObject("SELECT obj_description('item_semantic_id'::regclass)",String.class));
        assertTrue(db.queryForObject("SELECT indexdef LIKE '%(c0, c1, c2)%' FROM pg_indexes WHERE indexname='idx_semid_prefix'",Boolean.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='item_semantic_id'::regclass AND contype='f'",Integer.class));
        source.assertClosed(admin,"semantic-id-import-writer");
    }

    @Test void missingFilePreservesDataWhileExistingEmptyFileClearsIt() throws Exception {
        Path missing=files.resolve("missing.csv");job.run(args(missing));
        assertNull(admin.queryForObject("SELECT to_regclass('item_semantic_id')::text",String.class));
        initializeOldRow();job.run(args(missing));assertEquals(1,count());
        assertEquals("rqvae",db.queryForObject("SELECT model FROM item_semantic_id WHERE item_id=9800",String.class));
        job.run(args(csv("empty.csv",List.of())));assertEquals(0,count());
        source.assertClosed(admin,"semantic-id-import-writer");
    }

    @Test void secondBatchFailureKeepsMeasuredAutocommitBoundary() throws Exception {
        initializeOldRow();db.execute("ALTER TABLE item_semantic_id ADD CONSTRAINT reject_item CHECK(item_id<>700)");
        Path file=csv("failure.csv",thousandRows());
        assertThrows(DataIntegrityViolationException.class,()->job.run(args(file)));
        System.out.println("SEMANTIC_ID_LATE_FAILURE_COMMITTED="+count());
        assertEquals(500,count());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM item_semantic_id WHERE item_id=9800",Integer.class));
        source.assertClosed(admin,"semantic-id-import-writer");
    }

    @Test void successfulRebuildCanBeRolledBackInOriginalSourceTransaction() throws Exception {
        initializeOldRow();Path file=csv("rebuild.csv",thousandRows());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            assertEquals("public",sessions.selectOne("offline.probe.derived.schema"));
            assertDoesNotThrow(()->job.run(args(file)));assertEquals(1000,count());
            assertEquals("public",sessions.selectOne("offline.probe.derived.schema"));
            status.setRollbackOnly();
        });
        assertEquals(List.of(9800L),db.queryForList("SELECT item_id FROM item_semantic_id",Long.class));
        source.assertClosed(admin,"semantic-id-import-writer");
    }

    @Test void failedOuterTransactionRestoresDataAcrossClearAndBothBatches() throws Exception {
        initializeOldRow();db.execute("ALTER TABLE item_semantic_id ADD CONSTRAINT reject_item CHECK(item_id<>700)");
        Path file=csv("rollback.csv",thousandRows());
        assertThrows(DataIntegrityViolationException.class,()->new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->runUnchecked(file)));
        assertEquals(List.of(9800L),db.queryForList("SELECT item_id FROM item_semantic_id",Long.class));
        source.assertClosed(admin,"semantic-id-import-writer");
    }

    @Test void outerCommitKeepsSimpleSessionUsableAcrossBootstrapAndBatches() throws Exception {
        Path file=csv("commit.csv",thousandRows());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            assertEquals("public",sessions.selectOne("offline.probe.derived.schema"));
            assertDoesNotThrow(()->job.run(args(file)));assertEquals(1000,count());
            assertEquals("public",sessions.selectOne("offline.probe.derived.schema"));
        });
        assertEquals(1000,count());
        source.assertClosed(admin,"semantic-id-import-writer");
    }

    private void initializeOldRow() throws Exception { job.run(args(csv("init.csv",List.of("9800,1,2,3")))); }
    private static void runUnchecked(Path file) {
        try { job.run(args(file)); }
        catch(RuntimeException failure){throw failure;}
        catch(Exception unexpected){throw new AssertionError(unexpected);}
    }
    private Path csv(String name,List<String> lines) throws Exception { return Files.write(files.resolve(name),lines); }
    private static List<String> thousandRows(){return IntStream.rangeClosed(1,1000).mapToObj(i->i+",1,2,3").toList();}
    private static DefaultApplicationArguments args(Path file,String...extra){
        List<String> values=new ArrayList<>();values.add("--file="+file);values.addAll(List.of(extra));
        return new DefaultApplicationArguments(values.toArray(String[]::new));
    }
    private static int count(){return db.queryForObject("SELECT count(*) FROM item_semantic_id",Integer.class);}
}
