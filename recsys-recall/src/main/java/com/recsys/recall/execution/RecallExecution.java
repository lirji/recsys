package com.recsys.recall.execution;

import com.recsys.common.error.ErrorCode;
import com.recsys.recall.RecallProperties;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** C20资源所有者：请求槽跟随真实子任务退出，不把取消Future误当底层已经停止。 */
@Component
@EnableConfigurationProperties(RecallProperties.class)
public final class RecallExecution {
    public final ThreadPoolExecutor slow;
    public final ThreadPoolExecutor fast;
    public final ThreadPoolExecutor prefetch;
    private final MeterRegistry meters;
    private final ConcurrentMap<Long, Request> requests = new ConcurrentHashMap<>();
    private final Semaphore admission;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired public RecallExecution(RecallProperties properties, ObjectProvider<MeterRegistry> registry) {
        this(properties, registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 非Spring评估/测试入口也复用相同资源限制，调用方必须close。 */
    public RecallExecution(RecallProperties properties, MeterRegistry meters) {
        var p = properties.getParallel(); this.meters = meters;
        if (p.getTimeoutMs() < 1 || p.getTotalTimeoutMs() < 1 || p.getTotalTimeoutMs() > 10_000
                || p.getPrefetchTimeoutMs() < 1 || p.getMaxRequests() < 1 || p.getMaxRequests() > 10_000
                || p.getMaxResultsPerChannel() < 1 || p.getMaxResultsPerChannel() > 1000)
            throw new IllegalArgumentException(ErrorCode.VALIDATION_FAILED.displayMessage());
        slow = pool("recall-slow", p.getPoolSize(), p.getQueueCapacity(), meters);
        fast = p.isBulkheadEnabled() ? pool("recall-fast", p.getFastPoolSize(), p.getFastQueueCapacity(), meters) : slow;
        prefetch = pool("recall-prefetch", p.getPrefetchPoolSize(), p.getPrefetchQueueCapacity(), meters);
        admission = new Semaphore(p.getMaxRequests());
        meters.gauge("recsys.recall.requests.active", requests, Map::size);
    }

    /** 所有排队容量显式配置，满时拒绝，绝不退到请求线程执行慢调用。 */
    public static ThreadPoolExecutor pool(String name, int threads, int queue, MeterRegistry meters) {
        if (threads < 1 || threads > 128 || queue < 0 || queue > 10_000) throw new IllegalArgumentException(ErrorCode.VALIDATION_FAILED.displayMessage());
        var counter = new AtomicInteger();
        var executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                queue == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queue), task -> {
                    var thread = new Thread(task, name + "-" + counter.incrementAndGet()); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void beforeExecute(Thread thread, Runnable runnable) {
                if (runnable instanceof Task<?> task) {
                    task.startedAt = System.nanoTime();
                    meters.timer("recsys.executor.queue.wait", "pool", name).record(task.startedAt - task.submittedAt, TimeUnit.NANOSECONDS);
                }
                super.beforeExecute(thread, runnable);
            }
            @Override protected void afterExecute(Runnable runnable, Throwable failure) {
                if (runnable instanceof Task<?> task) meters.timer("recsys.executor.run.duration", "pool", name)
                        .record(System.nanoTime() - task.startedAt, TimeUnit.NANOSECONDS);
                super.afterExecute(runnable, failure);
            }
        };
        meters.gauge("recsys.executor.active", java.util.List.of(io.micrometer.core.instrument.Tag.of("pool", name)), executor, ThreadPoolExecutor::getActiveCount);
        meters.gauge("recsys.executor.queued", java.util.List.of(io.micrometer.core.instrument.Tag.of("pool", name)), executor, e -> e.getQueue().size());
        return executor;
    }

    public Request admit(long userId) {
        if (closed.get() || !admission.tryAcquire()) { count("admission_rejected"); return null; }
        var request = new Request(userId);
        if (requests.putIfAbsent(userId, request) != null) { admission.release(); count("user_rejected"); return null; }
        return request;
    }

    /** 子任务只持有不可变参数；父调用返回后仍保留槽直到底层真实退出。 */
    public final class Request implements AutoCloseable {
        private final long userId;
        private final AtomicInteger remaining = new AtomicInteger(1);
        private final AtomicBoolean rootClosed = new AtomicBoolean();
        private Request(long userId) { this.userId = userId; }
        private void retain() { remaining.incrementAndGet(); }
        private void release() {
            if (remaining.decrementAndGet() == 0) { requests.remove(userId, this); admission.release(); }
        }
        @Override public void close() { if (rootClosed.compareAndSet(false, true)) release(); }
    }

    public <T> Task<T> submit(ThreadPoolExecutor pool, Request request, long deadline, Callable<T> work) {
        request.retain();
        var task = new Task<T>(pool, deadline, work, request::release);
        try { pool.execute(task); return task; }
        catch (RejectedExecutionException rejected) { task.cancel(false); count("queue_rejected"); return null; }
    }

    /** 捕获已有Observation/Tracing与MDC；不注册或传播Spring事务ThreadLocal。 */
    public static <T> Callable<T> contextual(Callable<T> work) {
        var snapshot = ContextSnapshotFactory.builder().clearMissing(true).build().captureAll();
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (var scope = snapshot.setThreadLocals()) {
                if (captured == null) MDC.clear(); else MDC.setContextMap(captured);
                return work.call();
            } finally { if (previous == null) MDC.clear(); else MDC.setContextMap(previous); }
        };
    }

    /** 可移除排队任务；active取消保留配额直到run真正退出。 */
    public static final class Task<T> extends FutureTask<T> {
        private final ThreadPoolExecutor owner;
        private final Runnable release;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicLong completed;
        private final long submittedAt = System.nanoTime();
        private volatile long startedAt;
        public final long deadline;
        public Task(ThreadPoolExecutor owner, long deadline, Callable<T> work, Runnable release) {
            this(owner, deadline, contextual(work), release, new AtomicLong());
        }
        private Task(ThreadPoolExecutor owner, long deadline, Callable<T> work, Runnable release, AtomicLong completed) {
            super(() -> {
                try {
                    if (System.nanoTime() >= deadline) throw new TimeoutException();
                    return work.call();
                } finally { completed.set(System.nanoTime()); }
            });
            this.owner = owner; this.deadline = deadline; this.release = release; this.completed = completed;
        }
        public long completedAt() { return completed.get(); }
        @Override public void run() {
            started.set(true);
            try { super.run(); } finally { releaseOnce(); }
        }
        @Override protected void done() {
            if (!started.get()) { owner.remove(this); releaseOnce(); }
        }
        private void releaseOnce() { if (released.compareAndSet(false, true)) release.run(); }
    }

    public void count(String outcome) { meters.counter("recsys.recall.execution", "outcome", outcome).increment(); }
    public void duration(long nanos) { meters.timer("recsys.recall.duration").record(nanos, TimeUnit.NANOSECONDS); }
    public int activeRequests() { return requests.size(); }

    @PreDestroy public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stop(prefetch); stop(slow); if (fast != slow) stop(fast);
    }
    /** 关闭取消队列并唤醒等待方；不响应中断的调用仍受固定线程上限约束。 */
    public static void stop(ThreadPoolExecutor pool) {
        for (Runnable queued : pool.shutdownNow()) if (queued instanceof Future<?> future) future.cancel(false);
        try { pool.awaitTermination(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
