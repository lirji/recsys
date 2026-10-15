package com.recsys.offline;

import com.recsys.offline.persistence.R9EmbeddingStore;
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
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** R9三组版本发布真实事务保护，明确记录原手动提交与外层事务的相互影响。 */
@Testcontainers
class ImportR9EmbeddingsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static ImportR9EmbeddingsJob job;
    static SqlSessionTemplate owner;
    static final List<String> TABLES=List.of("multi_interest_item_embedding","graph_user_embedding","graph_item_embedding");
    @TempDir Path files;

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=r9-publish-writer",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        owner=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/*/derived/**/*.xml"));
        job=new ImportR9EmbeddingsJob(new R9EmbeddingStore(owner));
        admin.execute("CREATE TABLE transaction_probe(id INT PRIMARY KEY)");
        admin.execute("COMMENT ON TABLE transaction_probe IS 'R9手动提交边界独占测试'");
        admin.execute("COMMENT ON COLUMN transaction_probe.id IS '调用方事务中前后写入的探针'");
    }
    @BeforeEach void reset(){
        for(String table:TABLES)admin.execute("DROP TABLE IF EXISTS "+table);
        admin.execute("TRUNCATE transaction_probe");
    }

    @Test void replacesOnlyOneVersionAcrossThreeGroupsAndMultipleBatches() throws Exception {
        job.run(artifacts("old",1,1));job.run(artifacts("release'一",1,1));
        job.run(artifacts("release'一",2,1002));
        for(String table:TABLES){
            assertEquals(1,count(table,"old"));assertEquals(1001,count(table,"release'一"));
            int axis=TABLES.indexOf(table);
            String expected="["+IntStream.range(0,64).mapToObj(i->i==axis?"1":"0").collect(Collectors.joining(","))+"]";
            assertEquals(List.of(expected),db.queryForList("SELECT DISTINCT embedding::text FROM "+table+" WHERE model_version=?",String.class,"release'一"));
            String id=table.equals("graph_user_embedding")?"user_id":"item_id";
            assertEquals(2L,db.queryForObject("SELECT min("+id+") FROM "+table+" WHERE model_version=?",Long.class,"release'一"));
            assertEquals(1002L,db.queryForObject("SELECT max("+id+") FROM "+table+" WHERE model_version=?",Long.class,"release'一"));
        }
        assertTrue(db.queryForObject("SELECT count(*)=2 FROM pg_indexes WHERE indexname IN('idx_multi_interest_item_hnsw','idx_graph_item_hnsw')",Boolean.class));
        source.assertClosed(admin,"r9-publish-writer");
    }

    @Test void optionalMissingGroupsKeepTheirExistingVersionRows() throws Exception {
        job.run(artifacts("v1",1,1));
        Path mind=write("partial.csv",List.of(row("v1",9,0)));
        job.run(args(mind,files.resolve("no-users.csv"),files.resolve("no-items.csv"),"--require-all=false"));
        assertEquals(List.of(9L),db.queryForList("SELECT item_id FROM multi_interest_item_embedding",Long.class));
        assertEquals(List.of(1L),db.queryForList("SELECT user_id FROM graph_user_embedding",Long.class));
        assertEquals(List.of(1L),db.queryForList("SELECT item_id FROM graph_item_embedding",Long.class));
        source.assertClosed(admin,"r9-publish-writer");
    }

    @Test void validationFinishesBeforeAnyBootstrapOrMutation() throws Exception {
        Path good=write("good.csv",List.of(row("v1",1,0)));
        Path missing=files.resolve("missing.csv");
        assertThrows(IllegalArgumentException.class,()->job.run(args(good,good,missing)));
        Path duplicate=write("duplicate.csv",List.of(row("v1",1,0),row("v1",1,1)));
        assertThrows(IllegalArgumentException.class,()->job.run(args(good,duplicate,good)));
        Path mismatch=write("version.csv",List.of(row("v2",1,0)));
        assertThrows(IllegalArgumentException.class,()->job.run(args(good,mismatch,good)));
        Path empty=write("empty.csv",List.of());
        assertThrows(IllegalArgumentException.class,()->job.run(args(good,empty,good)));
        for(String table:TABLES)assertNull(admin.queryForObject("SELECT to_regclass(?)::text",String.class,table));
        source.assertClosed(admin,"r9-publish-writer");
    }

    @Test void allOptionalMissingFilesReturnWithoutCreatingTables() throws Exception {
        Path missing=files.resolve("missing.csv");job.run(args(missing,missing,missing,"--require-all=false"));
        for(String table:TABLES)assertNull(admin.queryForObject("SELECT to_regclass(?)::text",String.class,table));
        source.assertClosed(admin,"r9-publish-writer");
    }

    @Test void lateThirdGroupFailureRestoresAllOldVersionRows() throws Exception {
        job.run(artifacts("old",1,1));job.run(artifacts("v1",1,1));
        var before=snapshot();
        db.execute("ALTER TABLE graph_item_embedding ADD CONSTRAINT reject_item CHECK(item_id<>700)");
        var input=artifacts("v1",2,1002);
        assertThrows(DataIntegrityViolationException.class,()->job.run(input));
        assertEquals(before,snapshot());
        source.assertClosed(admin,"r9-publish-writer");
    }

    @Test void originalManualCommitAlsoCommitsPrecedingOuterTransactionWork() throws Exception {
        job.run(artifacts("v1",1,1));var input=artifacts("v1",2,2);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            assertEquals("public",owner.selectOne("offline.probe.derived.schema"));
            db.update("INSERT INTO transaction_probe VALUES(1)");runUnchecked(input);
            assertEquals("public",owner.selectOne("offline.probe.derived.schema"));
            db.update("INSERT INTO transaction_probe VALUES(2)");status.setRollbackOnly();
        });
        // 这是旧实现的真实行为，不将手动commit误描述为受外层rollback保护。
        assertEquals(List.of(1),db.queryForList("SELECT id FROM transaction_probe ORDER BY id",Integer.class));
        assertEquals(List.of(2L),db.queryForList("SELECT item_id FROM multi_interest_item_embedding",Long.class));
        source.assertClosed(admin,"r9-publish-writer");
    }

    @Test void originalManualFailureRollsBackPrecedingOuterTransactionWork() throws Exception {
        job.run(artifacts("v1",1,1));var before=snapshot();
        db.execute("ALTER TABLE graph_item_embedding ADD CONSTRAINT reject_item CHECK(item_id<>2)");
        var input=artifacts("v1",2,2);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            db.update("INSERT INTO transaction_probe VALUES(1)");
            assertThrows(DataIntegrityViolationException.class,()->runUnchecked(input));
            db.update("INSERT INTO transaction_probe VALUES(2)");
        });
        assertEquals(List.of(2),db.queryForList("SELECT id FROM transaction_probe ORDER BY id",Integer.class));
        assertEquals(before,snapshot());
        source.assertClosed(admin,"r9-publish-writer");
    }

    @Test void bootstrapDocumentsEveryTableAndColumnWithoutChangingTheOrdinarySession() throws Exception {
        var originalEnvironment=owner.getSqlSessionFactory().getConfiguration().getEnvironment();
        job.run(artifacts("documented",1,1));
        for(String table:TABLES){
            assertNotNull(db.queryForObject("SELECT obj_description(?::regclass,'pg_class')",String.class,table));
            assertEquals(3,db.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NOT NULL",Integer.class,table));
        }
        assertSame(originalEnvironment,owner.getSqlSessionFactory().getConfiguration().getEnvironment());
        assertFalse(owner.getSqlSessionFactory().getConfiguration().hasMapper(
                com.recsys.offline.persistence.mapper.manual.R9EmbeddingMapper.class));
        assertEquals("public",owner.selectOne("offline.probe.derived.schema"));
        source.assertClosed(admin,"r9-publish-writer");
    }

    private DefaultApplicationArguments artifacts(String version,int from,int to) throws Exception {
        Path mind=write("mind.csv",IntStream.rangeClosed(from,to).mapToObj(i->row(version,i,0)).toList());
        Path users=write("users.csv",IntStream.rangeClosed(from,to).mapToObj(i->row(version,i,1)).toList());
        Path items=write("items.csv",IntStream.rangeClosed(from,to).mapToObj(i->row(version,i,2)).toList());
        return args(mind,users,items);
    }
    private Path write(String name,List<String> rows) throws Exception {return Files.write(files.resolve(name),rows);}
    private static String row(String version,int id,int axis){return version+","+id+","+IntStream.range(0,64).mapToObj(i->i==axis?"1":"0").collect(Collectors.joining(","));}
    private static DefaultApplicationArguments args(Path mind,Path users,Path items,String...extra){
        var values=new java.util.ArrayList<>(List.of("--mind-file="+mind,"--graph-user-file="+users,"--graph-item-file="+items));values.addAll(List.of(extra));return new DefaultApplicationArguments(values.toArray(String[]::new));
    }
    private static int count(String table,String version){return db.queryForObject("SELECT count(*) FROM "+table+" WHERE model_version=?",Integer.class,version);}
    private static Map<String,List<Map<String,Object>>> snapshot(){
        Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();
        for(String table:TABLES){String id=table.equals("graph_user_embedding")?"user_id":"item_id";rows.put(table,db.queryForList("SELECT model_version,"+id+",embedding::text FROM "+table+" ORDER BY model_version,"+id));}return rows;
    }
    private static void runUnchecked(DefaultApplicationArguments args){try{job.run(args);}catch(RuntimeException e){throw e;}catch(Exception e){throw new AssertionError(e);}}
}
