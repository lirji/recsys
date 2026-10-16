package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import com.recsys.common.behavior.LegacyBehaviorAccepted;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/** 三个开关独立默认关闭；旧兼容模式不连接新表，也不自动执行迁移。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(BehaviorEventConfiguration.Properties.class)
public class BehaviorEventConfiguration {
    @ConfigurationProperties(prefix="recsys.behavior.events")
    public record Properties(boolean captureEnabled, boolean relayEnabled, boolean historyRelayEnabled,
                             String topic, String historyTopic) {
        public Properties {
            topic=topic==null ? LegacyBehaviorAccepted.LIVE_TOPIC : topic;
            historyTopic=historyTopic==null ? LegacyBehaviorAccepted.HISTORY_TOPIC : historyTopic;
            validateTopic(Destination.LIVE,topic); validateTopic(Destination.HISTORY,historyTopic);
        }
        /** 任一可靠功能都不能与旧best-effort发布混用。 */
        public boolean enabled() { return captureEnabled || relayEnabled || historyRelayEnabled; }
    }
    /** 固定启动验证结果用于建立装配依赖，不创建无意义后台线程。 */
    public record CheckedMode(boolean enabled) { }
    @Bean public CheckedMode behaviorEventMode(Properties p, Environment env) {
        boolean old=env.getProperty("recsys.behavior.use-kafka",Boolean.class,false)
                || env.getProperty("recsys.behavior.publish-events",Boolean.class,false);
        if (p.enabled() && old) throw new IllegalArgumentException("可靠行为模式不能同时开启旧发布");
        String topic=env.getProperty("recsys.behavior.topic","behavior-events");
        if (old && (reserved(topic,LegacyBehaviorAccepted.LIVE_TOPIC) || reserved(topic,LegacyBehaviorAccepted.HISTORY_TOPIC)
                || reserved(topic,"natural-impression-accepted-v1"))) throw new IllegalArgumentException("旧格式不能投递保留事件流");
        return new CheckedMode(p.enabled());
    }
    private static boolean reserved(String topic,String logical) { return topic.equals(logical) || topic.startsWith(logical+"."); }
    /** 只有开启新能力时核对同源事务、真实schema和迁移摘要。 */
    @Bean @Conditional(Enabled.class)
    public BehaviorEventRepository behaviorEventRepository(@Qualifier("sqlSessionTemplate") SqlSessionTemplate sessions,
            @Qualifier("dataSource") DataSource source, CheckedMode mode) {
        var repository=new BehaviorEventRepository(sessions,new DataSourceTransactionManager(source));
        repository.verifySchema(); return repository;
    }
    /** 每条流至多一个专属worker/producer，关闭历史发送不影响在线待发。 */
    @Bean(initMethod="start",destroyMethod="close")
    @ConditionalOnProperty(name="recsys.behavior.events.relay-enabled",havingValue="true")
    public BehaviorEventWorker behaviorLiveWorker(BehaviorEventRepository repository, Properties props, Environment env, MeterRegistry metrics) {
        return new BehaviorEventWorker(repository,Destination.LIVE,props.topic(),producerProperties(env),metrics);
    }
    @Bean(initMethod="start",destroyMethod="close")
    @ConditionalOnProperty(name="recsys.behavior.events.history-relay-enabled",havingValue="true")
    public BehaviorEventWorker behaviorHistoryWorker(BehaviorEventRepository repository, Properties props, Environment env, MeterRegistry metrics) {
        return new BehaviorEventWorker(repository,Destination.HISTORY,props.historyTopic(),producerProperties(env),metrics);
    }
    /** 只读取启动布尔值，无热刷新承诺。 */
    static class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var e=context.getEnvironment();
            return e.getProperty("recsys.behavior.events.capture-enabled",Boolean.class,false)
                || e.getProperty("recsys.behavior.events.relay-enabled",Boolean.class,false)
                || e.getProperty("recsys.behavior.events.history-relay-enabled",Boolean.class,false);
        }
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


    /** 物理名只允许本逻辑流和有界隔离后缀，不能混入其他领域。 */
    static void validateTopic(Destination destination, String topic) {
        if (topic==null || topic.length()>249 || !(topic.equals(destination.topic())
                || topic.matches(java.util.regex.Pattern.quote(destination.topic())+"\\.[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException("行为topic不满足来源隔离约束");
        }
    }
}
