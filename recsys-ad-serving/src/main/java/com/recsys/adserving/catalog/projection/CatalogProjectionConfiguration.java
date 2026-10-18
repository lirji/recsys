package com.recsys.adserving.catalog.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 开关只控制本片，默认关闭不创建新 Admin/consumer/worker，也不自动安装迁移。 */
@Configuration(proxyBeanMethods = false)
public class CatalogProjectionConfiguration {
    public record Settings(boolean enabled, boolean consume, boolean redis, boolean recovery, String topic, String group) { }

    /** 先核验组合，子开关不能绕过总准入或与旧消费者混跑。 */
    @Bean public Settings catalogProjectionSettings(Environment env) {
        String prefix = "recsys.ad.catalog.projection.";
        boolean enabled = env.getProperty(prefix + "enabled", Boolean.class, false);
        boolean consume = env.getProperty(prefix + "consume-enabled", Boolean.class, false);
        boolean redis = env.getProperty(prefix + "redis-enabled", Boolean.class, false);
        boolean recovery = env.getProperty(prefix + "recovery-enabled", Boolean.class, false);
        if (!enabled && (consume || redis || recovery)
                || enabled && env.getProperty("recsys.ad.catalog.consume", Boolean.class, false)
                || consume && !"replica".equals(env.getProperty("recsys.ad.catalog.source"))) throw new CatalogProjectionProtocol.Failure(CatalogProjectionProtocol.Code.INVALID_COMMAND);
        String topic = env.getProperty(prefix + "topic", VersionedAdCatalogEvent.TOPIC);
        String group = env.getProperty(prefix + "group", CatalogProjectionProtocol.GROUP);
        if (topic.isBlank() || group.isBlank() || topic.equals(com.recsys.common.ad.AdCatalogEvent.TOPIC)) throw new CatalogProjectionProtocol.Failure(CatalogProjectionProtocol.Code.INVALID_COMMAND);
        return new Settings(enabled, consume, redis, recovery, topic, group);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "recsys.ad.catalog.projection.enabled", havingValue = "true")
    static class Enabled {
        /** 复用既有连接配置、认证与 Redis 实例，只收紧本服务命令/连接预算。 */
        @Bean LettuceClientConfigurationBuilderCustomizer catalogRedisTimeouts() {
            return builder -> builder.commandTimeout(Duration.ofSeconds(2)).clientOptions(ClientOptions.builder()
                    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).build());
        }
        /** 装配只核验显式安装结果，不在启动期间修改 DDL。 */
        @Bean CatalogProjectionStore catalogProjectionStore(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions, ObjectMapper json, Settings settings) {
            var store = new CatalogProjectionStore(sessions, json); store.verifySchema(); return store;
        }
        /** Redis 待办与 Kafka 各有独立开关，消费成功不依赖即时 Redis 成功。 */
        @Bean CatalogRedisProjection catalogRedisProjection(CatalogProjectionStore store, StringRedisTemplate redis) { return new CatalogRedisProjection(store, redis); }
        /** 受控操作 Bean 不导出公共 HTTP；恢复开关仍由这个入口强制检查。 */
        @Bean CatalogProjectionOperations catalogProjectionOperations(CatalogProjectionStore store, CatalogKafkaProjection kafka, Settings settings) {
            return new CatalogProjectionOperations(store, kafka, settings.recovery());
        }
        /** Kafka 安全连接参数复用 Spring 配置，可靠性选项固定。 */
        @Bean(destroyMethod = "close") CatalogKafkaProjection catalogKafkaProjection(CatalogProjectionStore store, CatalogRedisProjection redis,
                                                                                   KafkaProperties kafka, Settings settings) {
            Map<String, Object> props = new HashMap<>(kafka.buildConsumerProperties(null));
            props.put("group.id", settings.group()); props.put("enable.auto.commit", false); props.put("auto.offset.reset", "none");
            props.put("key.deserializer", ByteArrayDeserializer.class); props.put("value.deserializer", ByteArrayDeserializer.class);
            props.put("max.poll.records", CatalogProjectionProtocol.BATCH_SIZE); props.put("default.api.timeout.ms", 5000);
            props.put("request.timeout.ms", 5000); props.put("max.partition.fetch.bytes", CatalogProjectionProtocol.MAX_WIRE_BYTES);
            Map<String, Object> admin = new HashMap<>(kafka.buildAdminProperties(null));
            admin.put("request.timeout.ms", 5000); admin.put("default.api.timeout.ms", 5000); admin.put("retries", 0);
            return new CatalogKafkaProjection(store, redis, Admin.create(admin), () -> new KafkaConsumer<>(props), settings.topic(), settings.group());
        }
        /** 注册和 schema 核验完成后才按消费开关启动。 */
        @Bean org.springframework.boot.ApplicationRunner catalogProjectionStart(CatalogKafkaProjection kafka, Settings settings) {
            return args -> { if (settings.consume()) kafka.start(); };
        }
        /** 只有 Redis 开关打开才创建专用单线程定时器，每次 tick 最多推进十个任务。 */
        @Bean(destroyMethod = "shutdown")
        @ConditionalOnProperty(name = "recsys.ad.catalog.projection.redis-enabled", havingValue = "true")
        ThreadPoolTaskScheduler catalogProjectionScheduler(CatalogRedisProjection redis) {
            var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("catalog-redis-");
            scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(10); scheduler.initialize();
            scheduler.scheduleWithFixedDelay(() -> {
                try { for (int i = 0; i < CatalogProjectionProtocol.BATCH_SIZE && redis.advance(); i++) { /* 单轮有界 */ } }
                catch (RuntimeException unavailable) { org.slf4j.LoggerFactory.getLogger(CatalogProjectionConfiguration.class).warn("catalog Redis worker incomplete code=DEPENDENCY_FAILURE"); }
            }, Duration.ofSeconds(1));
            return scheduler;
        }
        /** 依赖故障输出 NaN，不将未知积压报告为零。 */
        @Bean io.micrometer.core.instrument.binder.MeterBinder catalogProjectionMetrics(CatalogProjectionStore store, CatalogKafkaProjection kafka) {
            return registry -> {
                for (var state : java.util.List.of(CatalogProjectionProtocol.State.READY, CatalogProjectionProtocol.State.RUNNING, CatalogProjectionProtocol.State.PAUSED))
                    io.micrometer.core.instrument.Gauge.builder("recsys.catalog.projection.tasks", () -> metric(() -> store.count(state))).tag("state", state.code()).register(registry);
                io.micrometer.core.instrument.Gauge.builder("recsys.catalog.projection.oldest.seconds", () -> metric(store::oldestPendingSeconds)).register(registry);
                io.micrometer.core.instrument.Gauge.builder("recsys.catalog.projection.quarantined", () -> metric(store::quarantinedCount)).register(registry);
                io.micrometer.core.instrument.Gauge.builder("recsys.catalog.projection.running", kafka, k -> k.isRunning() ? 1 : 0).register(registry);
            };
        }
        private static double metric(java.util.function.DoubleSupplier query) { try { return query.getAsDouble(); } catch (RuntimeException unavailable) { return Double.NaN; } }
    }
}
