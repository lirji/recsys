package com.recsys.recengine.filter.seen;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import java.time.Duration;

/** 三流共用确认顺序，流身份由已绑定入口提供，不能来自消息内容。 */
public final class SeenRecordListener {
    private SeenRecordListener() { }
    /** 每次处理只在当前consumer线程确认一个已持久终态，失败保持原位点。 */
    public static void handle(SeenStream identity,SeenProjectionService projection,ConsumerRecord<byte[],byte[]> record,
                              Acknowledgment ack,Consumer<?,?> consumer,MessageListenerContainer container) {
        String stream=identity.name();
        boolean confirming=false;
        try {
            var outcome=projection.consume(stream,record.partition(),record.offset(),record.key(),record.value());
            if(outcome==SeenProjectionService.Outcome.ACK) {
                if(!consumer.assignment().contains(new TopicPartition(record.topic(),record.partition())))throw new SeenRecord.Failure(SeenRecord.Code.ACTIVE_LEASE);
                confirming=true;ack.acknowledge(); // 返回不等于broker位点已落盘，重平衡内部窗口由终态重投恢复。
            } else {
                ack.nack(Duration.ofSeconds(1));
                if(outcome==SeenProjectionService.Outcome.PAUSED)container.pause();
            }
        } catch(SeenRecord.Failure known) {throw known;}
        catch(RuntimeException unknown) {
            if(confirming)io.micrometer.core.instrument.Metrics.counter("recsys.seen.commit.failures","stream",stream).increment();
            throw new SeenRecord.Failure(SeenRecord.Code.INTERNAL_ERROR);
        }
    }
}
