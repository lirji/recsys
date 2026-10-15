package com.recsys.ad.billing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 唯一 topic 与临时 Kafka/PG，不对共享 dev_infra 停机或清数据。 */
@Tag("money-chain") @Testcontainers
class ClickEventsKafkaTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final BillingEventsKafkaTest.KafkaFixture KAFKA=new BillingEventsKafkaTest.KafkaFixture();
    ClickEventsFixture f;
    String topic,group;
    MockEnvironment env;
    @BeforeEach void setup() throws Exception {
        f=new ClickEventsFixture(PG);topic="billing-"+UUID.randomUUID();group="billing-projection-"+UUID.randomUUID();
        env=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers",KAFKA.bootstrap())
                .withProperty("recsys.ad.billing.click-events.topic",topic).withProperty("recsys.ad.billing.click-events.group",group);
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(topic,2,(short)1).configs(Map.of(
                    "cleanup.policy","delete","retention.ms","-1","retention.bytes","-1")))).all().get(15,TimeUnit.SECONDS);
        }
    }
    BillingKafkaPublisher publisher() {return new BillingKafkaPublisher(new KafkaProducer<>(BillingEventConfiguration.producerProperties(env)),topic);}
    KafkaConsumer<String,String> consumer() {return new KafkaConsumer<>(ClickEventConfiguration.consumerProperties(env));}
    BillingOutboxRelay relay(BillingOutboxRepository repo,BillingKafkaPublisher publisher) {
        return new BillingOutboxRelay(repo,publisher,f.codec::validatePending,new SimpleMeterRegistry(),BillingOutboxRepository.EventFamily.CLICK);
    }
    List<ConsumerRecord<String,String>> poll(Consumer<String,String> consumer,int expected) {
        var result=new ArrayList<ConsumerRecord<String,String>>();long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(result.size()<expected && System.nanoTime()<until)consumer.poll(Duration.ofMillis(200)).forEach(result::add);
        assertTrue(result.size()>=expected,"records="+result.size());return result;
    }
    @Test void bothEventFamiliesAssembleAndDrainWhileAllNewBillingAdmissionsAreOff() throws Exception {
        String cpmTopic=topic+"-cpm";
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(cpmTopic,2,(short)1))).all().get(15,TimeUnit.SECONDS);
        }
        var cpmId=f.cpm.capture(1,"0.200000");var clickId=f.capture(1,"0.100001");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(BillingEventConfiguration.class,ClickEventConfiguration.class)
                .withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.receipts-enabled=false",
                        "recsys.ad.billing.clicks-enabled=false","recsys.ad.billing.click-settlement-enabled=false",
                        "recsys.ad.billing.click-receipt-admission-enabled=false","recsys.ad.billing.click-preparation-admission-enabled=false",
                        "recsys.ad.serving.mode=grpc","spring.application.name=recsys-ad-serving",
                        "spring.kafka.bootstrap-servers="+KAFKA.bootstrap(),"recsys.ad.billing.events.relay-enabled=true",
                        "recsys.ad.billing.events.projection-enabled=true","recsys.ad.billing.events.topic="+cpmTopic,
                        "recsys.ad.billing.events.group="+group+"-cpm","recsys.ad.billing.click-events.relay-enabled=true",
                        "recsys.ad.billing.click-events.projection-enabled=true","recsys.ad.billing.click-events.topic="+topic,
                        "recsys.ad.billing.click-events.group="+group)
                .withBean("adDbJdbc",org.springframework.jdbc.core.JdbcTemplate.class,()->f.jdbc)
                .withBean("adDbDataSource",javax.sql.DataSource.class,()->f.ds)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,()->ClickEventsFixture.JSON)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,SimpleMeterRegistry::new)
                .run(context->{
                    org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
                    assertEquals(2,context.getBeansOfType(BillingOutboxRepository.class).size());
                    assertEquals(2,context.getBeansOfType(BillingOutboxRelay.class).size());
                    assertEquals(2,context.getBeansOfType(BillingProjectionConsumer.class).size());
                    await(()->f.count()==1 && f.cpm.count()==1,20);
                    await(()->"PUBLISHED".equals(f.state(clickId)) && "PUBLISHED".equals(f.cpm.state(cpmId)),10);
                    assertEquals("0.100001",f.total().toPlainString());assertEquals("0.200000",f.cpm.total().toPlainString());
                });
    }
    @Test void committedButUnsentThenAckBeforeMarkCrashResendsAndProjectsOnce() throws Exception {
        UUID id=f.capture(1,"0.123456");
        assertEquals("READY",f.state(id));assertEquals(0,f.count());
        try(var publisher=publisher()) {
            var failAfterAck=new BillingOutboxRepository(f.jdbc,BillingOutboxRepository.EventFamily.CLICK) {
                @Override public boolean published(Pending event) {throw new IllegalStateException("crash after broker ACK");}
            };
            assertThrows(IllegalStateException.class,()->relay(failAfterAck,publisher).relayOne());
            assertEquals("SENDING",f.state(id));
            // 模拟旧进程没有标记即消失，数据库租约到期由新 relay 接管。
            f.due(id);assertTrue(relay(f.outbox,publisher).relayOne());assertEquals("PUBLISHED",f.state(id));
            try(var consumer=consumer()) {
                consumer.subscribe(List.of(topic));var records=poll(consumer,2);
                var adapter=new BillingProjectionConsumer(()->consumer,f.projection::apply,topic,new SimpleMeterRegistry(),BillingOutboxRepository.EventFamily.CLICK);
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
            var adapter=new BillingProjectionConsumer(()->failAck,f.projection::apply,topic,new SimpleMeterRegistry(),BillingOutboxRepository.EventFamily.CLICK);
            assertThrows(org.apache.kafka.common.KafkaException.class,()->adapter.process(failAck,record));
            assertEquals(1,f.count());
            assertNull(first.committed(Set.of(new TopicPartition(topic,record.partition()))).get(new TopicPartition(topic,record.partition())));
        }
        try(var restarted=consumer()) {
            restarted.subscribe(List.of(topic));var record=poll(restarted,1).getFirst();
            assertEquals(e.payload(),record.value());
            var adapter=new BillingProjectionConsumer(()->restarted,f.projection::apply,topic,new SimpleMeterRegistry(),BillingOutboxRepository.EventFamily.CLICK);
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
                    "SELECT published_at FROM ad_click_outbox WHERE event_id=?",java.sql.Timestamp.class,id));
            f.due(id);assertTrue(relay(f.outbox,publisher).relayOne());assertEquals("PUBLISHED",f.state(id));
        }
        try(var consumer=consumer()) {
            consumer.subscribe(List.of(topic));
            var adapter=new BillingProjectionConsumer(()->consumer,f.projection::apply,topic,new SimpleMeterRegistry(),BillingOutboxRepository.EventFamily.CLICK);
            for(var record:poll(consumer,1))adapter.process(consumer,record);
            assertEquals(1,f.count());assertEquals("0.200000",f.total().toPlainString());
        }
    }
    @Test void runningConsumerReplaysReverseVersionsAndStopsOnPoisonWithoutAck() throws Exception {
        var first=f.event(f.capture(1,"0.000001"));var later=f.event(f.capture(1,"0.100000"));
        try(var publisher=publisher()) {publisher.publish(later);publisher.publish(first);publisher.publish(first);}
        var metrics=new SimpleMeterRegistry();
        var worker=new BillingProjectionConsumer(this::consumer,f.projection::apply,topic,metrics,BillingOutboxRepository.EventFamily.CLICK);
        worker.start();
        try {
            await(()->f.count()==2,20);assertEquals("0.100001",f.total().toPlainString());
            await(()->metrics.counter("ad.billing.click.projection.duplicate").count()>0,10);
            try(var raw=new KafkaProducer<String,String>(BillingEventConfiguration.producerProperties(env))) {
                var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)ClickEventsFixture.JSON.readTree(first.payload());
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
}
