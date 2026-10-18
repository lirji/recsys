package com.recsys.adserving.catalog.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.adserving.catalog.AdCatalogEventConsumer;
import com.recsys.adserving.catalog.AdServableRepository;
import com.recsys.adserving.catalog.BidwordInvMaintainer;
import com.recsys.adserving.persistence.mapper.addb.*;
import com.recsys.adserving.persistence.mapper.addb.CatalogProjectionMapper.*;
import com.recsys.common.ad.*;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import static com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 独占 PG/Redis 验证实际 SQL、约束、版本、失败窗口和恢复，不靠 Mock 证明原子性。 */
@Testcontainers @Tag("catalog-projection-reliability")
class CatalogProjectionPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final UUID SOURCE = UUID.fromString("e41f4759-b595-4137-9586-d75e38d129da");
    static final String TOPIC = "catalog-projection-test";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static CatalogProjectionStore store;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static CatalogRedisProjection writer;
    static long fence;

    @BeforeAll static void prepare() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/ad-serving-catalog.sql")).execute(ds);
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/adserving/addb/*.xml"));
        store = new CatalogProjectionStore(sessions, JSON); store.install();
        connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        connection.afterPropertiesSet(); connection.start(); redis = new StringRedisTemplate(connection);
        writer = new CatalogRedisProjection(store, redis);
    }
    @BeforeEach void resetExclusiveFixture() {
        jdbc.execute("TRUNCATE catalog_projection_receipt,catalog_projection_inbox,catalog_projection_redis_task,catalog_projection,catalog_projection_position,catalog_projection_control,catalog_projection_audit,ad_servable,ad_embedding RESTART IDENTITY CASCADE");
        // 仅本测试类持有的独占 Redis 容器，产品恢复入口从不 FLUSH。
        try (var c = connection.getConnection()) { c.serverCommands().flushDb(); }
        store.initialize(UUID.randomUUID(), control(), List.of(new Position(0, 0, 0)), "独占空源的已知测试基线", "test-owner", "隔离初始化");
        fence = store.claimConsumer("test-worker").fence();
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }

    @Test void highLowDuplicatePauseDeleteNeverResurrectAndInboxRetainsIdentity() throws Exception {
        byte[] high = bytes(event(101, 3, 10, true, false, "high"));
        accept(0, high); accept(1, bytes(event(101, 1, 10, true, false, "old"))); accept(2, high);
        assertEquals("high", jdbc.queryForObject("SELECT title FROM ad_servable WHERE ad_id=101", String.class));
        assertEquals(3, store.projection(101).aggregateVersion()); assertEquals(1, scalar("SELECT count(*) FROM catalog_projection_redis_task"));
        accept(3, bytes(event(101, 4, 10, false, false, "paused")));
        assertFalse(store.projection(101).deleted()); assertEquals(0, scalar("SELECT count(*) FROM ad_servable"));
        accept(4, bytes(event(101, 5, 10, true, false, "resumed")));
        accept(5, bytes(event(101, 6, 10, false, true, "deleted"))); accept(6, high);
        assertTrue(store.projection(101).deleted()); assertEquals(0, scalar("SELECT count(*) FROM ad_servable"));
        assertEquals(7, store.positions().getFirst().nextOffset());
        assertEquals(5, scalar("SELECT count(*) FROM catalog_projection_inbox"));
        assertEquals(State.QUARANTINED, accept(7, bytes(event(101, 7, 10, true, false, "illegal resurrection"))));
        assertTrue(store.projection(101).deleted());
    }

    @Test void invalidBytesWrongSourceAndSameVersionConflictAreDurableBeforeOffsetMoves() throws Exception {
        byte[] original = bytes(event(101, 1, 10, true, false, "original")); accept(0, original);
        assertEquals(State.QUARANTINED, accept(1, bytes(event(101, 1, 10, true, false, "conflict"))));
        assertEquals(State.QUARANTINED, accept(2, "not-json-private".getBytes()));
        byte[] foreign = new String(bytes(event(101, 2, 10, true, false, "foreign"))).replace(SOURCE.toString(), UUID.randomUUID().toString()).getBytes();
        assertEquals(State.QUARANTINED, accept(3, foreign));
        assertEquals(State.QUARANTINED, accept(4, null));
        assertEquals(4, scalar("SELECT count(*) FROM catalog_projection_receipt WHERE state='QUARANTINED'"));
        assertEquals(CatalogEventCodec.digest(original), jdbc.queryForObject("SELECT digest FROM catalog_projection_inbox", String.class));
        assertEquals("original", jdbc.queryForObject("SELECT title FROM ad_servable", String.class));
        assertEquals(5, store.positions().getFirst().nextOffset());
        assertThrows(Failure.class, () -> accept(0, bytes(event(101, 1, 10, true, false, "changed-coordinate"))));
    }

    @Test void quarantinedOriginalRecheckIsAuditedIdempotentAndDoesNotRewindPosition() {
        byte[] original = "malformed original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(State.QUARANTINED, accept(0, original));
        var disabled = new CatalogProjectionOperations(store, null, false);
        UUID command = UUID.randomUUID();
        assertThrows(Failure.class, () -> disabled.recheck(command, 0, 0, "owner", "协议修复后核查"));
        var enabled = new CatalogProjectionOperations(store, null, true);
        enabled.recheck(command, 0, 0, "owner", "协议修复后核查");
        enabled.recheck(command, 0, 0, "owner", "协议修复后核查");
        assertEquals(1, store.positions().getFirst().nextOffset());
        assertEquals(1, store.quarantinedCount());
        assertEquals(1, scalar("SELECT count(*) FROM catalog_projection_audit WHERE kind='RECHECK_RECORD'"));
        assertArrayEquals(original, jdbc.queryForObject("SELECT payload FROM catalog_projection_receipt", byte[].class));
        assertThrows(Failure.class, () -> enabled.recheck(command, 0, 0, "owner", "changed"));
        assertThrows(Failure.class, () -> enabled.recheck(UUID.randomUUID(), 0, 1, "owner", "missing"));
    }

    @Test void sqlFailureRollsBackEffectsInboxTasksAndPositionButKeepsAttemptBudget() throws Exception {
        jdbc.execute("ALTER TABLE ad_servable ADD CONSTRAINT deny_test_title CHECK(title<>'reject')");
        byte[] input = bytes(event(101, 1, 10, true, false, "reject"));
        try {
            assertThrows(RuntimeException.class, () -> accept(0, input));
            assertEquals(0, scalar("SELECT count(*) FROM catalog_projection"));
            assertEquals(0, scalar("SELECT count(*) FROM catalog_projection_inbox"));
            assertEquals(0, scalar("SELECT count(*) FROM catalog_projection_redis_task"));
            assertEquals(0, store.positions().getFirst().nextOffset());
            assertEquals(1, scalar("SELECT attempts FROM catalog_projection_receipt"));
        } finally { jdbc.execute("ALTER TABLE ad_servable DROP CONSTRAINT deny_test_title"); }
        assertEquals(State.APPLIED, accept(0, input)); assertEquals(2, scalar("SELECT attempts FROM catalog_projection_receipt"));
        assertEquals(State.APPLIED, accept(0, input)); assertEquals(1, scalar("SELECT count(*) FROM catalog_projection_redis_task"));
    }

    @Test void isolationStorageFailureCannotAdvanceOrAcknowledgePoison() {
        jdbc.execute("ALTER TABLE catalog_projection_receipt ADD CONSTRAINT reject_quarantine CHECK(state<>'QUARANTINED')");
        try { assertThrows(RuntimeException.class, () -> accept(0, "bad".getBytes())); assertEquals(0, store.positions().getFirst().nextOffset()); }
        finally { jdbc.execute("ALTER TABLE catalog_projection_receipt DROP CONSTRAINT reject_quarantine"); }
        assertEquals(State.QUARANTINED, accept(0, "bad".getBytes()));
    }

    @Test void unknownEmbeddingPreservesOnlyTheSameItemAndFiltersUnapprovedCreatives() throws Exception {
        var base = event(101, 1, 10, true, false, "vector");
        var coordinates = new ArrayList<Double>(Collections.nCopies(768, 0.0)); coordinates.set(0, 1.0);
        var embedding = new VersionedAdCatalogEvent.Embedding(VersionedAdCatalogEvent.EmbeddingState.PRESENT, 10L, "model", coordinates, Instant.now(), VersionedAdCatalogEvent.EmbeddingReason.NONE);
        var full = new VersionedAdCatalogEvent(base.schemaVersion(), base.eventType(), base.sourceId(), base.ownerShard(), base.topologyVersion(), base.eventId(), base.adId(), base.advertiserId(), base.aggregateVersion(), base.advertiserVersion(), base.commandId(), base.occurredAt(), base.capturedAt(), base.lifecycle(), base.servable(), base.ad(), base.advertiserStatus(), base.bidwords(), base.creatives(), embedding, true, null);
        accept(0, bytes(full)); assertEquals(1, scalar("SELECT count(*) FROM ad_embedding"));
        assertEquals("model", jdbc.queryForObject("SELECT model FROM ad_embedding", String.class));
        assertEquals(1, JSON.readTree(jdbc.queryForObject("SELECT creatives_json FROM ad_servable", String.class)).size());
        accept(1, bytes(event(101, 2, 10, true, false, "unknown same"))); assertEquals(1, scalar("SELECT count(*) FROM ad_embedding"));
        accept(2, bytes(event(101, 3, 10, false, false, "paused"))); assertEquals(0, scalar("SELECT count(*) FROM ad_embedding"));
        assertNotNull(store.projection(101).embeddingPayload());
        accept(3, bytes(event(101, 4, 10, true, false, "resumed unknown"))); assertEquals(1, scalar("SELECT count(*) FROM ad_embedding"));
        accept(4, bytes(event(101, 5, 11, true, false, "unknown different"))); assertEquals(0, scalar("SELECT count(*) FROM ad_embedding"));
    }

    @Test void leaseTakeoverFencesOldExecutorAndRestartDoesNotResetBudget() throws Exception {
        assertThrows(Failure.class, () -> store.claimConsumer("other"));
        jdbc.execute("UPDATE catalog_projection_control SET lease_until=clock_timestamp()-interval '1 second'");
        long newer = store.claimConsumer("other").fence(); assertTrue(newer > fence);
        assertThrows(Failure.class, () -> accept(0, bytes(event(101, 1, 10, true, false, "stale"))));
        store.pause("other", newer, Code.DEPENDENCY_FAILURE);
        assertThrows(Failure.class, () -> store.claimConsumer("restart"));
        UUID resume = UUID.randomUUID(); store.resume(resume, "owner", "依赖恢复"); store.resume(resume, "owner", "依赖恢复");
        assertThrows(Failure.class, () -> store.resume(resume, "owner", "改原因"));
        fence = store.claimConsumer("test-worker").fence(); accept(0, bytes(event(101, 1, 10, true, false, "recovered")));
    }

    @Test void redisWrongTypeRetriesPersistThenResumeKeepsOriginalTaskAndRemovesOldWords() throws Exception {
        accept(0, bytes(event(101, 1, 10, true, false, "first"))); writer.advance();
        assertEquals(1, redis.opsForZSet().zCard("bidword:inv:first"));
        accept(1, bytes(event(101, 2, 10, true, false, "second")));
        redis.opsForValue().set("bidword:inv:second", "wrongtype");
        for (int i = 0; i < 3; i++) {
            jdbc.execute("UPDATE catalog_projection_redis_task SET available_at=clock_timestamp() WHERE state='READY'"); writer.advance();
        }
        assertEquals(State.PAUSED.code(), store.task(2).state()); assertEquals(3, store.task(2).attempts());
        assertEquals(1, redis.opsForZSet().zCard("bidword:inv:first"));
        redis.delete("bidword:inv:second"); UUID retry = UUID.randomUUID();
        assertEquals(2, store.retryTask(retry, 2, "owner", "修复类型")); writer.advance();
        assertEquals(2, store.retryTask(retry, 2, "owner", "修复类型"));
        assertEquals(State.DONE.code(), store.task(2).state()); assertFalse(redis.hasKey("bidword:inv:first"));
        assertEquals(1, redis.opsForZSet().zCard("bidword:inv:second"));
    }

    @Test void redisSuccessWithoutDbConfirmationIsIdempotentAndExpiredFinalAttemptPauses() throws Exception {
        accept(0, bytes(event(101, 1, 10, true, false, "word")));
        var first = store.claimTask(); writer.apply(first);
        jdbc.execute("UPDATE catalog_projection_redis_task SET lease_until=clock_timestamp()-interval '1 second'");
        var second = store.claimTask(); assertEquals(first.taskId(), second.taskId()); writer.apply(second); store.finish(second);
        assertThrows(Failure.class, () -> store.finish(first)); assertEquals(1, redis.opsForZSet().zCard("bidword:inv:word"));
        accept(1, bytes(event(101, 2, 10, false, true, "delete")));
        for (int i = 0; i < 3; i++) { assertNotNull(store.claimTask()); jdbc.execute("UPDATE catalog_projection_redis_task SET lease_until=clock_timestamp()-interval '1 second' WHERE state='RUNNING'"); }
        assertNull(store.claimTask()); assertEquals(State.PAUSED.code(), store.task(2).state());
    }

    @Test void perAdOrderDoesNotBlockOtherAdsAndLateOldScriptCannotReviveDeletion() throws Exception {
        accept(0, bytes(event(101, Long.MAX_VALUE - 1, 10, true, false, "word")));
        accept(1, bytes(event(101, Long.MAX_VALUE, 10, false, true, "deleted")));
        var old = store.claimTask(); assertNotNull(old); assertNull(store.claimTask());
        // 第三个投递属于另一广告，必须采用其真实 key。
        byte[] another = bytes(event(102, 1, 10, true, false, "other"));
        store.accept(new ConsumerRecord<>(TOPIC, 0, 2, CatalogEventCodec.key(102), another), "test-worker", fence);
        var unrelated = store.claimTask(); assertEquals(102, unrelated.adId()); writer.apply(unrelated); store.finish(unrelated);
        writer.apply(old); store.finish(old); var deleted = store.claimTask(); writer.apply(deleted); store.finish(deleted);
        writer.apply(old); assertFalse(redis.hasKey("bidword:inv:word")); assertEquals(1, redis.opsForZSet().zCard("bidword:inv:other"));
    }

    @Test void explicitRedisRestoreIsAuditedAndLegacyWriterCannotRepopulate() throws Exception {
        accept(0, bytes(event(101, 1, 10, true, false, "word"))); writer.advance();
        redis.delete("bidword:inv:word"); UUID command = UUID.randomUUID();
        long id = store.refresh(command, 101, "owner", "已知丢失"); assertEquals(id, store.refresh(command, 101, "owner", "已知丢失"));
        writer.advance(); assertEquals(1, redis.opsForZSet().zCard("bidword:inv:word"));
        var legacy = new AdCatalogEventConsumer(new AdServableRepository(sessions.getMapper(AdServableMapper.class), jdbc.getDataSource()), mock(BidwordInvMaintainer.class));
        assertThrows(Failure.class, () -> legacy.onEvent(JSON.writeValueAsString(AdCatalogEvent.tombstone(101, 1))));
        assertEquals(1, scalar("SELECT count(*) FROM ad_servable"));
        assertThrows(RuntimeException.class, () -> jdbc.update("DELETE FROM ad_servable WHERE ad_id=101"));
    }

    @Test void realRedisOutageKeepsAcceptedPgAndRecoversTheSameTask() throws Exception {
        accept(0, bytes(event(101, 1, 10, true, false, "before"))); writer.advance();
        String container = REDIS.getContainerId();
        REDIS.getDockerClient().pauseContainerCmd(container).exec();
        try {
            accept(1, bytes(event(101, 2, 10, true, false, "after"))); writer.advance();
            assertEquals("after", jdbc.queryForObject("SELECT title FROM ad_servable", String.class));
            assertEquals(2, store.positions().getFirst().nextOffset());
            assertNotEquals(State.DONE.code(), store.task(2).state());
        } finally { REDIS.getDockerClient().unpauseContainerCmd(container).exec(); }
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!State.DONE.code().equals(store.task(2).state()) && System.nanoTime() < deadline) { Thread.sleep(300); writer.advance(); }
        if (State.PAUSED.code().equals(store.task(2).state())) {
            store.retryTask(UUID.randomUUID(), 2, "owner", "Redis恢复后新一轮"); writer.advance();
        }
        assertEquals(State.DONE.code(), store.task(2).state());
        assertFalse(redis.hasKey("bidword:inv:before")); assertEquals(1, redis.opsForZSet().zCard("bidword:inv:after"));
    }

    @Test void migrationIsIdempotentAndAllNewColumnsAndTablesHaveComments() {
        store.install(); store.verifySchema();
        assertEquals(0, scalar("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid WHERE c.relname LIKE 'catalog_projection%' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL"));
        assertEquals(0, scalar("SELECT count(*) FROM pg_class c WHERE c.relname LIKE 'catalog_projection%' AND c.relkind='r' AND obj_description(c.oid,'pg_class') IS NULL"));
        jdbc.execute("UPDATE catalog_projection_schema SET script_sha256='wrong'");
        try { assertThrows(Failure.class, store::verifySchema); }
        finally { jdbc.update("UPDATE catalog_projection_schema SET script_sha256=?", shaMigration()); }
    }

    static String shaMigration() { try (var in = new ClassPathResource(CatalogProjectionStore.MIGRATION).getInputStream()) { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())); } catch (Exception e) { throw new IllegalStateException(e); } }
    static Control control() { return new Control(SOURCE.toString(), "cluster", "topic-id", TOPIC, "group", "READY", null, 0, null); }
    static int scalar(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    static State accept(long offset, byte[] input) { return store.accept(new ConsumerRecord<>(TOPIC, 0, offset, CatalogEventCodec.key(101), input), "test-worker", fence); }
    static byte[] bytes(VersionedAdCatalogEvent event) throws Exception { return JSON.writeValueAsBytes(event); }
    static VersionedAdCatalogEvent event(long ad, long version, long item, boolean servable, boolean deleted, String title) {
        Instant at = Instant.parse("2026-10-18T00:00:00Z");
        return new VersionedAdCatalogEvent(1, deleted ? VersionedAdCatalogEvent.CatalogEventType.AD_DELETED : VersionedAdCatalogEvent.CatalogEventType.AD_SNAPSHOT,
                SOURCE, 1, VersionedAdCatalogEvent.TOPOLOGY, CatalogEventCodec.eventId(SOURCE, ad, version), ad, 3, version, 1,
                UUID.fromString("e84cb29d-4e89-43e3-9ee0-7d5c7797ce39"), at, at,
                deleted ? VersionedAdCatalogEvent.Lifecycle.DELETED : VersionedAdCatalogEvent.Lifecycle.PRESENT, servable,
                deleted ? null : new VersionedAdCatalogEvent.Ad(item, title, "https://example.test", 1, servable ? "active" : "paused", "approved", null, "CPC", null, null),
                deleted ? null : "active", deleted ? List.of() : List.of(new VersionedAdCatalogEvent.Bidword(1, title, "EXACT", 2.5, "CPC")),
                deleted ? List.of() : List.of(new VersionedAdCatalogEvent.Creative(1, "approved", "https://example.test", "active", "approved"), new VersionedAdCatalogEvent.Creative(2, "pending", null, "active", "pending_review")),
                deleted ? new VersionedAdCatalogEvent.Embedding(VersionedAdCatalogEvent.EmbeddingState.NOT_APPLICABLE, null, null, null, null, VersionedAdCatalogEvent.EmbeddingReason.DELETED)
                        : new VersionedAdCatalogEvent.Embedding(VersionedAdCatalogEvent.EmbeddingState.UNKNOWN, item, null, null, at, VersionedAdCatalogEvent.EmbeddingReason.MISSING),
                true, deleted ? "deleted" : null);
    }
}
