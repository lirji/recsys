package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import com.recsys.common.behavior.*;
import com.recsys.platform.behavior.LegacyBehaviorCodec;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.apache.kafka.clients.producer.ProducerConfig;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 配置组合和wire严格性；不把装配测试当成真实broker或数据库证据。 */
@Tag("behavior-reliability")
class BehaviorEventConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(BehaviorEventConfiguration.class);
    @Test void defaultsAndAllOldCombinationsNeedNoNewDatabaseAndConflictsFailClosed() {
        for(boolean use:List.of(false,true)) for(boolean publish:List.of(false,true)) {
            runner.withPropertyValues("recsys.behavior.use-kafka="+use,"recsys.behavior.publish-events="+publish).run(c->{
                assertNull(c.getStartupFailure());assertFalse(c.getBean(BehaviorEventConfiguration.Properties.class).enabled());
                assertEquals(0,c.getBeansOfType(BehaviorEventRepository.class).size());assertEquals(0,c.getBeansOfType(BehaviorEventWorker.class).size());
            });
            if(use || publish) for(String flag:List.of("capture-enabled","relay-enabled","history-relay-enabled")) {
                // 直接核对冲突原因，不能让缺DataSource偶然制造相同的启动失败。
                var env=new MockEnvironment().withProperty("recsys.behavior.use-kafka",String.valueOf(use)).withProperty("recsys.behavior.publish-events",String.valueOf(publish));
                var p=new BehaviorEventConfiguration.Properties(flag.equals("capture-enabled"),flag.equals("relay-enabled"),flag.equals("history-relay-enabled"),null,null);
                var ex=assertThrows(IllegalArgumentException.class,()->new BehaviorEventConfiguration().behaviorEventMode(p,env));
                assertEquals("可靠行为模式不能同时开启旧发布",ex.getMessage());
            }
        }
        runner.withPropertyValues("recsys.behavior.events.capture-enabled=true").run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void topicNamespacesAndSafetyPropertiesCannotBeOverridden() {
        for(String topic:List.of("behavior-events","natural-impression-accepted-v1","behavior-history-recovery-v1","behavior-accepted-v1.","behavior-accepted-v1.x/y")) {
            assertThrows(IllegalArgumentException.class,()->BehaviorEventConfiguration.validateTopic(Destination.LIVE,topic));
        }
        BehaviorEventConfiguration.validateTopic(Destination.LIVE,"behavior-accepted-v1.test_1");
        for(String topic:List.of("behavior-accepted-v1","behavior-history-recovery-v1.test","natural-impression-accepted-v1.test")) {
            runner.withPropertyValues("recsys.behavior.publish-events=true","recsys.behavior.topic="+topic).run(c->assertNotNull(c.getStartupFailure()));
        }
        var p=BehaviorEventConfiguration.producerProperties(new MockEnvironment().withProperty("spring.kafka.producer.acks","0").withProperty("spring.kafka.producer.properties.transactional.id","bad").withProperty("spring.kafka.properties.security.protocol","SASL_SSL"));
        assertEquals("all",p.get(ProducerConfig.ACKS_CONFIG));assertEquals(true,p.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));assertEquals(1,p.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
        assertEquals(5000,p.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));assertEquals(1048576L,p.get(ProducerConfig.BUFFER_MEMORY_CONFIG));assertEquals("SASL_SSL",p.get("security.protocol"));assertFalse(p.containsKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG));
    }
    @Test void strictWireRejectsUnknownVersionKeysDuplicatesPrecisionLossAndSources() {
        var codec=new LegacyBehaviorCodec();String source="00112233-4455-6677-8899-aabbccddeeff";
        String snapshot="{\"id\":1,\"user_id\":9223372036854775807,\"item_id\":42,\"action\":\"RATING\",\"value\":4.5,\"scene\":null,\"bucket\":null,\"exposure_id\":null,\"ts\":\"2001-02-03T04:05:06.123456\"}";
        String payload=codec.capture(source,snapshot,"2026-10-16T10:00:00.123456Z",LegacyBehaviorAccepted.Origin.LEGACY_ROW,null);
        var event=codec.decode(Long.toString(Long.MAX_VALUE),payload);assertNull(event.behavior().ts());assertEquals("2001-02-03T04:05:06.123456",event.sourceTsLocal());
        for(String invalid:List.of(payload.replace("\"schemaVersion\":1","\"schemaVersion\":2"),payload.replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),
                payload.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),payload.replace("\"9223372036854775807\"","9223372036854775807"),
                payload.replace("\"UNKNOWN\"","\"NOT_ATTEMPTED\""),payload.replace(".123456\"",".123456Z\""),payload+"{}",
                payload.replace("\"value\":4.5","\"value\":\"NaN\""),payload.replace("\"value\":4.5","\"value\":1e999"),payload.replace("\"ts\":null","\"ts\":1"))) {
            assertThrows(LegacyBehaviorCodec.InvalidEvent.class,()->codec.decode(Long.toString(Long.MAX_VALUE),invalid));
        }
        assertThrows(LegacyBehaviorCodec.InvalidEvent.class,()->codec.decode("7",payload));
    }
}
