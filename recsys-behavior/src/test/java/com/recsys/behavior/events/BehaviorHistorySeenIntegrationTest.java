package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import com.recsys.recengine.filter.*;
import com.recsys.recengine.filter.seen.*;
import com.recsys.persistence.spring.SpringSqlSessions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 原B2捕获/发布→真实HISTORY产品listener→Redis→Replica读模型；不使用Sink或反向Maven依赖。 */
@Tag("behavior-reliability") @Testcontainers
class BehaviorHistorySeenIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final BehaviorEventsKafkaTest.KafkaFixture KAFKA=new BehaviorEventsKafkaTest.KafkaFixture();
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Test void originalHistoryCaptureReliableRetryAndActualReplicaKeepGapsExplicit() throws Exception {
        var owner=new BehaviorHistoryFixture(PG);String topic="behavior-history-recovery-v1.chain-"+UUID.randomUUID(),group="history-chain-"+UUID.randomUUID();
        try(var admin=Admin.create(Map.of("bootstrap.servers",KAFKA.bootstrap(),"default.api.timeout.ms",5000,"request.timeout.ms",5000,"retries",3))) {
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1).configs(Map.of("cleanup.policy","delete","retention.ms","-1","max.message.bytes","1048576")))).all().get(10,TimeUnit.SECONDS);
            for(long id=1;id<=4;id++)owner.seed(id);
            owner.jdbc.update("UPDATE user_behavior SET action='IMPRESSION' WHERE id=2");owner.jdbc.update("UPDATE user_behavior SET action='INVALID' WHERE id=3");
            var command=UUID.randomUUID();owner.register(command,1L,2L,3L,4L);var captured=owner.history().advanceHistory(command);
            assertEquals("COMPLETED_WITH_ISSUES",captured.command().status());assertEquals(3L,captured.resultCounts().get("CAPTURED"));assertEquals(1L,captured.resultCounts().get("INVALID_SOURCE"));
            var original=owner.repo.claim(Destination.HISTORY).orElseThrow();assertEquals("1",owner.codec.decode(original.key(),original.payload()).behaviorId());String payload=original.payload();
            var env=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers",KAFKA.bootstrap());
            try(var publisher=new BehaviorKafkaPublisher(new KafkaProducer<>(BehaviorEventConfiguration.producerProperties(env)),Destination.HISTORY,topic)) {
                publisher.publish(original); // 真broker ACK后不mark，沿原30秒租约重发，不能造新事件。
                owner.due(original.eventId());
                var relay=new BehaviorEventRelay(owner.repo,publisher,owner.codec,new SimpleMeterRegistry(),Destination.HISTORY);
                for(int i=0;i<3;i++)assertTrue(relay.relayOne());assertFalse(relay.relayOne());
            }
            assertFalse(owner.repo.published(original));assertEquals(3L,owner.history().historyReport(command).deliveryCounts().get("PUBLISHED"));
            owner.jdbc.update("DELETE FROM user_behavior WHERE id=1");owner.jdbc.update("UPDATE user_behavior SET item_id=99 WHERE id=4");
            var inspect=UUID.randomUUID();owner.register(inspect,1L,2L,3L,4L);var gap=owner.history().advanceHistory(inspect);
            assertEquals("COMPLETED_WITH_ISSUES",gap.command().status());assertEquals(1L,gap.resultCounts().get("INVALID_SOURCE"));assertEquals(1L,gap.resultCounts().get("SOURCE_CHANGED"));assertEquals(2L,gap.resultCounts().get("ALREADY_CAPTURED"));
            var ownerBefore=owner.jdbc.queryForList("SELECT * FROM behavior_event_outbox ORDER BY event_id");
            // engine独立schema/事务，只读模型owner写；不让消费者访问behavior表。
            String schema="seen_chain_"+UUID.randomUUID().toString().replace("-","");owner.jdbc.execute("CREATE SCHEMA "+schema);
            var engine=new DriverManagerDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema,PG.getUsername(),PG.getPassword());
            try(var input=new ClassPathResource(SeenRecoveryRepository.MIGRATION).getInputStream();var connection=engine.getConnection();var statement=connection.createStatement()) {
                statement.execute("SET recsys.migration_sha256='"+SeenRecoveryRepository.migrationSha256()+"'");statement.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            }
            var sessions=new SqlSessionTemplate(SpringSqlSessions.create(engine,"classpath*:mapper/recengine/primary/SeenRecoveryMapper.xml"));
            var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(400)).shutdownTimeout(Duration.ofMillis(100))
                    .clientOptions(io.lettuce.core.ClientOptions.builder().socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofMillis(400)).build()).build()).build();
            var redisFactory=new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379)),client);redisFactory.afterPropertiesSet();redisFactory.start();
            try {
                long ready=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(true)try{admin.describeConsumerGroups(List.of(group)).all().get(5,TimeUnit.SECONDS);break;}
                catch(java.util.concurrent.ExecutionException failure){if(failure.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException)break;assertTrue(System.nanoTime()<ready);Thread.sleep(300);}
                var redis=new StringRedisTemplate(redisFactory);var engineJdbc=new JdbcTemplate(engine);
                new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(SeenItemsConsumer.class,SeenConsumerConfiguration.class,ReplicaSeenItemsSource.class)
                    .withBean("sqlSessionTemplate",SqlSessionTemplate.class,()->sessions).withBean(StringRedisTemplate.class,()->redis)
                    .withPropertyValues("spring.kafka.bootstrap-servers="+KAFKA.bootstrap(),"recsys.behavior.seen.recovery-enabled=true","recsys.behavior.seen-source=replica",
                        "recsys.behavior.seen.history-topic="+topic,"recsys.behavior.seen.history-group="+group)
                    .run(context->{
                        assertNull(context.getStartupFailure());var service=context.getBean("historySeenRecoveryService",SeenRecoveryService.class);var control=context.getBean("historySeenListenerControl",SeenListenerControl.class);
                        var init=UUID.randomUUID();service.initialize(init,SeenOffsetInitializer.Mode.EARLIEST_RETAINED,"owner","原历史真实链路");assertEquals("SUCCEEDED",service.advance(init).status());control.start();
                        var tp=new TopicPartition(topic,0);long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                        while(true){var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);if(offsets.get(tp)!=null && offsets.get(tp).offset()==4)break;assertTrue(System.nanoTime()<deadline);Thread.sleep(100);}
                        assertEquals(Set.of(42L),context.getBean(ReplicaSeenItemsSource.class).seenItems(7));
                        assertEquals(3,engineJdbc.queryForObject("SELECT count(*) FROM seen_record WHERE stream='HISTORY' AND status='APPLIED'",Integer.class));
                        assertEquals(1,engineJdbc.queryForObject("SELECT count(*) FROM seen_record WHERE status='NO_EFFECT'",Integer.class));
                        var copies=engineJdbc.queryForList("SELECT value_bytes FROM seen_record WHERE digest=?",SeenMessageDecoder.digest("7".getBytes(StandardCharsets.UTF_8),payload.getBytes(StandardCharsets.UTF_8)));
                        assertEquals(2,copies.size());for(var copy:copies)assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8),(byte[])copy.get("value_bytes"));
                        var event=owner.codec.decode("7",payload);assertNull(event.behavior().ts());assertEquals("2001-02-03T04:05:06.123456",event.sourceTsLocal());
                        assertEquals(2L,gap.resultCounts().get("ALREADY_CAPTURED"));assertEquals(ownerBefore,owner.jdbc.queryForList("SELECT * FROM behavior_event_outbox ORDER BY event_id"));
                        control.stop();System.out.println("C14_REAL_CHAIN captured=3 invalid_source=1 source_changed=1 published=3 kafka_records=4 applied=3 no_effect=1 replica_members=1 original_event_copies=2");
                    });
            }finally{redisFactory.destroy();}
        }
    }
}
