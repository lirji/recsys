package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 显式历史清单的真实history流恢复；测试Sink只验证原事件重复应用，不代表Seen完成。 */
@Tag("behavior-reliability") @Testcontainers
class BehaviorHistoryKafkaTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final BehaviorEventsKafkaTest.KafkaFixture KAFKA=new BehaviorEventsKafkaTest.KafkaFixture();
    BehaviorHistoryFixture f;
    String topic;
    MockEnvironment environment;

    @BeforeEach void setup() throws Exception {
        f=new BehaviorHistoryFixture(PG);
        topic="behavior-history-recovery-v1.t"+UUID.randomUUID().toString().replace("-", "");
        environment=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers",KAFKA.bootstrap());
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1).configs(Map.of("cleanup.policy","delete","retention.ms","-1"))))
                    .all().get(10,TimeUnit.SECONDS);
        }
    }
    BehaviorKafkaPublisher publisher() {
        return new BehaviorKafkaPublisher(new KafkaProducer<>(BehaviorEventConfiguration.producerProperties(environment)),Destination.HISTORY,topic);
    }
    BehaviorEventRelay relay(BehaviorEventsFixture owner,BehaviorKafkaPublisher publisher) {
        return new BehaviorEventRelay(owner.repo,publisher,owner.codec,new SimpleMeterRegistry(),Destination.HISTORY);
    }

    @Test void brokerOutageKeepsCapturedDistinctFromPublishedAndDeletedSourceRecoversOriginalHistory() throws Exception {
        UUID command=UUID.randomUUID();f.seed(1);String id,payload;
        try(var publisher=publisher()) {
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {
                f.register(command,1L);var captured=f.history().advanceHistory(command);
                assertEquals("COMPLETE",captured.command().status());assertEquals(1L,captured.resultCounts().get("CAPTURED"));
                assertEquals(0L,captured.deliveryCounts().get("PUBLISHED"));id=captured.items().getFirst().eventId();payload=f.payload(id);
                assertTrue(relay(f,publisher).relayOne());assertEquals("READY",f.state(id));
                assertEquals(0L,f.history().historyReport(command).deliveryCounts().get("PUBLISHED"));
                f.jdbc.update("DELETE FROM user_behavior WHERE id=1");
            } finally {KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();}
        }
        f.due(id);
        var recovered=new BehaviorEventsFixture(new DriverManagerDataSource(f.ds.getUrl(),f.ds.getUsername(),f.ds.getPassword()));
        try(var publisher=publisher()) {assertTrue(relay(recovered,publisher).relayOne());}
        assertEquals(1L,f.history().historyReport(command).deliveryCounts().get("PUBLISHED"));
        assertEquals("CAPTURED",f.history().historyReport(command).items().getFirst().status());
        verifyRecords(id,payload,1);
    }

    @Test void acknowledgedButUnmarkedHistoryRetriesOriginalBytesAfterRealLeaseAndKeepsOneEffect() throws Exception {
        UUID command=UUID.randomUUID();f.seed(1);f.register(command,1L);String id=f.history().advanceHistory(command).items().getFirst().eventId();
        String payload=f.payload(id);var old=f.repo.claim(Destination.HISTORY).orElseThrow();
        try(var publisher=publisher()) {publisher.publish(old);} // broker ACK后丢失DB标记，保留真实租约。
        assertEquals(1L,f.history().historyReport(command).deliveryCounts().get("SENDING"));
        assertEquals(0L,f.history().historyReport(command).deliveryCounts().get("PUBLISHED"));
        f.due(id);
        var recovered=new BehaviorEventsFixture(new DriverManagerDataSource(f.ds.getUrl(),f.ds.getUsername(),f.ds.getPassword()));
        try(var publisher=publisher()) {assertTrue(relay(recovered,publisher).relayOne());}
        assertFalse(f.repo.published(old));assertEquals(1L,f.history().historyReport(command).deliveryCounts().get("PUBLISHED"));
        verifyRecords(id,payload,2);
    }

    private void verifyRecords(String id,String payload,int expected) throws Exception {
        var sink=new BehaviorEventsKafkaTest.Sink(f);var offsets=new HashSet<Long>();
        try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",KAFKA.bootstrap(),"group.id","history-"+UUID.randomUUID(),
                "key.deserializer",StringDeserializer.class,"value.deserializer",StringDeserializer.class,"enable.auto.commit",false,
                "auto.offset.reset","earliest","allow.auto.create.topics",false,"default.api.timeout.ms",10000,"max.poll.records",10))) {
            consumer.subscribe(List.of(topic));long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(offsets.size()<expected && System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofMillis(200))) {
                assertEquals(topic,record.topic());assertEquals("7",record.key());assertEquals(payload,record.value());
                var event=f.codec.decode(record.key(),record.value());assertEquals(id,event.eventId());assertNull(event.behavior().ts());
                assertEquals("2001-02-03T04:05:06.123456",event.sourceTsLocal());assertEquals("UNKNOWN",event.priorPublication().name());
                assertEquals("LEGACY_ROW",event.origin().name());sink.apply(record);offsets.add(record.offset());
                consumer.commitSync(Map.of(new TopicPartition(topic,record.partition()),new OffsetAndMetadata(record.offset()+1)),Duration.ofSeconds(5));
                String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
                System.out.println("S15B2_KAFKA eventId="+id+" payload_sha256="+hash+" topic="+topic+" partition="+record.partition()+" offset="+record.offset()+" published="+f.state(id)+" fixture_effects="+sink.count());
            }
        }
        assertEquals(expected,offsets.size());assertEquals(1,sink.count());
    }
}
