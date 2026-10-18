package com.recsys.feature;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FeatureService;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 基于 Redis Hash 的在线特征服务。
 * 离线作业把特征写入 feat:user:{id} / feat:item:{id},在线直接读。
 * 无数据时返回空 map(排序层据此降级)。
 *
 * 注意(docs/03 §6):离线写特征必须复用相同的特征名,保证在线/离线一致。
 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(FeatureReadProperties.class)
public class RedisFeatureService implements FeatureService {

    private final StringRedisTemplate redis;
    private final FeatureReadProperties config;
    private final java.util.concurrent.ThreadPoolExecutor reads;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public RedisFeatureService(StringRedisTemplate redis) {
        this(redis, new FeatureReadProperties(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** 只读池与Spring组件同生命周期；没有等待队列，禁止故障请求无限堆积。 */
    @org.springframework.beans.factory.annotation.Autowired
    public RedisFeatureService(StringRedisTemplate redis, FeatureReadProperties config,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        this(redis, config, meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
    }

    public RedisFeatureService(StringRedisTemplate redis, FeatureReadProperties config, io.micrometer.core.instrument.MeterRegistry meters) {
        if (config.getThreads() < 1 || config.getThreads() > 32 || config.getTimeoutMs() < 1 || config.getTimeoutMs() > 1000
                || config.getMaxBatchSize() < 1 || config.getMaxBatchSize() > 1024)
            throw new IllegalArgumentException(com.recsys.common.error.ErrorCode.VALIDATION_FAILED.displayMessage());
        this.redis = redis; this.config = config; this.meters = meters;
        var count = new java.util.concurrent.atomic.AtomicInteger();
        reads = new java.util.concurrent.ThreadPoolExecutor(config.getThreads(), config.getThreads(), 0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.SynchronousQueue<>(), task -> {
                    var thread = new Thread(task, "feature-read-" + count.incrementAndGet()); thread.setDaemon(true); return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        meters.gauge("recsys.feature.read.active", reads, java.util.concurrent.ThreadPoolExecutor::getActiveCount);
    }

    @Override
    public Map<String, Double> userFeatures(long userId) {
        return boundedRead(() -> readNumericHash(RedisKeys.userFeature(userId)));
    }

    @Override
    public Map<String, Double> itemFeatures(long itemId) {
        return boundedRead(() -> readNumericHash(RedisKeys.itemFeature(itemId)));
    }

    /**
     * 批量读物品特征:一次 Redis pipeline 把所有候选的 feat hash 拉回,消除排序候选循环里逐个 HGETALL
     * 的 N 次往返(N+1)。失败整批降为空特征，不再向故障Redis放大N次请求。
     */
    @Override
    public Map<Long, Map<String, Double>> itemFeatures(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return Map.of();
        var unique = new LinkedHashSet<Long>();
        for (Long id : itemIds) {
            if (id == null || id <= 0) continue;
            unique.add(id);
            if (unique.size() > config.getMaxBatchSize()) { count("batch_rejected"); return Map.of(); }
        }
        List<Long> ids = List.copyOf(unique);
        return boundedRead(() -> readBatch(ids));
    }

    private Map<Long, Map<String, Double>> readBatch(List<Long> ids) {
        Map<Long, Map<String, Double>> out = new HashMap<>();
        List<Object> raw = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : ids) {
                connection.hGetAll(RedisKeys.itemFeature(id).getBytes(StandardCharsets.UTF_8));
            }
            return null;   // pipeline 回调必须返回 null
        });
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Double> feats = new HashMap<>();
            Object r = i < raw.size() ? raw.get(i) : null;
            if (r instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) {
                    try {
                        feats.put(String.valueOf(e.getKey()), Double.parseDouble(String.valueOf(e.getValue())));
                    } catch (NumberFormatException ignore) {
                        // 跳过非数值特征
                    }
                }
            }
            out.put(ids.get(i), feats);
        }
        return out;
    }

    /** 写入特征(供离线作业复用,保证 key/字段一致)。 */
    public void writeUserFeatures(long userId, Map<String, Double> features) {
        writeNumericHash(RedisKeys.userFeature(userId), features);
    }

    public void writeItemFeatures(long itemId, Map<String, Double> features) {
        writeNumericHash(RedisKeys.itemFeature(itemId), features);
    }

    private Map<String, Double> readNumericHash(String key) {
        Map<String, Double> out = new HashMap<>();
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        for (var e : raw.entrySet()) {
            try {
                out.put(String.valueOf(e.getKey()), Double.parseDouble(String.valueOf(e.getValue())));
            } catch (NumberFormatException ignore) {
                // 跳过非数值特征
            }
        }
        return out;
    }

    /** 停止等待不等于驱动已停止；固定线程上限持续约束未响应中断的IO。 */
    private <K, V> Map<K, V> boundedRead(java.util.concurrent.Callable<Map<K, V>> query) {
        long started = System.nanoTime();
        long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(config.getTimeoutMs());
        var snapshot = io.micrometer.context.ContextSnapshotFactory.builder().clearMissing(true).build().captureAll();
        Map<String, String> captured = org.slf4j.MDC.getCopyOfContextMap();
        var task = new java.util.concurrent.FutureTask<Map<K, V>>(() -> {
            long executionStarted = System.nanoTime();
            var prior = org.slf4j.MDC.getCopyOfContextMap();
            try (var scope = snapshot.setThreadLocals()) {
                if (captured == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(captured);
                if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException();
                return query.call();
            } finally {
                if (prior == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(prior);
                meters.timer("recsys.feature.read.execution").record(System.nanoTime() - executionStarted, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        });
        try {
            reads.execute(task);
            return task.get(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            task.cancel(true); Thread.currentThread().interrupt(); count("interrupted"); return Map.of();
        } catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(true); count("timeout"); return Map.of();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.RejectedExecutionException failure) {
            task.cancel(false); count("unavailable"); return Map.of();
        } finally { meters.timer("recsys.feature.read.duration").record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS); }
    }

    private void count(String outcome) { meters.counter("recsys.feature.read", "outcome", outcome).increment(); }

    @jakarta.annotation.PreDestroy
    public void close() {
        reads.shutdownNow();
        try { reads.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void writeNumericHash(String key, Map<String, Double> features) {
        if (features == null || features.isEmpty()) {
            return;
        }
        Map<String, String> str = new HashMap<>();
        features.forEach((k, v) -> str.put(k, String.valueOf(v)));
        redis.opsForHash().putAll(key, str);
    }
}
