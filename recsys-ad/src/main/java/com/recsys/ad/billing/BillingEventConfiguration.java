package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.ImpressionReceipt.CapturedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import javax.sql.DataSource;
import java.util.*;

/** 发送与消费可独立启停且默认关闭，沿用连接/安全配置并固定资金可靠性参数。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(name="recsys.ad.billing.sandbox-enabled",havingValue="true")
public class BillingEventConfiguration {
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name="recsys.ad.billing.sandbox-enabled",havingValue="true")
    @ConditionalOnExpression("${recsys.ad.billing.events.relay-enabled:false} or ${recsys.ad.billing.events.projection-enabled:false}")
    static class Shared {
        /** 事件能力可在停止新回执后单独续办，仍核验全部迁移历史。 */
        @Bean BillingOutboxRepository billingOutboxRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions,ObjectMapper json) {
            new BudgetRepository(sessions).verifySchema();new CpmQuoteRepository(sessions).verifySchema();
            new ReservationRepository(sessions).verifySchema();new PreparationRepository(sessions,json).verifySchema();
            new ImpressionReceiptRepository(sessions,json).verifySchema();
            var repository=new BillingOutboxRepository(sessions);repository.verifySchema();return repository;
        }
        @Bean BillingEventCodec billingEventCodec(ObjectMapper json){return new BillingEventCodec(json);}
    }
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.events.relay-enabled"},havingValue="true")
    static class Relay {
        @Bean(destroyMethod="close") BillingKafkaPublisher billingKafkaPublisher(Environment env) {
            return new BillingKafkaPublisher(new KafkaProducer<>(producerProperties(env)),topic(env));
        }
        @Bean BillingOutboxRelay billingOutboxRelay(@Qualifier("billingOutboxRepository") BillingOutboxRepository repo,@Qualifier("billingKafkaPublisher") BillingKafkaPublisher publisher,BillingEventCodec codec,MeterRegistry metrics) {
            return new BillingOutboxRelay(repo,publisher,codec,metrics);
        }
        @Bean(destroyMethod="shutdown") ThreadPoolTaskScheduler billingOutboxScheduler() {
            var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("ad-billing-outbox-");
            scheduler.setRemoveOnCancelPolicy(true);scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
        }
        @Bean(initMethod="start",destroyMethod="stop") BillingOutboxJob billingOutboxJob(@Qualifier("billingOutboxRepository") BillingOutboxRepository repo,@Qualifier("billingOutboxRelay") BillingOutboxRelay relay,
                @Qualifier("billingOutboxScheduler") ThreadPoolTaskScheduler scheduler,MeterRegistry metrics) {
            return new BillingOutboxJob(repo,relay,scheduler,metrics);
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.events.projection-enabled"},havingValue="true")
    static class Projection {
        @Bean BillingProjectionService billingProjectionService(@Qualifier("adDbJdbc") JdbcTemplate jdbc,
                @Qualifier("adDbDataSource") DataSource ds,BillingEventCodec codec,@Qualifier("billingOutboxRepository") BillingOutboxRepository verifiedSchema) {
            return new BillingProjectionService(new BillingProjectionRepository(jdbc),codec,BudgetConfiguration.transactions(ds,5));
        }
        @Bean(initMethod="start",destroyMethod="stop") BillingProjectionConsumer billingProjectionConsumer(BillingProjectionService service,Environment env,MeterRegistry metrics) {
            var properties=consumerProperties(env);
            return new BillingProjectionConsumer(()->new KafkaConsumer<>(properties),service,topic(env),metrics);
        }
    }

    /** 允许复用 TLS/SASL 配置；旧链路不能通过覆盖 acks/auto commit 降低本片保证。 */
    static Map<String,Object> producerProperties(Environment env) {
        var properties=new HashMap<String,Object>(kafka(env).buildProducerProperties(null));
        properties.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG,"all");properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        properties.put(ProducerConfig.RETRIES_CONFIG,3);properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,1);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,2000);properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,5000);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,2000);properties.put(ProducerConfig.LINGER_MS_CONFIG,0);
        properties.put(ProducerConfig.BUFFER_MEMORY_CONFIG,1048576L);properties.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG,65536);
        return properties;
    }
    /** 手动位点、有限 poll、独立 group；连接秘密不会写到应用日志。 */
    static Map<String,Object> consumerProperties(Environment env) {
        return consumerProperties(env,env.getProperty("recsys.ad.billing.events.group","recsys-sandbox-billing-projection-v1"));
    }
    /** 连接及安全参数共用，消费组由有限业务族的配置入口独立选择。 */
    static Map<String,Object> consumerProperties(Environment env,String group) {
        var properties=new HashMap<String,Object>(kafka(env).buildConsumerProperties(null));
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        properties.remove(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed");
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,300000);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,10);properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        if(!group.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}"))throw new IllegalArgumentException("无效账务 consumer group");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG,group);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,10000);properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);
        properties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,1048576);properties.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,1048576);
        return properties;
    }
    private static KafkaProperties kafka(Environment env){return Binder.get(env).bind("spring.kafka",KafkaProperties.class).orElseGet(KafkaProperties::new);}
    static String topic(Environment env) {
        String topic=env.getProperty("recsys.ad.billing.events.topic",CapturedEvent.TOPIC);
        if(!topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}") || topic.equals(".") || topic.equals(".."))throw new IllegalArgumentException("无效账务 topic");
        return topic;
    }
}
