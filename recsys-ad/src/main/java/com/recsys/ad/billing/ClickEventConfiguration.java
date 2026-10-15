package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.billing.BillingOutboxRepository.EventFamily;
import com.recsys.common.ad.billing.ClickSettlement.CapturedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import javax.sql.DataSource;
import java.util.Map;

/** 点击事实发送与投影独立于新收费开关，复用有界传输机制而保留独立数据/版本契约。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(name="recsys.ad.billing.sandbox-enabled",havingValue="true")
public class ClickEventConfiguration {
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name="recsys.ad.billing.sandbox-enabled",havingValue="true")
    @ConditionalOnExpression("${recsys.ad.billing.click-events.relay-enabled:false} or ${recsys.ad.billing.click-events.projection-enabled:false}")
    static class Shared {
        /** 无需启用新收费或签名密钥即可续发，但必须具备已交付的原事实及投影迁移。 */
        @Bean BillingOutboxRepository clickOutboxRepository(@Qualifier("adDbJdbc") JdbcTemplate jdbc,ObjectMapper json) {
            new BudgetRepository(jdbc).verifySchema(); new ClickQuoteRepository(jdbc).verifySchema();
            new ClickSettlementRepository(jdbc,json).verifySchema();
            var repository=new BillingOutboxRepository(jdbc,EventFamily.CLICK);repository.verifySchema();return repository;
        }
        @Bean ClickEventCodec clickEventCodec(ObjectMapper json) { return new ClickEventCodec(json); }
    }
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.click-events.relay-enabled"},havingValue="true")
    static class Relay {
        /** 复用原强制ACK/幂等/超时参数，topic独立并显式预建。 */
        @Bean(destroyMethod="close") BillingKafkaPublisher clickKafkaPublisher(Environment env) {
            return new BillingKafkaPublisher(new KafkaProducer<>(BillingEventConfiguration.producerProperties(env)),topic(env));
        }
        @Bean BillingOutboxRelay clickOutboxRelay(@Qualifier("clickOutboxRepository") BillingOutboxRepository repo,
                @Qualifier("clickKafkaPublisher") BillingKafkaPublisher publisher,ClickEventCodec codec,MeterRegistry metrics) {
            return new BillingOutboxRelay(repo,publisher,codec::validatePending,metrics,EventFamily.CLICK);
        }
        @Bean(destroyMethod="shutdown") ThreadPoolTaskScheduler clickOutboxScheduler() {
            var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("ad-click-outbox-");
            scheduler.setRemoveOnCancelPolicy(true);scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
        }
        @Bean(initMethod="start",destroyMethod="stop") BillingOutboxJob clickOutboxJob(@Qualifier("clickOutboxRepository") BillingOutboxRepository repo,
                @Qualifier("clickOutboxRelay") BillingOutboxRelay relay,@Qualifier("clickOutboxScheduler") ThreadPoolTaskScheduler scheduler,MeterRegistry metrics) {
            return new BillingOutboxJob(repo,relay,scheduler,metrics,EventFamily.CLICK);
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Conditional(SandboxOwnerCondition.class)
    @ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.click-events.projection-enabled"},havingValue="true")
    static class Projection {
        @Bean ClickProjectionService clickProjectionService(@Qualifier("adDbJdbc") JdbcTemplate jdbc,
                @Qualifier("adDbDataSource") DataSource ds,ClickEventCodec codec,@Qualifier("clickOutboxRepository") BillingOutboxRepository verifiedSchema) {
            return new ClickProjectionService(new ClickProjectionRepository(jdbc),codec,BudgetConfiguration.transactions(ds,5));
        }
        @Bean(initMethod="start",destroyMethod="stop") BillingProjectionConsumer clickProjectionConsumer(ClickProjectionService service,Environment env,MeterRegistry metrics) {
            var properties=consumerProperties(env);
            return new BillingProjectionConsumer(()->new KafkaConsumer<>(properties),service::apply,topic(env),metrics,EventFamily.CLICK);
        }
    }
    /** 消费组和topic与CPM独立，其余有界连接与手动确认参数共用。 */
    static Map<String,Object> consumerProperties(Environment env) {
        String group=env.getProperty("recsys.ad.billing.click-events.group","recsys-sandbox-click-projection-v1");
        return BillingEventConfiguration.consumerProperties(env,group);
    }
    static String topic(Environment env) {
        String topic=env.getProperty("recsys.ad.billing.click-events.topic",CapturedEvent.TOPIC);
        if(!topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,200}") || topic.equals(".") || topic.equals("..")) throw new IllegalArgumentException("无效点击topic");
        return topic;
    }
}
