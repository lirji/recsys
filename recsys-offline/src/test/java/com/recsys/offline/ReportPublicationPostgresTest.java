package com.recsys.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.offline.persistence.mapper.primary.ReportPublicationMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 独占PG验证报表JSONB发布、CSV容错、名称幂等和原事务边界。 */
@Testcontainers
class ReportPublicationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate db,admin;static TrackedDriverDataSource source;static PublishReportsJob job;
    static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path directory;
    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+"&ApplicationName=report-publisher",PG.getUsername(),PG.getPassword());db=new JdbcTemplate(source);job=new PublishReportsJob(new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml")).getMapper(ReportPublicationMapper.class));
    }
    @BeforeEach void reset(){db.execute("DROP TABLE IF EXISTS eval_report");}
    @AfterEach void closeConnections(){source.assertClosed(admin,"report-publisher");}
    @Test void bootstrapStillHappensBeforeMissingDirectoryCheck() throws Exception {
        job.run(new DefaultApplicationArguments("--eval-dir="+directory.resolve("missing")));assertEquals(0,count());
        assertEquals("离线评估与分析报表的结构化存储",db.queryForObject("SELECT obj_description('eval_report'::regclass)",String.class));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='eval_report' AND indexname='idx_eval_report_cat_ts'",Integer.class));
        assertEquals(8,db.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='eval_report'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NOT NULL",Integer.class));
        run();assertEquals(0,count());
    }
    @Test void csvColumnsRowsPaddingAndUtf8AreStoredAsStructuredJsonb() throws Exception {
        Path file=write("metrics-20250101-120000.csv","name,note,extra\n\"电影,一\",\"跨\n行\",ok,ignored\nshort\n");run();
        assertEquals("eval",field("category",file));assertEquals("20250101-120000",field("ts",file));
        assertEquals(JSON.readTree("[\"name\",\"note\",\"extra\"]"),JSON.readTree(field("columns_json::text",file)));
        assertEquals(JSON.readTree("[[\"电影,一\",\"跨\\n行\",\"ok\"],[\"short\",\"\",\"\"]]"),JSON.readTree(field("rows_json::text",file)));
        assertEquals(Files.size(file),db.queryForObject("SELECT size_bytes FROM eval_report WHERE name=?",Long.class,file.getFileName().toString()));
        assertNotNull(field("created_at::text",file));
    }
    @Test void allCategoriesUnknownNamesAndSqlLikeFileNameAreBoundValues() throws Exception {
        for(String prefix:List.of("ab-report-","ad-report-","data-quality-","ad-quality-"))write(prefix+"date.csv","a\n1\n");
        Path custom=write("custom.csv","a\n1\n");Path special=write("metrics-x' OR true --.csv","a\n2\n");write("ignored.CSV","a\n3\n");write("ignored.txt","a\n4\n");run();assertEquals(6,count());
        for(String prefix:List.of("ab-report-","ad-report-","data-quality-","ad-quality-"))assertEquals(prefix.substring(0,prefix.length()-1),field("category",directory.resolve(prefix+"date.csv")));
        assertEquals("other",field("category",custom));assertEquals("custom",field("ts",custom));assertEquals("x' OR true --",field("ts",special));
    }
    @Test void sameNameKeepsOriginalJsonIdentityAndCreationTime() throws Exception {
        Path file=write("metrics-same.csv","a\nfirst\n");run();String created=field("created_at::text",file);Long id=db.queryForObject("SELECT id FROM eval_report",Long.class);
        Files.writeString(file,"different\nsecond\n");run();assertEquals(1,count());assertEquals("[[\"first\"]]",field("rows_json::text",file));assertEquals(created,field("created_at::text",file));assertEquals(id,db.queryForObject("SELECT id FROM eval_report",Long.class));
    }
    @Test void malformedFileDirectoryAndRejectedRowDoNotStopLaterFiles() throws Exception {
        run();db.execute("ALTER TABLE eval_report ADD CONSTRAINT reject_test_report CHECK(name<>'02-reject.csv')");
        write("00-good.csv","a\n0\n");write("01-malformed.csv","a\n\"unfinished\n");write("02-reject.csv","a\n2\n");Files.createDirectory(directory.resolve("03-directory.csv"));write("04-good.csv","a\n4\n");run();
        assertEquals(List.of("00-good.csv","04-good.csv"),db.queryForList("SELECT name FROM eval_report ORDER BY name",String.class));
    }
    @Test void outerTransactionRollsBackPublicationAndBootstrapFailurePropagates() throws Exception {
        run();write("metrics-tx.csv","a\n1\n");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{try{run();}catch(Exception error){throw new RuntimeException(error);}assertEquals(1,count());status.setRollbackOnly();});assertEquals(0,count());
        db.execute("ALTER TABLE eval_report RENAME COLUMN ts TO hidden_ts;DROP INDEX idx_eval_report_cat_ts");
        try{assertThrows(BadSqlGrammarException.class,this::run);assertEquals(0,count());}finally{db.execute("ALTER TABLE eval_report RENAME COLUMN hidden_ts TO ts");}
    }
    private static int count(){return db.queryForObject("SELECT count(*) FROM eval_report",Integer.class);}
    private String field(String field,Path file){return db.queryForObject("SELECT "+field+" FROM eval_report WHERE name=?",String.class,file.getFileName().toString());}
    private Path write(String name,String text) throws Exception {return Files.writeString(directory.resolve(name),text);}
    private void run() throws Exception {job.run(new DefaultApplicationArguments("--eval-dir="+directory));}
}
