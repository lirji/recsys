package com.recsys.behavior.exposure;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 配置默认关闭且无法通过普通覆盖属性降低传输上限。 */
@Tag("natural-exposure")
class NaturalEventConfigurationTest {
    @Test void disabledNeedsNoSchemaKeysOrBroker() {
        new ApplicationContextRunner().withUserConfiguration(NaturalEventConfiguration.class).run(ctx -> {
            assertNull(ctx.getStartupFailure()); assertFalse(ctx.containsBean("naturalOutboxRepository"));
            assertFalse(ctx.containsBean("naturalKafkaPublisher")); assertFalse(ctx.containsBean("naturalOutboxJob"));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"", "behavior-events", "ad-billing-events", " natural-impression-accepted-v1", "natural-impression-accepted-v1.", "natural-impression-accepted-v1.bad.dot"})
    void refusesCrossSourceOrMalformedTopics(String topic) {
        assertThrows(IllegalArgumentException.class, () -> new NaturalEventConfiguration.Properties(true, topic));
    }

    @Test void preservesConnectionSecurityAndForcesBoundedReliableProducer() {
        var env = new MockEnvironment().withProperty("spring.kafka.bootstrap-servers", "isolated:19092")
                .withProperty("spring.kafka.properties.security.protocol", "SASL_SSL")
                .withProperty("spring.kafka.properties.sasl.mechanism", "SCRAM-SHA-256")
                .withProperty("spring.kafka.producer.properties.transactional.id", "must-remove")
                .withProperty("spring.kafka.producer.acks", "0")
                .withProperty("spring.kafka.producer.properties.enable.idempotence", "false")
                .withProperty("spring.kafka.producer.properties.max.block.ms", "999999")
                .withProperty("spring.kafka.producer.properties.max.in.flight.requests.per.connection", "10");
        var p = NaturalEventConfiguration.producerProperties(env);
        assertEquals("SASL_SSL", p.get("security.protocol")); assertEquals("SCRAM-SHA-256", p.get("sasl.mechanism"));
        assertEquals(java.util.List.of("isolated:19092"), p.get("bootstrap.servers"));
        assertFalse(p.containsKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG));
        assertEquals("all", p.get(ProducerConfig.ACKS_CONFIG)); assertEquals(true, p.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertEquals(3, p.get(ProducerConfig.RETRIES_CONFIG)); assertEquals(1, p.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
        assertEquals(2000, p.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)); assertEquals(5000, p.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
        assertEquals(2000, p.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)); assertEquals(0, p.get(ProducerConfig.LINGER_MS_CONFIG));
        assertEquals(1048576L, p.get(ProducerConfig.BUFFER_MEMORY_CONFIG)); assertEquals(65536, p.get(ProducerConfig.MAX_REQUEST_SIZE_CONFIG));
        assertEquals("natural-impression-accepted-v1", new NaturalEventConfiguration.Properties(true, null).topic());
        new NaturalEventConfiguration.Properties(true, "natural-impression-accepted-v1.isolated_1");
        assertThrows(IllegalArgumentException.class, () -> new NaturalEventConfiguration.Properties(true, "natural-impression-accepted-v1." + "a".repeat(250)));
    }
}
