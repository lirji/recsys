package com.recsys.recall.channel;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import com.recsys.recall.execution.RecallExecution;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** 热门Redis故障与单飞回源；共享Future超时不取消实际DB调用，否则会重新放大回源。 */
final class HotReadBudget implements AutoCloseable {
    private final ThreadPoolExecutor redisPool;
    private final ThreadPoolExecutor databasePool;
    private final RecallProperties.CacheBudget config;
    private final MeterRegistry meters;
    private final AtomicLong blockedUntil = new AtomicLong();
    private final Object lock = new Object();
    private volatile RecallExecution.Task<List<RecallItem>> pending;
    private volatile Snapshot snapshot;
    private volatile boolean closed;
    private record Snapshot(int limit, long validUntil, List<RecallItem> items) { }

    HotReadBudget(RecallProperties.CacheBudget config, MeterRegistry meters) {
        if (config.getRedisWaitMs() < 1 || config.getRedisWaitMs() > 1000 || config.getDatabaseWaitMs() < 1
                || config.getDatabaseWaitMs() > 1000 || config.getFailureCooldownMs() < 1 || config.getFailureCooldownMs() > 60_000
                || config.getSnapshotTtlMs() < 1 || config.getSnapshotTtlMs() > 1000)
            throw new IllegalArgumentException(ErrorCode.VALIDATION_FAILED.displayMessage());
        this.config = config; this.meters = meters;
        redisPool = RecallExecution.pool("hot-redis", config.getRedisThreads(), 0, meters);
        databasePool = RecallExecution.pool("hot-database", 1, 0, meters);
    }

    List<RecallItem> read(int limit, Supplier<List<RecallItem>> redis, Supplier<List<RecallItem>> database) {
        if (closed || Thread.currentThread().isInterrupted()) return List.of();
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException(ErrorCode.VALIDATION_FAILED.displayMessage());
        if (System.nanoTime() >= blockedUntil.get()) {
            var task = new RecallExecution.Task<>(redisPool, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getRedisWaitMs()), redis::get, () -> { });
            try {
                redisPool.execute(task);
                var result = task.get(config.getRedisWaitMs(), TimeUnit.MILLISECONDS);
                if (!result.isEmpty()) return result;
            } catch (InterruptedException interrupted) {
                task.cancel(true); Thread.currentThread().interrupt(); count("interrupted"); return List.of();
            } catch (ExecutionException | TimeoutException | RejectedExecutionException failure) {
                task.cancel(true); blockedUntil.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getFailureCooldownMs()));
                count("redis_unavailable");
            }
        }
        Snapshot cached = snapshot;
        if (cached != null && cached.limit == limit && System.nanoTime() < cached.validUntil) { count("snapshot_hit"); return cached.items; }
        RecallExecution.Task<List<RecallItem>> current;
        synchronized (lock) {
            if (closed || Thread.currentThread().isInterrupted()) return List.of();
            current = pending;
            if (current == null) {
                var reference = new AtomicReference<RecallExecution.Task<List<RecallItem>>>();
                current = new RecallExecution.Task<>(databasePool, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getDatabaseWaitMs()), () -> {
                    count("database_load");
                    var loaded = List.copyOf(database.get());
                    snapshot = new Snapshot(limit, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getSnapshotTtlMs()), loaded);
                    return loaded;
                }, () -> { synchronized (lock) { if (pending == reference.get()) pending = null; } });
                reference.set(current); pending = current;
                try { databasePool.execute(current); }
                catch (RejectedExecutionException rejected) { current.cancel(false); count("database_rejected"); return List.of(); }
            } else count("database_coalesced");
        }
        try { return current.get(config.getDatabaseWaitMs(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); count("interrupted"); return List.of(); }
        catch (ExecutionException | TimeoutException | CancellationException failure) { count("database_unavailable"); return List.of(); }
    }

    private void count(String outcome) { meters.counter("recsys.recall.hot", "outcome", outcome).increment(); }
    @Override public void close() { closed = true; RecallExecution.stop(redisPool); RecallExecution.stop(databasePool); }
}
