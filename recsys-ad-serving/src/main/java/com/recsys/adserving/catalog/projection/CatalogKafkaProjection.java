package com.recsys.adserving.catalog.projection;

import com.recsys.adserving.persistence.mapper.addb.CatalogProjectionMapper.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static com.recsys.adserving.catalog.projection.CatalogProjectionProtocol.*;

/** 单数据集持久租约消费；本库位置是恢复事实，Kafka 位点仅确认已落库前缀。 */
public class CatalogKafkaProjection implements AutoCloseable {
    public record Boundary(int partition, long beginning, long end) { }
    public record Manifest(String clusterId, String topicId, List<Boundary> partitions) { }
    private final CatalogProjectionStore store;
    private final CatalogRedisProjection redis;
    private final Admin admin;
    private final Supplier<Consumer<byte[], byte[]>> factory;
    private final String topic, group;
    private final String worker = UUID.randomUUID().toString();
    private volatile boolean stopping, running;
    private volatile Consumer<byte[], byte[]> owned;
    private Thread thread;
    private long fence;

    /** broker 安全配置由现有 KafkaProperties 提供，组与 topic 在此实例固定。 */
    public CatalogKafkaProjection(CatalogProjectionStore store, CatalogRedisProjection redis, Admin admin,
                                  Supplier<Consumer<byte[], byte[]>> factory, String topic, String group) {
        this.store = store; this.redis = redis; this.admin = admin; this.factory = factory; this.topic = topic; this.group = group;
    }

    /** 受控首次登记：冻结真实保留起点，已有组位点绝不被重置。 */
    public void initialize(UUID command, UUID sourceId, String baseline, String actor, String reason) {
        CatalogProjectionProtocol.audit(command, actor, reason);
        if (sourceId == null) throw new Failure(Code.INVALID_COMMAND);
        Control old = store.control();
        Manifest manifest = observe();
        if (old == null) {
            redis.requireEmpty();
            try {
                boolean exists = admin.listConsumerGroups().valid().get(5, TimeUnit.SECONDS).stream().anyMatch(g -> g.groupId().equals(group));
                if (exists && !admin.describeConsumerGroups(List.of(group)).all().get(5, TimeUnit.SECONDS).get(group).members().isEmpty())
                    throw new Failure(Code.ACTIVE_LEASE);
                if (!admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).isEmpty())
                    throw new Failure(Code.OFFSET_GAP);
            } catch (Failure classified) { throw classified; }
            catch (Exception unavailable) { throw new Failure(Code.DEPENDENCY_FAILURE); }
        }
        // 同 ID 恢复使用首次冻结位置；不能随保留起点变动修改原清单。
        List<Position> positions = old == null ? manifest.partitions().stream()
                .map(p -> new Position(p.partition(), p.beginning(), p.beginning())).toList()
                : store.positions().stream().map(p -> new Position(p.partitionId(), p.startOffset(), p.startOffset())).toList();
        store.initialize(command, new Control(sourceId.toString(), manifest.clusterId(), manifest.topicId(), topic, group,
                State.READY.code(), null, 0, null), positions, baseline, actor, reason);
    }

    /** 首次开关启动前同步核验身份，故障不能藏在一个已经就绪的后台线程里。 */
    public synchronized void start() {
        if (thread != null) throw new Failure(Code.NOT_ELIGIBLE);
        validate(observe());
        Control lease = store.claimConsumer(worker); fence = lease.fence();
        thread = new Thread(this::run, "catalog-projection-consumer"); thread.setDaemon(true); thread.start();
    }
    /** 固定低基数运行指标及测试等待入口。 */
    public boolean isRunning() { return running; }

    private void run() {
        Consumer<byte[], byte[]> consumer = null;
        try {
            consumer = factory.get();
            owned = consumer;
            if (stopping) return;
            List<Position> positions = store.positions();
            consumer.assign(positions.stream().map(p -> new TopicPartition(topic, p.partitionId())).toList());
            for (Position p : positions) consumer.seek(new TopicPartition(topic, p.partitionId()), p.nextOffset());
            // PG提交后进程退出时Kafka可能仍落后；只把已确认的本库前缀补提交，不回退/跳过PG位置。
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(new HashSet<>(consumer.assignment()), CALL_TIMEOUT);
            Map<TopicPartition, OffsetAndMetadata> durable = new HashMap<>();
            for (Position p : positions) {
                var partition = new TopicPartition(topic, p.partitionId());
                OffsetAndMetadata previous = committed.get(partition);
                if (previous != null && previous.offset() > p.nextOffset()) throw new Failure(Code.OFFSET_GAP);
                durable.put(partition, new OffsetAndMetadata(p.nextOffset()));
            }
            consumer.commitSync(durable, CALL_TIMEOUT);
            running = true;
            while (!stopping) {
                store.renew(worker, fence);
                validate(observe());
                store.renew(worker, fence);
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (stopping) return;
                    process(consumer, record);
                }
            }
        } catch (WakeupException stopped) {
            if (!stopping) pause(Code.DEPENDENCY_FAILURE);
        } catch (RuntimeException failure) {
            pause(failure instanceof Failure classified ? classified.code() : Code.DEPENDENCY_FAILURE);
        } finally {
            running = false; owned = null;
            if (consumer != null) try { consumer.close(Duration.ofSeconds(2)); } catch (RuntimeException unavailable) { log(Code.DEPENDENCY_FAILURE); }
            if (stopping) try { store.release(worker, fence); } catch (RuntimeException unavailable) { log(Code.DEPENDENCY_FAILURE); }
        }
    }

    /** PG 已提交之后才提交单分区 offset+1，提交丢失不会丢失本库效果。 */
    public void process(Consumer<byte[], byte[]> consumer, ConsumerRecord<byte[], byte[]> record) {
        for (int attempt = 1; ; attempt++) {
            try {
                store.renew(worker, fence);
                store.accept(record, worker, fence);
                break;
            } catch (RuntimeException failure) {
                if (failure instanceof Failure || attempt >= MAX_ATTEMPTS || stopping) throw failure;
                try { Thread.sleep((1L << (attempt - 1)) * 1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure(Code.DEPENDENCY_FAILURE); }
            }
        }
        afterDatabase(record);
        consumer.commitSync(Map.of(new TopicPartition(topic, record.partition()), new OffsetAndMetadata(record.offset() + 1)), CALL_TIMEOUT);
    }
    /** 独立进程故障验收在此终止，产品没有可开启的跳过确认选项。 */
    protected void afterDatabase(ConsumerRecord<byte[], byte[]> record) { }

    /** Topic 身份、cleanup policy 和实际保留范围都必须来自 broker。 */
    public Manifest observe() {
        try {
            String cluster = admin.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
            TopicDescription description = admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS).get(topic);
            if (description.partitions().isEmpty() || description.partitions().size() > 256) throw new Failure(Code.OFFSET_GAP);
            var resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            String cleanup = admin.describeConfigs(List.of(resource)).all().get(5, TimeUnit.SECONDS).get(resource).get("cleanup.policy").value();
            if (!"delete".equals(cleanup)) throw new Failure(Code.OFFSET_GAP);
            Map<TopicPartition, OffsetSpec> earliest = new HashMap<>(), latest = new HashMap<>();
            for (var p : description.partitions()) {
                var partition = new TopicPartition(topic, p.partition()); earliest.put(partition, OffsetSpec.earliest()); latest.put(partition, OffsetSpec.latest());
            }
            var beginnings = admin.listOffsets(earliest).all().get(5, TimeUnit.SECONDS);
            var ends = admin.listOffsets(latest).all().get(5, TimeUnit.SECONDS);
            List<Boundary> positions = earliest.keySet().stream().sorted(Comparator.comparingInt(TopicPartition::partition))
                    .map(p -> new Boundary(p.partition(), beginnings.get(p).offset(), ends.get(p).offset())).toList();
            return new Manifest(cluster, description.topicId().toString(), positions);
        } catch (Failure classified) { throw classified; }
        catch (Exception unavailable) { throw new Failure(Code.DEPENDENCY_FAILURE); }
    }
    private void validate(Manifest manifest) {
        Control control = store.control();
        if (control == null || !control.clusterId().equals(manifest.clusterId()) || !control.topicId().equals(manifest.topicId())
                || !control.topicName().equals(topic) || !control.groupId().equals(group)) throw new Failure(Code.SOURCE_CONFLICT);
        List<Position> positions = store.positions();
        if (positions.size() != manifest.partitions().size()) throw new Failure(Code.OFFSET_GAP);
        for (Position position : positions) {
            Boundary observed = manifest.partitions().stream().filter(p -> p.partition() == position.partitionId()).findFirst().orElseThrow(() -> new Failure(Code.OFFSET_GAP));
            if (position.nextOffset() < observed.beginning() || position.nextOffset() > observed.end()) throw new Failure(Code.OFFSET_GAP);
        }
    }
    private void pause(Code code) { try { store.pause(worker, fence, code); } catch (RuntimeException unavailable) { log(Code.DEPENDENCY_FAILURE); } log(code); }
    private static void log(Code code) { LoggerFactory.getLogger(CatalogKafkaProjection.class).warn("catalog projection stopped code={}", code); }
    /** wakeup 是允许的跨线程调用，连接和确认只由消费线程持有。 */
    @Override public void close() {
        stopping = true;
        Consumer<byte[], byte[]> consumer = owned; if (consumer != null) consumer.wakeup();
        if (thread != null) try { thread.join(10000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        admin.close(Duration.ofSeconds(2));
    }
}
