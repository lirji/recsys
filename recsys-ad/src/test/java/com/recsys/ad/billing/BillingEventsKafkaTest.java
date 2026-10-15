package com.recsys.ad.billing;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 唯一 topic 与临时 Kafka/PG，不对共享 dev_infra 停机或清数据。 */
@Tag("money-chain") @Testcontainers
class BillingEventsKafkaTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaFixture KAFKA=new KafkaFixture();
    BillingEventsFixture f;
    String topic,group;
    MockEnvironment env;
    @BeforeEach void setup() throws Exception {
        f=new BillingEventsFixture(PG);topic="billing-"+UUID.randomUUID();group="billing-projection-"+UUID.randomUUID();
        env=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers",KAFKA.bootstrap())
                .withProperty("recsys.ad.billing.events.topic",topic).withProperty("recsys.ad.billing.events.group",group);
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(topic,2,(short)1).configs(Map.of(
                    "cleanup.policy","delete","retention.ms","-1","retention.bytes","-1")))).all().get(15,TimeUnit.SECONDS);
        }
    }
    BillingKafkaPublisher publisher() {return new BillingKafkaPublisher(new KafkaProducer<>(BillingEventConfiguration.producerProperties(env)),topic);}
    KafkaConsumer<String,String> consumer() {return new KafkaConsumer<>(BillingEventConfiguration.consumerProperties(env));}
    BillingOutboxRelay relay(BillingOutboxRepository repo,BillingKafkaPublisher publisher) {
        return new BillingOutboxRelay(repo,publisher,f.codec,new SimpleMeterRegistry());
    }
    List<ConsumerRecord<String,String>> poll(Consumer<String,String> consumer,int expected) {
        var result=new ArrayList<ConsumerRecord<String,String>>();long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(result.size()<expected && System.nanoTime()<until)consumer.poll(Duration.ofMillis(200)).forEach(result::add);
        assertTrue(result.size()>=expected,"records="+result.size());return result;
    }
    @Test void committedButUnsentThenAckBeforeMarkCrashResendsAndProjectsOnce() throws Exception {
        UUID id=f.capture(1,"0.123456");
        assertEquals("READY",f.state(id));assertEquals(0,f.count());
        try(var publisher=publisher()) {
            var failAfterAck=new BillingOutboxRepository(f.sessions) {
                @Override public boolean published(Pending event) {throw new IllegalStateException("crash after broker ACK");}
            };
            assertThrows(IllegalStateException.class,()->relay(failAfterAck,publisher).relayOne());
            assertEquals("SENDING",f.state(id));
            // 模拟旧进程没有标记即消失，数据库租约到期由新 relay 接管。
            f.due(id);assertTrue(relay(f.outbox,publisher).relayOne());assertEquals("PUBLISHED",f.state(id));
            try(var consumer=consumer()) {
                consumer.subscribe(List.of(topic));var records=poll(consumer,2);
                var adapter=new BillingProjectionConsumer(()->consumer,f.projection,topic,new SimpleMeterRegistry());
                for(var record:records)adapter.process(consumer,record);
                assertEquals(1,f.count());assertEquals("0.123456",f.total().toPlainString());
            }
        }
    }
    @Test void databaseCommitBeforeConsumerAckIsRecoveredByFreshConsumer() throws Exception {
        var e=f.event(f.capture(1,"0.100001"));
        try(var publisher=publisher()) {assertTrue(relay(f.outbox,publisher).relayOne());}
        try(var first=consumer()) {
            first.subscribe(List.of(topic));var record=poll(first,1).getFirst();
            var failAck=spy(first);
            doThrow(new org.apache.kafka.common.KafkaException("injected offset ACK failure")).when(failAck).commitSync(anyMap(),any(Duration.class));
            var adapter=new BillingProjectionConsumer(()->failAck,f.projection,topic,new SimpleMeterRegistry());
            assertThrows(org.apache.kafka.common.KafkaException.class,()->adapter.process(failAck,record));
            assertEquals(1,f.count());
            assertNull(first.committed(Set.of(new TopicPartition(topic,record.partition()))).get(new TopicPartition(topic,record.partition())));
        }
        try(var restarted=consumer()) {
            restarted.subscribe(List.of(topic));var record=poll(restarted,1).getFirst();
            assertEquals(e.payload(),record.value());
            var adapter=new BillingProjectionConsumer(()->restarted,f.projection,topic,new SimpleMeterRegistry());
            adapter.process(restarted,record);
            assertEquals(record.offset()+1,restarted.committed(Set.of(new TopicPartition(topic,record.partition())))
                    .get(new TopicPartition(topic,record.partition())).offset());
            assertEquals(1,f.count());assertEquals("0.100001",f.total().toPlainString());
        }
    }
    @Test void brokerPauseFailsWithoutMarkThenRecoveryDeliversSameFact() throws Exception {
        var id=f.capture(1,"0.200000");
        try(var publisher=publisher()) {
            // 只暂停本测试拥有的 broker，验证客户端真实超时；finally 恢复容器。
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {assertTrue(relay(f.outbox,publisher).relayOne());}
            finally {KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();}
            assertEquals("READY",f.state(id));assertNull(f.jdbc.queryForObject(
                    "SELECT published_at FROM ad_billing_outbox WHERE event_id=?",java.sql.Timestamp.class,id));
            f.due(id);assertTrue(relay(f.outbox,publisher).relayOne());assertEquals("PUBLISHED",f.state(id));
        }
        try(var consumer=consumer()) {
            consumer.subscribe(List.of(topic));
            var adapter=new BillingProjectionConsumer(()->consumer,f.projection,topic,new SimpleMeterRegistry());
            for(var record:poll(consumer,1))adapter.process(consumer,record);
            assertEquals(1,f.count());assertEquals("0.200000",f.total().toPlainString());
        }
    }
    @Test void runningConsumerReplaysReverseVersionsAndStopsOnPoisonWithoutAck() throws Exception {
        var first=f.event(f.capture(1,"0.000001"));var later=f.event(f.capture(1,"0.100000"));
        try(var publisher=publisher()) {publisher.publish(later);publisher.publish(first);publisher.publish(first);}
        var metrics=new SimpleMeterRegistry();
        var worker=new BillingProjectionConsumer(this::consumer,f.projection,topic,metrics);
        worker.start();
        try {
            await(()->f.count()==2,20);assertEquals("0.100001",f.total().toPlainString());
            await(()->metrics.counter("ad.billing.projection.duplicate").count()>0,10);
            try(var raw=new KafkaProducer<String,String>(BillingEventConfiguration.producerProperties(env))) {
                var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)BillingEventsFixture.JSON.readTree(first.payload());
                invalid.put("schemaVersion",2);
                var metadata=raw.send(new ProducerRecord<>(topic,first.key(),invalid.toString())).get(10,TimeUnit.SECONDS);
                await(()->!worker.isRunning(),10);
                try(var inspection=consumer()) {
                    var offset=inspection.committed(Set.of(new TopicPartition(topic,metadata.partition()))).get(new TopicPartition(topic,metadata.partition()));
                    assertNotNull(offset);assertEquals(metadata.offset(),offset.offset());
                }
                assertEquals(2,f.count());
            }
        } finally {worker.stop();}
        assertFalse(worker.isRunning());
    }
    static void await(java.util.function.BooleanSupplier condition,int seconds) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(50);
        assertTrue(condition.getAsBoolean());
    }
    /** 官方镜像与 dev_infra recsys profile 同版本；动态宿主端口在启动前写入临时容器脚本。 */
    static class KafkaFixture extends GenericContainer<KafkaFixture> {
        KafkaFixture() {
            super("apache/kafka:3.7.1");
            withExposedPorts(19092);
            withEnv(Map.of("KAFKA_NODE_ID","1","KAFKA_PROCESS_ROLES","broker,controller",
                    "KAFKA_CONTROLLER_QUORUM_VOTERS","1@localhost:9093",
                    "KAFKA_LISTENERS","INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:19092",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP","INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME","INTERNAL","KAFKA_CONTROLLER_LISTENER_NAMES","CONTROLLER",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR","1","KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS","0",
                    "KAFKA_AUTO_CREATE_TOPICS_ENABLE","false"));
            withCommand("/bin/bash","-c","while [ ! -f /tmp/billing-kafka-start ]; do sleep 0.1; done; . /tmp/billing-kafka-start; exec /etc/kafka/docker/run");
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*",1).withStartupTimeout(Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(InspectContainerResponse info) {
            copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://"+bootstrap()+"'\n",0644),
                    "/tmp/billing-kafka-start");
        }
        String bootstrap(){return getHost()+":"+getMappedPort(19092);}
    }
}
