package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.billing.BillingOutboxRepository.EventFamily;
import com.recsys.common.ad.billing.ConversionSettlement.CapturedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import javax.sql.DataSource;
import java.util.Map;

/** CPA事实发送与投影独立于新收费开关，复用有界传输机制而保留独立数据/版本契约。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(name="recsys.ad.billing.sandbox-enabled",havingValue="true")
public class ConversionEventConfiguration {
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name="recsys.ad.billing.sandbox-enabled",havingValue="true")
    @ConditionalOnExpression("${recsys.ad.billing.conversion-events.relay-enabled:false} or ${recsys.ad.billing.conversion-events.projection-enabled:false}")
    static class Shared {
        /** 无需启用新收费或签名密钥即可续发，但必须具备已交付的原事实及投影迁移。 */
        @Bean BillingOutboxRepository conversionOutboxRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions, @Qualifier("adDbJdbc") JdbcTemplate jdbc,ObjectMapper json) {
            new BudgetRepository(sessions).verifySchema(); new ConversionQuoteRepository(jdbc).verifySchema();
            new ConversionSettlementRepository(jdbc,json).verifySchema();
            var repository=new BillingOutboxRepository(jdbc,EventFamily.CONVERSION);repository.verifySchema();return repository;
        }
        @Bean ConversionEventCodec conversionEventCodec(ObjectMapper json) { return new ConversionEventCodec(json); }
    }
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.conversion-events.relay-enabled"},havingValue="true")
    static class Relay {
        /** 复用原强制ACK/幂等/超时参数，topic独立并显式预建。 */
        @Bean(destroyMethod="close") BillingKafkaPublisher conversionKafkaPublisher(Environment env) {
            return new BillingKafkaPublisher(new KafkaProducer<>(BillingEventConfiguration.producerProperties(env)),topic(env));
        }
        @Bean BillingOutboxRelay conversionOutboxRelay(@Qualifier("conversionOutboxRepository") BillingOutboxRepository repo,
                @Qualifier("conversionKafkaPublisher") BillingKafkaPublisher publisher,ConversionEventCodec codec,MeterRegistry metrics) {
            return new BillingOutboxRelay(repo,publisher,codec::validatePending,metrics,EventFamily.CONVERSION);
        }
        @Bean(destroyMethod="shutdown") ThreadPoolTaskScheduler conversionOutboxScheduler() {
            var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("ad-conversion-outbox-");
            scheduler.setRemoveOnCancelPolicy(true);scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
        }
        @Bean(initMethod="start",destroyMethod="stop") BillingOutboxJob conversionOutboxJob(@Qualifier("conversionOutboxRepository") BillingOutboxRepository repo,
                @Qualifier("conversionOutboxRelay") BillingOutboxRelay relay,@Qualifier("conversionOutboxScheduler") ThreadPoolTaskScheduler scheduler,MeterRegistry metrics) {
            return new BillingOutboxJob(repo,relay,scheduler,metrics,EventFamily.CONVERSION);
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.conversion-events.projection-enabled"},havingValue="true")
    static class Projection {
        @Bean ConversionProjectionService conversionProjectionService(@Qualifier("adDbJdbc") JdbcTemplate jdbc,
                @Qualifier("adDbDataSource") DataSource ds,ConversionEventCodec codec,@Qualifier("conversionOutboxRepository") BillingOutboxRepository verifiedSchema) {
            return new ConversionProjectionService(new ConversionProjectionRepository(jdbc),codec,BudgetConfiguration.transactions(ds,5));
        }
        @Bean(initMethod="start",destroyMethod="stop") BillingProjectionConsumer conversionProjectionConsumer(ConversionProjectionService service,Environment env,MeterRegistry metrics) {
            var properties=consumerProperties(env);
            return new BillingProjectionConsumer(()->new KafkaConsumer<>(properties),service::apply,topic(env),metrics,EventFamily.CONVERSION);
        }
    }
    /** 消费组和topic与CPM独立，其余有界连接与手动确认参数共用。 */
    static Map<String,Object> consumerProperties(Environment env) {
        String group=env.getProperty("recsys.ad.billing.conversion-events.group","recsys-sandbox-conversion-projection-v1");
        return BillingEventConfiguration.consumerProperties(env,group);
    }
    static String topic(Environment env) {
        String topic=env.getProperty("recsys.ad.billing.conversion-events.topic",CapturedEvent.TOPIC);
        if(!topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}") || topic.equals(".") || topic.equals("..")) throw new IllegalArgumentException("无效CPAtopic");
        return topic;
    }
}
