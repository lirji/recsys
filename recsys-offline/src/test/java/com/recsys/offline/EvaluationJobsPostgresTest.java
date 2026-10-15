package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 三种评估任务的真实评分切分、SQL边界、指标CSV和Redis校准曲线基线。 */
@Testcontainers
class EvaluationJobsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate db;static LettuceConnectionFactory factory;static StringRedisTemplate redis;
    @TempDir Path output;

    @BeforeAll static void prepare(){
        db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        for(String table:List.of("user_behavior","behavior_log")){
            db.execute("CREATE TABLE "+table+"(user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION,ts TIMESTAMPTZ)");
            db.execute("COMMENT ON TABLE "+table+" IS '评估独占行为源'");
            for(String c:List.of("user_id","item_id","action","value","ts"))db.execute("COMMENT ON COLUMN "+table+"."+c+" IS '旧行为语义测试字段'");
        }
        for(String table:List.of("item","item_local")){
            db.execute("CREATE TABLE "+table+"(item_id BIGINT,popularity DOUBLE PRECISION)");
            db.execute("COMMENT ON TABLE "+table+" IS '评估独占目录'");
            for(String c:List.of("item_id","popularity"))db.execute("COMMENT ON COLUMN "+table+"."+c+" IS '目录测试字段'");
        }
        factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);
    }
    @AfterAll static void close(){if(factory!=null)factory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE user_behavior,behavior_log,item,item_local");redis.delete(RedisKeys.recCalib("rec"));}

    @Test void pipelineMetricsAndPrintSplitFreezeRoundingHistoryExclusionAndCatalog() throws Exception {
        seed();Result result=run("eval","--threads=1","--k=1,3");check("33d46a229c760e538f44871c9f949807d2eae26bf7de264032f08befff46b8ec",report(result));
        assertEquals(3,report(result).lines().count());
        Result split=run("eval","--print-split");assertTrue(split.log.contains("SPLIT_TS=102"));assertFalse(Files.exists(split.dir.resolve("eval")));
    }
    @Test void tuneGridAndUserSamplingKeepEntireCsv() throws Exception {
        seed();check("b867ae79b8e85db8657761dc628c5bba799e691e421fd61e858c136fae1f9db5",report(run("tune","--biases=0,0.1,1","--weights=0,1,2","--k=3","--max-users=3")));
    }
    @Test void calibrationPreservesCompleteCurveAndInsufficientSamplesKeepPreviousValue() throws Exception {
        seed();run("calibrate","--bins=2");String curve=redis.opsForValue().get(RedisKeys.recCalib("rec"));check("b07af253a5fc43cdd0114bdfdaacb58679f589306d870ba8a4710a8c45ff974e",curve);
        run("calibrate","--bins=100");assertEquals(curve,redis.opsForValue().get(RedisKeys.recCalib("rec")));
    }
    @Test void bothReplicaSourcesMatchAndDoNotReadOwnedTables() throws Exception {
        seed();String metrics=report(run("eval","--threads=1","--k=3"));String tune=report(run("tune","--biases=0,0.1","--weights=0,1"));run("calibrate","--bins=2");String curve=redis.opsForValue().get(RedisKeys.recCalib("rec"));
        db.execute("INSERT INTO behavior_log SELECT * FROM user_behavior;INSERT INTO item_local SELECT * FROM item;TRUNCATE user_behavior,item");
        assertEquals(metrics,report(run("eval","--threads=1","--k=3","--behavior-table=behavior_log","--item-table=item_local")));
        assertEquals(tune,report(run("tune","--biases=0,0.1","--weights=0,1","--behavior-table=behavior_log")));
        run("calibrate","--bins=2","--behavior-table=behavior_log");assertEquals(curve,redis.opsForValue().get(RedisKeys.recCalib("rec")));
    }
    @Test void emptyInputAndOnlyHistoricalRepeatedItemsProduceNoReportsAndKeepCurve() throws Exception {
        redis.opsForValue().set(RedisKeys.recCalib("rec"),"keep");
        for(int stage=0;stage<2;stage++){
            for(String kind:List.of("eval","tune","calibrate")){var result=run(kind);assertFalse(Files.exists(result.dir.resolve("eval")));}
            assertEquals("keep",redis.opsForValue().get(RedisKeys.recCalib("rec")));
            if(stage==0)db.execute("INSERT INTO user_behavior SELECT 1,1,'RATING',5,to_timestamp(n) FROM generate_series(1,100)n");
        }
    }
    @Test void invalidSourceMissingBehaviorAndCatalogFailBeforePublication() throws Exception {
        seed();redis.opsForValue().set(RedisKeys.recCalib("rec"),"keep");
        for(String kind:List.of("eval","tune","calibrate"))fail(kind,"IllegalArgumentException","--behavior-table=user_behavior;DROP TABLE item");
        db.execute("ALTER TABLE user_behavior RENAME TO hidden_behavior");
        try {for(String kind:List.of("eval","tune","calibrate"))fail(kind,"BadSqlGrammarException");}
        finally{db.execute("ALTER TABLE hidden_behavior RENAME TO user_behavior");}
        db.execute("ALTER TABLE item RENAME TO hidden_item");
        try{fail("eval","BadSqlGrammarException");}
        finally{db.execute("ALTER TABLE hidden_item RENAME TO item");}
        assertEquals("keep",redis.opsForValue().get(RedisKeys.recCalib("rec")));
    }
    @Test void perUserRecallAndRankFailureKeepOriginalPartialEvaluationPolicy() throws Exception {
        seed();check("c3d24ad25ba59c1a98a495a80a2c2ac6e6e2f585489bf5a237a6c9f1d80337bc",report(run("eval","--threads=1","--k=3","--fixture-failures")));
        check("a3a924cf5c766353091e4b5bdb075ea5401f98308beacf9b3c3e82842939ff1c",report(run("tune","--biases=0,0.1","--weights=0,1","--fixture-failures")));
        run("calibrate","--bins=2","--fixture-failures");check("4caf4c21901d62158aaf808f62c84b3304bc6989b74062746f92d9614b11cee7",redis.opsForValue().get(RedisKeys.recCalib("rec")));
    }
    private static void seed(){
        db.execute("INSERT INTO item VALUES(1,10),(2,4),(3,NULL),(4,-1),(5,0),(6,1),(7,2),(8,3),(NULL,9),(0,2),(2,8),(9223372036854775807,1)");
        db.execute("INSERT INTO user_behavior SELECT (n-1)%10+1,1,'RATING',5,to_timestamp(n) FROM generate_series(1,100)n");
        db.execute("INSERT INTO user_behavior SELECT (n-1)%10+1,n%4+2,'RATING',3.95,to_timestamp(100+n) FROM generate_series(1,25)n");
        db.execute("INSERT INTO user_behavior VALUES(NULL,NULL,'RATING',NULL,NULL),(9223372036854775807,9223372036854775807,'RATING',5,to_timestamp(130.6)),(1,8,'RATING',3.94,to_timestamp(129.4)),(1,1,'RATING',5,to_timestamp(129.6)),(2,6,'CLICK',5,to_timestamp(1000))");
    }
    private record Result(int status,String log,Path dir){}
    private Result run(String kind,String...options) throws Exception {Result result=invoke(kind,options);assertEquals(0,result.status,result.log);return result;}
    private void fail(String kind,String error,String...options) throws Exception {Result result=invoke(kind,options);assertNotEquals(0,result.status,result.log);assertTrue(result.log.contains(error),result.log);assertFalse(Files.exists(result.dir.resolve("eval")));}
    private Result invoke(String kind,String...options) throws Exception {
        Path dir=Files.createTempDirectory(output,"evaluation-");Path log=dir.resolve("process.log");
        var cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),EvaluationFixture.class.getName(),PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=evaluation-reader",PG.getUsername(),PG.getPassword(),REDIS.getHost(),String.valueOf(REDIS.getMappedPort(6379)),kind));
        cmd.addAll(Arrays.asList(options));var process=new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS),"评估子进程超时");return new Result(process.exitValue(),Files.readString(log),dir);}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    private static String report(Result result) throws Exception {
        try(var files=Files.list(result.dir.resolve("eval"))){var paths=files.filter(p->p.toString().endsWith(".csv")).toList();assertEquals(1,paths.size());return Files.readString(paths.get(0));}
    }
    private static void check(String expected,String value) throws Exception {assertNotNull(value);assertEquals(expected,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))));}
}
