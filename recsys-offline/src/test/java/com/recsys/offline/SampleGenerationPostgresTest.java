package com.recsys.offline;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG及临时目录CSV冻结三种样本的顺序、特征、标签、序列与失败边界。 */
@Testcontainers
class SampleGenerationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate db;
    @TempDir Path output;

    @BeforeAll static void prepare() {
        db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        for(String table:List.of("user_behavior","behavior_log")) {
            db.execute("CREATE TABLE "+table+"(user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION,ts TIMESTAMPTZ,position INTEGER)");
            db.execute("COMMENT ON TABLE "+table+" IS '样本生成独占行为源'");
            for(String c:List.of("user_id","item_id","action","value","ts","position"))db.execute("COMMENT ON COLUMN "+table+"."+c+" IS '原输入语义测试字段'");
        }
        for(String table:List.of("item","item_local")) {
            db.execute("CREATE TABLE "+table+"(item_id BIGINT,category TEXT,popularity DOUBLE PRECISION)");
            db.execute("COMMENT ON TABLE "+table+" IS '样本生成独占目录'");
            for(String c:List.of("item_id","category","popularity"))db.execute("COMMENT ON COLUMN "+table+"."+c+" IS '候选目录测试字段'");
        }
    }
    @BeforeEach void reset(){db.execute("TRUNCATE user_behavior,behavior_log,item,item_local");}

    @Test void ctrAsOfAndLeakyExtendedKeepEntireOutput() throws Exception {
        seed();run("ctr");check("b62655806fdc95678949abc6f5aadc470259cfd7652ca2fd961d7dc365f5db34",csv("ctr"));
        run("ctr","--leaky","--extended-features","--neg-ratio=1","--seed=99","--valid-frac=0.4");check("14c70326b3a07ff07b1a62b57eec9b4c866593ec510bdbee3ff3a99162c246ac",csv("ctr"));
        assertEquals(13,Files.readAllLines(csv("ctr")).get(0).split(",",-1).length);
    }
    @Test void multiTaskSequenceHardNegativesAndPositionProxyKeepEntireOutput() throws Exception {
        seed();run("mt");check("28e7b017d4192eb5d11e5c293cd9f63ce828662065e95ffed77e9a589a4896da",csv("mt"));
        run("mt","--hard-neg-ratio=1","--position-proxy","--max-position=3","--seq-len=1","--extended-features");check("b8ba1b805473b8bb6b833f609dcfcd3b1d04624cb79a528ce8c50df67aa44fd7",csv("mt"));
        assertEquals(18,Files.readAllLines(csv("mt")).get(0).split(",",-1).length);
    }
    @Test void impressionAttributionAsOfTiePositionFallbackAndUnboundedWindow() throws Exception {
        seed();run("impr");check("7a7674a7c3ea7f57619043aba339bd9d6c0f831d7e02a6025be7b94a0a5552e9",csv("impr"));
        var defaultRows=Files.readAllLines(csv("impr")).stream().skip(1).map(line->line.split(",",-1)).toList();
        var sameTime=defaultRows.stream().filter(r->r[2].equals("1")&&r[3].equals("10")).findFirst().orElseThrow();
        assertEquals("0",sameTime[0]);assertEquals("0",sameTime[1]);assertEquals("",sameTime[10]);assertEquals("8",sameTime[13]);
        var laterLike=defaultRows.stream().filter(r->r[2].equals("1")&&r[3].equals("60")).findFirst().orElseThrow();
        assertEquals("0",laterLike[0]);assertEquals("0",laterLike[1]);assertEquals("10",laterLike[13]);
        run("impr","--window-hours=0","--seq-len=1","--max-position=3","--valid-frac=0");check("211af398ceaf03e0d4f693b19d5c225170d5484a2d62d29156c1a2abcdc18008",csv("impr"));
        assertTrue(Files.readAllLines(csv("impr")).stream().skip(1).allMatch(s->s.endsWith(",train")));
    }
    @Test void replicaSelectorsPreserveAllThreeOutputsAndIgnoreUnselectedTables() throws Exception {
        seed();
        for(String kind:List.of("ctr","mt","impr")){run(kind);Files.copy(csv(kind),output.resolve(kind+".expected"));}
        db.execute("INSERT INTO item_local SELECT * FROM item; INSERT INTO behavior_log SELECT * FROM user_behavior; TRUNCATE item,user_behavior");
        for(String kind:List.of("ctr","mt","impr")){
            run(kind,"--behavior-table=behavior_log","--item-table=item_local");assertArrayEquals(Files.readAllBytes(output.resolve(kind+".expected")),Files.readAllBytes(csv(kind)));
        }
    }
    @Test void emptyInputsAndNoCtrPositivesKeepExistingCsv() throws Exception {
        Files.createDirectories(output.resolve("train"));Files.writeString(csv("ctr"),"keep-ctr");Files.writeString(csv("mt"),"keep-mt");
        for(String kind:List.of("ctr","mt","impr"))run(kind);
        assertEquals("keep-ctr",Files.readString(csv("ctr")));assertEquals("keep-mt",Files.readString(csv("mt")));
        db.execute("INSERT INTO user_behavior VALUES(1,1,'RATING',3,to_timestamp(1),NULL)");run("ctr");assertEquals("keep-ctr",Files.readString(csv("ctr")));
    }
    @Test void invalidSourceAndLateCatalogSqlFailurePreserveOldOutput() throws Exception {
        seed();Files.createDirectories(output.resolve("train"));Files.writeString(csv("ctr"),"keep-ctr");Files.writeString(csv("mt"),"keep-mt");
        for(String kind:List.of("ctr","mt","impr"))fail(kind,"IllegalArgumentException","--item-table=item;DROP TABLE item_local");
        db.execute("ALTER TABLE item RENAME COLUMN popularity TO hidden_popularity");
        try {for(String kind:List.of("ctr","mt"))fail(kind,"BadSqlGrammarException");}
        finally {db.execute("ALTER TABLE item RENAME COLUMN hidden_popularity TO popularity");}
        db.execute("ALTER TABLE item RENAME TO hidden_item");
        try {fail("impr","BadSqlGrammarException");}
        finally {db.execute("ALTER TABLE hidden_item RENAME TO item");}
        assertEquals("keep-ctr",Files.readString(csv("ctr")));assertEquals("keep-mt",Files.readString(csv("mt")));
    }
    @Test void emptyNegativePoolPreservesOriginalPartiallyWrittenCsv() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(1,1,'RATING',5,to_timestamp(1),NULL)");
        for(String kind:List.of("ctr","mt")) {
            fail(kind,"ArrayIndexOutOfBoundsException");
            var rows=Files.readAllLines(csv(kind));assertEquals(2,rows.size());assertTrue(rows.get(0).startsWith("label"));assertTrue(rows.get(1).startsWith("1,"));
        }
    }
    private static void seed(){
        db.execute("INSERT INTO item VALUES(30,'A',5),(10,'A',2),(20,'B',NULL),(40,NULL,-2),(50,'',0),(60,'B',1),(9223372036854775807,'A',1),(NULL,'A',1)");
        db.execute("INSERT INTO user_behavior VALUES " +
                "(1,10,'RATING',5,to_timestamp(1),NULL),(1,20,'RATING',2,to_timestamp(2),NULL),"+
                "(2,30,'RATING',4,to_timestamp(3),NULL),(1,30,'RATING',4,to_timestamp(4),NULL),"+
                "(2,10,'RATING',NULL,to_timestamp(5),NULL),(2,99,'RATING',5,to_timestamp(6),NULL),"+
                "(NULL,NULL,'RATING',4,NULL,NULL),(3,9223372036854775807,'RATING',5,to_timestamp(7.6),NULL),"+
                "(3,40,'RATING',3,to_timestamp(7.4),NULL),(3,50,'RATING',4,to_timestamp(9),NULL),"+
                "(1,10,'IMPRESSION',8,to_timestamp(1),NULL),(1,30,'IMPRESSION',99,to_timestamp(3),2),"+
                "(2,10,'IMPRESSION',NULL,to_timestamp(4),NULL),(2,99,'IMPRESSION',-1,to_timestamp(6),NULL),"+
                "(1,60,'IMPRESSION',0,to_timestamp(10),50),(NULL,NULL,'IMPRESSION',NULL,NULL,NULL),"+
                "(1,60,'LIKE',NULL,to_timestamp(86411),NULL),(2,99,'CLICK',NULL,to_timestamp(3606),NULL),"+
                "(1,30,'PLAY',NULL,to_timestamp(3.6),NULL),(1,30,'LIKE',NULL,to_timestamp(4),NULL)");
    }
    private Path csv(String kind){return output.resolve("train/"+(kind.equals("ctr")?"samples.csv":"samples_mt.csv"));}
    private void run(String kind,String...args) throws Exception {var result=invoke(kind,args);assertEquals(0,result.status,result.log);}
    private void fail(String kind,String error,String...args) throws Exception {var result=invoke(kind,args);assertNotEquals(0,result.status,result.log);assertTrue(result.log.contains(error),result.log);}
    private record Result(int status,String log){}
    private Result invoke(String kind,String...args) throws Exception {
        var cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),SampleGenerationFixture.class.getName(),PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=samples-reader",PG.getUsername(),PG.getPassword(),kind));
        cmd.addAll(Arrays.asList(args));Path log=Files.createTempFile(output,"job-",".log");
        Process process=new ProcessBuilder(cmd).directory(output.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS),"样本子进程超时");return new Result(process.exitValue(),Files.readString(log));}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    private static void check(String expected,Path path) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        assertEquals(expected,hash);
    }
}
