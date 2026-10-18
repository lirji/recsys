package com.recsys.adserving.catalog.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.CatalogEventCodec;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
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
import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static com.recsys.adserving.catalog.projection.CatalogProjectionPostgresTest.event;
import static com.recsys.adserving.catalog.projection.CatalogProjectionPostgresTest.bytes;
import static com.recsys.adserving.catalog.projection.CatalogProjectionPostgresTest.SOURCE;
import static org.junit.jupiter.api.Assertions.*;

/** 真正的 Kafka 消费器及独立JVM退出；位点来自Admin，本库事实来自独立JDBC查询。 */
@Testcontainers @Tag("catalog-projection-reliability")
class CatalogProjectionKafkaTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container static final KafkaFixture KAFKA = new KafkaFixture();
    static JdbcTemplate jdbc;
    static CatalogProjectionStore store;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static CatalogRedisProjection writer;
    final List<CatalogKafkaProjection> runtimes = new ArrayList<>();
    String topic, group;

    @BeforeAll static void setup() throws Exception {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/ad-serving-catalog.sql")).execute(ds);
        store = new CatalogProjectionStore(new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/adserving/addb/*.xml")), new ObjectMapper().findAndRegisterModules());
        store.install();
        connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)), LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        connection.afterPropertiesSet(); connection.start(); redis = new StringRedisTemplate(connection); writer = new CatalogRedisProjection(store, redis);
    }
    @BeforeEach void resetExclusive() throws Exception {
        jdbc.execute("TRUNCATE catalog_projection_receipt,catalog_projection_inbox,catalog_projection_redis_task,catalog_projection,catalog_projection_position,catalog_projection_control,catalog_projection_audit,ad_servable,ad_embedding RESTART IDENTITY CASCADE");
        try (var c = connection.getConnection()) { c.serverCommands().flushDb(); }
        topic = "catalog-projection-" + UUID.randomUUID(); group = "catalog-projection-" + UUID.randomUUID();
        try (var admin = admin()) { admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1).configs(Map.of("cleanup.policy", "delete")))).all().get(10, TimeUnit.SECONDS); }
    }
    @AfterEach void closeConsumers() { for (var runtime : runtimes) runtime.close(); }
    @AfterAll static void closeRedis() { if (connection != null) connection.destroy(); }

    @Test void existingKafkaBaselineHighLowDuplicateAndDeletionReachPgAndRedis() throws Exception {
        byte[] high = bytes(event(101, 3, 10, true, false, "current"));
        send(high); send(bytes(event(101, 1, 10, true, false, "stale"))); send(high);
        send(bytes(event(101, 4, 10, false, true, "delete"))); send(high);
        var runtime = runtime(); initialize(runtime); runtime.start(); await(() -> offset() == 5);
        assertEquals(4, store.projection(101).aggregateVersion()); assertTrue(store.projection(101).deleted());
        assertEquals(5, store.positions().getFirst().nextOffset()); assertEquals(0, count("ad_servable"));
        while (writer.advance()) { /* 只推进本测试两条持久任务 */ }
        assertFalse(redis.hasKey("bidword:inv:current")); assertFalse(redis.hasKey("bidword:inv:stale"));
    }

    @Test void poisonPersistsBeforeAckAndGoodSuccessorStillApplies() throws Exception {
        send("private-bad-json".getBytes()); send(null); send(bytes(event(101, 1, 10, true, false, "valid")));
        var runtime = runtime(); initialize(runtime); runtime.start(); await(() -> offset() == 3);
        assertEquals(2, store.quarantinedCount()); assertEquals(1, count("ad_servable"));
        assertArrayEquals("private-bad-json".getBytes(), jdbc.queryForObject("SELECT payload FROM catalog_projection_receipt WHERE record_offset=0", byte[].class));
    }

    @Test void databaseCommitBeforeKafkaAckResumesFromPgWithoutRepeatingEffects() throws Exception {
        send(bytes(event(101, 1, 10, true, false, "accepted")));
        var first = new CatalogKafkaProjection(store, writer, admin(), () -> new KafkaConsumer<>(consumerProps(KAFKA.bootstrap(), group)), topic, group) {
            @Override protected void afterDatabase(ConsumerRecord<byte[], byte[]> record) { throw new IllegalStateException("test-after-commit"); }
        };
        runtimes.add(first); initialize(first); first.start();
        await(() -> "PAUSED".equals(store.control().state())); assertEquals(1, store.positions().getFirst().nextOffset()); assertEquals(0, offset());
        first.close(); store.resume(UUID.randomUUID(), "test-owner", "确认原PG提交");
        var next = runtime(); next.start(); await(() -> offset() == 1);
        assertEquals(1, count("catalog_projection_inbox")); assertEquals(1, count("catalog_projection_redis_task"));
    }

    @Test void actualJvmExitAfterCommitKeepsFenceAndRecoversWithOriginalPayload() throws Exception {
        byte[] input = bytes(event(101, 1, 10, true, false, "jvm")); send(input);
        var initialized = runtime(); initialize(initialized); initialized.close();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = Path.of("target/catalog-projection-exit.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classpath,
                CrashProcess.class.getName(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), KAFKA.bootstrap(), topic, group)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), "独立进程未在预算内退出，见" + log);
            assertEquals(17, child.exitValue(), "见" + log); assertEquals(1, store.positions().getFirst().nextOffset());
            assertArrayEquals(input, store.projection(101).payload()); assertEquals(0, offset());
        } finally { if (child.isAlive()) child.destroyForcibly(); }
        assertThrows(CatalogProjectionProtocol.Failure.class, () -> store.claimConsumer("too-early"));
        jdbc.execute("UPDATE catalog_projection_control SET lease_until=clock_timestamp()-interval '1 second'");
        var recovered = runtime(); recovered.start(); await(() -> offset() == 1);
        assertEquals(1, count("catalog_projection_inbox")); assertEquals(1, count("catalog_projection_redis_task"));
    }

    @Test void retentionGapAndNewPartitionAreRejectedBeforeConsumption() throws Exception {
        send(bytes(event(101, 1, 10, true, false, "one"))); send(bytes(event(101, 2, 10, true, false, "two")));
        var runtime = runtime(); initialize(runtime);
        try (var admin = admin()) { admin.deleteRecords(Map.of(new TopicPartition(topic, 0), RecordsToDelete.beforeOffset(1))).all().get(10, TimeUnit.SECONDS); }
        assertThrows(CatalogProjectionProtocol.Failure.class, runtime::start); assertEquals(0, store.positions().getFirst().nextOffset());
        try (var admin = admin()) { admin.createPartitions(Map.of(topic, NewPartitions.increaseTo(2))).all().get(10, TimeUnit.SECONDS); }
        assertThrows(CatalogProjectionProtocol.Failure.class, runtime::start); assertEquals(0, count("catalog_projection_inbox"));
    }

    @Test void secondExecutorCannotTakeLiveLeaseAndSameTopicNameCannotHideRecreation() throws Exception {
        var first = runtime(); initialize(first); first.start(); await(first::isRunning);
        var second = runtime(); assertThrows(CatalogProjectionProtocol.Failure.class, second::start); first.close();
        try (var admin = admin()) {
            admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        assertThrows(CatalogProjectionProtocol.Failure.class, second::start);
    }

    @Test void nonemptyRedisOrOldDirectoryCannotBeSilentlyAdopted() throws Exception {
        var runtime = runtime(); redis.opsForZSet().add("bidword:inv:legacy", "old", 1);
        assertThrows(CatalogProjectionProtocol.Failure.class, () -> initialize(runtime)); assertNull(store.control()); redis.delete("bidword:inv:legacy");
        jdbc.execute("INSERT INTO ad_servable(ad_id,advertiser_id,item_id) VALUES(7,3,10)");
        assertThrows(CatalogProjectionProtocol.Failure.class, () -> initialize(runtime)); assertNull(store.control());
    }

    private CatalogKafkaProjection runtime() {
        var runtime = new CatalogKafkaProjection(store, writer, admin(), () -> new KafkaConsumer<>(consumerProps(KAFKA.bootstrap(), group)), topic, group);
        runtimes.add(runtime); return runtime;
    }
    private void initialize(CatalogKafkaProjection runtime) { runtime.initialize(UUID.randomUUID(), SOURCE, "测试源基线完整，所有原事件已发送", "test-owner", "隔离登记"); }
    private static Admin admin() { return Admin.create(Map.of("bootstrap.servers", KAFKA.bootstrap(), "default.api.timeout.ms", 5000, "request.timeout.ms", 5000)); }
    private void send(byte[] value) throws Exception {
        try (var producer = new KafkaProducer<byte[], byte[]>(Map.of("bootstrap.servers", KAFKA.bootstrap(), "acks", "all", "key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer", "value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer"))) {
            producer.send(new ProducerRecord<>(topic, 0, CatalogEventCodec.key(101), value)).get(10, TimeUnit.SECONDS);
        }
    }
    private long offset() {
        try (var admin = admin()) { var value = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(new TopicPartition(topic, 0)); return value == null ? -1 : value.offset(); }
        catch (Exception unavailable) { return -1; }
    }
    private static int count(String fixedTable) { return jdbc.queryForObject("SELECT count(*) FROM " + fixedTable, Integer.class); }
    private static void await(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) { if (predicate.getAsBoolean()) return; Thread.sleep(150); }
        assertTrue(predicate.getAsBoolean(), "真实消费者未在等待预算内收敛");
    }
    static Map<String, Object> consumerProps(String broker, String group) {
        return Map.of("bootstrap.servers", broker, "group.id", group, "enable.auto.commit", false, "auto.offset.reset", "none", "max.poll.records", 10,
                "default.api.timeout.ms", 5000, "key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer", "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    }
    /** 子进程使用与产品相同的真实Store/Consumer，不通过测试替身假装JVM退出。 */
    public static class CrashProcess {
        public static void main(String[] args) throws Exception {
            var ds = new DriverManagerDataSource(args[0], args[1], args[2]);
            var store = new CatalogProjectionStore(new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/adserving/addb/*.xml")), new ObjectMapper().findAndRegisterModules()); store.verifySchema();
            var runtime = new CatalogKafkaProjection(store, null, Admin.create(Map.of("bootstrap.servers", args[3])), () -> new KafkaConsumer<>(consumerProps(args[3], args[5])), args[4], args[5]) {
                @Override protected void afterDatabase(ConsumerRecord<byte[], byte[]> record) { Runtime.getRuntime().halt(17); }
            };
            runtime.start(); Thread.sleep(30000); runtime.close(); System.exit(2);
        }
    }
    /** 沿用本仓 apache/kafka 3.7.1 测试启动方式，宿主端口由Testcontainers独占分配。 */
    static class KafkaFixture extends GenericContainer<KafkaFixture> {
        KafkaFixture() {
            super("apache/kafka:3.7.1"); withExposedPorts(19092);
            withEnv(Map.of("KAFKA_NODE_ID", "1", "KAFKA_PROCESS_ROLES", "broker,controller", "KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093",
                    "KAFKA_LISTENERS", "INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:19092", "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL", "KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER", "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1", "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0", "KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false"));
            withCommand("/bin/bash", "-c", "while [ ! -f /tmp/catalog-ready ]; do sleep 0.1; done; . /tmp/catalog-start; exec /etc/kafka/docker/run");
            waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(com.github.dockerjava.api.command.InspectContainerResponse info) {
            copyFileToContainer(org.testcontainers.images.builder.Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://" + bootstrap() + "'\n", 0644), "/tmp/catalog-start");
            copyFileToContainer(org.testcontainers.images.builder.Transferable.of("ready\n", 0644), "/tmp/catalog-ready");
        }
        String bootstrap() { return getHost() + ":" + getMappedPort(19092); }
    }
}
