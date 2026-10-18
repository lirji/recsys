package com.recsys.streaming.reliable;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.admin.AdminClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static com.recsys.streaming.reliable.FeatureSettings.value;

/** C19可靠管线入口；legacy默认入口与新namespace保持显式切换边界。 */
public final class ReliableFeatureJob {
    private ReliableFeatureJob() { }

    public static void run(Map<String, String> args) throws Exception {
        FeatureSettings settings = settings(args, "unresolved"); // 外部连接前先拒绝非法参数/串流。
        String sourceIdentity;
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", settings.kafka(), "request.timeout.ms", "5000", "default.api.timeout.ms", "5000"))) {
            String cluster = admin.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
            var topic = admin.describeTopics(List.of(settings.topic())).allTopicNames().get(5, TimeUnit.SECONDS).get(settings.topic());
            sourceIdentity = cluster + ":" + topic.topicId() + ":" + topic.partitions().size();
        }
        settings = settings(args, sourceIdentity);
        var env = StreamExecutionEnvironment.getExecutionEnvironment();
        build(env, settings);
        env.execute("recsys-reliable-features-v1");
    }

    /** 测试与CLI共用实际拓扑，恢复时按固定UID严格加载全部状态。 */
    public static void build(StreamExecutionEnvironment env, FeatureSettings settings) {
        Configuration configuration = new Configuration();
        if (settings.restore() != null && !settings.restore().isBlank()) configuration.setString("execution.savepoint.path", settings.restore());
        configuration.setBoolean("execution.savepoint.ignore-unclaimed-state", false);
        env.configure(configuration, ReliableFeatureJob.class.getClassLoader());
        env.setParallelism(1); env.setMaxParallelism(1);
        env.enableCheckpointing(settings.checkpointMs(), CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setCheckpointStorage(settings.checkpointDir());
        env.getCheckpointConfig().setCheckpointTimeout(60_000);
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        env.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Duration.ofSeconds(30)));
        env.getConfig().setAutoWatermarkInterval(100);
        var source = KafkaSource.<FeatureWire.Event>builder().setBootstrapServers(settings.kafka())
                .setTopics(settings.topic()).setGroupId(settings.group()).setStartingOffsets(new StrictEarliest())
                .setProperty("auto.offset.reset", "none").setProperty("enable.auto.commit", "false")
                .setDeserializer(new FeatureWire(settings)).build();
        env.fromSource(source, WatermarkStrategy.<FeatureWire.Event>forBoundedOutOfOrderness(Duration.ofMillis(settings.disorderMs()))
                        .withTimestampAssigner((event, ignored) -> event.ts).withIdleness(Duration.ofSeconds(30)), "feature-source")
                .uid("feature-source-v1").keyBy(event -> 0)
                .process(new FeatureWindows(settings)).uid("feature-windows-v1").name("bounded-event-windows")
                .addSink(new FeatureRedisSink(settings)).uid("feature-redis-v1").name("atomic-window-snapshot");
    }

    /** 首次按实际beginning登记；已恢复位点过期时禁止Kafka自动跳到新的earliest。 */
    public static final class StrictEarliest implements OffsetsInitializer {
        @Override public Map<org.apache.kafka.common.TopicPartition, Long> getPartitionOffsets(
                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions, PartitionOffsetsRetriever retriever) {
            return retriever.beginningOffsets(partitions);
        }
        @Override public org.apache.kafka.clients.consumer.OffsetResetStrategy getAutoOffsetResetStrategy() {
            return org.apache.kafka.clients.consumer.OffsetResetStrategy.NONE;
        }
    }

    private static FeatureSettings settings(Map<String, String> args, String sourceIdentity) {
        return new FeatureSettings(value(args,"kafka","KAFKA_SERVERS","localhost:9092"), value(args,"topic","BEHAVIOR_TOPIC","behavior-events"),
                value(args,"group","RT_GROUP",null), value(args,"projection-id","RT_PROJECTION_ID",null),
                value(args,"namespace","RT_NAMESPACE",null), value(args,"source-id","RT_SOURCE_ID",null),
                Boolean.parseBoolean(value(args,"live","RT_LIVE","false")), value(args,"checkpoint-dir","RT_CHECKPOINT_DIR",null),
                value(args,"restore","RT_RESTORE",null), value(args,"redis-host","REDIS_HOST","localhost"),
                Integer.parseInt(value(args,"redis-port","REDIS_PORT","6379")), value(args,"pg-url","PG_URL","jdbc:postgresql://localhost:5432/recsys"),
                value(args,"pg-user","PG_USER","recsys"), value(args,"pg-pass","PG_PASSWORD",""),
                Long.parseLong(value(args,"window-ms","RT_WINDOW_MS","600000")), Long.parseLong(value(args,"slide-ms","RT_SLIDE_MS","20000")),
                Long.parseLong(value(args,"disorder-ms","RT_DISORDER_MS","30000")), Long.parseLong(value(args,"freshness-ms","RT_FRESHNESS_MS","120000")),
                Long.parseLong(value(args,"checkpoint-ms","RT_CHECKPOINT_MS","10000")), Integer.parseInt(value(args,"max-events","RT_MAX_EVENTS","100000")), sourceIdentity);
    }
}
