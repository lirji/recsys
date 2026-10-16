package com.recsys.behavior.exposure;

import com.recsys.behavior.exposure.NaturalOutboxRepository.Error;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.ThreadLocalRandom;

/** 三段投递：领取提交、事务外 broker ACK、有效 fence 标记。 */
public class NaturalOutboxRelay {
    public static final String METRIC_PREFIX = "natural.events.outbox.";
    private static final Logger log = LoggerFactory.getLogger(NaturalOutboxRelay.class);
    private final NaturalOutboxRepository repository;
    private final NaturalKafkaPublisher publisher;
    private final NaturalEventCodec codec;
    private final Counter published, failed, stale;

    /** 指标不携带用户或事件 ID，排查仅在受控日志记录关联 ID。 */
    public NaturalOutboxRelay(NaturalOutboxRepository repository, NaturalKafkaPublisher publisher,
                              NaturalEventCodec codec, MeterRegistry metrics) {
        this.repository = repository; this.publisher = publisher; this.codec = codec;
        published = metrics.counter(METRIC_PREFIX + "published");
        failed = metrics.counter(METRIC_PREFIX + "failed");
        stale = metrics.counter(METRIC_PREFIX + "stale");
    }

    /** false 仅表示没有可领行；DB/mark 失败向 worker 传播，不能伪造发送成功。 */
    public boolean relayOne() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("自然发送不可处于外层事务");
        if (Thread.currentThread().isInterrupted()) return false;
        var claimed = repository.claim();
        if (claimed.isEmpty()) return false;
        var event = claimed.get();
        try {
            codec.validate(event);
            publisher.publish(event);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // 停机中断不冒险新增数据库操作，原 SENDING 租约负责恢复。
            failed.increment();
            return true;
        } catch (Exception ex) {
            // 发送边界的错误/超时都不能确认完成；按原事件持久重试，数据库失败继续向上传递。
            Error error = ex instanceof NaturalEventCodec.InvalidEvent ? Error.INVALID_EVENT : Error.SEND_UNKNOWN;
            failed.increment();
            if (!repository.failed(event, retryDelay(event.attempts()), error)) stale.increment();
            log.warn("自然事件发送未完成 eventId={} error={}", event.eventId(), error);
            return true;
        }
        // ACK 后数据库异常不能改记为发送失败，否则会掩盖提交窗口。
        if (repository.published(event)) published.increment(); else stale.increment();
        return true;
    }

    /** 重试包含抖动且最多一分钟，不因重启丢失已耗次数。 */
    static long retryDelay(int attempts) {
        long cap = Math.min(60000, 1000L << Math.min(Math.max(attempts - 1, 0), 6));
        return ThreadLocalRandom.current().nextLong(Math.max(1000, cap / 2), cap + 1);
    }
}
