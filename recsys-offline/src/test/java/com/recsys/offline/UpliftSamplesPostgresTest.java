package com.recsys.offline;

import com.recsys.offline.persistence.mapper.addb.UpliftSampleMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG验证成熟期、归因边界、整用户时间切分及CSV失败保留。 */
@Testcontainers
class UpliftSamplesPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate db,admin;static TrackedDriverDataSource source;static GenAdUpliftSamplesJob job;
    @TempDir Path output;
    static final String AS_OF="2025-01-20T00:00:00Z";
    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=uplift-samples-reader",PG.getUsername(),PG.getPassword());db=new JdbcTemplate(source);
        db.execute("CREATE TABLE ad_uplift_assignment(assignment_id UUID PRIMARY KEY,user_id BIGINT,item_id BIGINT,ad_id BIGINT,advertiser_id BIGINT,treatment BOOLEAN,propensity DOUBLE PRECISION,pctr DOUBLE PRECISION,pcvr DOUBLE PRECISION,quality DOUBLE PRECISION,relevance DOUBLE PRECISION,bid DOUBLE PRECISION,assigned_at TIMESTAMPTZ NOT NULL,outcome_due_at TIMESTAMPTZ NOT NULL)");
        db.execute("COMMENT ON TABLE ad_uplift_assignment IS '独占uplift样本分配测试'");
        for(String c:List.of("assignment_id","user_id","item_id","ad_id","advertiser_id","treatment","propensity","pctr","pcvr","quality","relevance","bid","assigned_at","outcome_due_at"))db.execute("COMMENT ON COLUMN ad_uplift_assignment."+c+" IS '原随机分配输入字段'");
        db.execute("CREATE TABLE ad_conversion_fact(advertiser_id BIGINT,user_id BIGINT,objective TEXT,occurred_at TIMESTAMPTZ)");
        db.execute("COMMENT ON TABLE ad_conversion_fact IS '独占uplift独立转化事实测试'");
        for(String c:List.of("advertiser_id","user_id","objective","occurred_at"))db.execute("COMMENT ON COLUMN ad_conversion_fact."+c+" IS '归因匹配字段'");
        job=new GenAdUpliftSamplesJob(new SqlSessionTemplate(SpringSqlSessions.create(source,
                "classpath*:mapper/offline/addb/**/*.xml")).getMapper(UpliftSampleMapper.class));
    }
    @BeforeEach void reset(){db.execute("TRUNCATE ad_uplift_assignment,ad_conversion_fact");}
    @Test void sortedAssignmentsInclusiveOutcomesAndWholeUserSplitMatchCsv() throws Exception {
        seed();run();List<String[]> rows=rows();assertEquals(7,rows.size());
        assertEquals(List.of("1","2","3","4","5","6","1"),rows.stream().map(r->r[3]).toList());
        assertEquals(List.of("1","1","0","0","0","0","0"),rows.stream().map(r->r[0]).toList());
        assertEquals(List.of("valid","train","train","train","train","valid","valid"),rows.stream().map(r->r[12]).toList());
        assertEquals("1,1,0.5,1,101,201,10,0.1,0.2,0.3,0.4,0.5,valid",String.join(",",rows.getFirst()));
        assertEquals("0",rows.get(1)[1]);source.assertClosed(admin,"uplift-samples-reader");
    }
    @Test void maturityAndDaysUseInclusiveExactBoundaries() throws Exception {
        seed();assignment(8,8,"2025-01-03T00:00:00Z",AS_OF);assignment(9,9,"2025-01-03T00:00:00Z","2025-01-20T00:00:00.000001Z");
        assignment(10,10,"2025-01-02T23:59:59.999999Z",AS_OF);
        run("--days=17");var ids=rows().stream().map(r->r[3]).toList();assertTrue(ids.contains("8"));assertFalse(ids.contains("9"));assertFalse(ids.contains("10"));assertFalse(ids.contains("2"));
        run("--days=-1");assertEquals(9,rows().size());source.assertClosed(admin,"uplift-samples-reader");
    }
    @Test void objectiveIsBoundAndAlternateObjectiveOnlyChangesLabels() throws Exception {
        seed();db.execute("INSERT INTO ad_conversion_fact VALUES(10,3,'signup','2025-01-04T00:00:00Z')");
        run("--objective=signup");assertEquals(List.of("0","0","1","1","0","0","0"),rows().stream().map(r->r[0]).toList());
        run("--objective=purchase' OR true --");assertTrue(rows().stream().allMatch(r->r[0].equals("0")));assertEquals(7,db.queryForObject("SELECT count(*) FROM ad_uplift_assignment",Integer.class));source.assertClosed(admin,"uplift-samples-reader");
    }
    @Test void nullNumericIdsAndLongMaxValuePreserveOriginalCsvValues() throws Exception {
        db.execute("INSERT INTO ad_uplift_assignment VALUES('00000000-0000-0000-0000-000000000001',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2025-01-01','2025-01-02')");
        db.execute("INSERT INTO ad_uplift_assignment VALUES('00000000-0000-0000-0000-000000000002',9223372036854775807,9223372036854775807,9223372036854775807,9223372036854775807,true,0.25,0.1,0.2,0.3,0.4,1.5,'2025-01-03','2025-01-04')");
        run();assertEquals("0,0,0.0,0,0,0,0,0.0,0.0,0.0,0.0,0.0,train",String.join(",",rows().getFirst()));assertEquals(Long.toString(Long.MAX_VALUE),rows().get(1)[3]);source.assertClosed(admin,"uplift-samples-reader");
    }
    @Test void emptyOrImmatureInputsAndSqlFailurePreservePreviousFile() throws Exception {
        Files.writeString(csv(),"keep");run();assertEquals("keep",Files.readString(csv()));
        assignment(1,1,"2025-01-01T00:00:00Z","2025-01-21T00:00:00Z");run();assertEquals("keep",Files.readString(csv()));
        db.execute("ALTER TABLE ad_conversion_fact RENAME TO hidden_facts");
        try{assertThrows(BadSqlGrammarException.class,()->run());assertEquals("keep",Files.readString(csv()));source.assertClosed(admin,"uplift-samples-reader");}
        finally{db.execute("ALTER TABLE hidden_facts RENAME TO ad_conversion_fact");}
    }
    @Test void invalidAsOfAndUnwritableOutputFailWithoutLeakingReadConnection() throws Exception {
        seed();assertThrows(java.time.format.DateTimeParseException.class,()->run("--as-of=invalid"));
        Path directory=output.resolve("directory");Files.createDirectory(directory);
        assertThrows(java.io.IOException.class,()->job.run(new DefaultApplicationArguments("--out="+directory,"--as-of="+AS_OF)));source.assertClosed(admin,"uplift-samples-reader");
    }
    private static void seed(){
        for(int n=1;n<=6;n++)assignment(n,n,"2025-01-0"+n+"T00:00:00Z","2025-01-15T00:00:00Z");assignment(7,1,"2025-01-16T00:00:00Z","2025-01-18T00:00:00Z");
        db.execute("INSERT INTO ad_conversion_fact VALUES(10,1,'purchase','2025-01-01'),(10,1,'purchase','2025-01-02'),(10,2,'purchase','2025-01-15'),(11,3,'purchase','2025-01-04'),(10,4,'signup','2025-01-05'),(10,5,'purchase','2025-01-04 23:59:59.999999Z'),(10,6,'purchase','2025-01-15 00:00:00.000001Z')");
    }
    private static void assignment(int id,long user,String assigned,String due){db.update("INSERT INTO ad_uplift_assignment VALUES(?::uuid,?,?,?,10,?,0.5,0.1,0.2,0.3,0.4,0.5,?::timestamptz,?::timestamptz)",String.format("00000000-0000-0000-0000-%012d",id),user,100+user,200+user,user%2==1,assigned,due);}
    private Path csv(){return output.resolve("uplift.csv");}
    private void run(String...args) throws Exception {var options=new ArrayList<>(List.of("--out="+csv()));if(Arrays.stream(args).noneMatch(a->a.startsWith("--as-of=")))options.add("--as-of="+AS_OF);options.addAll(Arrays.asList(args));job.run(new DefaultApplicationArguments(options.toArray(String[]::new)));}
    private List<String[]> rows() throws Exception {var lines=Files.readAllLines(csv());assertEquals("outcome,treatment,propensity,user_id,item_id,ad_id,advertiser_id,pctr,pcvr,quality,relevance,bid,split",lines.getFirst());return lines.stream().skip(1).map(s->s.split(",",-1)).toList();}
}
