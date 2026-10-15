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

/** 真实TIMESTAMP、原始NULL分组、首次曝光ITT及晚查询失败保护报表兼容性。 */
@Testcontainers
class ExperimentReportPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate db;
    @TempDir Path output;

    @BeforeAll static void prepare() {
        db = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        for (String table : List.of("ad_event", "ad_event_log")) {
            db.execute("CREATE TABLE " + table + "(request_id TEXT,ad_id BIGINT,event_type TEXT,position INTEGER,charged_price DOUBLE PRECISION,relevance DOUBLE PRECISION,ad_bucket TEXT,ts TIMESTAMP)");
            comment(table, "request_id", "ad_id", "event_type", "position", "charged_price", "relevance", "ad_bucket", "ts");
        }
        for (String table : List.of("user_behavior", "behavior_log")) {
            db.execute("CREATE TABLE " + table + "(user_id BIGINT,action TEXT,bucket TEXT,scene TEXT,ts TIMESTAMP)");
            comment(table, "user_id", "action", "bucket", "scene", "ts");
        }
    }
    private static void comment(String table, String... columns) {
        db.execute("COMMENT ON TABLE " + table + " IS '实验报表迁移独占测试'");
        for (String column : columns) db.execute("COMMENT ON COLUMN " + table + "." + column + " IS '报表输入字段'");
    }
    @BeforeEach void reset() { db.execute("TRUNCATE ad_event,ad_event_log,user_behavior,behavior_log"); }

    @Test void adWholeReportsPreserveDistinctJoinsAndExposureOnlyWindow() throws Exception {
        seedAd(); var all = run("ad"); hash("AD_ALL", all);
        var filtered = run("ad", "--since=2026-04-01"); hash("AD_SINCE", filtered);
        var rows = lines(filtered);
        assertTrue(rows.contains("1,2,1,1,2.5000,0.500000,1.000000,1250.0000,0.5000"));
        assertTrue(rows.contains("ALL,4,2,1,2.5000,0.500000,0.500000,625.0000,"));
        assertTrue(filtered.log.contains("反作弊拦截无效点击 1"));
        db.execute("INSERT INTO ad_event_log SELECT * FROM ad_event");
        assertEquals(content(filtered), content(run("ad", "--since=2026-04-01", "--ad-event-table=ad_event_log")));
    }
    @Test void adRawNullAndZeroPositionsRemainSeparateAndNullSortsLast() throws Exception {
        seedAd(); var rows = lines(run("ad", "--since=2026-04-01"));
        assertEquals("0,1,0,0,0.0000,0.000000,0.000000,0.0000,0.0000", rows.get(1));
        assertEquals("0,1,1,0,0.0000,1.000000,0.000000,0.0000,0.0000", rows.get(3));
    }
    @Test void adLateBucketSqlFailureLeavesEarlierCsvRows() throws Exception {
        seedAd(); db.execute("ALTER TABLE ad_event RENAME COLUMN ad_bucket TO hidden_bucket");
        try {
            var failed = invoke("ad", false); assertNotEquals(0, failed.status); assertTrue(failed.log.contains("hidden_bucket") || failed.log.contains("ad_bucket"));
            var csv = content(failed); assertTrue(csv.contains("ALL,")); assertFalse(csv.contains("bucket:"));
        } finally { db.execute("ALTER TABLE ad_event RENAME COLUMN hidden_bucket TO ad_bucket"); }
    }
    @Test void adEmptyAndSingleBucketAndFileFailureKeepBoundaries() throws Exception {
        assertNoReport(run("ad")); seedAd(); db.execute("UPDATE ad_event SET ad_bucket=NULL");
        assertFalse(content(run("ad")).contains("bucket:"));
        var failed = invoke("ad", true); assertNotEquals(0, failed.status); assertEquals("blocked", Files.readString(failed.dir.resolve("eval")));
    }
    @Test void abRawWholeReportsPreserveWindowsSceneDistinctUsersAndOverrides() throws Exception {
        seedAb(); var filtered = run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home"); hash("AB_RAW", filtered);
        var rows = data(filtered); assertEquals(2, rows.size()); assertEquals("A", rows.get(0)[0]);
        assertEquals(List.of("35", "8"), Arrays.asList(rows.get(0)).subList(1,3)); assertEquals("6", rows.get(0)[6]);
        assertEquals(12, rows.get(0).length); assertFalse(content(filtered).contains("(none)"));
        var options = run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home", "--baseline=B", "--alpha=0.1", "--power=0.9"); hash("AB_OPTIONS", options);
        assertEquals("", data(options).get(1)[7]);
        assertEquals(content(filtered), content(run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home", "--baseline=missing")));
        assertEquals(1, data(run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home", "--min-impressions=30")).size());
        db.execute("INSERT INTO behavior_log SELECT * FROM user_behavior");
        assertEquals(content(filtered), content(run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home", "--behavior-table=behavior_log")));
        hash("AB_ALL", run("ab"));
    }
    @Test void cupedWholeReportKeepsPreWindowMissingCovariatesAndFirstExposureItt() throws Exception {
        seedAb(); var report = cuped(); hash("AB_CUPED", report); var rows = data(report);
        assertEquals(2, rows.size()); assertEquals(24, rows.get(0).length);
        assertEquals("A", rows.get(0)[0]); assertEquals("38", rows.get(0)[1]); assertEquals("8", rows.get(0)[2]);
        assertEquals("5", rows.get(0)[6]); assertEquals("1", rows.get(0)[23]); assertEquals("1", rows.get(1)[23]);
        assertEquals("applied", rows.get(0)[12]); assertEquals("0.736842", rows.get(0)[14]);
        assertEquals("24", rows.get(1)[1]); assertEquals("9", rows.get(1)[2]);
        db.execute("INSERT INTO behavior_log SELECT * FROM user_behavior");
        assertEquals(content(report), content(run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home", "--cuped-pre-days=2", "--cuped-min-users=2", "--behavior-table=behavior_log")));
    }
    @Test void cupedPreBoundsAndSceneExcludeOutsideEventsButAcceptNullBuckets() throws Exception {
        seedAb(); String original = content(cuped());
        // 下界之前、实验结束及其他scene都不能进入指定窗口。
        db.execute("INSERT INTO user_behavior VALUES(1,'CLICK',NULL,'home','2026-03-29 23:59:59.999999'),(1,'CLICK','A','home','2026-04-03'),(1,'CLICK',NULL,'other','2026-03-31')");
        assertEquals(original, content(cuped()));
        db.execute("INSERT INTO user_behavior VALUES(1,'CLICK',NULL,'home','2026-03-30')");
        assertNotEquals(original, content(cuped()));
    }
    @Test void cupedFallbackAndNullIdentityKeepLegacyPopulationRules() throws Exception {
        seedAb(); var fallback = run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home", "--cuped-pre-days=2");
        assertTrue(data(fallback).stream().allMatch(r -> r[12].equals("baseline_insufficient")));
        db.execute("TRUNCATE user_behavior");
        // NULL与真实0仍按原SQL分别分组，进入Java后按getLong的0身份合并；同毫秒首曝光按桶名裁决。
        db.execute("INSERT INTO user_behavior VALUES(NULL,'IMPRESSION','B','home','2026-04-01 00:00:00.000100'),(0,'IMPRESSION','A','home','2026-04-01 00:00:00.000900'),(0,'CLICK','A','home','2026-04-02')");
        var rows = data(cuped()); assertEquals(1, rows.size()); assertEquals("A", rows.get(0)[0]);
        assertEquals("2", rows.get(0)[1]); assertEquals("1", rows.get(0)[6]); assertEquals("1", rows.get(0)[23]);
    }
    @Test void emptyFilteringValidationAndSqlErrorsDoNotCreateReport() throws Exception {
        assertNoReport(run("ab")); seedAb(); assertNoReport(run("ab", "--min-impressions=10000"));
        for (String option : List.of("--cuped-pre-days=-1", "--cuped-pre-days=2", "--cuped-min-users=1", "--behavior-table=user_behavior;SELECT 1", "--since=bad-date")) {
            var failed = invoke("ab", false, option); assertNotEquals(0, failed.status, option); assertNoReport(failed);
        }
        for (String option : List.of("--ad-event-table=ad_event;SELECT 1", "--since=bad-date")) {
            var failed = invoke("ad", false, option); assertNotEquals(0, failed.status, option); assertNoReport(failed);
        }
        var failure = invoke("ab", true); assertNotEquals(0, failure.status);
    }
    @Test void parameterValuesRemainBoundAndCannotChangeSourceOrSceneFilter() throws Exception {
        seedAb(); assertNoReport(run("ab", "--scene=home' OR 1=1 --"));
        var failed = invoke("ab", false, "--since=2026-04-01' OR 1=1 --"); assertNotEquals(0, failed.status); assertNoReport(failed);
        assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'", Integer.class));
    }

    private void seedAd() {
        db.execute("INSERT INTO ad_event VALUES('a',1,'IMPRESSION',1,2.5,0.8,'A','2026-04-01'),('b',2,'IMPRESSION',1,9,0.2,'B','2026-04-02'),('c',3,'IMPRESSION',0,8,NULL,'A','2026-04-02'),('d',4,'IMPRESSION',NULL,NULL,NULL,NULL,'2026-04-02'),('old',5,'IMPRESSION',2,7,1,'B','2026-03-31 23:59:59.999999'),('a',1,'CLICK',NULL,NULL,NULL,NULL,'2026-03-01'),('a',1,'CLICK',NULL,NULL,NULL,NULL,'2026-04-10'),('a',1,'CONVERSION',NULL,NULL,NULL,NULL,'2026-03-01'),('a',1,'CONVERSION',NULL,NULL,NULL,NULL,'2026-04-10'),('d',4,'CLICK',NULL,NULL,NULL,NULL,'2026-04-01'),('old',5,'CLICK',NULL,NULL,NULL,NULL,'2026-04-01'),('invalid',6,'INVALID_CLICK',NULL,NULL,NULL,NULL,'2026-04-01'),('invalid-old',6,'INVALID_CLICK',NULL,NULL,NULL,NULL,'2026-03-31')");
    }
    private void seedAb() {
        // 两桶不同曝光量避免原ORDER BY曝光数的未定义并列顺序；前置桶允许NULL。
        db.execute("INSERT INTO user_behavior SELECT u,'IMPRESSION',CASE WHEN u<=4 THEN 'A' ELSE 'B' END,'home','2026-04-01'::timestamp FROM generate_series(1,8)u CROSS JOIN generate_series(1,6)n");
        db.execute("INSERT INTO user_behavior SELECT u,'CLICK',CASE WHEN u<=4 THEN 'A' ELSE 'B' END,'home','2026-04-02'::timestamp FROM generate_series(1,8)u CROSS JOIN generate_series(1,3)n WHERE n<=u%3+1");
        db.execute("INSERT INTO user_behavior SELECT u,'IMPRESSION',NULL,'home','2026-03-30'::timestamp FROM generate_series(1,8)u CROSS JOIN generate_series(1,4)n");
        db.execute("INSERT INTO user_behavior SELECT u,'CLICK',NULL,'home','2026-03-31 23:59:59.999999'::timestamp FROM generate_series(1,8)u CROSS JOIN generate_series(1,3)n WHERE n<=u%4");
        db.execute("INSERT INTO user_behavior SELECT 9,'IMPRESSION','A','home','2026-04-01'::timestamp FROM generate_series(1,10)");
        db.execute("INSERT INTO user_behavior SELECT 1,'IMPRESSION','B','home','2026-04-02'::timestamp FROM generate_series(1,3)");
        db.execute("INSERT INTO user_behavior VALUES(1,'IMPRESSION','A','home','2026-04-01'),(99,'LIKE','A','home','2026-04-01'),(100,'IMPRESSION',NULL,'home','2026-04-01'),(1,'IMPRESSION','A','home','2026-04-03'),(1,'IMPRESSION','A','other','2026-04-01')");
    }
    private Result cuped() throws Exception { return run("ab", "--since=2026-04-01", "--until=2026-04-03", "--scene=home", "--cuped-pre-days=2", "--cuped-min-users=2"); }
    private record Result(int status, String log, Path dir) { }
    private Result run(String job, String... args) throws Exception { var result = invoke(job, false, args); assertEquals(0, result.status, result.log); return result; }
    private Result invoke(String job, boolean blockOutput, String... args) throws Exception {
        Path dir = Files.createTempDirectory(output, job + "-"); if (blockOutput) Files.writeString(dir.resolve("eval"), "blocked");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=Asia/Taipei", "-cp", System.getProperty("java.class.path"), ExperimentReportFixture.class.getName(), PG.getJdbcUrl() + "&ApplicationName=experiment-reader", PG.getUsername(), PG.getPassword(), job));
        command.addAll(Arrays.asList(args)); Path log = dir.resolve("job.log"); var process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(process.waitFor(45, TimeUnit.SECONDS), "报表子进程超时"); return new Result(process.exitValue(), Files.readString(log), dir); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static Path csv(Result r) throws Exception {
        try (var files = Files.list(r.dir.resolve("eval"))) { var all = files.filter(p -> p.toString().endsWith(".csv")).toList(); assertEquals(1, all.size(), r.log); return all.getFirst(); }
    }
    private static String content(Result r) throws Exception { return Files.readString(csv(r)); }
    private static List<String> lines(Result r) throws Exception { return Files.readAllLines(csv(r)); }
    private static List<String[]> data(Result r) throws Exception { return lines(r).stream().skip(1).map(s -> s.split(",", -1)).toList(); }
    private static void assertNoReport(Result r) { assertFalse(Files.exists(r.dir.resolve("eval")), r.log); }
    private static void hash(String name, Result r) throws Exception {
        String expected = Map.of(
                "AB_CUPED", "6a8f4ac25f2e0c7890ed9536160b9893ce94364b254fb374f3c83f0740d1f76c",
                "AB_RAW", "d5bd68634764beba785c222132f1f0a80b4e2283b08683c05bf983bb7f789052",
                "AB_OPTIONS", "6ebbba2b3b112dd4c50d680dbb59ac651611846caee5e17492f976ba264ce142",
                "AB_ALL", "d636974845be506203474a67b98c558d74d36d1fa6e261d9cb4224ac7d9312ec",
                "AD_ALL", "d800d7dd5516e9521ba80ef3811ad2da381cd70806d33b8c17b27f9aab6fb325",
                "AD_SINCE", "e9004f4f773800b5ea6036a87fb204f1380e20565e0de5c88b322e400f1737d0").get(name);
        assertEquals(expected, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(csv(r)))));
    }
}
