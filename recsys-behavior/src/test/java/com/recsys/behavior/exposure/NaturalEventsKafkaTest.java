package com.recsys.behavior.exposure;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.recsys.common.exposure.NaturalReceipt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 独占 PG/Kafka 验证真实故障和子 JVM 崩溃；测试 Inbox/effect 不代表产品消费投影。 */
@Tag("natural-exposure")
@Testcontainers
class NaturalEventsKafkaTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaFixture KAFKA = new KafkaFixture();
    NaturalEventsFixture f;
    String topic, group;
    MockEnvironment environment;

    @BeforeEach void setup() throws Exception {
        f = new NaturalEventsFixture(PG, true);
        topic = "natural-impression-accepted-v1.t" + UUID.randomUUID().toString().replace("-", "");
        group = "natural-test-" + UUID.randomUUID();
        environment = new MockEnvironment().withProperty("spring.kafka.bootstrap-servers", KAFKA.bootstrap());
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short)1).configs(Map.of(
                    "cleanup.policy", "delete", "retention.ms", "-1", "retention.bytes", "-1")))).all().get(10, TimeUnit.SECONDS);
        }
    }

    NaturalKafkaPublisher publisher() {
        return new NaturalKafkaPublisher(new KafkaProducer<>(NaturalEventConfiguration.producerProperties(environment)), topic);
    }
    NaturalOutboxRelay relay(NaturalKafkaPublisher publisher) {
        return new NaturalOutboxRelay(f.outbox, publisher, f.codec, new SimpleMeterRegistry());
    }
    KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", KAFKA.bootstrap(), "group.id", group,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class,
                "enable.auto.commit", false, "auto.offset.reset", "earliest", "allow.auto.create.topics", false,
                "default.api.timeout.ms", 10000, "max.poll.records", 10));
    }
    List<ConsumerRecord<String, String>> poll(KafkaConsumer<String, String> consumer, int expected) {
        var records = new ArrayList<ConsumerRecord<String, String>>();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        assertTrue(records.size() >= expected, "实际收到记录不足: " + records.size()); return records;
    }

    @Test void brokerFailureAfterAcceptanceAndDuplicateReceiptsRecoverOriginalEvent() throws Exception {
        NaturalEventsFixture.Accepted accepted;
        try (var publisher = publisher()) {
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {
                accepted = f.accept(); var id = accepted.result().eventId();
                assertTrue(relay(publisher).relayOne()); assertEquals("READY", f.state(id));
                assertNull(f.jdbc.queryForObject("SELECT published_at FROM natural_impression_outbox WHERE event_id=?", java.sql.Timestamp.class, id));
                assertEquals(accepted.result(), f.service(f.receipts).accept(accepted.command()));
                var c = accepted.command();
                var duplicate = f.service(f.receipts).accept(new NaturalReceipt.Command(UUID.randomUUID(), c.subject(), c.qualificationToken(), 1000, 5000, true));
                assertEquals(NaturalReceipt.Status.ALREADY_ACCEPTED, duplicate.status()); assertEquals(id, duplicate.eventId());
                assertEquals(1, f.count("natural_impression_fact")); assertEquals(1, f.count("natural_impression_outbox"));
            } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
            f.awaitDue(accepted.result().eventId()); assertTrue(relay(publisher).relayOne());
        }
        UUID id = accepted.result().eventId(); assertEquals("PUBLISHED", f.state(id));
        var sink = new Sink(f);
        try (var consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            for (var record : poll(consumer, 1)) {
                assertEquals(f.payload(id), record.value()); sink.apply(record); commit(consumer, record); evidence(id, record, sink.count());
            }
        }
        assertEquals(1, sink.count());
    }

    @ParameterizedTest @ValueSource(strings = {"before-send", "ack-before-mark"})
    void realProcessExitAtCommitWindowsRecoversWithFreshWorker(String window) throws Exception {
        Path log = Path.of("target", "natural-event-evidence", window + "-" + UUID.randomUUID() + ".log");
        Files.createDirectories(log.getParent());
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), NaturalEventsCrashProbe.class.getName(), window);
        builder.environment().putAll(Map.of("S15_JDBC", f.ds.getUrl(), "S15_DB_USER", f.ds.getUsername(), "S15_DB_PASSWORD", f.ds.getPassword(),
                "S15_KAFKA", KAFKA.bootstrap(), "S15_TOPIC", topic));
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "子 JVM 超时，日志 " + log);
            assertEquals(window.equals("before-send") ? 71 : 72, process.exitValue(), "崩溃位置未命中，日志 " + log);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        assertEquals(1, f.count("natural_impression_fact"));
        UUID id = f.jdbc.queryForObject("SELECT event_id FROM natural_impression_outbox", UUID.class);
        String payload = f.payload(id);
        String marker = window.equals("before-send") ? "S15_COMMITTED=" : "S15_ACK_BEFORE_MARK=";
        assertTrue(Files.readString(log).contains(marker + id));
        assertEquals(window.equals("before-send") ? "READY" : "SENDING", f.state(id));
        f.awaitDue(id);
        // 新仓储/新连接与 producer；真实租约到期，不改表模拟时间。
        var recovered = new NaturalEventsFixture(new DriverManagerDataSource(f.ds.getUrl(), f.ds.getUsername(), f.ds.getPassword()));
        try (var publisher = publisher()) {
            assertTrue(new NaturalOutboxRelay(recovered.outbox, publisher, recovered.codec, new SimpleMeterRegistry()).relayOne());
        }
        assertEquals("PUBLISHED", f.state(id));
        var sink = new Sink(f);
        try (var consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            for (var record : poll(consumer, window.equals("before-send") ? 1 : 2)) {
                assertEquals(payload, record.value()); sink.apply(record); commit(consumer, record); evidence(id, record, sink.count());
            }
        }
        assertEquals(1, sink.count()); assertEquals(1, f.count("natural_impression_fact"));
        System.out.println("S15_PROCESS_RECOVERY window=" + window + " exit=" + process.exitValue() + " log=" + log);
    }

    @Test void consumerCommitWindowReplaysButIndependentPgEffectIsAppliedOnce() throws Exception {
        UUID id = f.accept().result().eventId();
        try (var publisher = publisher()) { assertTrue(relay(publisher).relayOne()); }
        var sink = new Sink(f); ConsumerRecord<String, String> first;
        try (var consumer = consumer()) {
            consumer.subscribe(List.of(topic)); first = poll(consumer, 1).getFirst();
            sink.apply(first);
            assertNull(consumer.committed(Set.of(new TopicPartition(topic, first.partition()))).get(new TopicPartition(topic, first.partition())));
            // 故意在已提交 PG 事务之后关闭真实消费者，不提交 offset。
        }
        try (var restarted = consumer()) {
            restarted.subscribe(List.of(topic)); var repeated = poll(restarted, 1).getFirst();
            assertEquals(first.offset(), repeated.offset()); assertEquals(first.value(), repeated.value());
            sink.apply(repeated); commit(restarted, repeated);
            assertEquals(repeated.offset() + 1, restarted.committed(Set.of(new TopicPartition(topic, repeated.partition()))).get(new TopicPartition(topic, repeated.partition())).offset());
            evidence(id, repeated, sink.count());
        }
        assertEquals(1, sink.count()); assertEquals(1, sink.jdbc.queryForObject("SELECT count(*) FROM test_inbox", Integer.class));
    }

    @Test void relayOnlyConfigurationWithoutReceiptKeysStartsAndStopsBounded() throws Exception {
        UUID id = f.accept().result().eventId();
        var runner = new ApplicationContextRunner().withUserConfiguration(NaturalEventConfiguration.class)
                .withBean("dataSource", DataSource.class, () -> f.ds)
                .withBean("sqlSessionTemplate", org.mybatis.spring.SqlSessionTemplate.class, () -> f.sessions)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, () -> NaturalEventsFixture.JSON)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("recsys.natural.events.relay-enabled=true", "recsys.natural.receipts-enabled=false",
                        "recsys.natural.events.topic=" + topic, "spring.kafka.bootstrap-servers=" + KAFKA.bootstrap());
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure()); assertFalse(ctx.containsBean("naturalReceiptService"));
            assertEquals(1, ctx.getBean("naturalOutboxScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize());
            long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!"PUBLISHED".equals(f.state(id)) && System.nanoTime() < until) Thread.sleep(50);
            assertEquals("PUBLISHED", f.state(id));
        });
        assertEquals(1, f.count("natural_impression_outbox"));
    }

    private static void commit(KafkaConsumer<String, String> consumer, ConsumerRecord<String, String> r) {
        consumer.commitSync(Map.of(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1)), Duration.ofSeconds(5));
    }

    private void evidence(UUID id, ConsumerRecord<String, String> record, int effect) throws Exception {
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(record.value().getBytes(StandardCharsets.UTF_8)));
        var state = f.jdbc.queryForMap("SELECT status,attempts,fence FROM natural_impression_outbox WHERE event_id=?", id);
        System.out.println("S15_EVIDENCE eventId=" + id + " payload_sha256=" + hash + " outbox=" + state
                + " topic=" + topic + " partition=" + record.partition() + " offset=" + record.offset() + " fixture_effects=" + effect);
    }

    /** 测试下游独占 schema，与 owner 无跨表事务；只证明重复应用和 offset 窗口。 */
    static class Sink {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        Sink(NaturalEventsFixture source) {
            String schema = "sink_" + UUID.randomUUID().toString().replace("-", ""); source.jdbc.execute("CREATE SCHEMA " + schema);
            var ds = new DriverManagerDataSource(source.ds.getUrl().replaceFirst("currentSchema=[^&]+", "currentSchema=" + schema), source.ds.getUsername(), source.ds.getPassword());
            jdbc = new JdbcTemplate(ds); transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
            jdbc.execute("CREATE TABLE test_inbox(event_id UUID PRIMARY KEY,payload TEXT NOT NULL); COMMENT ON TABLE test_inbox IS '仅测试订阅端幂等记录'; COMMENT ON COLUMN test_inbox.event_id IS '原事件身份'; COMMENT ON COLUMN test_inbox.payload IS '原始负载'");
            jdbc.execute("CREATE TABLE test_effect(event_id UUID PRIMARY KEY REFERENCES test_inbox(event_id)); COMMENT ON TABLE test_effect IS '仅测试订阅端单一效果'; COMMENT ON COLUMN test_effect.event_id IS '产生效果的原事件身份'");
        }
        void apply(ConsumerRecord<String, String> r) {
            var event = new NaturalEventCodec(NaturalEventsFixture.JSON).decode(r.key(), r.value());
            UUID id = UUID.fromString(event.eventId());
            transactions.execute(status -> {
                if (jdbc.update("INSERT INTO test_inbox(event_id,payload) VALUES(?,?) ON CONFLICT DO NOTHING", id, r.value()) == 1) {
                    jdbc.update("INSERT INTO test_effect(event_id) VALUES(?)", id);
                } else assertEquals(r.value(), jdbc.queryForObject("SELECT payload FROM test_inbox WHERE event_id=?", String.class, id));
                return null;
            });
        }
        int count() { return jdbc.queryForObject("SELECT count(*) FROM test_effect", Integer.class); }
    }

    /** 沿 S06 官方镜像测试方法，动态端口且只暂停本类拥有的容器。 */
    static class KafkaFixture extends GenericContainer<KafkaFixture> {
        KafkaFixture() {
            super("apache/kafka:3.7.1"); withExposedPorts(19092);
            withEnv(Map.of("KAFKA_NODE_ID", "1", "KAFKA_PROCESS_ROLES", "broker,controller", "KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093",
                    "KAFKA_LISTENERS", "INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:19092",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL", "KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1", "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0", "KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false"));
            withCommand("/bin/bash", "-c", "while [ ! -f /tmp/natural-kafka-start ]; do sleep 0.1; done; . /tmp/natural-kafka-start; exec /etc/kafka/docker/run");
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(InspectContainerResponse info) {
            copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://" + bootstrap() + "'\n", 0644), "/tmp/natural-kafka-start");
        }
        String bootstrap() { return getHost() + ":" + getMappedPort(19092); }
    }
}
