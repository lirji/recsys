package com.recsys.behavior.exposure;

import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper.Backlog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 独立单线程有界任务；没有内存待发队列，停机和数据库故障都留给持久租约恢复。 */
public class NaturalOutboxJob {
    static final int BATCH_SIZE = 10;
    private static final Logger log = LoggerFactory.getLogger(NaturalOutboxJob.class);
    private final NaturalOutboxRepository repository;
    private final NaturalOutboxRelay relay;
    private final TaskScheduler scheduler;
    private final LongSupplier nanoTime;
    private final Counter failures;
    private final AtomicBoolean inTick = new AtomicBoolean();
    private volatile Backlog backlog;
    private volatile boolean running;
    private ScheduledFuture<?> task;
    private long retryAt, observeAt;

    /** 单调时钟只用于进程内退避，业务租约始终由数据库决定。 */
    public NaturalOutboxJob(NaturalOutboxRepository repository, NaturalOutboxRelay relay,
                            TaskScheduler scheduler, MeterRegistry metrics) {
        this(repository, relay, scheduler, metrics, System::nanoTime);
    }

    NaturalOutboxJob(NaturalOutboxRepository repository, NaturalOutboxRelay relay,
                     TaskScheduler scheduler, MeterRegistry metrics, LongSupplier nanoTime) {
        this.repository = repository; this.relay = relay; this.scheduler = scheduler; this.nanoTime = nanoTime;
        failures = metrics.counter(NaturalOutboxRelay.METRIC_PREFIX + "worker_failures");
        metrics.gauge(NaturalOutboxRelay.METRIC_PREFIX + "pending", this, j -> j.backlog == null ? Double.NaN : j.backlog.pending());
        metrics.gauge(NaturalOutboxRelay.METRIC_PREFIX + "dead", this, j -> j.backlog == null ? Double.NaN : j.backlog.dead());
        metrics.gauge(NaturalOutboxRelay.METRIC_PREFIX + "oldest_seconds", this, j -> j.backlog == null ? Double.NaN : j.backlog.oldestSeconds());
    }

    /** 默认关闭配置之外才装配；重复启动不能生成第二条任务。 */
    public synchronized void start() {
        if (running) return;
        running = true;
        try { task = scheduler.scheduleWithFixedDelay(this::tick, Duration.ofSeconds(1)); }
        catch (RuntimeException ex) { running = false; throw ex; }
    }

    /** 先拒绝新领取，再中断发送；数据库未标记行不会被丢弃。 */
    public synchronized void stop() {
        running = false;
        if (task != null) task.cancel(true);
    }

    /** 一个 tick 最多十条发送及十条耗尽清算，异常停止本轮并退避。 */
    public void tick() {
        if (!running || !inTick.compareAndSet(false, true)) return;
        try {
            long now = nanoTime.getAsLong();
            if (retryAt != 0 && now - retryAt < 0) return;
            if (!running || Thread.currentThread().isInterrupted()) return;
            repository.exhaust();
            for (int i = 0; i < BATCH_SIZE && running && !Thread.currentThread().isInterrupted(); i++) {
                if (!relay.relayOne()) break;
            }
            if (running && !Thread.currentThread().isInterrupted() && (observeAt == 0 || now - observeAt >= 0)) {
                // 观测失败也遵守三十秒采样间隔，不能故障时放大扫描频率。
                observeAt = nanoTime.getAsLong() + Duration.ofSeconds(30).toNanos();
                backlog = repository.backlog();
            }
            retryAt = 0;
        } catch (Exception ex) {
            // fixed-delay 若向调度器抛出异常会停止未来运行；保留租约、观测未知并有界重试。
            backlog = null;
            failures.increment();
            retryAt = nanoTime.getAsLong() + Duration.ofSeconds(5).toNanos();
            log.error("自然事件 worker 失败，保留原租约 type={}", ex.getClass().getSimpleName());
        } finally { inTick.set(false); }
    }
}
