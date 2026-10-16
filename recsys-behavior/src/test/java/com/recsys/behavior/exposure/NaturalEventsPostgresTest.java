package com.recsys.behavior.exposure;

import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper;
import com.recsys.common.exposure.NaturalReceipt;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL 验证迁移、并发领取、fence、审计命令与最后一次崩溃恢复。 */
@Tag("natural-exposure")
@Testcontainers
class NaturalEventsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void upgradesExistingRowsAndOriginalWriterRemainsCompatibleAndImmutable() throws Exception {
        var f = new NaturalEventsFixture(PG, false);
        var old = f.accept(); String payload = f.payload(old.result().eventId());
        assertThrows(RuntimeException.class, f.outbox::verifySchema);
        f.upgrade(); f.receipts.verifySchema(); f.outbox.verifySchema();
        f.accept(); assertEquals(2, f.count("natural_impression_fact")); assertEquals(2, f.count("natural_impression_outbox"));
        var row = f.outbox.claim().orElseThrow(); assertEquals(old.result().eventId(), row.eventId()); assertEquals(payload, row.payload());
        assertThrows(RuntimeException.class, () -> f.jdbc.update("UPDATE natural_impression_outbox SET payload_json='{}' WHERE event_id=?", row.eventId()));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("DELETE FROM natural_impression_outbox WHERE event_id=?", row.eventId()));
        assertTrue(f.outbox.published(row)); assertFalse(f.outbox.published(row));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("UPDATE natural_impression_outbox SET status='READY',published_at=NULL WHERE event_id=?", row.eventId()));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("UPDATE natural_impression_fact SET accepted_at=clock_timestamp() WHERE qualification_id=?", row.eventId()));
        assertEquals(old.result(), f.service(f.receipts).accept(old.command()));
    }

    @Test void failedMigrationRollsBackAllChangesAndSchemaDriftRefusesRelay() throws Exception {
        var f = new NaturalEventsFixture(PG, false); f.accept();
        String script;
        try (var in = new ClassPathResource(NaturalOutboxRepository.MIGRATION).getInputStream()) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String interrupted = script.replace("COMMIT;", "SELECT 1/0; COMMIT;");
        assertThrows(Exception.class, () -> f.migrate(NaturalOutboxRepository.MIGRATION, NaturalOutboxRepository.migrationSha256(), interrupted));
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM natural_exposure_schema_history WHERE version=2", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='natural_impression_outbox' AND column_name='status'", Integer.class));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("UPDATE natural_impression_outbox SET published_at=clock_timestamp()"));
        f.upgrade(); f.outbox.verifySchema();
        f.jdbc.update("UPDATE natural_exposure_schema_history SET script_sha256=? WHERE version=2", "b".repeat(64));
        assertThrows(IllegalStateException.class, f.outbox::verifySchema);
    }

    @Test void anomalousV1PublicationMarkerIsRejectedWithoutCoercingItToSuccess() throws Exception {
        var f = new NaturalEventsFixture(PG, false);
        var writer = new NaturalReceiptRepository(f.receiptMapper, f.ds, NaturalEventsFixture.JSON) {
            @Override public void appendOutbox(NaturalReceipt.Event event) {
                try {
                    f.jdbc.update("INSERT INTO natural_impression_outbox(event_id,event_type,schema_version,destination,partition_key,payload_json,published_at) VALUES(?,?,?,?,?,?,clock_timestamp())",
                            UUID.fromString(event.eventId()), event.eventType(), event.schemaVersion(), NaturalReceipt.Event.TOPIC,
                            event.userId(), NaturalEventsFixture.JSON.writeValueAsString(event));
                } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            }
        };
        f.accept(writer);
        assertThrows(Exception.class, f::upgrade);
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM natural_exposure_schema_history WHERE version=2", Integer.class));
        assertEquals(1, f.count("natural_impression_fact"));
    }

    @Test void concurrentClaimsAndStaleWritersCannotOverrideCurrentFence() throws Exception {
        var f = new NaturalEventsFixture(PG, true); var id = f.accept().result().eventId();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Optional<NaturalOutboxMapper.Pending>> claim = () -> { barrier.await(); return f.outbox.claim(); };
            var a = threads.submit(claim); var b = threads.submit(claim);
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Optional::isPresent).count());
            var old = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertTrue(f.outbox.failed(old, 1000, NaturalOutboxRepository.Error.SEND_UNKNOWN)); f.awaitDue(id);
            var current = f.outbox.claim().orElseThrow(); assertEquals(old.fence() + 1, current.fence());
            assertFalse(f.outbox.published(old)); assertFalse(f.outbox.failed(old, 1000, NaturalOutboxRepository.Error.SEND_UNKNOWN));
            assertTrue(f.outbox.published(current)); assertEquals("PUBLISHED", f.state(id));
        }
    }

    @Test void retriesAndFinalClaimCrashAreExhaustedInBatchesOfTen() throws Exception {
        var f = new NaturalEventsFixture(PG, true); var ids = new ArrayList<UUID>();
        for (int i = 0; i < 12; i++) ids.add(f.accept().result().eventId());
        var last = new ArrayList<NaturalOutboxMapper.Pending>();
        for (int n = 1; n <= 20; n++) {
            last.clear();
            for (int i = 0; i < 12; i++) {
                var row = f.outbox.claim().orElseThrow(); assertEquals(n, row.attempts()); last.add(row);
            }
            if (n < 20) {
                for (var row : last) assertTrue(f.outbox.failed(row, 1000, NaturalOutboxRepository.Error.SEND_UNKNOWN));
                f.awaitDue(last.getLast().eventId());
            }
        }
        assertEquals(0, f.outbox.exhaust());
        // 不改数据库时间或租约，不关闭触发器；等待最后一次真实三十秒租约自然到期。
        f.awaitDue(last.getLast().eventId());
        assertFalse(f.outbox.published(last.getFirst())); assertTrue(f.outbox.claim().isEmpty());
        assertEquals(10, f.outbox.exhaust()); assertEquals(2, f.outbox.exhaust()); assertEquals(0, f.outbox.exhaust());
        assertEquals(12, f.outbox.backlog().dead());
        assertEquals(12, f.jdbc.queryForObject("SELECT count(*) FROM natural_impression_outbox WHERE attempts=20 AND last_error='ATTEMPTS_EXHAUSTED'", Integer.class));
    }

    @Test void replayIsAuditedIdempotentConcurrentAndDoesNotReopenAgain() throws Exception {
        var f = new NaturalEventsFixture(PG, true); UUID id = f.accept().result().eventId(), command = UUID.randomUUID();
        String payload = f.payload(id); f.dead(id);
        NaturalOutboxMapper.Replay first;
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a = threads.submit(() -> f.outbox.replay(command, id, "operator", "isolated recovery"));
            var b = threads.submit(() -> f.outbox.replay(command, id, "operator", "isolated recovery"));
            first = a.get(10, TimeUnit.SECONDS); assertEquals(first, b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, f.count("natural_impression_outbox_replay")); assertEquals("READY", f.state(id));
        f.dead(id);
        assertEquals(first, f.outbox.replay(command, id, "operator", "isolated recovery")); assertEquals("DEAD", f.state(id));
        assertThrows(IllegalArgumentException.class, () -> f.outbox.replay(command, id, "other", "isolated recovery"));
        assertThrows(IllegalArgumentException.class, () -> f.outbox.replay(command, UUID.randomUUID(), "operator", "isolated recovery"));
        assertThrows(IllegalArgumentException.class, () -> f.outbox.replay(command, id, "operator", "changed"));
        var second = f.outbox.replay(UUID.randomUUID(), id, "operator", "new explicit recovery");
        assertTrue(second.newFence() > first.newFence()); assertEquals(payload, f.payload(id));
        assertThrows(RuntimeException.class, () -> f.jdbc.update("UPDATE natural_impression_outbox_replay SET reason='changed'"));
    }

    @Test void auditOrStateWriteFailureRollsBackWholeReplay() throws Exception {
        var f = new NaturalEventsFixture(PG, true); UUID id = f.accept().result().eventId(); f.dead(id);
        f.jdbc.execute("CREATE FUNCTION test_reject_reopen() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated reopen failure'; END $$");
        f.jdbc.execute("CREATE TRIGGER test_reject_reopen BEFORE UPDATE ON natural_impression_outbox FOR EACH ROW WHEN (OLD.status='DEAD') EXECUTE FUNCTION test_reject_reopen()");
        assertThrows(RuntimeException.class, () -> f.outbox.replay(UUID.randomUUID(), id, "operator", "test"));
        assertEquals(0, f.count("natural_impression_outbox_replay")); assertEquals("DEAD", f.state(id));
        f.jdbc.execute("DROP TRIGGER test_reject_reopen ON natural_impression_outbox");
        f.jdbc.execute("ALTER TABLE natural_impression_outbox_replay ADD CONSTRAINT test_reject_audit CHECK (actor <> 'rejected')");
        assertThrows(RuntimeException.class, () -> f.outbox.replay(UUID.randomUUID(), id, "rejected", "test"));
        assertEquals(0, f.count("natural_impression_outbox_replay")); assertEquals("DEAD", f.state(id));
    }

    @Test void rejectsMismatchedDataSourceAndNetworkInsideOuterTransaction() throws Exception {
        var f = new NaturalEventsFixture(PG, true);
        var other = new DriverManagerDataSource(f.ds.getUrl(), f.ds.getUsername(), f.ds.getPassword());
        assertThrows(IllegalArgumentException.class, () -> new NaturalOutboxRepository(f.sessions, new DataSourceTransactionManager(other)));
        var publisher = mock(NaturalKafkaPublisher.class);
        var relay = new NaturalOutboxRelay(f.outbox, publisher, f.codec, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        new TransactionTemplate(new DataSourceTransactionManager(f.ds)).execute(status -> {
            assertThrows(IllegalStateException.class, relay::relayOne); return null;
        });
        verifyNoInteractions(publisher);
    }

    @Test void missingMigrationRefusesEnabledContextButReceiptOnlyStillWorks() throws Exception {
        var f = new NaturalEventsFixture(PG, false);
        var runner = new ApplicationContextRunner().withUserConfiguration(NaturalEventConfiguration.class)
                .withBean("dataSource", javax.sql.DataSource.class, () -> f.ds)
                .withBean("sqlSessionTemplate", org.mybatis.spring.SqlSessionTemplate.class, () -> f.sessions)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, () -> NaturalEventsFixture.JSON)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new);
        runner.withPropertyValues("recsys.natural.events.relay-enabled=true").run(ctx -> assertNotNull(ctx.getStartupFailure()));
        runner.withUserConfiguration(NaturalReceiptConfiguration.class).withPropertyValues(
                "recsys.natural.receipts-enabled=true", "recsys.natural.keys.test=" + NaturalEventsFixture.KEY,
                "recsys.natural.policy.version=natural-text-v1", "recsys.natural.policy.min-ratio-bps=5000",
                "recsys.natural.policy.min-duration-ms=1000", "recsys.natural.policy.ttl-ms=300000")
                .withBean(com.recsys.behavior.persistence.mapper.primary.NaturalReceiptMapper.class, () -> f.receiptMapper)
                .run(ctx -> { assertNull(ctx.getStartupFailure()); assertTrue(ctx.containsBean("naturalReceiptService")); assertFalse(ctx.containsBean("naturalKafkaPublisher")); });
    }

    @Test void invalidStoredEventIsDeadWithoutSendAndInterruptedSenderLeavesRecoverableLease() throws Exception {
        var f = new NaturalEventsFixture(PG, true);
        var writer = new NaturalReceiptRepository(f.receiptMapper, f.ds, NaturalEventsFixture.JSON) {
            @Override public void appendOutbox(NaturalReceipt.Event event) {
                f.receiptMapper.appendOutbox(new com.recsys.behavior.persistence.mapper.primary.NaturalReceiptMapper.OutboxWrite(
                        UUID.fromString(event.eventId()), event.eventType(), 1, NaturalReceipt.Event.TOPIC, event.userId(), "{}"));
            }
        };
        UUID bad = f.accept(writer).result().eventId(); var publisher = mock(NaturalKafkaPublisher.class);
        var metrics = new SimpleMeterRegistry(); var relay = new NaturalOutboxRelay(f.outbox, publisher, f.codec, metrics);
        assertTrue(relay.relayOne()); assertEquals("DEAD", f.state(bad)); verifyNoInteractions(publisher);
        assertEquals("INVALID_EVENT", f.jdbc.queryForObject("SELECT last_error FROM natural_impression_outbox WHERE event_id=?", String.class, bad));
        UUID good = f.accept().result().eventId();
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("SENDING", f.state(good)); throw new InterruptedException("isolated stop");
        }).when(publisher).publish(any());
        try {
            assertTrue(relay.relayOne()); assertTrue(Thread.currentThread().isInterrupted()); assertEquals("SENDING", f.state(good));
            assertNull(f.jdbc.queryForObject("SELECT published_at FROM natural_impression_outbox WHERE event_id=?", java.sql.Timestamp.class, good));
        } finally { Thread.interrupted(); }
    }

    @Test void sameReplayIdWithDifferentEventsCannotReopenBoth() throws Exception {
        var f = new NaturalEventsFixture(PG, true);
        UUID a = f.accept().result().eventId(), b = f.accept().result().eventId(), replay = UUID.randomUUID();
        f.dead(a); f.dead(b);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var results = new ArrayList<Future<Boolean>>();
            for (UUID id : List.of(a, b)) results.add(threads.submit(() -> {
                barrier.await();
                try { f.outbox.replay(replay, id, "operator", "test"); return true; }
                catch (IllegalArgumentException conflict) { return false; }
            }));
            assertNotEquals(results.getFirst().get(10, TimeUnit.SECONDS), results.getLast().get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, f.count("natural_impression_outbox_replay"));
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM natural_impression_outbox WHERE status='READY'", Integer.class));
        assertEquals(1, f.outbox.backlog().dead());
    }
}
