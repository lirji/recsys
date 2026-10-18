package com.recsys.streaming.reliable;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.common.constant.ActionType;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.recsys.streaming.reliable.FeatureFixture.*;

/** 真正Flink MiniCluster关闭后从持久checkpoint启动新集群，连接真实Kafka/Redis/PG。 */
@Tag("streaming-reliability") @Testcontainers
class FeatureRecoveryTest {
    @Container static final Kafka KAFKA = new Kafka();
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @TempDir Path directory;
    String topic, namespace, projection;
    JobClient job;
    Jedis redis;
    KafkaProducer<byte[], byte[]> producer;
    @BeforeEach void prepare() throws Exception {
        topic = LegacyBehaviorAccepted.LIVE_TOPIC + ".test-" + UUID.randomUUID(); namespace = "feature-test:" + UUID.randomUUID(); projection = UUID.randomUUID().toString();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        try (var connection = PG.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS item (item_id BIGINT PRIMARY KEY, category TEXT);
                COMMENT ON TABLE item IS '实时窗口隔离测试目录';
                COMMENT ON COLUMN item.item_id IS '物品ID';
                COMMENT ON COLUMN item.category IS '启动时冻结的类目';
                TRUNCATE item;
                INSERT INTO item VALUES (1,'cat'),(2,'other');
                """);
        }
        redis = new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379));
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", KAFKA.bootstrap(), "acks", "all"), new ByteArraySerializer(), new ByteArraySerializer());
    }
    @AfterEach void close() throws Exception {
        if (job != null) job.cancel().get(20, TimeUnit.SECONDS);
        producer.close(Duration.ofSeconds(5)); redis.close();
    }
    FeatureSettings settings(String restore) {
        return new FeatureSettings(KAFKA.bootstrap(), topic, "group-" + projection, projection, namespace, SOURCE, true,
                directory.toUri().toString(), restore, REDIS.getHost(), REDIS.getMappedPort(6379), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(),
                4000, 1000, 100, 60_000, 500, 100, "isolated-source:" + topic);
    }
    void start(String restore) throws Exception {
        start(settings(restore), false);
    }
    void start(FeatureSettings settings, boolean noRestart) throws Exception {
        Configuration config = new Configuration(); config.setInteger("rest.port", 0); config.setString("rest.bind-port", "0");
        config.setString("taskmanager.memory.network.min", "16mb"); config.setString("taskmanager.memory.network.max", "16mb");
        var env = StreamExecutionEnvironment.createLocalEnvironment(1, config);
        ReliableFeatureJob.build(env, settings);
        if (noRestart) env.setRestartStrategy(org.apache.flink.api.common.restartstrategy.RestartStrategies.noRestart());
        job = env.executeAsync("feature-recovery-test");
    }
    void send(long id, long item, long ts, ActionType action) throws Exception {
        producer.send(new ProducerRecord<>(topic, "1".getBytes(java.nio.charset.StandardCharsets.UTF_8), event(id, item, ts, action))).get(10, TimeUnit.SECONDS);
    }
    long windowEnd() { String value = redis.hget(namespace + ":control", "end"); return value == null ? 0 : Long.parseLong(value); }
    String checkpointAfter(long millis) throws Exception {
        final String[] checkpoint = {null};
        await(() -> {
            try (var paths = Files.walk(directory)) {
                checkpoint[0] = paths.filter(path -> path.getFileName().toString().equals("_metadata"))
                        .filter(path -> { try { return Files.getLastModifiedTime(path).toMillis() > millis; } catch (Exception failure) { return false; } })
                        .max(Comparator.comparing(Path::toString)).map(path -> path.getParent().toUri().toString()).orElse(null);
                return checkpoint[0] != null;
            } catch (Exception failure) { return false; }
        }, 30);
        return checkpoint[0];
    }
    @Test void restoredOffsetsDedupFrozenCategoriesAndRedisAheadOfCheckpointReconcile() throws Exception {
        long base = System.currentTimeMillis() / 1000 * 1000 - 4000;
        send(1, 1, base + 100, ActionType.CLICK); send(2, 2, base + 1200, ActionType.LIKE);
        send(3, 1, base + 2200, ActionType.IMPRESSION); start(null);
        await(() -> windowEnd() >= base + 2000, 45);
        assertEquals(1d, redis.zscore(namespace + ":hot", "1")); assertEquals(2d, redis.zscore(namespace + ":hot", "2"));
        String checkpoint = checkpointAfter(System.currentTimeMillis());
        // checkpoint之后已完成Redis副作用：恢复旧窗口不得覆盖新窗口。
        send(4, 1, base + 2300, ActionType.CLICK); send(5, 1, base + 3200, ActionType.IMPRESSION);
        await(() -> windowEnd() >= base + 3000, 20); assertEquals(2d, redis.zscore(namespace + ":hot", "1"));
        job.cancel().get(20, TimeUnit.SECONDS); job = null;
        try (var connection = PG.createConnection(""); var statement = connection.createStatement()) { statement.execute("UPDATE item SET category='changed'"); }
        start(checkpoint);
        // 恢复的source从checkpoint继续；额外重投旧eventId也不能增加计数。
        send(1, 1, base + 100, ActionType.CLICK); send(6, 2, base + 3300, ActionType.CLICK);
        send(7, 1, base + 4200, ActionType.IMPRESSION);
        await(() -> windowEnd() >= base + 4000, 45);
        assertEquals(2d, redis.zscore(namespace + ":hot", "1")); assertEquals(3d, redis.zscore(namespace + ":hot", "2"));
        assertEquals(Map.of("cat", "2", "other", "2"), redis.hgetAll(namespace + ":user:1"));
        // 丢输出键后，处理时间心跳用checkpoint状态重建，原绝对过期不变。
        long expiry = redis.pexpireTime(namespace + ":hot"); redis.del(namespace + ":hot", namespace + ":user:1");
        await(() -> redis.zcard(namespace + ":hot") == 2, 10); assertEquals(expiry, redis.pexpireTime(namespace + ":hot"));
        // 水位之前的新身份为迟到，不回灌实时窗口。未来水位触发最终空窗清除全部旧成员。
        send(8, 1, base + 3500, ActionType.LIKE); send(9, 1, base + 5200, ActionType.IMPRESSION);
        await(() -> windowEnd() >= base + 5000, 20);
        assertEquals(1d, redis.zscore(namespace + ":hot", "1"), "迟到LIKE不应混入仍有重叠的未来窗口");
        send(10, 1, base + 10_200, ActionType.IMPRESSION);
        await(() -> windowEnd() >= base + 8000, 20);
        assertEquals(0, redis.zcard(namespace + ":hot")); assertTrue(redis.hgetAll(namespace + ":user:1").isEmpty());
    }

    @Test void checkpointIdentityChangeFailsBeforePublishingAndCapacityNeverSilentlyDrops() throws Exception {
        long base = System.currentTimeMillis() / 1000 * 1000 - 3000;
        send(1, 1, base + 100, ActionType.CLICK); send(2, 1, base + 2200, ActionType.IMPRESSION); start(null);
        await(() -> windowEnd() >= base + 2000, 40);
        String checkpoint = checkpointAfter(System.currentTimeMillis());
        job.cancel().get(20, TimeUnit.SECONDS); job = null;
        var s = settings(checkpoint);
        var incompatible = new FeatureSettings(s.kafka(), s.topic(), s.group(), s.projection(), s.namespace(), s.sourceId(), true,
                s.checkpointDir(), s.restore(), s.redisHost(), s.redisPort(), s.pgUrl(), s.pgUser(), s.pgPassword(),
                s.windowMs(), s.slideMs(), s.disorderMs(), s.freshnessMs() + 1, s.checkpointMs(), s.maxEvents(), s.sourceIdentity());
        long before = windowEnd(); start(incompatible, true);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> job.getJobExecutionResult().get(20, TimeUnit.SECONDS));
        assertEquals(before, windowEnd()); job = null;
        var limited = new FeatureSettings(s.kafka(), s.topic(), s.group(), UUID.randomUUID().toString(), "capacity:" + UUID.randomUUID(), s.sourceId(), true,
                directory.resolve("capacity").toUri().toString(), null, s.redisHost(), s.redisPort(), s.pgUrl(), s.pgUser(), s.pgPassword(),
                s.windowMs(), s.slideMs(), s.disorderMs(), s.freshnessMs(), s.checkpointMs(), 1, s.sourceIdentity());
        send(3, 2, base + 2300, ActionType.LIKE); start(limited, true);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> job.getJobExecutionResult().get(20, TimeUnit.SECONDS));
        assertTrue(chainContains(failure, FeatureSettings.CAPACITY)); job = null;
    }

    @Test void actualRedisInterruptionRestartsFlinkFromCheckpointAndRestoresCurrentSnapshot() throws Exception {
        long base = System.currentTimeMillis() / 1000 * 1000 - 2000;
        send(1, 1, base + 100, ActionType.CLICK); send(2, 1, base + 1200, ActionType.IMPRESSION); start(null);
        await(() -> windowEnd() >= base + 1000, 40); checkpointAfter(System.currentTimeMillis());
        String priorLease = redis.get(namespace + ":lease");
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try { Thread.sleep(4_000); } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        redis.del(namespace + ":hot", namespace + ":user:1");
        await(() -> redis.zscore(namespace + ":hot", "1") != null, 55);
        assertEquals(1d, redis.zscore(namespace + ":hot", "1"));
        assertEquals(Map.of("cat", "1"), redis.hgetAll(namespace + ":user:1"));
        assertNotEquals(priorLease, redis.get(namespace + ":lease"), "必须真正重启sink执行器后恢复");
    }

    private static boolean chainContains(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause.getMessage() != null && cause.getMessage().contains(text)) return true;
        return false;
    }

    @Test void retainedCheckpointOffsetBelowKafkaRetentionFailsInsteadOfSkippingGap() throws Exception {
        long base = System.currentTimeMillis() / 1000 * 1000 - 2000;
        send(1, 1, base + 100, ActionType.CLICK); send(2, 1, base + 1200, ActionType.IMPRESSION); start(null);
        await(() -> windowEnd() >= base + 1000, 40);
        String checkpoint = checkpointAfter(System.currentTimeMillis());
        job.cancel().get(20, TimeUnit.SECONDS); job = null;
        send(3, 2, base + 1300, ActionType.LIKE); send(4, 1, base + 2200, ActionType.IMPRESSION);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.bootstrap()))) {
            admin.deleteRecords(Map.of(new org.apache.kafka.common.TopicPartition(topic, 0),
                    org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(4))).all().get(10, TimeUnit.SECONDS);
        }
        long end = windowEnd(); start(settings(checkpoint), true);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> job.getJobExecutionResult().get(20, TimeUnit.SECONDS));
        boolean found = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            found |= cause instanceof org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
        assertTrue(found, "保留缺口必须明确失败而不是跳过未消费记录");
        assertEquals(end, windowEnd()); job = null;
    }
}
