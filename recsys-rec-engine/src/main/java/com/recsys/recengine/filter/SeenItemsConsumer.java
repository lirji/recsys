package com.recsys.recengine.filter;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.recengine.filter.seen.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** 旧Seen真实入口：只有持久终态才能ACK，Redis/PG/未知异常不能吞掉后正常返回。 */
@Component
public class SeenItemsConsumer {
    private final ObjectProvider<SeenProjectionService> projection;
    /** 默认关闭仍校验旧topic；provider不创建Kafka/Admin或访问恢复表。 */
    public SeenItemsConsumer(ObjectProvider<SeenProjectionService> projection,
            @Value("${recsys.behavior.topic:behavior-events}") String topic) {
        LegacyBehaviorAccepted.requireLegacyTopic(topic);this.projection=projection;
    }
    /** C2未实现时显式拒绝其开启请求，避免配置看似生效却没有产品listener。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void verifyC1Boundary(@Value("${recsys.behavior.seen.live-consume:false}") boolean live,
                                 @Value("${recsys.behavior.seen.history-consume:false}") boolean history) {
        if(live || history)throw new SeenRecord.Failure(SeenRecord.Code.NOT_ELIGIBLE);
    }
    /** 专用factory的单记录consumer线程调用，禁用跨线程ACK与同批跳过。 */
    public void onEvent(ConsumerRecord<byte[],byte[]> record,Acknowledgment ack,Consumer<?,?> consumer,MessageListenerContainer container) {
        boolean confirming=false;
        try {
            var outcome=projection.getObject().consume("LEGACY",record.partition(),record.offset(),record.key(),record.value());
            if(outcome==SeenProjectionService.Outcome.ACK) {
                if(!consumer.assignment().contains(new TopicPartition(record.topic(),record.partition())))throw new SeenRecord.Failure(SeenRecord.Code.ACTIVE_LEASE);
                confirming=true;ack.acknowledge(); // 返回不等于broker位点已落盘，重平衡内部窗口由终态重投恢复。
            } else {
                ack.nack(Duration.ofSeconds(1));
                if(outcome==SeenProjectionService.Outcome.PAUSED)container.pause();
            }
        } catch(SeenRecord.Failure known) {throw known;}
        catch(RuntimeException unknown) {
            if(confirming)io.micrometer.core.instrument.Metrics.counter("recsys.seen.commit.failures","stream","LEGACY").increment();
            throw new SeenRecord.Failure(SeenRecord.Code.INTERNAL_ERROR);
        }
    }
}
