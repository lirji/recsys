package com.recsys.ad.billing;

import com.recsys.common.ad.billing.ImpressionReceipt.CapturedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.slf4j.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.ThreadLocalRandom;

/** 跨数据库/broker 的明确三步：领取提交、发送确认、按 fence 标记。 */
public class BillingOutboxRelay {
    private static final Logger log=LoggerFactory.getLogger(BillingOutboxRelay.class);
    private final BillingOutboxRepository repository;
    private final BillingKafkaPublisher publisher;
    private final java.util.function.Consumer<BillingOutboxRepository.Pending> validate;
    private final Counter published,failed,stale;
    public BillingOutboxRelay(BillingOutboxRepository repository,BillingKafkaPublisher publisher,BillingEventCodec codec,MeterRegistry metrics) {
        this(repository,publisher,event->{
            var value=codec.decode(event.key(),event.payload());
            if (!CapturedEvent.TOPIC.equals(event.destination()) || !value.event().eventId().equals(event.eventId().toString())) {
                throw new BillingEventCodec.InvalidEvent();
            }
        },metrics,BillingOutboxRepository.EventFamily.IMPRESSION);
    }
    /** 两种资金事实共用ACK/fence算法，严格校验仍由各自版本化codec负责。 */
    public BillingOutboxRelay(BillingOutboxRepository repository,BillingKafkaPublisher publisher,
            java.util.function.Consumer<BillingOutboxRepository.Pending> validate,MeterRegistry metrics,BillingOutboxRepository.EventFamily family) {
        this.repository=repository;this.publisher=publisher;this.validate=validate;
        published=metrics.counter(family.metrics()+".outbox.published");
        failed=metrics.counter(family.metrics()+".outbox.failed");
        stale=metrics.counter(family.metrics()+".outbox.stale");
    }
    /** 不允许外层事务持锁穿过网络；false 只表示当下没有可领取行。 */
    public boolean relayOne() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("投递不可在数据库事务内运行");
        var claimed=repository.claim();
        if (claimed.isEmpty()) return false;
        var event=claimed.get();
        try {
            validate.accept(event);
            publisher.publish(event);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            failed.increment();
            long base=Math.min(30000L,1000L << Math.min(event.attempts()-1,5));
            repository.failed(event,base+ThreadLocalRandom.current().nextLong(base+1),"SEND_FAILED");
            log.warn("sandbox outbox send failed eventId={} type={}",event.eventId(),ex.getClass().getSimpleName());
            return true;
        }
        // ACK 后此处数据库失败不能降级为发送失败/成功；租约过期后原事件重发。
        if (repository.published(event)) published.increment(); else stale.increment();
        return true;
    }
}
