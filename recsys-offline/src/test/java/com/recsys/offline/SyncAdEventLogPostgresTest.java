package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.offline.adlog.AdEventSyncStore;
import com.recsys.offline.adlog.AdEventSyncProtocol;
import com.recsys.offline.persistence.mapper.primary.AdEventSyncMapper.Checkpoint;
import com.recsys.offline.persistence.mapper.source.AdEventCopySourceMapper;
import java.util.UUID;
import java.util.concurrent.*;
import com.recsys.common.error.PublicErrorException;

import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** C18真实双PG验收：全部16列、晚提交补洞、分页原子、租约预算及审计重建。 */
@Testcontainers
class SyncAdEventLogPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final PostgreSQLContainer<?> SOURCE_PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final String SOURCE_ID = "a10959ed-5334-4f32-a4dd-43d8d8dced04";
    JdbcTemplate target, source, admin, sourceAdmin;
    SqlSessionTemplate targetSessions, sourceSessions;
    AdEventSyncStore store;
    SyncAdEventLogJob job;

    @BeforeEach void prepare() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        admin.execute("DROP SCHEMA IF EXISTS target_store CASCADE; DROP SCHEMA IF EXISTS source_store CASCADE; CREATE SCHEMA target_store; CREATE SCHEMA source_store");
        sourceAdmin = new JdbcTemplate(new DriverManagerDataSource(SOURCE_PG.getJdbcUrl(), SOURCE_PG.getUsername(), SOURCE_PG.getPassword()));
        sourceAdmin.execute("DROP SCHEMA IF EXISTS source_store CASCADE; CREATE SCHEMA source_store");
        target = jdbc("target_store"); source = jdbc("source_store");
        for (var db : new JdbcTemplate[]{target, source}) {
            db.execute("""
                    CREATE TABLE ad_event(id bigint PRIMARY KEY, request_id text, query text, user_id bigint,
                        ad_id bigint, bidword_id bigint, position integer, event_type text, pctr double precision,
                        pctr_calib double precision, ecpm double precision, charged_price double precision,
                        relevance double precision, ad_bucket text, ts timestamp, creative_id bigint);
                    COMMENT ON TABLE ad_event IS '独占广告事件同步源';
                    COMMENT ON COLUMN ad_event.id IS '源事件唯一ID';
                    COMMENT ON COLUMN ad_event.request_id IS '原请求标识';
                    COMMENT ON COLUMN ad_event.query IS '原搜索词';
                    COMMENT ON COLUMN ad_event.user_id IS '原用户身份';
                    COMMENT ON COLUMN ad_event.ad_id IS '原广告身份';
                    COMMENT ON COLUMN ad_event.bidword_id IS '原竞价词身份';
                    COMMENT ON COLUMN ad_event.position IS '原展示位置';
                    COMMENT ON COLUMN ad_event.event_type IS '原事件类型';
                    COMMENT ON COLUMN ad_event.pctr IS '原预估CTR';
                    COMMENT ON COLUMN ad_event.pctr_calib IS '原校准CTR';
                    COMMENT ON COLUMN ad_event.ecpm IS '原排序收益';
                    COMMENT ON COLUMN ad_event.charged_price IS '原事件记录价格';
                    COMMENT ON COLUMN ad_event.relevance IS '原相关性';
                    COMMENT ON COLUMN ad_event.ad_bucket IS '原实验桶';
                    COMMENT ON COLUMN ad_event.ts IS '原微秒事件时间';
                    COMMENT ON COLUMN ad_event.creative_id IS '原创意身份';
                    """);
        }
        targetSessions = new SqlSessionTemplate(SpringSqlSessions.create(target.getDataSource(),
                "classpath*:mapper/offline/primary/**/*.xml"));
        sourceSessions = new SqlSessionTemplate(SpringSqlSessions.create(source.getDataSource(),
                "classpath*:mapper/offline/source/AdEventCopySourceMapper.xml"));
        assertFalse(targetSessions.getConfiguration().hasStatement(
                "com.recsys.offline.persistence.mapper.source.AdEventCopySourceMapper.page"));
        store = new AdEventSyncStore(targetSessions);
        job = new SyncAdEventLogJob(targetSessions) {
            @Override SqlSessionTemplate buildSourceSessions(String name) {
                if (name == null) return super.buildSourceSessions(null);
                assertEquals("isolated", name);
                return sourceSessions;
            }
        };
    }

    @Test void localIncrementalFullAndEveryColumnKeepOriginalValues() {
        seed(target);
        job.run(args());
        job.run(args());
        assertEquals(target.queryForList("SELECT * FROM ad_event ORDER BY id"), target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
        target.execute("INSERT INTO ad_event(id,event_type) VALUES(2,'CONVERSION')");
        job.run(args());
        assertEquals(3, count());
        target.execute("UPDATE ad_event SET event_type='CLICK' WHERE id=3");
        assertThrows(PublicErrorException.class, () -> job.run(args()));
        assertEquals("PAUSED", store.checkpoint().state());
        assertEquals("IMPRESSION", target.queryForObject("SELECT event_type FROM ad_event_log WHERE id=3", String.class));
        job.run(fullArgs(UUID.randomUUID()));
        assertEquals(3, count());
        assertEquals(target.queryForList("SELECT * FROM ad_event ORDER BY id"), target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
        assertEquals(16, target.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='ad_event_log'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NOT NULL", Integer.class));
        assertEquals(3, target.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='target_store' AND indexname IN('idx_ad_event_log_type_ts','idx_ad_event_log_ad','idx_ad_event_log_req')", Integer.class));
    }

    @Test void crossDatabaseCopiesAllFieldsAndClosesSourceConnection() {
        seed(source);
        source.execute("INSERT INTO ad_event(id) SELECT n FROM generate_series(4,1003) n");
        job.run(args("--source-db=isolated"));
        job.run(args("--source-db=isolated"));
        assertEquals(1002, count());
        assertEquals(source.queryForList("SELECT * FROM ad_event ORDER BY id"), target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
        assertEquals(0, target.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
        assertSourceClosed();
    }

    @Test void secondBatchFailureLeavesFirstBatchAndNoPartialFailedBatch() {
        store.install();
        target.execute("ALTER TABLE ad_event_log ADD CONSTRAINT test_charged_price CHECK(charged_price >= 0)");
        source.execute("INSERT INTO ad_event(id,charged_price) SELECT n,CASE WHEN n=1002 THEN -1 ELSE 1 END FROM generate_series(1,1002) n");
        assertThrows(PublicErrorException.class, () -> job.run(args("--source-db=isolated")));
        assertEquals(1000, count());
        assertEquals(0, target.queryForObject("SELECT count(*) FROM ad_event_log WHERE id IN(1001,1002)", Integer.class));
        assertSourceClosed();
    }

    @Test void outerTransactionIsRejectedBeforeAnyFullOrNewRows() {
        seed(target); job.run(args());
        var original = target.queryForList("SELECT * FROM ad_event_log ORDER BY id");
        target.execute("INSERT INTO ad_event(id) VALUES(2)");
        new TransactionTemplate(new DataSourceTransactionManager(target.getDataSource())).executeWithoutResult(status -> {
            assertThrows(PublicErrorException.class, () -> job.run(fullArgs(UUID.randomUUID())));
            status.setRollbackOnly();
        });
        assertEquals(original, target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
    }

    @Test void invalidSourceOptionCannotCreateOrClearReadModelEvenWithFull() {
        for (String[] options : new String[][]{{"--full", "--source-db"}, {"--full", "--source-db= "},
                {"--full", "--source-db=one", "--source-db=two"}}) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                    () -> job.run(invalidFullArgs(options))).errorCode());
            assertNull(target.queryForObject("SELECT to_regclass('ad_event_log')::text", String.class));
        }
        target.execute("INSERT INTO ad_event(id) VALUES(1)");
        job.run(args());
        var original = target.queryForList("SELECT * FROM ad_event_log");
        target.execute("INSERT INTO ad_event(id) VALUES(2)");
        for (String[] options : new String[][]{{"--full", "--source-db"}, {"--full", "--source-db= "},
                {"--full", "--source-db=one", "--source-db=two"}}) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                    () -> job.run(invalidFullArgs(options))).errorCode());
            assertEquals(original, target.queryForList("SELECT * FROM ad_event_log"));
        }
        job.run(fullArgs(UUID.randomUUID()));assertEquals(2, count());
    }

    @Test void lowIdCommittedAfterHighIdIsRecoveredInNextLocalSweep() throws Exception { delayedCommit(target, false); }
    @Test void lowIdCommittedAfterHighIdIsRecoveredAcrossPhysicalDatabases() throws Exception { delayedCommit(source, true); }

    private void delayedCommit(JdbcTemplate origin, boolean cross) throws Exception {
        try (var delayed = origin.getDataSource().getConnection()) {
            delayed.setAutoCommit(false);
            try (var insert = delayed.prepareStatement("INSERT INTO ad_event(id,request_id) VALUES(1,'late-low')")) { insert.executeUpdate(); }
            origin.execute("INSERT INTO ad_event(id,request_id) VALUES(2,'early-high')");
            var arguments = cross ? args("--source-db=isolated") : args();
            job.run(arguments); assertEquals(1, count()); assertEquals(2, store.checkpoint().cursorId());
            delayed.commit();
            job.run(arguments); assertEquals(2, count()); assertEquals(2, store.checkpoint().epoch());
            assertEquals(origin.queryForList("SELECT * FROM ad_event ORDER BY id"), target.queryForList("SELECT * FROM ad_event_log ORDER BY id"));
        }
    }

    @Test void pageLimitPersistsCursorAndFrozenUpperDoesNotChaseNewHighIds() {
        target.execute("INSERT INTO ad_event(id) SELECT n FROM generate_series(1,1002) n");
        job.run(args("--max-pages=1"));
        assertEquals(1000, count()); assertEquals("READY", store.checkpoint().state()); assertEquals(1000, store.checkpoint().cursorId());
        target.execute("INSERT INTO ad_event(id) VALUES(2000)");
        new SyncAdEventLogJob(targetSessions).run(args("--max-pages=1"));
        assertEquals(1002, count()); assertEquals("COMPLETE", store.checkpoint().state()); assertEquals(1002, store.checkpoint().upperId());
        job.run(args()); assertEquals(1003, count());
    }

    @Test void pageFailureBudgetSurvivesRestartsAndAuditedResumeDoesNotClearSuccessfulPages() {
        store.install();
        target.execute("ALTER TABLE ad_event_log ADD CONSTRAINT fault_page CHECK(id<>1002)");
        target.execute("INSERT INTO ad_event(id) SELECT n FROM generate_series(1,1002) n");
        for (int attempt = 1; attempt <= 3; attempt++) {
            var failure = assertThrows(PublicErrorException.class, () -> new SyncAdEventLogJob(targetSessions).run(args()));
            assertNull(failure.getCause(), "CLI不能携带约束错误中的源行原文");
            assertEquals(1000, count()); assertEquals(1000, store.checkpoint().cursorId()); assertEquals(attempt, store.checkpoint().attempts());
            target.execute("UPDATE ad_event_sync_checkpoint SET available_at=clock_timestamp()-interval '1 second'");
        }
        assertEquals("PAUSED", store.checkpoint().state());
        assertThrows(PublicErrorException.class, () -> job.run(args()));
        target.execute("ALTER TABLE ad_event_log DROP CONSTRAINT fault_page");
        var recovery = args("--resume-command-id=" + UUID.randomUUID(), "--actor=owner", "--reason=移除隔离故障");
        job.run(recovery); assertEquals(1002, count());
        job.run(recovery); assertEquals(1002, count());
        assertEquals(1, target.queryForObject("SELECT count(*) FROM ad_event_sync_audit", Integer.class));
    }

    @Test void concurrentClaimHasOneWinnerAndExpiredFenceCannotCommit() throws Exception {
        store.install(); store.prepare(UUID.fromString(SOURCE_ID), "fixture", null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<Checkpoint> claim = () -> { start.await(); try { return store.claim(UUID.randomUUID().toString()); } catch (PublicErrorException occupied) { return null; } };
            var a = pool.submit(claim); var b = pool.submit(claim); start.countDown();
            Checkpoint first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null));
            Checkpoint old = store.beginAttempt(first != null ? first : second); old = store.freeze(old, 0);
            target.execute("UPDATE ad_event_sync_checkpoint SET lease_until=clock_timestamp()-interval '1 second'");
            Checkpoint replacement = store.claim("replacement"); assertTrue(replacement.fence() > old.fence());
            Checkpoint stale = old;
            assertThrows(PublicErrorException.class, () -> store.commitPage(stale, java.util.List.of()));
            replacement = store.beginAttempt(replacement); store.commitPage(replacement, java.util.List.of());
            assertEquals("COMPLETE", store.checkpoint().state());
        }
    }

    @Test void lastAttemptProcessLossBecomesPausedAndRepeatedResumeCannotResetLiveBudget() {
        store.install(); store.prepare(UUID.fromString(SOURCE_ID), "fixture", null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = store.beginAttempt(store.claim("worker-" + attempt)); assertEquals(attempt, claim.attempts());
            target.execute("UPDATE ad_event_sync_checkpoint SET lease_until=clock_timestamp()-interval '1 second'");
        }
        assertThrows(PublicErrorException.class, () -> store.claim("restart")); assertEquals("PAUSED", store.checkpoint().state());
        var command = new AdEventSyncProtocol.Command(UUID.randomUUID(), AdEventSyncProtocol.Action.RESUME, "owner", "恢复");
        store.prepare(UUID.fromString(SOURCE_ID), "fixture", command);
        store.beginAttempt(store.claim("new-round"));
        store.prepare(UUID.fromString(SOURCE_ID), "fixture", command); assertEquals(1, store.checkpoint().attempts());
        var changed = new AdEventSyncProtocol.Command(command.id(), command.action(), "owner", "different");
        assertThrows(PublicErrorException.class, () -> store.prepare(UUID.fromString(SOURCE_ID), "fixture", changed));
    }

    @Test void contentConflictRollsBackWholePageAndFullCommandRetryNeverTruncatesTwice() {
        seed(target); job.run(args());
        target.execute("UPDATE ad_event SET query='changed' WHERE id=1; INSERT INTO ad_event(id) VALUES(2)");
        assertThrows(PublicErrorException.class, () -> job.run(args()));
        assertEquals(2, count()); assertNull(target.queryForObject("SELECT query FROM ad_event_log WHERE id=1", String.class));
        assertEquals("CONTENT_CONFLICT", store.checkpoint().errorCode()); assertEquals(1, store.checkpoint().errorId());
        UUID full = UUID.randomUUID(); job.run(fullArgs(full)); assertEquals(3, count());
        target.execute("INSERT INTO ad_event_log(id) VALUES(9999)");
        job.run(fullArgs(full)); assertEquals(4, count());
        assertEquals(1, target.queryForObject("SELECT count(*) FROM ad_event_sync_audit WHERE action='REBUILD'", Integer.class));
        assertThrows(PublicErrorException.class, () -> job.run(args("--full", "--rebuild-command-id=" + full, "--actor=owner", "--reason=changed")));
        assertEquals(4, count());
    }

    @Test void explicitFullFailureResumesOriginalRebuildWithoutDroppingCompletedPage() {
        seed(target); job.run(args()); target.execute("INSERT INTO ad_event(id) SELECT n FROM generate_series(4,1003) n");
        target.execute("ALTER TABLE ad_event_log ADD CONSTRAINT full_fault CHECK(id<>1003)");
        UUID full = UUID.randomUUID(); assertThrows(PublicErrorException.class, () -> job.run(fullArgs(full)));
        assertEquals(1000, count()); long epoch = store.checkpoint().epoch();
        target.execute("INSERT INTO ad_event_log(id) VALUES(9999); ALTER TABLE ad_event_log DROP CONSTRAINT full_fault; UPDATE ad_event_sync_checkpoint SET available_at=clock_timestamp()-interval '1 second'");
        job.run(fullArgs(full)); assertEquals(1003, count()); assertEquals(epoch, store.checkpoint().epoch());
    }

    @Test void wrongSourceAndRecreatedSourceTableCannotOverwriteOrFullClearTarget() {
        seed(target); job.run(args());
        assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments("--source-id=" + UUID.randomUUID())));
        assertEquals(2, count());
        target.execute("ALTER TABLE ad_event RENAME TO old_ad_event; CREATE TABLE ad_event(LIKE old_ad_event INCLUDING ALL); COMMENT ON TABLE ad_event IS '重建身份的独占源fixture'");
        for (String column : java.util.List.of("id", "request_id", "query", "user_id", "ad_id", "bidword_id", "position", "event_type", "pctr", "pctr_calib", "ecpm", "charged_price", "relevance", "ad_bucket", "ts", "creative_id"))
            target.execute("COMMENT ON COLUMN ad_event." + column + " IS '重建源fixture原字段'");
        assertThrows(PublicErrorException.class, () -> job.run(fullArgs(UUID.randomUUID()))); assertEquals(2, count());
        assertThrows(PublicErrorException.class, () -> job.run(args()));
        assertEquals("SOURCE_CHANGED", store.checkpoint().errorCode()); assertEquals(2, count());
    }

    @Test void sourceOutageIsBoundedAndKeepsDurablePageBudget() throws Exception {
        seed(source); job.run(args("--source-db=isolated"));
        String id = SOURCE_PG.getContainerId(); SOURCE_PG.getDockerClient().pauseContainerCmd(id).exec();
        long start = System.nanoTime();
        try { assertThrows(PublicErrorException.class, () -> job.run(args("--source-db=isolated"))); }
        finally { SOURCE_PG.getDockerClient().unpauseContainerCmd(id).exec(); }
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 25);
        assertEquals(1, store.checkpoint().attempts()); assertEquals(2, count());
        target.execute("UPDATE ad_event_sync_checkpoint SET available_at=clock_timestamp()-interval '1 second'");
        job.run(args("--source-db=isolated")); assertEquals("COMPLETE", store.checkpoint().state()); assertSourceClosed();
    }

    @Test void independentJvmExitAfterCommittedPageResumesWithoutDuplicateEffects() throws Exception {
        target.execute("INSERT INTO ad_event(id) SELECT n FROM generate_series(1,1002) n");
        var ds = (DriverManagerDataSource) target.getDataSource();
        var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("surefire.test.class.path"),
                CrashProcess.class.getName(), ds.getUrl(), PG.getUsername(), PG.getPassword()).redirectErrorStream(true)
                .redirectOutput(new java.io.File("target/ad-event-sync-exit.log")).start();
        try { assertTrue(process.waitFor(40, TimeUnit.SECONDS)); assertEquals(18, process.exitValue()); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        assertEquals(1000, count()); assertEquals(1000, store.checkpoint().cursorId());
        assertThrows(PublicErrorException.class, () -> job.run(args()));
        target.execute("UPDATE ad_event_sync_checkpoint SET lease_until=clock_timestamp()-interval '1 second'");
        job.run(args()); assertEquals(1002, count()); assertEquals("COMPLETE", store.checkpoint().state());
    }

    /** 真实独立JVM在持久提交之后退出，父进程从PG恢复，而非内存fixture模拟重启。 */
    public static class CrashProcess {
        public static void main(String[] connection) throws Exception {
            var ds = new DriverManagerDataSource(connection[0], connection[1], connection[2]);
            var sessions = new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/offline/primary/**/*.xml"));
            new SyncAdEventLogJob(sessions) {
                @Override protected void afterPageCommitted(Checkpoint checkpoint) { Runtime.getRuntime().halt(18); }
            }.run(args());
        }
    }

    @Test void invalidIdentityBudgetFullAndAuditArgumentsFailBeforeAnyDdl() {
        for (String[] options : new String[][]{{}, {"--source-id=bad"}, {"--source-id=" + SOURCE_ID, "--max-pages=101"},
                {"--source-id=" + SOURCE_ID, "--max-seconds=0"}, {"--source-id=" + SOURCE_ID, "--full"},
                {"--source-id=" + SOURCE_ID, "--source-db=x?bad=yes"}}) {
            assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments(options)));
            assertNull(target.queryForObject("SELECT to_regclass('ad_event_log')::text", String.class));
        }
    }

    @Test void fullLongIdAndNonPositiveSourceIdsAreHandledWithoutRoundingOrSilentOmission() {
        target.execute("INSERT INTO ad_event(id) VALUES(9223372036854775807)");
        job.run(args()); assertEquals(Long.MAX_VALUE, store.checkpoint().cursorId()); assertEquals(1, count());
        job.run(args()); assertEquals(1, count());
        target.execute("INSERT INTO ad_event(id) VALUES(0)");
        assertThrows(PublicErrorException.class, () -> job.run(fullArgs(UUID.randomUUID()))); assertEquals(1, count());
        assertThrows(PublicErrorException.class, () -> job.run(args())); assertEquals("PAUSED", store.checkpoint().state());
    }

    @Test void migrationIsIdempotentChecksChecksumAndCommentsEveryTableAndColumn() {
        store.install(); store.install();
        assertEquals(0, target.queryForObject("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='target_store' AND c.relname LIKE 'ad_event_sync_%' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL", Integer.class));
        assertEquals(0, target.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='target_store' AND c.relname LIKE 'ad_event_sync_%' AND c.relkind='r' AND obj_description(c.oid,'pg_class') IS NULL", Integer.class));
        target.execute("UPDATE ad_event_sync_schema SET script_sha256='changed'");
        assertThrows(PublicErrorException.class, store::install);
    }

    static DefaultApplicationArguments args(String... options) {
        var values = new java.util.ArrayList<String>(); values.add("--source-id=" + SOURCE_ID); values.addAll(java.util.List.of(options));
        return new DefaultApplicationArguments(values.toArray(String[]::new));
    }
    static DefaultApplicationArguments invalidFullArgs(String... invalidOptions) {
        var values = new java.util.ArrayList<String>(java.util.List.of(invalidOptions));
        values.add("--rebuild-command-id=" + UUID.randomUUID()); values.add("--actor=owner"); values.add("--reason=参数拒绝fixture");
        return args(values.toArray(String[]::new));
    }
    static DefaultApplicationArguments fullArgs(UUID command) {
        return args("--full", "--rebuild-command-id=" + command, "--actor=test-owner", "--reason=隔离读模型重建");
    }

    private void seed(JdbcTemplate db) {
        db.execute("INSERT INTO ad_event(id) VALUES(1)");
        db.update("INSERT INTO ad_event VALUES(3,?,?,9223372036854775807,7,8,0,'IMPRESSION',0.00000001,0.123456789,3.14,1.234567,0.5,'bucket','2024-01-01 00:00:00.123456',9223372036854775807)", "request'中文", "q';SELECT 1");
    }

    private void assertSourceClosed() {
        assertEquals(0, sourceAdmin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='adcopy-source_store'", Integer.class));
    }

    private JdbcTemplate jdbc(String schema) {
        var pg = schema.equals("source_store") ? SOURCE_PG : PG;
        String url = pg.getJdbcUrl() + (pg.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=adcopy-" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(url + "&connectTimeout=5&socketTimeout=10", pg.getUsername(), pg.getPassword()));
    }

    private int count() {
        return target.queryForObject("SELECT count(*) FROM ad_event_log", Integer.class);
    }
}
