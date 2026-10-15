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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG/Redis与临时CSV保护归因排序、质量聚合和原部分发布边界。 */
@Testcontainers
class AdAnalysisPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate db;static StringRedisTemplate redis;static LettuceConnectionFactory factory;
    @TempDir Path output;
    @BeforeAll static void prepare(){
        db=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        for(String table:List.of("ad_event","ad_event_log")){
            db.execute("CREATE TABLE "+table+"(request_id TEXT,user_id BIGINT,ad_id BIGINT,event_type TEXT,relevance DOUBLE PRECISION,ts TIMESTAMP,offset_s DOUBLE PRECISION)");
            db.execute("COMMENT ON TABLE "+table+" IS '归因和质量聚合迁移独占测试'");
            for(String column:List.of("request_id","user_id","ad_id","event_type","relevance","ts","offset_s"))db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原事件或测试时间偏移字段'");
        }
        factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);
    }
    @AfterAll static void close(){if(factory!=null)factory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE ad_event,ad_event_log");try(var c=factory.getConnection()){c.serverCommands().flushDb();}}
    @Test void attributionModelsKeepWholeReportsAndFutureEvents() throws Exception {
        seedAttribution();var position=run("attribution","freeze");var rows=rows(position,"attribution");assertEquals(6,rows.size());
        var ads=byAd(rows);assertEquals("2",ads.get("9")[1]);assertEquals("0.4",ads.get("1")[2]);assertEquals("0.7",ads.get("3")[2]);assertEquals("0.5",ads.get("4")[2]);assertEquals("1.0",ads.get("7")[2]);
        checkHash("ATTR_POSITION",position,"attribution");var linear=run("attribution","freeze","--model=linear");checkHash("ATTR_LINEAR",linear,"attribution");var decay=run("attribution","freeze","--model=time-decay","--half-life-days=2");checkHash("ATTR_DECAY",decay,"attribution");
        var fallback=run("attribution","freeze","--model=PRIVATE_UNKNOWN_MODEL");assertEquals(content(position,"attribution"),content(fallback,"attribution"));
        assertTrue(fallback.log.contains("[JOB_ATTRIBUTION_MODEL_FALLBACK]"));assertFalse(fallback.log.contains("PRIVATE_UNKNOWN_MODEL"));
        assertEquals(content(position,"attribution"),content(run("attribution","freeze","--model=POSITION"),"attribution"));
    }
    @Test void attributionReplicaAndAllDaysIncludeOldConversions() throws Exception {
        seedAttribution();db.execute("INSERT INTO ad_event_log SELECT * FROM ad_event");var original=run("attribution","freeze","--days=0");var replica=run("attribution","freeze","--days=-1","--ad-event-table=ad_event_log");assertEquals(content(original,"attribution"),content(replica,"attribution"));assertEquals("1",byAd(rows(original,"attribution")).get("20")[1]);
    }
    @Test void attributionRawNullSortKeysMustStayAfterNonNullRows() throws Exception {
        db.execute("INSERT INTO ad_event(user_id,ad_id,event_type,offset_s) VALUES(0,1,'CLICK',8640000),(NULL,2,'CONVERSION',0),(5,10,'CLICK',-86400),(5,11,'CONVERSION',NULL)");
        var result=run("attribution","freeze","--days=0");var ads=byAd(rows(result,"attribution"));assertEquals("1.0",ads.get("1")[2]);assertEquals("0.0",ads.get("2")[2]);assertEquals("1.0",ads.get("10")[2]);assertEquals("0.0",ads.get("11")[2]);
    }
    @Test void attributionLongIdentityRemainsExactAndNullIdentityStillMapsToZero() throws Exception {
        db.execute("INSERT INTO ad_event(user_id,ad_id,event_type,offset_s) VALUES(1,9007199254740993,'CONVERSION',0),(2,NULL,'CONVERSION',0),(3,9007199254740992,'CONVERSION',0),(4,9223372036854775807,'CONVERSION',0)");
        var ads=byAd(rows(run("attribution","freeze"),"attribution"));
        assertEquals(Set.of("9007199254740992","9007199254740993","9223372036854775807","0"),ads.keySet());
        for(var row:ads.values()){assertEquals("1",row[1]);assertEquals("1.0",row[2]);}
    }
    @Test void adjacentLargeTouchIdsMustNotCollapseAndAllModelsConserveCredit() throws Exception {
        db.execute("INSERT INTO ad_event(user_id,ad_id,event_type,offset_s) VALUES(1,9007199254740992,'CLICK',-2),(1,9007199254740993,'CLICK',-1),(1,9223372036854775807,'CONVERSION',0)");
        for(String model:List.of("linear","position","time-decay")) {
            var ads=byAd(rows(run("attribution","freeze","--model="+model),"attribution"));
            assertEquals(Set.of("9007199254740992","9007199254740993","9223372036854775807"),ads.keySet());
            assertEquals("0.5",ads.get("9007199254740992")[2]);assertEquals("0.5",ads.get("9007199254740993")[2]);
            assertEquals("1",ads.get("9223372036854775807")[1]);assertEquals("0.0",ads.get("9223372036854775807")[2]);
            assertEquals(1.0,ads.values().stream().mapToDouble(row->Double.parseDouble(row[2])).sum());
        }
    }
    @Test void attributionEmptyNoConversionAndOutputFailureHaveSafeFailureCodes() throws Exception {
        assertNoReport(rejected("attribution","freeze","JOB_NO_ATTRIBUTION_CONVERSIONS"));
        db.execute("INSERT INTO ad_event(user_id,ad_id,event_type,offset_s) VALUES(1,1,'CLICK',0)");
        assertNoReport(rejected("attribution","freeze","JOB_NO_ATTRIBUTION_CONVERSIONS"));
        db.execute("INSERT INTO ad_event(user_id,ad_id,event_type,offset_s) VALUES(1,1,'CONVERSION',1)");
        var failed=rejected("attribution","output-file","JOB_REPORT_OUTPUT_FAILED");
        assertEquals("blocked",Files.readString(failed.dir.resolve("eval")));
        assertEquals("1.0",byAd(rows(run("attribution","freeze"),"attribution")).get("1")[2]);
    }
    @Test void qualityDefaultAndOptionsKeepAggregatesWholeCsvAndRedisValues() throws Exception {
        seedQuality();redis.opsForValue().set(RedisKeys.adQuality(3),"keep");var defaults=run("quality","freeze");var rows=rows(defaults,"quality");assertEquals(2,rows.size());var ads=byAd(rows);
        assertEquals(List.of("100","10","5"),Arrays.asList(ads.get("1")).subList(1,4));assertEquals(List.of("50","5","1"),Arrays.asList(ads.get("2")).subList(1,4));assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(3)));
        for(var row:rows)assertEquals(row[10],redis.opsForValue().get(RedisKeys.adQuality(Long.parseLong(row[0]))));checkHash("QUALITY_DEFAULT",defaults,"quality");
        var options=qualityOptions("--min-impr=20");assertEquals(2,rows(options,"quality").size());
        // 原第三行把0/0算出的NaN发布成0；只用它核对未改动的前两行，而非重新认可该坏输出。
        String oldInvalidRow="3,10,0,0,0.0,0.0,0.0,0.8,0.8,0.0,0.0"+System.lineSeparator();
        assertEquals("20ee560152d0cdd4687bb6b0170b24aaa5003106f46db8c20dcada89803efd2d",sha(content(options,"quality")+oldInvalidRow));
        var invalid=rejected("quality","freeze","JOB_AD_QUALITY_INPUT_INVALID","--min-impr=1","--prior-impr=0","--prior-click=0","--w-rel=1","--w-ctr=2","--w-cvr=3","--comp-clamp=0.2","--q-min=0.8","--q-max=1.2");
        assertEquals(content(options,"quality"),content(invalid,"quality"));assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(3)));
        checkHash("QUALITY_DEFAULT",run("quality","freeze"),"quality");
    }
    @Test void qualityReplicaWindowAndNonPositiveDaysFollowOriginalEventFilters() throws Exception {
        seedQuality();db.execute("INSERT INTO ad_event_log SELECT * FROM ad_event");var all=run("quality","freeze","--days=0","--min-impr=1");var replica=run("quality","freeze","--days=-1","--min-impr=1","--ad-event-table=ad_event_log");assertEquals(content(all,"quality"),content(replica,"quality"));assertTrue(byAd(rows(all,"quality")).containsKey("4"));
        var exact=run("quality","freeze","--days=1","--min-impr=1");assertTrue(byAd(rows(exact,"quality")).containsKey("1"));assertFalse(byAd(rows(exact,"quality")).containsKey("4"));
    }
    @Test void qualityNullIdentityAndLongIdentityStayIntegerPrecision() throws Exception {
        db.execute("INSERT INTO ad_event(user_id,ad_id,event_type,relevance,offset_s) VALUES(1,NULL,'IMPRESSION',NULL,0),(1,9007199254740993,'IMPRESSION',1,0),(1,9007199254740993,'CLICK',NULL,0)");
        var ads=byAd(rows(run("quality","freeze","--min-impr=1"),"quality"));assertEquals(2,ads.size());assertTrue(ads.containsKey("9007199254740993"));assertTrue(ads.containsKey("0"));assertEquals("0",ads.get("9007199254740993")[2]);
    }
    @Test void qualityEmptyBelowThresholdAndInvalidWeightsDoNotOverwriteOldKeys() throws Exception {
        redis.opsForValue().set(RedisKeys.adQuality(1),"keep");
        assertNoReport(rejected("quality","freeze","JOB_NO_AD_QUALITY_INPUT"));
        db.execute("INSERT INTO ad_event(ad_id,event_type,relevance,offset_s) VALUES(1,'IMPRESSION',1,0)");
        var small=rejected("quality","freeze","JOB_NO_AD_QUALITY_OUTPUT");assertEquals(0,rows(small,"quality").size());
        assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(1)));
        assertNoReport(rejected("quality","freeze","JOB_INVALID_ARGUMENT","--w-rel=0","--w-ctr=0","--w-cvr=0"));
        assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(1)));
    }
    @Test void qualityRedisAndCsvFailureMustFailWhilePreservingEarlierEffects() throws Exception {
        seedQuality();redis.opsForValue().set(RedisKeys.adQuality(2),"keep");
        var acl=REDIS.execInContainer("redis-cli","ACL","SETUSER","quality-test","on",">test-only-password","+@all","~"+RedisKeys.adQuality(1));assertEquals(0,acl.getExitCode());
        var partial=rejected("quality","acl","JOB_AD_QUALITY_PUBLICATION_FAILED");
        assertTrue(partial.log.contains("NOPERM"));assertEquals(1,rows(partial,"quality").size());
        assertEquals(rows(partial,"quality").getFirst()[10],redis.opsForValue().get(RedisKeys.adQuality(1)));
        assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(2)));
        var failed=rejected("quality","output-file","JOB_AD_QUALITY_PUBLICATION_FAILED");
        assertEquals("blocked",Files.readString(failed.dir.resolve("eval")));assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(2)));
        checkHash("QUALITY_DEFAULT",run("quality","freeze"),"quality");
    }
    @Test void invalidAnalysisArgumentsCannotCreateReportsOrReplaceOldQuality() throws Exception {
        seedQuality();redis.opsForValue().set(RedisKeys.adQuality(1),"keep");
        for(String option:List.of("--days", "--min-impr=-1", "--prior-impr=-1", "--prior-click=NaN", "--w-rel=-1", "--w-ctr=Infinity", "--q-min=-1", "--q-max=0.1", "--comp-clamp=-1")) {
            assertNoReport(rejected("quality","freeze","JOB_INVALID_ARGUMENT",option));
            assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(1)));
        }
        assertNoReport(rejected("quality","freeze","JOB_INVALID_ARGUMENT","--w-rel=1e308","--w-ctr=1e308"));
        for(String option:List.of("--days=bad", "--model", "--model= ", "--half-life-days=0", "--half-life-days=4.9e-324", "--click-window-days=-1", "--view-window-days=NaN", "--first=-1", "--last=Infinity")) {
            assertNoReport(rejected("attribution","freeze","JOB_INVALID_ARGUMENT",option));
        }
        assertNoReport(rejected("attribution","freeze","JOB_INVALID_ARGUMENT","--first=1e308","--last=1e308"));
    }
    @Test void invalidDatabaseQualityValuesCannotBeRoundedToZeroAndDataCanRecover() throws Exception {
        seedQuality();redis.opsForValue().set(RedisKeys.adQuality(1),"keep");
        for(double relevance:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,1e20}) {
            db.update("UPDATE ad_event SET relevance=? WHERE event_type='IMPRESSION'",relevance);
            assertNoReport(rejected("quality","freeze","JOB_AD_QUALITY_INPUT_INVALID"));
            assertEquals("keep",redis.opsForValue().get(RedisKeys.adQuality(1)));
        }
        db.execute("TRUNCATE ad_event");seedQuality();checkHash("QUALITY_DEFAULT",run("quality","freeze"),"quality");
    }
    @Test void missingSourcesAndInvalidSelectorsFailBeforeCreatingReports() throws Exception {
        db.execute("ALTER TABLE ad_event RENAME TO hidden_events");try{for(String job:List.of("quality","attribution")){var result=invoke(job,"read-only");assertNotEquals(0,result.status,result.log);assertTrue(result.log.contains("BadSqlGrammarException"),result.log);assertNoReport(result);}}finally{db.execute("ALTER TABLE hidden_events RENAME TO ad_event");}
        for(String job:List.of("quality","attribution")){var result=invoke(job,"read-only","--ad-event-table=ad_event;DROP TABLE ad_event");assertNotEquals(0,result.status,result.log);assertTrue(result.log.contains("非法 --ad-event-table"));assertNoReport(result);}
    }
    private Result qualityOptions(String minImpr) throws Exception {
        return run("quality","freeze",minImpr,"--prior-impr=0","--prior-click=0","--w-rel=1","--w-ctr=2","--w-cvr=3","--comp-clamp=0.2","--q-min=0.8","--q-max=1.2");
    }
    private Result rejected(String job,String mode,String code,String...options) throws Exception {
        var result=invoke(job,mode,options);assertNotEquals(0,result.status,result.log);
        assertTrue(result.log.contains("["+code+"]"),result.log);
        assertFalse(result.log.contains("ad-"+job+" 完成"),result.log);return result;
    }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void seedAttribution(){db.execute("INSERT INTO ad_event(user_id,ad_id,event_type,offset_s) VALUES(1,1,'CLICK',-604800),(1,1,'IMPRESSION',-43200),(1,2,'IMPRESSION',-86400),(1,3,'CLICK',-172800),(1,3,'CLICK',-129600),(1,4,'CLICK',86400),(1,9,'CONVERSION',0),(1,9,'CONVERSION',172800),(2,7,'CONVERSION',0),(3,8,'CLICK',-86400),(4,20,'CONVERSION',-2678400),(1,99,'INVALID_CLICK',-1)");}
    private static void seedQuality(){
        db.execute("INSERT INTO ad_event(request_id,user_id,ad_id,event_type,relevance,offset_s) SELECT 'a'||n,1,1,'IMPRESSION',0.8,-86400 FROM generate_series(1,100)n;INSERT INTO ad_event(request_id,user_id,ad_id,event_type,relevance,offset_s) SELECT 'b'||n,1,2,'IMPRESSION',0.2,86400 FROM generate_series(1,50)n;INSERT INTO ad_event(request_id,user_id,ad_id,event_type,relevance,offset_s) SELECT 'c'||n,1,3,'IMPRESSION',NULL,0 FROM generate_series(1,10)n");
        db.execute("INSERT INTO ad_event(request_id,ad_id,event_type,offset_s) SELECT 'a'||n,1,'CLICK',-86400 FROM generate_series(1,10)n;INSERT INTO ad_event(request_id,ad_id,event_type,offset_s) SELECT 'a'||n,1,'CONVERSION',-86400 FROM generate_series(1,5)n;INSERT INTO ad_event(request_id,ad_id,event_type,offset_s) SELECT 'b'||n,2,'CLICK',0 FROM generate_series(1,5)n");
        db.execute("INSERT INTO ad_event(request_id,ad_id,event_type,relevance,offset_s) VALUES('b1',2,'CONVERSION',NULL,0),('a1',1,'CLICK',NULL,-86400),('a1',1,'CONVERSION',NULL,-86400),(NULL,1,'CLICK',NULL,-86400),('lonely',9,'CLICK',NULL,0),('old',4,'IMPRESSION',1,-2678400),('null',4,'IMPRESSION',1,NULL)");
    }
    private record Result(int status,String log,Path dir){}
    private Result run(String job,String mode,String...args)throws Exception{var result=invoke(job,mode,args);assertEquals(0,result.status,result.log);return result;}
    private Result invoke(String job,String mode,String...args)throws Exception {
        Path dir=Files.createTempDirectory(output,job+"-");if(mode.equals("output-file"))Files.writeString(dir.resolve("eval"),"blocked");
        var cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),AdAnalysisFixture.class.getName(),PG.getJdbcUrl()+"&ApplicationName=ad-analysis-reader",PG.getUsername(),PG.getPassword(),REDIS.getHost(),Integer.toString(REDIS.getMappedPort(6379)),job,mode));cmd.addAll(Arrays.asList(args));Path log=dir.resolve("job.log");var p=new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(p.waitFor(45,TimeUnit.SECONDS),"分析子进程超时");return new Result(p.exitValue(),Files.readString(log),dir);}finally{if(p.isAlive())p.destroyForcibly();}
    }
    private static Path csv(Result result,String kind)throws Exception {try(var files=Files.list(result.dir.resolve("eval"))){var found=files.filter(p->p.getFileName().toString().startsWith("ad-"+kind+"-")).toList();assertEquals(1,found.size(),result.log);return found.getFirst();}}
    private static String content(Result r,String kind)throws Exception{return Files.readString(csv(r,kind));}
    private static List<String[]> rows(Result r,String kind)throws Exception{return Files.readAllLines(csv(r,kind)).stream().skip(1).map(s->s.split(",",-1)).toList();}
    private static Map<String,String[]> byAd(List<String[]> rows){var map=new HashMap<String,String[]>();for(var row:rows)assertNull(map.put(row[0],row));return map;}
    private static void assertNoReport(Result r){assertFalse(Files.exists(r.dir.resolve("eval")),r.log);}
    private static void checkHash(String name,Result r,String kind)throws Exception{
        String expected=Map.of("QUALITY_DEFAULT","a2c6d011f044c1ef53901e08f632b2439bbb1cb640808f3aecb35dfd5d787c0a",
                "QUALITY_OPTIONS","20ee560152d0cdd4687bb6b0170b24aaa5003106f46db8c20dcada89803efd2d",
                "ATTR_POSITION","1e895bb78b2f147601867c3e314f7759fc37d52ad130420c26001787d1242393",
                "ATTR_LINEAR","77358b1c133ca2dc809823c37c77d0436974cbf986ac338174b26d9b3f6d5c7a",
                "ATTR_DECAY","c45c9befbdd3afcf04de7db34e3e69d3066a6a65c80e805e709e45b23bf7d15a").get(name);
        assertEquals(expected,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(csv(r,kind)))));
    }
}
