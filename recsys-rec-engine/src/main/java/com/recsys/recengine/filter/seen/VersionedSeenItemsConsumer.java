package com.recsys.recengine.filter.seen;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

/** LIVE和HISTORY各实例独立入口；共享可靠机制，不共享group或流状态。 */
public final class VersionedSeenItemsConsumer {
    private final SeenStream stream;
    private final SeenProjectionService projection;
    /** 禁止将旧流交给版本化入口，避免意外改变7/8字段语义。 */
    public VersionedSeenItemsConsumer(SeenStream stream,SeenProjectionService projection) {
        if(stream==null || stream==SeenStream.LEGACY)throw new SeenRecord.Failure(SeenRecord.Code.INVALID_COMMAND);
        this.stream=stream;this.projection=projection;
    }
    /** 原字节交产品处理器，依赖异常不吞掉，确认条件与旧流相同。 */
    public void onEvent(ConsumerRecord<byte[],byte[]> record,Acknowledgment ack,Consumer<?,?> consumer,MessageListenerContainer container) {
        SeenRecordListener.handle(stream,projection,record,ack,consumer,container);
    }
}
