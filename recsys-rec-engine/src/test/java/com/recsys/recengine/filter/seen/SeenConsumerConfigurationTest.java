package com.recsys.recengine.filter.seen;

import com.recsys.recengine.filter.SeenItemsConsumer;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.ContainerProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 配置断言不代替真实offset/Redis验收，补默认关闭与严格解析边界。 */
@Tag("behavior-reliability")
class SeenConsumerConfigurationTest {
    @Test void defaultOffCreatesNoSeenAdminTablesFactoryOrThreads() {
        new ApplicationContextRunner().withUserConfiguration(SeenItemsConsumer.class,SeenConsumerConfiguration.class).run(c->{
            assertNull(c.getStartupFailure());assertNotNull(c.getBean(SeenItemsConsumer.class));
            assertEquals(0,c.getBeansOfType(SeenRecoveryRepository.class).size());
            assertEquals(0,c.getBeansOfType(SeenOffsetInitializer.class).size());
            assertFalse(c.containsBean("seenKafkaListenerContainerFactory"));
        });
    }
    @Test void allOffStillRejectsCrossStreamTopicsAndSharedGroups() {
        for(String property:List.of("recsys.behavior.seen.live-topic=behavior-history-recovery-v1",
                "recsys.behavior.seen.history-topic=behavior-accepted-v1","recsys.behavior.seen.live-group=recsys-rec-engine-seen",
                "recsys.behavior.seen.history-group=recsys-rec-engine-seen-live-v1","recsys.behavior.seen.live-topic=behavior-accepted-v1."))
            new ApplicationContextRunner().withUserConfiguration(SeenItemsConsumer.class,SeenConsumerConfiguration.class)
                .withPropertyValues(property).run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void hardPropertiesOverrideUnsafeGlobalKafkaAndErrorHandlerNeverRecovers() {
        var properties=new KafkaProperties();properties.getConsumer().setEnableAutoCommit(true);properties.getConsumer().setAutoOffsetReset("latest");
        var p=SeenConsumerConfiguration.consumerProperties(properties);
        assertEquals(false,p.get("enable.auto.commit"));assertEquals("none",p.get("auto.offset.reset"));assertEquals(1,p.get("max.poll.records"));
        try(var handler=new SeenConsumerErrorHandler(mock(SeenRecoveryRepository.class))) {
            assertFalse(handler.isAckAfterHandle());assertTrue(handler.seeksAfterHandling());assertEquals(3,handler.queueCapacity());
            assertThrows(IllegalArgumentException.class,()->handler.setAckAfterHandle(true));
            var cp=SeenConsumerConfiguration.factory(p,handler).getContainerProperties();
            assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE,cp.getAckMode());assertTrue(cp.isSyncCommits());assertFalse(cp.isAsyncAcks());
            assertEquals(0,cp.getCommitRetries());assertEquals(ContainerProperties.AssignmentCommitOption.NEVER,cp.getAssignmentCommitOption());
        }
    }
    @Test void strictDecoderRejectsAmbiguousIdsUtf8AndUnknownFieldsWithoutLeakingOriginal() {
        var decoder=new SeenMessageDecoder();
        for(String invalid:List.of("null","{}","{\"userId\":1.0,\"itemId\":2,\"action\":\"CLICK\"}",
                "{\"userId\":\"1\",\"itemId\":2,\"action\":\"CLICK\"}","{\"userId\":1,\"userId\":2,\"itemId\":3,\"action\":\"CLICK\"}",
                "{\"userId\":1,\"itemId\":2,\"action\":\"UNKNOWN\"}","{\"userId\":1,\"itemId\":2,\"action\":\"CLICK\",\"schemaVersion\":1}",
                "{\"userId\":1,\"itemId\":2,\"action\":\"CLICK\"} {}"))
            assertEquals("INVALID_DATA",decoder.decode(null,invalid.getBytes(StandardCharsets.UTF_8)).classification());
        assertEquals("INVALID_DATA",decoder.decode(null,new byte[]{(byte)0xc0,(byte)0xaf}).classification());
        assertEquals("INVALID_DATA",decoder.decode(null,null).classification());
        assertEquals("OVERSIZE",decoder.decode(null,new byte[32769]).error());
        assertNotEquals(SeenMessageDecoder.digest(null,null),SeenMessageDecoder.digest(new byte[0],null));
    }
}
