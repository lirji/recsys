package com.recsys.recengine.filter.seen;

import com.recsys.recengine.filter.SeenItemsConsumer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import java.time.Duration;
import java.util.*;
import static com.recsys.recengine.filter.seen.SeenRecord.Code.*;

/** 只装配C1旧流；全关时零Seen恢复表/Admin连接，C2开关不得被误当作已实现。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnExpression("${recsys.behavior.seen-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
@EnableConfigurationProperties(KafkaProperties.class)
public class SeenConsumerConfiguration {
    /** 迁移必须已显式完成，装配只校验primary库。 */
    @Bean public SeenRecoveryRepository seenRecoveryRepository(@Qualifier("sqlSessionTemplate") SqlSessionTemplate sessions) {
        var repository=new SeenRecoveryRepository(sessions,new DataSourceTransactionManager(sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource()));
        repository.verifySchema();return repository;
    }
    /** 每实例复用严格解析器，消息原字节始终先有界分类。 */
    @Bean public SeenMessageDecoder seenMessageDecoder() {return new SeenMessageDecoder();}
    /** Redis时限必须在已批准预算内，不能继承更宽松的全局设置。 */
    @Bean public SeenProjectionService seenProjectionService(SeenRecoveryRepository repository,SeenMessageDecoder decoder,StringRedisTemplate redis) {
        if(!(redis.getConnectionFactory() instanceof LettuceConnectionFactory lettuce)
                || lettuce.getClientConfiguration().getCommandTimeout().compareTo(Duration.ofSeconds(2))>0
                || lettuce.getClientConfiguration().getClientOptions().flatMap(o->Optional.ofNullable(o.getSocketOptions()))
                    .map(o->o.getConnectTimeout().compareTo(Duration.ofSeconds(2))>0).orElse(true))throw new SeenRecord.Failure(INVALID_COMMAND);
        return new SeenProjectionService(repository,decoder,redis);
    }
    /** 停止executor随宿主关闭，不留下无界后台任务。 */
    @Bean(destroyMethod="close") public SeenConsumerErrorHandler seenConsumerErrorHandler(SeenRecoveryRepository repository) {return new SeenConsumerErrorHandler(repository);}
    /** 硬可靠性参数覆盖通用Kafka配置，TLS/SASL等连接属性沿宿主。 */
    public static Map<String,Object> consumerProperties(KafkaProperties kafka) {
        var p=new HashMap<String,Object>(kafka.buildConsumerProperties(null));
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"none");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,ByteArrayDeserializer.class);
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1);p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,60000);
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,15000);p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,3000);
        p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,5000);p.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,List.of(RangeAssignor.class));
        p.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,1048576);p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,1048576);
        return p;
    }
    /** 旧Seen只能接专用确认策略，不使用Boot默认失败恢复器。 */
    @Bean(name="seenKafkaListenerContainerFactory") public ConcurrentKafkaListenerContainerFactory<byte[],byte[]> seenKafkaListenerContainerFactory(KafkaProperties kafka,SeenConsumerErrorHandler handler) {
        return factory(consumerProperties(kafka),handler);
    }
    /** 测试与产品使用同一factory，不能以测试consumer替换真实listener。 */
    public static ConcurrentKafkaListenerContainerFactory<byte[],byte[]> factory(Map<String,Object> properties,SeenConsumerErrorHandler handler) {
        var factory=new ConcurrentKafkaListenerContainerFactory<byte[],byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));factory.setConcurrency(1);factory.setBatchListener(false);
        factory.setCommonErrorHandler(handler);factory.setMissingTopicsFatal(true);
        var p=factory.getContainerProperties();p.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);p.setSyncCommits(true);
        p.setSyncCommitTimeout(Duration.ofSeconds(5));p.setCommitRetries(0);p.setAsyncAcks(false);
        p.setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);p.setPollTimeout(500);p.setPauseImmediate(true);p.setShutdownTimeout(10000);
        return factory;
    }
    /** Admin仅用于受控起点/身份查询，重试和总API时间均有界。 */
    @Bean(destroyMethod="close") public SeenOffsetInitializer seenOffsetInitializer(SeenRecoveryRepository repository,KafkaProperties kafka,Environment env) {
        var admin=new HashMap<String,Object>(kafka.buildAdminProperties(null));admin.put("default.api.timeout.ms",5000);admin.put("request.timeout.ms",5000);admin.put("retries",3);
        return new SeenOffsetInitializer(repository,Admin.create(admin),consumerProperties(kafka),"LEGACY",topic(env),group(env));
    }
    /** 恢复开关控制内部命令受理，不启动自动扫描或重放。 */
    @Bean public SeenRecoveryService seenRecoveryService(SeenRecoveryRepository repository,SeenOffsetInitializer offsets,SeenProjectionService projection,SeenMessageDecoder decoder,Environment env) {
        return new SeenRecoveryService(repository,offsets,projection,decoder,env.getProperty("recsys.behavior.seen.recovery-enabled",Boolean.class,false));
    }
    /** 开关开启也必须经过持久登记、位点与全流屏障检查。 */
    @Bean public SeenListenerControl seenListenerControl(@Qualifier("seenKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<byte[],byte[]> factory,
            SeenItemsConsumer listener,SeenRecoveryRepository repository,SeenOffsetInitializer offsets,Environment env) {
        return new SeenListenerControl(factory,listener,repository,offsets,topic(env),group(env),env.getProperty("recsys.behavior.seen-consume",Boolean.class,false));
    }
    /** 固定维度接现有Micrometer；采集失败为NaN，不把PG不可达伪装成零积压。 */
    @Bean public io.micrometer.core.instrument.binder.MeterBinder seenMetrics(SeenRecoveryRepository repository,SeenListenerControl control) {
        return registry->{
            for(String state:List.of(SeenRecord.State.READY.name(),SeenRecord.State.APPLYING.name(),SeenRecord.State.APPLIED.name(),SeenRecord.State.NO_EFFECT.name(),SeenRecord.State.QUARANTINED.name(),SeenRecord.State.PAUSED.name()))
                io.micrometer.core.instrument.Gauge.builder("recsys.seen.records",()->metric(()->repository.statusCount(state)))
                    .tags("stream","LEGACY","status",state).register(registry);
            io.micrometer.core.instrument.Gauge.builder("recsys.seen.oldest.pending.seconds",()->metric(repository::oldestPendingSeconds)).tag("stream","LEGACY").register(registry);
            io.micrometer.core.instrument.Gauge.builder("recsys.seen.paused",control,c->c.isPaused()?1:0).tag("stream","LEGACY").register(registry);
            io.micrometer.core.instrument.Gauge.builder("recsys.seen.stopped",control,c->c.isRunning()?0:1).tag("stream","LEGACY").register(registry);
        };
    }
    private static double metric(java.util.function.DoubleSupplier value) {try{return value.getAsDouble();}catch(SeenRecord.Failure unavailable){return Double.NaN;}}
    private static String topic(Environment env) {String topic=env.getProperty("recsys.behavior.topic","behavior-events");com.recsys.common.behavior.LegacyBehaviorAccepted.requireLegacyTopic(topic);return topic;}
    private static String group(Environment env) {
        String value=env.getProperty("recsys.behavior.seen-group","recsys-rec-engine-seen");
        if(value.isBlank() || value.length()>200 || value.contains(","))throw new SeenRecord.Failure(INVALID_COMMAND);return value;
    }
}
