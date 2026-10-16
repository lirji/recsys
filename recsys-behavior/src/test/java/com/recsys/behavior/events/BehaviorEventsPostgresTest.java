package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorEventRepository.*;
import com.recsys.common.behavior.*;
import com.recsys.platform.behavior.LegacyBehaviorCodec;
import com.recsys.common.constant.ActionType;
import com.recsys.common.dto.BehaviorEvent;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验收原子提交、并发赢家、快照隔离和租约恢复，不读共享数据。 */
@Tag("behavior-reliability") @Testcontainers
class BehaviorEventsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    BehaviorEventsFixture f;
    @BeforeEach void setup() throws Exception { f=new BehaviorEventsFixture(PG,true); }

    @Test void sourceIdentitySchemaChecksMigrationRollbackAndComments() throws Exception {
        f.repo.verifySchema(); String a=f.accept();
        var restart=new BehaviorEventsFixture(f.ds); restart.repo.verifySchema();
        assertEquals(a.split(":")[0],restart.accept().split(":")[0]);
        var other=new BehaviorEventsFixture(PG,true); assertNotEquals(a,other.accept());
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE behavior_event_source SET source_id=?",UUID.randomUUID()));
        assertThrows(Exception.class,()->f.jdbc.update("DELETE FROM behavior_event_source"));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE behavior_event_outbox SET source_snapshot_json='{}'"));
        f.jdbc.execute("DROP INDEX uq_behavior_click_exposure");
        f.jdbc.execute("CREATE INDEX uq_behavior_click_exposure ON user_behavior(exposure_id)");
        assertThrows(Exception.class,()->f.repo.verifySchema());
        var missing=new BehaviorEventsFixture(PG,false); missing.jdbc.execute("ALTER TABLE user_behavior DROP COLUMN position");
        assertThrows(Exception.class,()->missing.migrate(null,BehaviorEventRepository.migrationSha256()));
        assertNull(missing.jdbc.queryForObject("SELECT to_regclass('behavior_event_source')",String.class));
        var failed=new BehaviorEventsFixture(PG,false);
        String sql=new String(new ClassPathResource(BehaviorEventRepository.MIGRATION).getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThrows(Exception.class,()->failed.migrate(sql.replace("COMMIT;","SELECT 1/0; COMMIT;"),BehaviorEventRepository.migrationSha256()));
        assertNull(failed.jdbc.queryForObject("SELECT to_regclass('behavior_event_outbox')",String.class));
        failed.migrate(null,"0".repeat(64)); assertThrows(IllegalStateException.class,()->failed.repo.verifySchema());
        assertEquals(0,other.jdbc.queryForObject("""
            SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace=n.oid
            JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
            WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname LIKE 'behavior_event_%'
              AND (obj_description(c.oid,'pg_class') IS NULL OR col_description(c.oid,a.attnum) IS NULL)
            """,Integer.class));
        // V001不阻断旧writer的普通插入和删除。
        other.jdbc.update("INSERT INTO user_behavior(user_id,item_id,action) VALUES(7,42,'PLAY')");
        other.jdbc.update("DELETE FROM user_behavior WHERE action='PLAY'");
    }

    @Test void serializationOrOutboxFailureRollsBackSourceAndWrongDatasourceIsRejected() {
        var broken=new LegacyBehaviorCodec() {
            @Override public String encode(LegacyBehaviorAccepted event) { throw new IllegalStateException("injected serializer fault"); }
        };
        var repo=new BehaviorEventRepository(f.sessions,new DataSourceTransactionManager(f.ds),broken);
        assertThrows(IllegalStateException.class,()->repo.accept(f.event(ActionType.RATING,null)));
        assertEquals(0,f.count("user_behavior")); assertEquals(0,f.count("behavior_event_outbox"));
        f.jdbc.execute("CREATE FUNCTION test_reject_outbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$; CREATE TRIGGER test_reject BEFORE INSERT ON behavior_event_outbox FOR EACH ROW EXECUTE FUNCTION test_reject_outbox()");
        assertThrows(Exception.class,()->f.repo.accept(f.event(ActionType.RATING,null)));
        assertEquals(0,f.count("user_behavior")); assertEquals(0,f.count("behavior_event_outbox"));
        assertThrows(IllegalArgumentException.class,()->new BehaviorEventRepository(f.sessions,new DataSourceTransactionManager(new DriverManagerDataSource())));
    }

    @Test void twentyConcurrentVerifiedClicksOneMetricAndLikesRemainDistinct() throws Exception {
        f.impression("concurrent"); var service=f.service(); var ready=new CountDownLatch(20); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(20)) {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<20;i++) futures.add(pool.submit(()->{ready.countDown();start.await();service.record(f.event(ActionType.CLICK,"concurrent"));return null;}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();for(var future:futures)future.get(15,TimeUnit.SECONDS);
        }
        assertEquals(2,f.count("user_behavior"));assertEquals(1,f.count("behavior_event_outbox"));
        assertEquals(1,f.metrics.get("recsys.click").counter().count());
        service.record(f.event(ActionType.LIKE,"concurrent"));service.record(f.event(ActionType.LIKE,"concurrent"));
        assertEquals(4,f.count("user_behavior"));assertEquals(3,f.count("behavior_event_outbox"));
    }

    @Test void concurrentInsertSnapshotCannotSeeWinnerButNextStatementRepairsIt() throws Exception {
        try(var c=f.ds.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            c.setAutoCommit(false);
            c.createStatement().execute("INSERT INTO user_behavior(user_id,item_id,action,value,exposure_id) VALUES(7,42,'CLICK',1,'winner')");
            Future<Acceptance> waiting=pool.submit(()->f.repo.accept(f.event(ActionType.CLICK,"winner")));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(f.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event='transactionid' AND datname=current_database()",Integer.class)==0) {
                assertTrue(System.nanoTime()<deadline);Thread.sleep(20);
            }
            c.commit(); assertEquals(Acceptance.DUPLICATE,waiting.get(6,TimeUnit.SECONDS));
        }
        assertEquals(1,f.count("user_behavior"));assertEquals(1,f.count("behavior_event_outbox"));
        assertEquals("LEGACY_ROW",f.jdbc.queryForObject("SELECT origin FROM behavior_event_outbox",String.class));
    }

    @Test void duplicateRepairsOriginalHistoryButNeverReopensPublishedDeadOrOtherOwner() {
        f.impression("legacy");
        f.jdbc.update("INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,ts,exposure_id,position) VALUES(7,42,'CLICK',2,'old','original','2001-02-03 04:05:06.123456','legacy',9)");
        f.service().record(new BehaviorEvent(7,42,ActionType.CLICK,99,"new","forged",1,"legacy"));
        String id=f.lastId(),payload=f.payload(id);var e=f.codec.decode("7",payload);
        assertEquals(LegacyBehaviorAccepted.Origin.LEGACY_ROW,e.origin());assertEquals("original",e.behavior().bucket());
        assertEquals(2,e.behavior().value());assertNull(e.behavior().ts());assertEquals("2001-02-03T04:05:06.123456",e.sourceTsLocal());
        var pending=f.repo.claim(Destination.HISTORY).orElseThrow();assertTrue(f.repo.published(pending));
        f.service().record(f.event(ActionType.CLICK,"legacy"));assertEquals("PUBLISHED",f.state(id));assertEquals(payload,f.payload(id));
        assertEquals(Acceptance.COLLISION,f.repo.accept(new BehaviorEvent(8,42,ActionType.CLICK,1,"h",null,2,"legacy")));
        assertEquals(1,f.count("behavior_event_outbox"));
        f.impression("dead");f.service().record(f.event(ActionType.CLICK,"dead"));String live=f.lastId();f.dead(live);
        f.service().record(f.event(ActionType.CLICK,"dead"));assertEquals("DEAD",f.state(live));assertEquals(2,f.count("behavior_event_outbox"));
    }

    @Test void largeIdsNullFieldsAndGoldenSnapshotArePreservedAndInvalidDataIsQuarantined() {
        f.repo.accept(new BehaviorEvent(Long.MAX_VALUE,Long.MAX_VALUE-1,ActionType.RATING,4.5,null,null,1700000012345L));
        var e=f.codec.decode(Long.toString(Long.MAX_VALUE),f.payload(f.lastId()));assertEquals(Long.toString(Long.MAX_VALUE-1),e.behavior().itemId());assertNull(e.behavior().scene());assertEquals(1700000012345L,e.behavior().ts());
        f.jdbc.update("INSERT INTO user_behavior(id,user_id,item_id,action,value,scene,bucket,ts,position,exposure_id) VALUES(900,7,42,'CLICK',2.5,NULL,'b','2001-02-03 04:05:06.123456',3,'gold')");
        f.repo.accept(f.event(ActionType.CLICK,"gold"));
        String snapshot=f.jdbc.queryForObject("SELECT source_snapshot_json FROM behavior_event_outbox WHERE behavior_id=900",String.class);
        assertEquals("{\"id\": 900, \"ts\": \"2001-02-03T04:05:06.123456\", \"scene\": null, \"value\": 2.5, \"action\": \"CLICK\", \"bucket\": \"b\", \"item_id\": 42, \"user_id\": 7, \"position\": 3, \"exposure_id\": \"gold\"}",snapshot);
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            f.repo.accept(new BehaviorEvent(7,42,ActionType.RATING,value,null,null,2));
            assertEquals("DEAD",f.state(f.lastId()));assertNull(f.payload(f.lastId()));
            assertThrows(IllegalStateException.class,()->f.repo.replay(UUID.randomUUID(),f.lastId(),"test","cannot fix bytes"));
        }
        f.repo.accept(new BehaviorEvent(7,42,ActionType.RATING,1,"敏".repeat(30000),null,2));
        String big=f.lastId();assertEquals("DEAD",f.state(big));
        assertTrue(f.jdbc.queryForObject("SELECT octet_length(source_snapshot_json)>65536 FROM behavior_event_outbox WHERE event_id=?",Boolean.class,big));
        f.repo.accept(new BehaviorEvent(7,42,null,1,null,null,2));assertEquals("DEAD",f.state(f.lastId()));
        assertEquals(7,f.count("behavior_event_outbox"));
        assertEquals(5,f.jdbc.queryForObject("SELECT count(*) FROM behavior_event_outbox WHERE last_error='INVALID_SOURCE' AND attempts=0",Integer.class));
    }

    @Test void requiresNewPreservesPerItemBatchAndSourceDeletionDoesNotDeleteSnapshot() {
        var outer=new TransactionTemplate(new DataSourceTransactionManager(f.ds));
        outer.execute(s->{f.accept();s.setRollbackOnly();return null;});
        String id=f.lastId(),payload=f.payload(id);assertEquals(1,f.count("user_behavior"));
        f.jdbc.update("DELETE FROM user_behavior");assertEquals(payload,f.payload(id));
        assertThrows(Exception.class,()->f.jdbc.update("DELETE FROM behavior_event_outbox"));
    }

    @Test void httpAndBatchKeepResponsesPartialCommitsAndSanitizedDatabaseErrors() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.recsys.behavior.BehaviorController(f.service()))
                .setControllerAdvice(new com.recsys.platform.web.GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper())).build();
        String body="{\"userId\":7,\"itemId\":42,\"action\":\"RATING\",\"value\":4.5,\"ts\":1700000012345}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/behavior")
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ok").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/behavior/batch")
                .contentType("application/json").content("["+body+","+body+"]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.count").value(2));
        assertEquals(3,f.count("user_behavior"));assertEquals(3,f.count("behavior_event_outbox"));
        f.jdbc.execute("CREATE FUNCTION test_reject_second() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.behavior_id=5 THEN RAISE EXCEPTION 'private fixture token'; END IF; RETURN NEW; END $$; CREATE TRIGGER test_reject BEFORE INSERT ON behavior_event_outbox FOR EACH ROW EXECUTE FUNCTION test_reject_second()");
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/behavior/batch")
                .contentType("application/json").content("["+body+","+body+"]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("private fixture token"));assertFalse(response.contains("INSERT"));
        assertEquals(4,f.count("user_behavior"));assertEquals(4,f.count("behavior_event_outbox"));
    }

    @Test void twoWorkersClaimOnceAndExpiredFenceCannotMark() throws Exception {
        String id=f.accept();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->f.repo.claim(Destination.LIVE));var b=pool.submit(()->f.repo.claim(Destination.LIVE));
            var first=a.get();var second=b.get();assertEquals(1,(first.isPresent()?1:0)+(second.isPresent()?1:0));
            var old=first.orElseGet(second::orElseThrow);f.due(id);
            var newer=f.repo.claim(Destination.LIVE).orElseThrow();assertEquals(old.fence()+1,newer.fence());
            assertFalse(f.repo.published(old));assertFalse(f.repo.failed(old,1000,BehaviorEventRepository.Error.SEND_UNKNOWN));assertTrue(f.repo.published(newer));
        }
    }

    @Test void shutdownInterruptLeavesPendingLeaseAndNoFalseAcknowledgment() throws Exception {
        String id=f.accept();
        var publisher=org.mockito.Mockito.mock(BehaviorKafkaPublisher.class);
        org.mockito.Mockito.doThrow(new InterruptedException("isolated shutdown")).when(publisher).publish(org.mockito.ArgumentMatchers.any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            assertTrue(pool.submit(()->{
                boolean relayed=new BehaviorEventRelay(f.repo,publisher,f.codec,f.metrics,Destination.LIVE).relayOne();
                return relayed && Thread.currentThread().isInterrupted();
            }).get(5,TimeUnit.SECONDS));
        }
        assertEquals("SENDING",f.state(id));
        assertNull(f.jdbc.queryForObject("SELECT published_at FROM behavior_event_outbox WHERE event_id=?",java.sql.Timestamp.class,id));
        assertNull(f.jdbc.queryForObject("SELECT last_error FROM behavior_event_outbox WHERE event_id=?",String.class,id));
    }

    @Test void twentiethClaimCrashExhaustsAndReplayIsAuditedAtomicAndIdempotent() throws Exception {
        String id=f.accept(),payload=f.payload(id);
        for(int i=1;i<=20;i++) {
            var p=f.repo.claim(Destination.LIVE).orElseThrow();assertEquals(i,p.attempts());
            if(i<20) assertTrue(f.repo.failed(p,1000,BehaviorEventRepository.Error.SEND_UNKNOWN));
            f.due(id);
        }
        assertEquals(1,f.repo.exhaust(Destination.LIVE));assertEquals("DEAD",f.state(id));
        UUID command=UUID.randomUUID();var replay=f.repo.replay(command,id,"tester","broker recovered");
        assertEquals(20,replay.oldAttempts());assertEquals(21,replay.newFence());assertEquals(payload,f.payload(id));
        f.dead(id);assertEquals(replay,f.repo.replay(command,id,"tester","broker recovered"));assertEquals("DEAD",f.state(id));
        assertThrows(IllegalArgumentException.class,()->f.repo.replay(command,id,"tester","changed"));
        assertThrows(Exception.class,()->f.jdbc.update("UPDATE behavior_event_replay SET reason='changed'"));
        f.jdbc.execute("CREATE FUNCTION test_reject_reopen() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='READY' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER test_reject BEFORE UPDATE ON behavior_event_outbox FOR EACH ROW EXECUTE FUNCTION test_reject_reopen()");
        assertThrows(Exception.class,()->f.repo.replay(UUID.randomUUID(),id,"tester","failed recovery"));
        assertEquals(1,f.count("behavior_event_replay"));assertEquals("DEAD",f.state(id));
    }
}
