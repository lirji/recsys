package com.recsys.behavior.events;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.recsys.common.dto.BehaviorEvent;
import com.recsys.common.constant.ActionType;
import com.recsys.behavior.events.BehaviorEventRepository.Destination;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 两个独占真实组件验证完整发送链；Sink仅证明重复应用机制，不替代C15C/S19。 */
@Tag("behavior-reliability") @Testcontainers
class BehaviorEventsKafkaTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaFixture KAFKA=new KafkaFixture();
    BehaviorEventsFixture f;
    String topic,historyTopic,group;
    MockEnvironment environment;
    @BeforeEach void setup() throws Exception {
        f=new BehaviorEventsFixture(PG,true);
        String suffix=".t"+UUID.randomUUID().toString().replace("-","");
        topic="behavior-accepted-v1"+suffix;historyTopic="behavior-history-recovery-v1"+suffix;group="behavior-test-"+UUID.randomUUID();
        environment=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers",KAFKA.bootstrap());
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.bootstrap()))) {
            var config=Map.of("cleanup.policy","delete","retention.ms","-1","retention.bytes","-1");
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1).configs(config),new NewTopic(historyTopic,1,(short)1).configs(config))).all().get(10,TimeUnit.SECONDS);
        }
    }
    BehaviorKafkaPublisher publisher(Destination destination) {
        return new BehaviorKafkaPublisher(new KafkaProducer<>(BehaviorEventConfiguration.producerProperties(environment)),destination,destination==Destination.LIVE?topic:historyTopic);
    }
    BehaviorEventRelay relay(BehaviorKafkaPublisher publisher,Destination destination) {
        return new BehaviorEventRelay(f.repo,publisher,f.codec,new SimpleMeterRegistry(),destination);
    }
    KafkaConsumer<String,String> consumer() {
        return new KafkaConsumer<>(Map.of("bootstrap.servers",KAFKA.bootstrap(),"group.id",group,
                "key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class,
                "enable.auto.commit",false,"auto.offset.reset","earliest","allow.auto.create.topics",false,
                "default.api.timeout.ms",10000,"max.poll.records",10));
    }
    List<ConsumerRecord<String,String>> poll(KafkaConsumer<String,String> consumer,int expected) {
        var records=new ArrayList<ConsumerRecord<String,String>>();long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(records.size()<expected && System.nanoTime()<deadline)consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        assertTrue(records.size()>=expected,"实际Kafka记录不足: "+records.size());return records;
    }
    @Test void brokerFailureDuplicateClickAndLegacyRepairRecoverTheirOriginalStreams() throws Exception {
        f.impression("live");f.impression("old");
        f.jdbc.update("INSERT INTO user_behavior(user_id,item_id,action,value,bucket,ts,exposure_id) VALUES(7,42,'CLICK',2,'old-bucket','2001-02-03 04:05:06.123456','old')");
        String live,history;
        try(var publisher=publisher(Destination.LIVE)) {
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {
                f.service().record(f.event(ActionType.CLICK,"live"));live=f.lastId();
                assertTrue(relay(publisher,Destination.LIVE).relayOne());assertEquals("READY",f.state(live));
                String payload=f.payload(live);f.service().record(f.event(ActionType.CLICK,"live"));assertEquals(payload,f.payload(live));
                f.service().record(new BehaviorEvent(7,42,ActionType.CLICK,99,"retry","new",1,"old"));
                history=f.jdbc.queryForObject("SELECT event_id FROM behavior_event_outbox WHERE origin='LEGACY_ROW'",String.class);
                assertTrue(f.repo.claim(Destination.LIVE).isEmpty());
                assertEquals("READY",f.state(history));assertEquals(2,f.count("behavior_event_outbox"));assertEquals(4,f.count("user_behavior"));
            } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
            f.due(live);assertTrue(relay(publisher,Destination.LIVE).relayOne());
        }
        try(var publisher=publisher(Destination.HISTORY)) { assertTrue(relay(publisher,Destination.HISTORY).relayOne()); }
        assertEquals("PUBLISHED",f.state(live));assertEquals("PUBLISHED",f.state(history));
        var sink=new Sink(f);
        try(var consumer=consumer()) {
            consumer.subscribe(List.of(topic,historyTopic));
            for(var r:poll(consumer,2)) {
                var e=f.codec.decode(r.key(),r.value());assertEquals(f.payload(e.eventId()),r.value());
                if(r.topic().equals(historyTopic)) { assertEquals(history,e.eventId());assertEquals("old-bucket",e.behavior().bucket());assertNull(e.behavior().ts());assertEquals("2001-02-03T04:05:06.123456",e.sourceTsLocal()); }
                else assertEquals(live,e.eventId());
                sink.apply(r);commit(consumer,r);evidence(e.eventId(),r,sink.count());
            }
        }
        assertEquals(2,sink.count());
    }

    @ParameterizedTest @ValueSource(strings={"before-send","after-claim","ack-before-mark","final-claim"})
    void processExitRecoversOriginalPayloadAndDurableAttempt(String window) throws Exception {
        if(window.equals("final-claim")) {
            String id=f.accept();
            for(int i=1;i<20;i++) {var p=f.repo.claim(Destination.LIVE).orElseThrow();assertEquals(i,p.attempts());assertTrue(f.repo.failed(p,1000,BehaviorEventRepository.Error.SEND_UNKNOWN));f.due(id);}
        }
        Path log=Path.of("target","behavior-event-evidence",window+"-"+UUID.randomUUID()+".log");Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
            System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),BehaviorEventsCrashProbe.class.getName(),window);
        builder.environment().putAll(Map.of("S15B_JDBC",f.ds.getUrl(),"S15B_DB_USER",f.ds.getUsername(),"S15B_DB_PASSWORD",f.ds.getPassword(),"S15B_KAFKA",KAFKA.bootstrap(),"S15B_TOPIC",topic));
        Process process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit=window.equals("before-send")?81:window.equals("ack-before-mark")?83:82;
        try {assertTrue(process.waitFor(45,TimeUnit.SECONDS),"子进程超时 "+log);assertEquals(exit,process.exitValue(),"崩溃窗口未命中 "+log);}
        finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
        String id=f.lastId(),payload=f.payload(id);assertEquals(1,f.count("user_behavior"));
        assertTrue(Files.readString(log).contains("S15B_WINDOW="+window+" eventId="+id));
        assertEquals(window.equals("before-send")?"READY":"SENDING",f.state(id));f.due(id);
        var recovered=new BehaviorEventsFixture(new DriverManagerDataSource(f.ds.getUrl(),f.ds.getUsername(),f.ds.getPassword()));recovered.repo.verifySchema();
        if(window.equals("final-claim")) {
            assertEquals(1,recovered.repo.exhaust(Destination.LIVE));assertEquals("DEAD",f.state(id));
            var replay=recovered.repo.replay(UUID.randomUUID(),id,"isolated-test","final claim crashed");assertEquals(20,replay.oldAttempts());
        }
        try(var publisher=publisher(Destination.LIVE)) {assertTrue(new BehaviorEventRelay(recovered.repo,publisher,recovered.codec,new SimpleMeterRegistry(),Destination.LIVE).relayOne());}
        assertEquals("PUBLISHED",f.state(id));var sink=new Sink(f);
        try(var consumer=consumer()) {
            consumer.subscribe(List.of(topic));
            for(var r:poll(consumer,window.equals("ack-before-mark")?2:1)){assertEquals(payload,r.value());sink.apply(r);commit(consumer,r);evidence(id,r,sink.count());}
        }
        assertEquals(1,sink.count());System.out.println("S15B_PROCESS_RECOVERY window="+window+" exit="+exit+" log="+log);
    }

    @Test void twoRelayOnlyWorkersDrainWithoutCaptureAndNetworkIsOutsideTransactions() throws Exception {
        String live=f.accept();f.jdbc.update("INSERT INTO user_behavior(user_id,item_id,action,value,exposure_id) VALUES(7,42,'CLICK',1,'history')");
        f.repo.accept(f.event(ActionType.CLICK,"history"));
        var runner=new ApplicationContextRunner().withUserConfiguration(BehaviorEventConfiguration.class)
            .withBean("dataSource",DataSource.class,()->f.ds).withBean("sqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->f.sessions)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,SimpleMeterRegistry::new)
            .withPropertyValues("recsys.behavior.events.relay-enabled=true","recsys.behavior.events.history-relay-enabled=true",
                "recsys.behavior.events.topic="+topic,"recsys.behavior.events.history-topic="+historyTopic,"spring.kafka.bootstrap-servers="+KAFKA.bootstrap());
        long start=System.nanoTime();
        runner.run(c->{
            assertNull(c.getStartupFailure());assertFalse(c.getBean(BehaviorEventConfiguration.Properties.class).captureEnabled());
            assertEquals(2,c.getBeansOfType(BehaviorEventWorker.class).size());
            long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(f.jdbc.queryForObject("SELECT count(*) FROM behavior_event_outbox WHERE status='PUBLISHED'",Integer.class)!=2 && System.nanoTime()<end)Thread.sleep(50);
            assertEquals(2,f.jdbc.queryForObject("SELECT count(*) FROM behavior_event_outbox WHERE status='PUBLISHED'",Integer.class));
            assertEquals(2,Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().startsWith("behavior-outbox-")).count());
        });
        assertTrue(System.nanoTime()-start<Duration.ofSeconds(35).toNanos());
        assertEquals(0,Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().startsWith("behavior-outbox-")).count());
        String id=f.accept();
        try(var publisher=new BehaviorKafkaPublisher(new KafkaProducer<>(BehaviorEventConfiguration.producerProperties(environment)),Destination.LIVE,topic) {
            @Override public void publish(com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper.Pending event) throws Exception {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());super.publish(event);
            }
        }) {
            var tx=new TransactionTemplate(new DataSourceTransactionManager(f.ds));
            assertThrows(IllegalStateException.class,()->tx.execute(s->relay(publisher,Destination.LIVE).relayOne()));
            assertTrue(relay(publisher,Destination.LIVE).relayOne());assertEquals("PUBLISHED",f.state(id));
        }
    }
    private static void commit(KafkaConsumer<String,String> consumer,ConsumerRecord<String,String> r) {
        consumer.commitSync(Map.of(new TopicPartition(r.topic(),r.partition()),new OffsetAndMetadata(r.offset()+1)),Duration.ofSeconds(5));
    }
    private void evidence(String id,ConsumerRecord<String,String> r,int effects) throws Exception {
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(r.value().getBytes(StandardCharsets.UTF_8)));
        var state=f.jdbc.queryForMap("SELECT behavior_id,status,attempts,fence FROM behavior_event_outbox WHERE event_id=?",id);
        System.out.println("S15B_EVIDENCE eventId="+id+" payload_sha256="+hash+" outbox="+state+" topic="+r.topic()+" partition="+r.partition()+" offset="+r.offset()+" fixture_effects="+effects);
    }

    /** 测试下游独占 schema，与 owner 无跨表事务；只证明重复应用和 offset 窗口。 */
    static class Sink {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        Sink(BehaviorEventsFixture source) {
            String schema = "sink_" + UUID.randomUUID().toString().replace("-", ""); source.jdbc.execute("CREATE SCHEMA " + schema);
            var ds = new DriverManagerDataSource(source.ds.getUrl().replaceFirst("currentSchema=[^&]+", "currentSchema=" + schema), source.ds.getUsername(), source.ds.getPassword());
            jdbc = new JdbcTemplate(ds); transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
            jdbc.execute("CREATE TABLE test_inbox(event_id TEXT PRIMARY KEY,payload TEXT NOT NULL); COMMENT ON TABLE test_inbox IS '仅测试订阅端幂等记录'; COMMENT ON COLUMN test_inbox.event_id IS '原事件身份'; COMMENT ON COLUMN test_inbox.payload IS '原始负载'");
            jdbc.execute("CREATE TABLE test_effect(event_id TEXT PRIMARY KEY REFERENCES test_inbox(event_id)); COMMENT ON TABLE test_effect IS '仅测试订阅端单一效果'; COMMENT ON COLUMN test_effect.event_id IS '产生效果的原事件身份'");
        }
        void apply(ConsumerRecord<String, String> r) {
            var event = new BehaviorEventCodec().decode(r.key(), r.value());
            String id = event.eventId();
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
            withCommand("/bin/bash", "-c", "while [ ! -f /tmp/behavior-kafka-start ]; do sleep 0.1; done; . /tmp/behavior-kafka-start; exec /etc/kafka/docker/run");
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(InspectContainerResponse info) {
            copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://" + bootstrap() + "'\n", 0644), "/tmp/behavior-kafka-start");
        }
        String bootstrap() { return getHost() + ":" + getMappedPort(19092); }
    }
}
