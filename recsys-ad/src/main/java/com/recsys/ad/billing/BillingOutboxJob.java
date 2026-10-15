package com.recsys.ad.billing;

import io.micrometer.core.instrument.*;
import org.slf4j.*;
import org.springframework.scheduling.TaskScheduler;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/** 一条专用 fixed-delay 任务，无工作队列累积；每轮最多十条，数据库故障停止本轮。 */
public class BillingOutboxJob {
    private static final Logger log=LoggerFactory.getLogger(BillingOutboxJob.class);
    private static final int BATCH_SIZE=10;
    private final BillingOutboxRepository repository;
    private final BillingOutboxRelay relay;
    private final TaskScheduler scheduler;
    private final Counter failures,dead;
    private volatile BillingOutboxRepository.Backlog backlog=new BillingOutboxRepository.Backlog(0,0,0);
    private ScheduledFuture<?> task;
    private long retryAtNanos,observeAtNanos;
    public BillingOutboxJob(BillingOutboxRepository repository,BillingOutboxRelay relay,TaskScheduler scheduler,MeterRegistry metrics) {
        this.repository=repository;this.relay=relay;this.scheduler=scheduler;
        failures=metrics.counter("ad.billing.outbox.worker.failures");dead=metrics.counter("ad.billing.outbox.exhausted");
        metrics.gauge("ad.billing.outbox.pending",this,j->j.backlog.pending());
        metrics.gauge("ad.billing.outbox.dead",this,j->j.backlog.dead());
        metrics.gauge("ad.billing.outbox.oldest.seconds",this,j->j.backlog.oldestSeconds());
    }
    /** 停新增收费不停止该任务，已提交事实仍须续发。 */
    public void start() { task=scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1)); }
    /** 中断在途发送；未标记成功的行保留给租约恢复。 */
    public void stop() { if(task!=null)task.cancel(true); }
    /** 基础设施故障仅一轮一次日志并退避，不无界紧循环访问故障库。 */
    public void tick() {
        if (retryAtNanos!=0 && System.nanoTime()-retryAtNanos<0) return;
        try {
            dead.increment(repository.exhaust());
            for(int i=0;i<BATCH_SIZE && !Thread.currentThread().isInterrupted();i++) if(!relay.relayOne())break;
            if(observeAtNanos==0 || System.nanoTime()-observeAtNanos>=0) {
                backlog=repository.backlog();observeAtNanos=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            }
            retryAtNanos=0;
        } catch(Exception ex) {
            failures.increment();retryAtNanos=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            log.error("sandbox outbox worker failed; leases retained type={}",ex.getClass().getSimpleName());
        }
    }
}
