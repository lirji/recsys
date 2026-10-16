package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.exposure.NaturalReceipt.Event;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/** 默认关闭且独立于回执接收，停新增后仍可排空已接受自然事件。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recsys.natural.events.relay-enabled", havingValue = "true")
@EnableConfigurationProperties(NaturalEventConfiguration.Properties.class)
public class NaturalEventConfiguration {
    /** 物理 topic 只允许自然事件及隔离后缀，不允许误投旧行为或资金流。 */
    @ConfigurationProperties(prefix = "recsys.natural.events")
    public record Properties(boolean relayEnabled, String topic) {
        public Properties { topic = topic == null ? Event.TOPIC : topic; validateTopic(topic); }
    }

    /** 仓储独立核对 V001/V002，不依赖接收 bean 或密钥。 */
    @Bean public NaturalOutboxRepository naturalOutboxRepository(@Qualifier("sqlSessionTemplate") SqlSessionTemplate sessions,
                                                                 @Qualifier("dataSource") DataSource dataSource) {
        var repository = new NaturalOutboxRepository(sessions, new DataSourceTransactionManager(dataSource));
        repository.verifySchema();
        return repository;
    }

    /** 发送前严格验证原始事实，宿主 ObjectMapper 只提供已选时间模块。 */
    @Bean public NaturalEventCodec naturalEventCodec(ObjectMapper json) { return new NaturalEventCodec(json); }

    /** schema 先验证再创建客户端，配置失败不能启动后台发送。 */
    @Bean(destroyMethod = "close") public NaturalKafkaPublisher naturalKafkaPublisher(Environment environment, Properties props,
                                                                                     NaturalOutboxRepository verifiedSchema) {
        return new NaturalKafkaPublisher(new KafkaProducer<>(producerProperties(environment)), props.topic());
    }

    /** 每次领取、发送、标记遵循同一事件协议。 */
    @Bean public NaturalOutboxRelay naturalOutboxRelay(NaturalOutboxRepository repository, NaturalKafkaPublisher publisher,
                                                       NaturalEventCodec codec, MeterRegistry metrics) {
        return new NaturalOutboxRelay(repository, publisher, codec, metrics);
    }

    /** 专属线程及有界停机；依赖 producer 使 scheduler 先停止再关闭网络资源。 */
    @Bean(destroyMethod = "shutdown") public ThreadPoolTaskScheduler naturalOutboxScheduler(NaturalKafkaPublisher publisher) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("natural-outbox-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    /** 固定延迟在本轮结束后才排下一轮，避免任务积累。 */
    @Bean(initMethod = "start", destroyMethod = "stop") public NaturalOutboxJob naturalOutboxJob(
            NaturalOutboxRepository repository, NaturalOutboxRelay relay,
            @Qualifier("naturalOutboxScheduler") ThreadPoolTaskScheduler scheduler, MeterRegistry metrics) {
        return new NaturalOutboxJob(repository, relay, scheduler, metrics);
    }

    /** 保留 TLS/SASL 等受控连接属性，覆盖不能被降低的可靠性与资源上限。 */
    static Map<String, Object> producerProperties(Environment environment) {
        var kafka = Binder.get(environment).bind("spring.kafka", KafkaProperties.class).orElseGet(KafkaProperties::new);
        var properties = new HashMap<String, Object>(kafka.buildProducerProperties(null));
        properties.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.RETRIES_CONFIG, 3);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        properties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 1048576L);
        properties.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 65536);
        return properties;
    }

    static void validateTopic(String topic) {
        if (topic == null || topic.length() > 249 || !(topic.equals(Event.TOPIC)
                || topic.matches(java.util.regex.Pattern.quote(Event.TOPIC) + "\\.[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException("自然事件 topic 不满足来源隔离约束");
        }
    }
}
