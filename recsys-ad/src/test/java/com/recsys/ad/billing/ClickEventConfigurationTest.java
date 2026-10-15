package com.recsys.ad.billing;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** 专用开关/owner 以及可靠性参数不允许旧日志配置覆盖。 */
@Tag("money-chain")
class ClickEventConfigurationTest {
    @Test void defaultOffEvenWhenComponentScannerFindsNestedConfigurations() {
        var runner=new ApplicationContextRunner().withUserConfiguration(ClickEventConfiguration.class,
                ClickEventConfiguration.Shared.class,ClickEventConfiguration.Relay.class,ClickEventConfiguration.Projection.class);
        runner.run(c->assertThat(c).hasNotFailed().doesNotHaveBean(BillingOutboxRepository.class));
        runner.withPropertyValues("recsys.ad.billing.sandbox-enabled=true")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(BillingOutboxRepository.class));
        runner.withPropertyValues("recsys.ad.billing.click-events.relay-enabled=true","recsys.ad.billing.click-events.projection-enabled=true")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(BillingOutboxRepository.class));
        runner.withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.click-events.relay-enabled=true",
                "recsys.ad.billing.click-events.projection-enabled=true","recsys.ad.serving.mode=grpc","spring.application.name=recsys-rec-engine")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(BillingOutboxRepository.class));
        for(String owner:java.util.List.of("recsys-rec-engine","recsys-ad-serving")) {
            runner.withPropertyValues("recsys.ad.billing.sandbox-enabled=true","recsys.ad.billing.click-events.relay-enabled=true",
                    "recsys.ad.billing.receipts-enabled=false","spring.application.name="+owner)
                    .run(c->assertThat(c).hasFailed());
        }
    }
    @Test void ownAckOffsetAndBoundsOverrideUnsafeSharedDefaultsButKeepSecurity() {
        var env=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers","localhost:9092")
                .withProperty("spring.kafka.producer.acks","0").withProperty("spring.kafka.consumer.enable-auto-commit","true")
                .withProperty("spring.kafka.properties.security.protocol","SASL_SSL")
                .withProperty("spring.kafka.properties.sasl.mechanism","SCRAM-SHA-512")
                .withProperty("spring.kafka.producer.properties.transactional.id","old")
                .withProperty("spring.kafka.consumer.group-id","catalog");
        var producer=BillingEventConfiguration.producerProperties(env);
        assertEquals("all",producer.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(true,producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));assertFalse(producer.containsKey("transactional.id"));
        assertEquals(3,producer.get("retries"));assertEquals(5000,producer.get("delivery.timeout.ms"));
        assertEquals("SASL_SSL",producer.get("security.protocol"));
        var consumer=ClickEventConfiguration.consumerProperties(env);
        assertEquals(false,consumer.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals(false,consumer.get(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG));
        assertEquals(10,consumer.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
        assertEquals("recsys-sandbox-click-projection-v1",consumer.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals("SCRAM-SHA-512",consumer.get("sasl.mechanism"));
        assertThrows(IllegalArgumentException.class,()->ClickEventConfiguration.topic(env.withProperty("recsys.ad.billing.click-events.topic","bad topic")));
    }
}
