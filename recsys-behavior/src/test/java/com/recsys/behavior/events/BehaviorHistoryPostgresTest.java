package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorHistoryRepository.*;
import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import com.recsys.common.behavior.LegacyBehaviorAccepted;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;

/** B08/B09/B12：真实PG证明清单公平性、事务恢复和审计；不把已捕获误作broker确认。 */
@Tag("behavior-reliability") @Testcontainers
class BehaviorHistoryPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    BehaviorHistoryFixture f;
    @BeforeEach void setup() throws Exception { f = new BehaviorHistoryFixture(PG); }

    @Test void explicitMigrationRollbackChecksumCommentsAndLegacyOnlyStartup() throws Exception {
        var old = new BehaviorEventsFixture(PG, true);
        old.repo.verifySchema(); old.accept();
        assertThrows(Failure.class, () -> new BehaviorHistoryRepository(old.sessions, new DataSourceTransactionManager(old.ds), new MockEnvironment()));
        String sql;
        try (var input = new ClassPathResource(BehaviorHistoryRepository.MIGRATION).getInputStream()) { sql = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        assertThrows(Exception.class, () -> BehaviorHistoryFixture.migrateHistory(old, sql.replace("COMMIT;", "SELECT 1/0; COMMIT;"), BehaviorHistoryRepository.migrationSha256()));
        assertNull(old.jdbc.queryForObject("SELECT to_regclass('behavior_history_command')", String.class));
        assertEquals(1, old.jdbc.queryForObject("SELECT count(*) FROM behavior_event_schema_history", Integer.class));
        BehaviorHistoryFixture.migrateHistory(old, null, "0".repeat(64));
        assertEquals(BehaviorHistoryRepository.Error.MIGRATION_INVALID, assertThrows(Failure.class,
                () -> new BehaviorHistoryRepository(old.sessions, new DataSourceTransactionManager(old.ds), new MockEnvironment())).code());
        old.repo.verifySchema(); // V002存在不阻止原V001 owner继续工作。
        f.history(); f.repo.verifySchema();
        assertEquals(0, f.jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
                WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname LIKE 'behavior_history_%'
                AND (obj_description(c.oid,'pg_class') IS NULL OR col_description(c.oid,a.attnum) IS NULL)
                """, Integer.class));
        for (String property : List.of("recsys.behavior.use-kafka", "recsys.behavior.publish-events")) {
            assertEquals(BehaviorHistoryRepository.Error.INVALID_COMMAND, assertThrows(Failure.class,
                    () -> new BehaviorHistoryRepository(f.sessions, new DataSourceTransactionManager(f.ds), new MockEnvironment().withProperty(property, "true"))).code());
        }
        assertThrows(IllegalArgumentException.class, () -> new BehaviorHistoryRepository(f.sessions,
                new DataSourceTransactionManager(new DriverManagerDataSource()), new MockEnvironment()));
        assertThrows(IllegalStateException.class, () -> f.repo.captureHistoryInTransaction(1));
    }

    @Test void boundedCanonicalManifestExactRetryConflictsAndNoAutomaticCapture() {
        var h = f.history(); UUID id = UUID.randomUUID(); f.seed(1);
        var original = h.captureHistory(id, f.sourceId(), List.of(2L,1L), "actor", "reason");
        assertEquals("[1,2]", original.command().manifest());
        assertEquals(original, h.captureHistory(id, f.sourceId(), List.of(1L,2L), "actor", "reason"));
        assertEquals(2L, original.resultCounts().get("WAITING_SOURCE")); assertEquals(0, f.count("behavior_event_outbox"));
        assertEquals(BehaviorHistoryRepository.Error.COMMAND_CONFLICT, assertThrows(Failure.class,
                () -> h.captureHistory(id, f.sourceId(), List.of(1L), "actor", "reason")).code());
        assertThrows(Failure.class, () -> h.captureHistory(id, f.sourceId(), List.of(1L,2L), "changed", "reason"));
        assertThrows(Failure.class, () -> h.captureHistory(UUID.randomUUID(), UUID.randomUUID(), List.of(1L), "actor", "reason"));
        for (List<Long> ids : List.of(List.<Long>of(), List.of(1L,1L), List.of(0L), List.of(-1L), LongStream.rangeClosed(1,101).boxed().toList())) {
            assertThrows(Failure.class, () -> h.captureHistory(UUID.randomUUID(), f.sourceId(), ids, "actor", "reason"));
        }
        assertThrows(Failure.class, () -> h.captureHistory(UUID.randomUUID(), f.sourceId(), Arrays.asList(1L,null), "actor", "reason"));
        assertThrows(Failure.class, () -> h.captureHistory(UUID.randomUUID(), f.sourceId(), List.of(1L), " ", "reason"));
        assertThrows(Failure.class, () -> h.captureHistory(UUID.randomUUID(), f.sourceId(), List.of(1L), "actor", "x".repeat(1001)));
        assertThrows(Failure.class, () -> h.advanceHistory(UUID.randomUUID()));
        assertEquals(100, h.captureHistory(UUID.randomUUID(), f.sourceId(), LongStream.rangeClosed(1,100).boxed().toList(), "actor", "bounded").items().size());
    }

    @Test void firstTenMissingCannotStarveLaterVisibleItemsAndLateSourcesRemainRecoverable() {
        f.seed(11); f.seed(12); UUID id = UUID.randomUUID();
        f.history().captureHistory(id, f.sourceId(), LongStream.rangeClosed(1,12).boxed().toList(), "actor", "fairness");
        var first = f.history().advanceHistory(id);
        assertEquals(12L, first.resultCounts().get("WAITING_SOURCE")); assertEquals(10, first.command().visitSequence());
        assertEquals(10, first.items().stream().filter(i -> i.lastVisit()>0).count());
        var second = f.history().advanceHistory(id); // 新仓储模拟恢复，不依赖进程内cursor。
        assertEquals("OPEN", second.command().status()); assertEquals(2L, second.resultCounts().get("CAPTURED"));
        assertEquals(10L, second.resultCounts().get("WAITING_SOURCE")); assertEquals(0L, second.deliveryCounts().get("PUBLISHED"));
        assertEquals(2L, second.deliveryCounts().get("READY")); assertEquals(12, second.items().size());
        for (long source=1; source<=10; source++) f.seed(source);
        var complete = f.history().advanceHistory(id);
        assertEquals("COMPLETE", complete.command().status()); assertEquals(12, complete.covered());
        assertEquals(0L, complete.resultCounts().get("WAITING_SOURCE")); assertEquals(12, f.count("behavior_event_outbox"));
        assertEquals(complete, f.history().advanceHistory(id));
    }

    @Test void lowSequenceIdUncommittedBeforeHigherCommitIsNotLost() throws Exception {
        try (var low = f.ds.getConnection()) {
            low.setAutoCommit(false);
            long lowId;
            try (var statement=low.createStatement(); var rs=statement.executeQuery("INSERT INTO user_behavior(user_id,item_id,action,value) VALUES(7,42,'LIKE',1) RETURNING id")) {
                assertTrue(rs.next()); lowId=rs.getLong(1);
            }
            long highId=f.jdbc.queryForObject("INSERT INTO user_behavior(user_id,item_id,action,value) VALUES(7,42,'PLAY',2) RETURNING id",Long.class);
            assertTrue(lowId<highId); UUID command=UUID.randomUUID(); f.register(command,lowId,highId);
            var partial=f.history().advanceHistory(command);
            assertEquals(1L,partial.resultCounts().get("CAPTURED"));assertEquals("WAITING_SOURCE",partial.items().getFirst().status());
            assertEquals(1L,partial.resultCounts().get("WAITING_SOURCE"));assertEquals("OPEN",partial.command().status());
            low.commit();var done=f.history().advanceHistory(command);
            assertEquals(2L,done.resultCounts().get("CAPTURED"));assertEquals("COMPLETE",done.command().status());
        }
    }

    @Test void unknownHistoricalOriginAndLocalTimeSurviveInvalidRowsAndLargeSnapshots() {
        f.seed(1); f.seed(2); f.jdbc.update("UPDATE user_behavior SET ts=NULL,scene='engine-impression',action='IMPRESSION' WHERE id=2");
        for(long id=3;id<=6;id++) f.seed(id);
        f.jdbc.update("UPDATE user_behavior SET user_id=NULL WHERE id=3");
        f.jdbc.update("UPDATE user_behavior SET value='NaN'::float8 WHERE id=4");
        f.jdbc.update("UPDATE user_behavior SET scene=? WHERE id=5","secret-source-"+"x".repeat(70000));
        f.jdbc.update("UPDATE user_behavior SET action='BAD' WHERE id=6");
        UUID command=UUID.randomUUID(); f.register(command,1L,2L,3L,4L,5L,6L);
        var report=f.history().advanceHistory(command);
        assertEquals("COMPLETED_WITH_ISSUES",report.command().status());assertEquals(4L,report.resultCounts().get("INVALID_SOURCE"));
        assertEquals(2L,report.resultCounts().get("CAPTURED"));assertEquals(4L,report.deliveryCounts().get("DEAD"));assertEquals(0L,report.deliveryCounts().get("PUBLISHED"));
        for(var item:report.items().subList(0,2)) {
            var event=f.codec.decode("7",f.payload(item.eventId()));
            assertEquals(LegacyBehaviorAccepted.Origin.LEGACY_ROW,event.origin());
            assertEquals(LegacyBehaviorAccepted.PriorPublication.UNKNOWN,event.priorPublication());
            assertNull(event.behavior().ts());assertEquals(item.behaviorId()==1?"2001-02-03T04:05:06.123456":null,event.sourceTsLocal());
            assertFalse(f.payload(item.eventId()).contains("trafficClass"));
        }
        assertEquals(4, f.jdbc.queryForObject("SELECT count(*) FROM behavior_event_outbox WHERE attempts=0 AND wire_payload_json IS NULL AND last_error='INVALID_SOURCE'",Integer.class));
        assertTrue(f.jdbc.queryForObject("SELECT octet_length(source_snapshot_json)>70000 FROM behavior_event_outbox WHERE behavior_id=5",Boolean.class));
    }

    @Test void driftDeletionAndAlreadyCapturedLiveKeepOriginalIdentityRouteAndBytes() {
        String live=f.accept();long liveId=f.jdbc.queryForObject("SELECT behavior_id FROM behavior_event_outbox WHERE event_id=?",Long.class,live);
        f.seed(100); f.seed(101);UUID initial=UUID.randomUUID();f.register(initial,100L,101L);f.history().advanceHistory(initial);
        var original=f.jdbc.queryForList("SELECT event_id,wire_payload_json,source_snapshot_sha256,destination FROM behavior_event_outbox ORDER BY event_id");
        f.jdbc.update("UPDATE user_behavior SET bucket='drift' WHERE id=100");f.jdbc.update("DELETE FROM user_behavior WHERE id=101");
        f.seed(999);f.jdbc.update("DELETE FROM user_behavior WHERE id=999");
        UUID command=UUID.randomUUID();f.register(command,liveId,100L,101L,999L);var report=f.history().advanceHistory(command);
        assertEquals(1L,report.resultCounts().get("SOURCE_CHANGED"));assertEquals(2L,report.resultCounts().get("ALREADY_CAPTURED"));
        assertEquals(1L,report.resultCounts().get("WAITING_SOURCE"));assertEquals("OPEN",report.command().status());
        assertEquals(original,f.jdbc.queryForList("SELECT event_id,wire_payload_json,source_snapshot_sha256,destination FROM behavior_event_outbox ORDER BY event_id"));
        assertEquals(LegacyBehaviorAccepted.LIVE_TOPIC,report.items().getFirst().destination());
        assertEquals("CAPTURED",f.history().historyReport(initial).items().getFirst().status());
        var cancel=f.history().cancelHistory(UUID.randomUUID(),command,"actor","permanently deleted or unknown");
        assertEquals("CANCELLED",cancel.result());var stopped=f.history().advanceHistory(command);
        assertEquals(1,stopped.cancelledUnprocessed());assertEquals(1L,stopped.resultCounts().get("WAITING_SOURCE"));assertEquals(0L,stopped.deliveryCounts().get("PUBLISHED"));
    }

    @Test void concurrentDuplicateRegistrationAndOverlappingAdvanceCreateOneEventPerSource() throws Exception {
        for(long id=1;id<=15;id++)f.seed(id);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();List<Long> ids=LongStream.rangeClosed(1,15).boxed().toList();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var calls=new ArrayList<Future<Report>>();
            for(int i=0;i<8;i++)calls.add(pool.submit(()->{start.await();return f.history().captureHistory(a,f.sourceId(),ids,"actor","same");}));
            start.countDown();Report original=calls.getFirst().get(15,TimeUnit.SECONDS);
            for(var call:calls)assertEquals(original,call.get(15,TimeUnit.SECONDS));
            f.history().captureHistory(b,f.sourceId(),ids.reversed(),"actor","overlap");
            var work=new ArrayList<Future<?>>();for(int i=0;i<8;i++){UUID command=i%2==0?a:b;work.add(pool.submit(()->f.history().advanceHistory(command)));}
            for(var call:work)call.get(20,TimeUnit.SECONDS);
        }
        var ra=f.history().historyReport(a);var rb=f.history().historyReport(b);
        assertEquals("COMPLETE",ra.command().status());assertEquals("COMPLETE",rb.command().status());assertEquals(15,f.count("behavior_event_outbox"));
        assertEquals(15L,ra.resultCounts().get("CAPTURED")+rb.resultCounts().get("CAPTURED"));
        assertEquals(15L,ra.resultCounts().get("ALREADY_CAPTURED")+rb.resultCounts().get("ALREADY_CAPTURED"));
        assertEquals(ra.items().stream().map(i->i.eventId()).toList(),rb.items().stream().map(i->i.eventId()).toList());
    }

    @Test void commandItemsOutboxAndCancelAuditRollbackTogetherAndPartialAdvanceResumes() {
        f.seed(1);f.seed(2);f.seed(3);UUID id=UUID.randomUUID();
        f.jdbc.execute("CREATE FUNCTION test_fail_item_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'secret-token'; END $$; CREATE TRIGGER test_fail_item_insert BEFORE INSERT ON behavior_history_item FOR EACH ROW EXECUTE FUNCTION test_fail_item_insert()");
        Failure failed=assertThrows(Failure.class,()->f.register(id,1L,2L,3L));assertNull(failed.getCause());assertFalse(failed.toString().contains("secret-token"));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM behavior_history_command",Integer.class));
        f.jdbc.execute("DROP TRIGGER test_fail_item_insert ON behavior_history_item");f.register(id,1L,2L,3L);
        f.jdbc.execute("CREATE FUNCTION test_fail_second() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.behavior_id=2 THEN RAISE EXCEPTION 'secret-token'; END IF; RETURN NEW; END $$; CREATE TRIGGER test_fail_second BEFORE UPDATE ON behavior_history_item FOR EACH ROW EXECUTE FUNCTION test_fail_second()");
        assertThrows(Failure.class,()->f.history().advanceHistory(id));var partial=f.history().historyReport(id);
        assertEquals(1L,partial.resultCounts().get("CAPTURED"));assertEquals(2L,partial.resultCounts().get("WAITING_SOURCE"));
        assertEquals(1,partial.command().visitSequence());assertEquals(1,f.count("behavior_event_outbox"));
        f.jdbc.execute("CREATE FUNCTION test_fail_cancel() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='CANCELLED' THEN RAISE EXCEPTION 'secret-token'; END IF; RETURN NEW; END $$; CREATE TRIGGER test_fail_cancel BEFORE UPDATE ON behavior_history_command FOR EACH ROW EXECUTE FUNCTION test_fail_cancel()");
        assertThrows(Failure.class,()->f.history().cancelHistory(UUID.randomUUID(),id,"actor","cancel"));
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM behavior_history_cancel",Integer.class));assertEquals("OPEN",f.history().historyReport(id).command().status());
        f.jdbc.execute("DROP TRIGGER test_fail_second ON behavior_history_item");assertEquals("COMPLETE",f.history().advanceHistory(id).command().status());
        assertEquals(3,f.count("behavior_event_outbox"));
    }

    @Test void concurrentCancelSerializesWithAdvanceAndExactAuditRetryNeverReopens() throws Exception {
        for(long id=1;id<=15;id++)f.seed(id);
        UUID id=UUID.randomUUID(),cancelId=UUID.randomUUID();f.history().captureHistory(id,f.sourceId(),LongStream.rangeClosed(1,15).boxed().toList(),"actor","cancel race");
        try(var pool=Executors.newFixedThreadPool(5)) {
            var start=new CountDownLatch(1);var advance=pool.submit(()->{start.await();return f.history().advanceHistory(id);});
            var cancels=new ArrayList<Future<com.recsys.behavior.persistence.mapper.primary.BehaviorHistoryMapper.Cancellation>>();
            for(int i=0;i<4;i++)cancels.add(pool.submit(()->{start.await();return f.history().cancelHistory(cancelId,id,"trusted","stop");}));
            start.countDown();advance.get(15,TimeUnit.SECONDS);var original=cancels.getFirst().get(15,TimeUnit.SECONDS);
            for(var call:cancels)assertEquals(original,call.get(15,TimeUnit.SECONDS));
            assertEquals("CANCELLED",original.result());
        }
        var report=f.history().historyReport(id);assertEquals("CANCELLED",report.command().status());
        assertTrue(report.covered()<=10);assertEquals(15-report.covered(),report.cancelledUnprocessed());
        assertEquals(report,f.history().advanceHistory(id));assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM behavior_history_cancel",Integer.class));
        assertThrows(Failure.class,()->f.history().cancelHistory(cancelId,id,"trusted","changed"));
        UUID other=UUID.randomUUID();f.register(other,999L);
        assertThrows(Failure.class,()->f.history().cancelHistory(cancelId,other,"trusted","stop"));
        assertEquals("OPEN",f.history().historyReport(other).command().status());
        assertEquals("ALREADY_TERMINAL",f.history().cancelHistory(UUID.randomUUID(),id,"trusted","already stopped").result());
    }

    @Test void terminalResultsAndInputsAreImmutableButDeliveryReportTracksSendingAndDead() {
        f.seed(1);UUID id=UUID.randomUUID();f.register(id,1L);var complete=f.history().advanceHistory(id);
        assertEquals("COMPLETE",complete.command().status());assertEquals(0L,complete.deliveryCounts().get("PUBLISHED"));
        var pending=f.repo.claim(Destination.HISTORY).orElseThrow();assertEquals(1L,f.history().historyReport(id).deliveryCounts().get("SENDING"));
        assertTrue(f.repo.failed(pending,1000,BehaviorEventRepository.Error.INVALID_EVENT));
        assertEquals(1L,f.history().historyReport(id).deliveryCounts().get("DEAD"));assertEquals("CAPTURED",f.history().historyReport(id).items().getFirst().status());
        var cancel=f.history().cancelHistory(UUID.randomUUID(),id,"actor","already finished");assertEquals("ALREADY_TERMINAL",cancel.result());
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE behavior_history_command SET reason='changed' WHERE command_id=?",id));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE behavior_history_item SET status='SOURCE_CHANGED',error_code='SOURCE_CHANGED' WHERE command_id=?",id));
        assertThrows(Exception.class,()->f.jdbc.update("DELETE FROM behavior_history_item WHERE command_id=?",id));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE behavior_history_cancel SET reason='changed' WHERE cancel_id=?",cancel.cancelId()));
    }

    @ParameterizedTest @ValueSource(strings={"before-item","after-batch"})
    void processExitRollsBackUncommittedCaptureOrPreservesCommittedBatch(String window) throws Exception {
        for(long id=1;id<=15;id++)f.seed(id);UUID id=UUID.randomUUID();
        f.history().captureHistory(id,f.sourceId(),LongStream.rangeClosed(1,15).boxed().toList(),"actor","process recovery");
        Path log=Path.of("target/behavior-event-evidence/history-"+window+"-"+id+".log");Files.createDirectories(log.getParent());
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),BehaviorHistoryCrashProbe.class.getName(),window,id.toString());
        process.environment().putAll(Map.of("S15B2_JDBC",f.ds.getUrl(),"S15B2_USER",f.ds.getUsername(),"S15B2_PASSWORD",f.ds.getPassword()));
        Process child=process.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(40,TimeUnit.SECONDS));assertEquals(window.equals("before-item")?91:92,child.exitValue());
            assertTrue(Files.readString(log).contains("S15B2_WINDOW="+window));
        } finally {if(child.isAlive())child.destroyForcibly();}
        var partial=f.history().historyReport(id);int expected=window.equals("before-item")?0:10;
        assertEquals(expected,partial.covered());assertEquals(expected,f.count("behavior_event_outbox"));assertEquals(expected,partial.command().visitSequence());
        f.history().advanceHistory(id);var done=f.history().advanceHistory(id);
        assertEquals("COMPLETE",done.command().status());assertEquals(15,done.covered());assertEquals(15,f.count("behavior_event_outbox"));
        System.out.println("S15B2_PROCESS_RECOVERY window="+window+" committed="+expected+" resumed=15 log="+log);
    }
}
