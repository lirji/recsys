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

/** 三流按各自开关装配；全关时零Seen恢复表/Admin连接，组与路由绑定不可串流。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnExpression("${recsys.behavior.seen-consume:false} or ${recsys.behavior.seen.live-consume:false} or ${recsys.behavior.seen.history-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
@EnableConfigurationProperties(KafkaProperties.class)
public class SeenConsumerConfiguration {
    /** 纯配置校验不建连接，三流共用一致的已核验绑定。 */
    @Bean public Map<SeenStream,SeenStream.Binding> seenBindings(Environment env) {return SeenStream.bindings(env);}
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
    @Bean(destroyMethod="close") public SeenConsumerErrorHandler seenConsumerErrorHandler(SeenRecoveryRepository repository,@Qualifier("seenBindings") Map<SeenStream,SeenStream.Binding> bindings) {
        var groups=new HashMap<String,SeenStream>();bindings.values().forEach(b->groups.put(b.group(),b.stream()));return new SeenConsumerErrorHandler(repository,groups);
    }
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
    /** 三流只能接专用确认策略，不使用Boot默认失败恢复器。 */
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
    // 三流共享工厂和一个有界停止队列，各自独立Admin、内部命令与消费生命周期。
    private static SeenOffsetInitializer offsets(SeenRecoveryRepository repository,KafkaProperties kafka,SeenStream.Binding binding) {
        var admin=new HashMap<String,Object>(kafka.buildAdminProperties(null));admin.put("default.api.timeout.ms",5000);admin.put("request.timeout.ms",5000);admin.put("retries",3);
        return new SeenOffsetInitializer(repository,Admin.create(admin),consumerProperties(kafka),binding.stream().name(),binding.topic(),binding.group());
    }
    /** LEGACY仅在消费或显式恢复开启时创建Admin；启动不改位点。 */
    @ConditionalOnExpression("${recsys.behavior.seen-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean(destroyMethod="close") public SeenOffsetInitializer seenOffsetInitializer(SeenRecoveryRepository repository,KafkaProperties kafka,@Qualifier("seenBindings") Map<SeenStream,SeenStream.Binding> bindings) {
        return offsets(repository,kafka,bindings.get(SeenStream.LEGACY));
    }
    /** LEGACY受控命令只访问该流；恢复开关不启动自动作业。 */
    @ConditionalOnExpression("${recsys.behavior.seen-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public SeenRecoveryService seenRecoveryService(SeenRecoveryRepository repository,@Qualifier("seenOffsetInitializer") SeenOffsetInitializer offsets,SeenProjectionService projection,SeenMessageDecoder decoder,Environment env) {
        return new SeenRecoveryService(repository,offsets,projection,decoder,env.getProperty("recsys.behavior.seen.recovery-enabled",Boolean.class,false),SeenStream.LEGACY);
    }
    /** LEGACY开关只请求启动，不能绕过登记和持久义务。 */
    @ConditionalOnExpression("${recsys.behavior.seen-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public SeenListenerControl seenListenerControl(@Qualifier("seenKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<byte[],byte[]> factory,
            SeenItemsConsumer listener,SeenRecoveryRepository repository,@Qualifier("seenOffsetInitializer") SeenOffsetInitializer offsets,@Qualifier("seenBindings") Map<SeenStream,SeenStream.Binding> bindings) {
        var binding=bindings.get(SeenStream.LEGACY);
        return new SeenListenerControl(factory,SeenStream.LEGACY,listener::onEvent,repository,offsets,binding.topic(),binding.group(),binding.consume());
    }
    /** LIVE仅在消费或显式恢复开启时创建Admin；启动不改位点。 */
    @ConditionalOnExpression("${recsys.behavior.seen.live-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean(destroyMethod="close") public SeenOffsetInitializer liveSeenOffsetInitializer(SeenRecoveryRepository repository,KafkaProperties kafka,@Qualifier("seenBindings") Map<SeenStream,SeenStream.Binding> bindings) {
        return offsets(repository,kafka,bindings.get(SeenStream.LIVE));
    }
    /** LIVE受控命令只访问该流；恢复开关不启动自动作业。 */
    @ConditionalOnExpression("${recsys.behavior.seen.live-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public SeenRecoveryService liveSeenRecoveryService(SeenRecoveryRepository repository,@Qualifier("liveSeenOffsetInitializer") SeenOffsetInitializer offsets,SeenProjectionService projection,SeenMessageDecoder decoder,Environment env) {
        return new SeenRecoveryService(repository,offsets,projection,decoder,env.getProperty("recsys.behavior.seen.recovery-enabled",Boolean.class,false),SeenStream.LIVE);
    }
    /** LIVE版本化产品入口，按各自逻辑来源解码。 */
    @ConditionalOnExpression("${recsys.behavior.seen.live-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public VersionedSeenItemsConsumer liveSeenItemsConsumer(SeenProjectionService projection) {return new VersionedSeenItemsConsumer(SeenStream.LIVE,projection);}
    /** LIVE开关只请求启动，不能绕过登记和持久义务。 */
    @ConditionalOnExpression("${recsys.behavior.seen.live-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public SeenListenerControl liveSeenListenerControl(@Qualifier("seenKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<byte[],byte[]> factory,
            @Qualifier("liveSeenItemsConsumer") VersionedSeenItemsConsumer listener,SeenRecoveryRepository repository,@Qualifier("liveSeenOffsetInitializer") SeenOffsetInitializer offsets,@Qualifier("seenBindings") Map<SeenStream,SeenStream.Binding> bindings) {
        var binding=bindings.get(SeenStream.LIVE);
        return new SeenListenerControl(factory,SeenStream.LIVE,listener::onEvent,repository,offsets,binding.topic(),binding.group(),binding.consume());
    }
    /** HISTORY仅在消费或显式恢复开启时创建Admin；启动不改位点。 */
    @ConditionalOnExpression("${recsys.behavior.seen.history-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean(destroyMethod="close") public SeenOffsetInitializer historySeenOffsetInitializer(SeenRecoveryRepository repository,KafkaProperties kafka,@Qualifier("seenBindings") Map<SeenStream,SeenStream.Binding> bindings) {
        return offsets(repository,kafka,bindings.get(SeenStream.HISTORY));
    }
    /** HISTORY受控命令只访问该流；恢复开关不启动自动作业。 */
    @ConditionalOnExpression("${recsys.behavior.seen.history-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public SeenRecoveryService historySeenRecoveryService(SeenRecoveryRepository repository,@Qualifier("historySeenOffsetInitializer") SeenOffsetInitializer offsets,SeenProjectionService projection,SeenMessageDecoder decoder,Environment env) {
        return new SeenRecoveryService(repository,offsets,projection,decoder,env.getProperty("recsys.behavior.seen.recovery-enabled",Boolean.class,false),SeenStream.HISTORY);
    }
    /** HISTORY版本化产品入口，按各自逻辑来源解码。 */
    @ConditionalOnExpression("${recsys.behavior.seen.history-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public VersionedSeenItemsConsumer historySeenItemsConsumer(SeenProjectionService projection) {return new VersionedSeenItemsConsumer(SeenStream.HISTORY,projection);}
    /** HISTORY开关只请求启动，不能绕过登记和持久义务。 */
    @ConditionalOnExpression("${recsys.behavior.seen.history-consume:false} or ${recsys.behavior.seen.recovery-enabled:false}")
    @Bean public SeenListenerControl historySeenListenerControl(@Qualifier("seenKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<byte[],byte[]> factory,
            @Qualifier("historySeenItemsConsumer") VersionedSeenItemsConsumer listener,SeenRecoveryRepository repository,@Qualifier("historySeenOffsetInitializer") SeenOffsetInitializer offsets,@Qualifier("seenBindings") Map<SeenStream,SeenStream.Binding> bindings) {
        var binding=bindings.get(SeenStream.HISTORY);
        return new SeenListenerControl(factory,SeenStream.HISTORY,listener::onEvent,repository,offsets,binding.topic(),binding.group(),binding.consume());
    }
    /** 固定维度接现有Micrometer；采集失败为NaN，不把PG不可达伪装成零积压。 */
    @Bean public io.micrometer.core.instrument.binder.MeterBinder seenMetrics(SeenRecoveryRepository repository,List<SeenListenerControl> controls) {
        return registry->{
          for(var control:controls) {
            String stream=control.stream().name();
            for(String state:List.of(SeenRecord.State.READY.name(),SeenRecord.State.APPLYING.name(),SeenRecord.State.APPLIED.name(),SeenRecord.State.NO_EFFECT.name(),SeenRecord.State.QUARANTINED.name(),SeenRecord.State.PAUSED.name()))
                io.micrometer.core.instrument.Gauge.builder("recsys.seen.records",()->metric(()->repository.statusCount(stream,state)))
                    .tags("stream",stream,"status",state).register(registry);
            io.micrometer.core.instrument.Gauge.builder("recsys.seen.oldest.pending.seconds",()->metric(()->repository.oldestPendingSeconds(stream))).tag("stream",stream).register(registry);
            io.micrometer.core.instrument.Gauge.builder("recsys.seen.paused",control,c->c.isPaused()?1:0).tag("stream",stream).register(registry);
            io.micrometer.core.instrument.Gauge.builder("recsys.seen.stopped",control,c->c.isRunning()?0:1).tag("stream",stream).register(registry);
          }
        };
    }
    private static double metric(java.util.function.DoubleSupplier value) {try{return value.getAsDouble();}catch(SeenRecord.Failure unavailable){return Double.NaN;}}
}
