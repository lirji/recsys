package com.recsys.ad.billing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** 单线程唤醒持久化到期待办；多实例依赖数据库行锁，失败不丢记录也不无限紧循环。 */
public class ConversionExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(ConversionExpiryJob.class);
    private static final long MAX_BACKOFF_MS = 60000;
    private final ConversionSettlementService service;
    private final TaskScheduler scheduler;
    private final Duration interval;
    private final int batchSize;
    private final Counter expired;
    private final Counter failures;
    private ScheduledFuture<?> task;
    private long retryAfterNanos;
    private int consecutiveFailures;

    public ConversionExpiryJob(ConversionSettlementService service, MeterRegistry metrics, TaskScheduler scheduler,
                                Duration interval, int batchSize) {
        this.service = service;
        this.scheduler = scheduler;
        this.interval = interval;
        this.batchSize = batchSize;
        expired = metrics.counter("ad.billing.conversion.expired");
        failures = metrics.counter("ad.billing.conversion.expiry.failures");
    }

    /** fixed delay 不堆积追赶任务，待办真源始终是数据库 HELD 行。 */
    public void start() {
        task = scheduler.scheduleWithFixedDelay(this::tick, interval);
    }

    /** 停机中断正在执行的一笔，事务失败后由后续实例重新领取。 */
    public void stop() {
        if (task != null) task.cancel(true);
    }

    /** 单轮最多 batchSize 次短事务，任一故障停止本轮并进行有上限的本地退避。 */
    public void tick() {
        if (retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) return;
        try {
            for (int i = 0; i < batchSize && !Thread.currentThread().isInterrupted(); i++) {
                if (!service.expireOne()) break;
                expired.increment();
            }
            consecutiveFailures = 0;
            retryAfterNanos = 0;
        } catch (Exception ex) {
            // 不能吞错当成无待办；当前事务回滚，持久化CPA HELD 仍是下轮的恢复依据。
            failures.increment();
            consecutiveFailures = Math.min(consecutiveFailures + 1, 6);
            long base = Math.min(MAX_BACKOFF_MS / 2, 1000L << (consecutiveFailures - 1));
            long delay = Math.min(MAX_BACKOFF_MS, base + ThreadLocalRandom.current().nextLong(base + 1));
            retryAfterNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
            // SQL约束异常的DETAIL可能含资格整行；只记录错误类型，凭指标与数据库事实排查。
            log.error("sandbox conversion expiry failed; held rows retained, retry after {}ms, errorType={}", delay, ex.getClass().getSimpleName());
        }
    }
}
