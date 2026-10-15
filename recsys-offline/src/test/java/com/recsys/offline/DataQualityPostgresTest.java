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
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真正不同schema的数据所有权、PG校准分桶和窗口语义，不用Mock证明SQL结果。 */
@Testcontainers
class DataQualityPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate primary, derived;
    @TempDir Path output;

    @BeforeAll static void prepare() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        admin.execute("CREATE SCHEMA quality_primary;CREATE SCHEMA quality_derived");
        primary = db("quality_primary"); derived = db("quality_derived");
        for (var source : List.of(primary,derived)) {
            for (String table : List.of("item","item_local")) create(source,table,"item_id BIGINT,category TEXT");
            for (String table : List.of("user_behavior","behavior_log")) create(source,table,"user_id BIGINT,item_id BIGINT,action TEXT,ts TIMESTAMP,offset_s DOUBLE PRECISION");
            for (String table : List.of("ad_event","ad_event_log")) create(source,table,"request_id TEXT,ad_id BIGINT,event_type TEXT,pctr_calib DOUBLE PRECISION");
            create(source,"item_embedding","item_id BIGINT"); create(source,"user_embedding","user_id BIGINT");
        }
    }
    @BeforeEach void reset() {
        for (var source : List.of(primary,derived)) source.execute("TRUNCATE item,item_local,user_behavior,behavior_log,ad_event,ad_event_log,item_embedding,user_embedding");
        // 互放矛盾计数，误绑同一数据源会改变覆盖率，不能只证明Bean存在。
        primary.execute("INSERT INTO item_embedding SELECT n FROM generate_series(1,100)n;INSERT INTO user_embedding SELECT n FROM generate_series(1,100)n");
        derived.execute("INSERT INTO item SELECT n,'WRONG' FROM generate_series(1,30)n;INSERT INTO user_behavior(user_id) SELECT n FROM generate_series(1,20)n");
    }
    @Test void wholeReportsKeepOwnerCountsThresholdsAndReplicaSources() throws Exception {
        seed(); var result = run("freeze"); hash("DEFAULT",result); var metrics = metrics(result);
        assertEquals("0.5000",metrics.get("item_embedding_coverage")); assertEquals("0.2500",metrics.get("user_embedding_coverage"));
        assertEquals("0.2000",metrics.get("pctr_ece")); assertEquals("4",metrics.get("breaches")); assertEquals("significant",metrics.get("category_psi_level"));
        var options = run("freeze","--min-embedding-coverage=0.25","--max-ece=0.2","--max-psi=100"); hash("OPTIONS",options); assertEquals("0",metrics(options).get("breaches"));
        primary.execute("INSERT INTO item_local SELECT * FROM item;INSERT INTO behavior_log SELECT * FROM user_behavior;INSERT INTO ad_event_log SELECT * FROM ad_event;TRUNCATE item,user_behavior,ad_event");
        assertEquals(content(result),content(run("freeze","--item-table=item_local","--behavior-table=behavior_log","--ad-event-table=ad_event_log")));
    }
    @Test void calibrationUsesPgWidthBucketsAndDistinctClickPairsWithoutProbabilityClamp() throws Exception {
        primary.execute("INSERT INTO ad_event VALUES('negative',1,'IMPRESSION',-0.1),('zero',2,'IMPRESSION',0),('one',3,'IMPRESSION',1),('above',4,'IMPRESSION',1.1),('missing',5,'IMPRESSION',NULL),('one',3,'CLICK',NULL),('one',3,'CLICK',NULL),(NULL,2,'CLICK',NULL)");
        var result = run("freeze"); hash("ECE_EDGES",result); assertEquals("0.3000",metrics(result).get("pctr_ece"));
        primary.execute("INSERT INTO ad_event VALUES('one',3,'CLICK',NULL),('one',3,'CLICK',NULL)");
        assertEquals(content(result),content(run("freeze")));
    }
    @Test void recentDaysIncludesExactLowerBoundAndZeroOrNegativeDaysAreNotAllHistory() throws Exception {
        primary.execute("INSERT INTO item VALUES(1,'A'),(2,'B');INSERT INTO user_behavior(user_id,item_id,action,offset_s) VALUES(1,1,'CLICK',0),(2,2,'LIKE',-604800),(3,2,'PLAY',-604800.000001),(4,1,'RATING',86400),(5,2,'CLICK',86400.000001)");
        var seven = run("freeze"); var zero = run("freeze","--days=0"); var future = run("freeze","--days=-1");
        hash("WINDOW_7",seven); hash("WINDOW_0",zero); hash("WINDOW_NEGATIVE",future);
        assertEquals("0.0405",metrics(seven).get("category_psi_7d"));
        assertEquals("0.2930",metrics(zero).get("category_psi_0d"));
        assertEquals("0.0405",metrics(future).get("category_psi_-1d"));
    }
    @Test void missingDerivedTableSkipsEntireCoverageButKeepsOtherChecks() throws Exception {
        seed(); derived.execute("ALTER TABLE user_embedding RENAME TO hidden_users");
        try {
            var result = run("autocommit"); var m = metrics(result); assertFalse(m.containsKey("item_embedding_coverage")); assertFalse(m.containsKey("user_embedding_coverage"));
            assertEquals("0.2000",m.get("pctr_ece")); assertTrue(m.containsKey("category_psi_7d")); assertEquals("2",m.get("breaches"));
            assertTrue(result.log.contains("Embedding 覆盖率检查跳过"));
        } finally { derived.execute("ALTER TABLE hidden_users RENAME TO user_embedding"); }
    }
    @Test void primaryCheckFailuresRemainIndependentInOriginalAutocommitMode() throws Exception {
        seed(); primary.execute("ALTER TABLE ad_event RENAME TO hidden_events");
        try { var r = run("autocommit"); assertFalse(metrics(r).containsKey("pctr_ece")); assertTrue(metrics(r).containsKey("category_psi_7d")); assertTrue(metrics(r).containsKey("item_embedding_coverage")); }
        finally { primary.execute("ALTER TABLE hidden_events RENAME TO ad_event"); }
        primary.execute("ALTER TABLE item RENAME COLUMN category TO hidden_category");
        try { var r = run("autocommit"); assertFalse(metrics(r).containsKey("category_psi_7d")); assertEquals("0.2000",metrics(r).get("pctr_ece")); assertEquals("0.5000",metrics(r).get("item_embedding_coverage")); }
        finally { primary.execute("ALTER TABLE item RENAME COLUMN hidden_category TO category"); }
    }
    @Test void allFailedChecksStillKeepLegacyZeroBreachesAndCompletionLog() throws Exception {
        seed(); primary.execute("ALTER TABLE item RENAME TO hidden_items;ALTER TABLE ad_event RENAME TO hidden_events");
        try {
            var result = run("autocommit"); assertEquals("metric,value\nbreaches,0\n",content(result));
            assertTrue(result.log.contains("巡检通过,无越阈值项"));
        } finally { primary.execute("ALTER TABLE hidden_items RENAME TO item;ALTER TABLE hidden_events RENAME TO ad_event"); }
    }
    @Test void emptyInputsAndMissingRecentPopulationKeepOriginalSkipAndCoverageRules() throws Exception {
        var empty = run("freeze"); assertEquals("metric,value\nitem_embedding_coverage,1.0000\nuser_embedding_coverage,1.0000\nbreaches,0\n",content(empty));
        seed(); primary.execute("UPDATE user_behavior SET offset_s=-2592000"); var old = run("freeze"); assertFalse(metrics(old).containsKey("category_psi_7d"));
        primary.execute("TRUNCATE user_behavior;INSERT INTO user_behavior(user_id,item_id,action,offset_s) VALUES(1,1,'IMPRESSION',0),(2,4,'CLICK',0),(3,999,'LIKE',0),(NULL,1,'UNKNOWN',0)");
        assertFalse(metrics(run("freeze")).containsKey("category_psi_7d"));
    }
    @Test void coverageCanExceedOneAndDistinctUsersExcludeNullButIncludeAllActions() throws Exception {
        primary.execute("INSERT INTO item VALUES(1,'A');INSERT INTO user_behavior(user_id,action) VALUES(1,'IMPRESSION'),(1,'CLICK'),(2,'UNKNOWN'),(NULL,'LIKE')");
        derived.execute("INSERT INTO item_embedding VALUES(1),(1);INSERT INTO user_embedding VALUES(1),(2),(3)");
        var m = metrics(run("freeze")); assertEquals("2.0000",m.get("item_embedding_coverage")); assertEquals("1.5000",m.get("user_embedding_coverage"));
    }
    @Test void invalidArgumentsAndOutputFailureKeepEarlyAndLateBoundaries() throws Exception {
        seed();
        for (String option : List.of("--item-table=item;SELECT 1","--behavior-table=bad","--ad-event-table=bad","--days=bad","--max-ece=bad")) {
            var r = invoke("autocommit",false,option); assertNotEquals(0,r.status,option); assertFalse(Files.exists(r.dir.resolve("eval")));
        }
        var failed = invoke("autocommit",true); assertNotEquals(0,failed.status); assertEquals("blocked",Files.readString(failed.dir.resolve("eval")));
    }

    private static void seed() {
        primary.execute("INSERT INTO item VALUES(1,'A'),(2,'A'),(3,'B'),(4,NULL);INSERT INTO user_behavior(user_id,item_id,action,offset_s) VALUES(1,1,'CLICK',-864000),(2,2,'LIKE',-86400),(3,3,'PLAY',-864000),(4,3,'RATING',-1728000),(4,3,'IMPRESSION',0),(NULL,999,'CLICK',0)");
        primary.execute("UPDATE user_behavior SET ts=now()+offset_s*interval '1 second'");
        primary.execute("INSERT INTO ad_event VALUES('a',1,'IMPRESSION',0.2),('b',2,'IMPRESSION',0.8),('b',2,'CLICK',NULL),('b',2,'CLICK',NULL)");
        derived.execute("INSERT INTO item_embedding VALUES(1),(2);INSERT INTO user_embedding VALUES(1)");
    }
    private static String url(String schema,String app) { return PG.getJdbcUrl()+"&currentSchema="+schema+"&ApplicationName="+app; }
    private static JdbcTemplate db(String schema) { return new JdbcTemplate(new DriverManagerDataSource(url(schema,"dq-test-admin"),PG.getUsername(),PG.getPassword())); }
    private static void create(JdbcTemplate db,String table,String columns) {
        db.execute("CREATE TABLE "+table+"("+columns+")"); db.execute("COMMENT ON TABLE "+table+" IS '数据质量迁移独占测试'");
        for(String column:columns.split(",")) db.execute("COMMENT ON COLUMN "+table+"."+column.strip().split(" ")[0]+" IS '测试统计输入字段'");
    }
    private record Result(int status,String log,Path dir) { }
    private Result run(String mode,String... args) throws Exception { var result = invoke(mode,false,args); assertEquals(0,result.status,result.log); return result; }
    private Result invoke(String mode,boolean block,String... args) throws Exception {
        Path dir=Files.createTempDirectory(output,"quality-"); if(block)Files.writeString(dir.resolve("eval"),"blocked");
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),DataQualityFixture.class.getName(),url("quality_primary","dq-primary-reader"),url("quality_derived","dq-derived-reader"),PG.getUsername(),PG.getPassword(),mode));
        command.addAll(Arrays.asList(args));Path log=dir.resolve("job.log");var process=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(process.waitFor(45,TimeUnit.SECONDS),"数据质量子进程超时");return new Result(process.exitValue(),Files.readString(log),dir); }
        finally { if(process.isAlive())process.destroyForcibly(); }
    }
    private static String content(Result r) throws Exception {
        try(var files=Files.list(r.dir.resolve("eval"))) { var csv=files.filter(p->p.toString().endsWith(".csv")).toList();assertEquals(1,csv.size(),r.log);return Files.readString(csv.getFirst()); }
    }
    private static Map<String,String> metrics(Result r) throws Exception { var map=new LinkedHashMap<String,String>();for(String line:content(r).split("\n")){var pair=line.split(",",2);if(!pair[0].equals("breach"))map.put(pair[0],pair[1]);}return map; }
    private static void hash(String name,Result r) throws Exception {
        String expected = Map.of(
                "ECE_EDGES", "f00006f049bab318f1ef5a989eac4d56958fce801e7581e67c8fe8c01d515521",
                "DEFAULT", "b19f65e6f832da3f2d481a18df6fd0ff2726b317ceb7be9e7fa0ff4a45b789b1",
                "OPTIONS", "d91f4ad293d1dc1c9681f57ff753f2a06a36a473b78b36ee461ffad02f86f030",
                "WINDOW_7", "e284ed57a0cb0270491a91ec88acae0cf3cc5784f56c4fc749f95e7d1e9c1e7e",
                "WINDOW_0", "d198a880ba4d458780357a2ec5355a3f0d295c47b2de4610917c14ddc27a43c2",
                "WINDOW_NEGATIVE", "8b6234360b5d3598a5f90fca58626e39fbe0980d2f5cffb912448a5bc2e607b3").get(name);
        assertEquals(expected,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content(r).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
