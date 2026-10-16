package com.recsys.recengine.filter;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.recengine.filter.seen.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** 旧Seen真实入口：只有持久终态才能ACK，Redis/PG/未知异常不能吞掉后正常返回。 */
@Component
public class SeenItemsConsumer {
    private final ObjectProvider<SeenProjectionService> projection;
    /** 默认关闭仍校验旧topic；provider不创建Kafka/Admin或访问恢复表。 */
    public SeenItemsConsumer(ObjectProvider<SeenProjectionService> projection,
            @Value("${recsys.behavior.topic:behavior-events}") String topic) {
        LegacyBehaviorAccepted.requireLegacyTopic(topic);this.projection=projection;
    }
    /** 全关也拒绝跨流topic或复用group，不建立网络连接。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void verifyRoutes(org.springframework.core.env.Environment env) {SeenStream.bindings(env);}
    /** 专用factory的单记录consumer线程调用，禁用跨线程ACK与同批跳过。 */
    public void onEvent(ConsumerRecord<byte[],byte[]> record,Acknowledgment ack,Consumer<?,?> consumer,MessageListenerContainer container) {
        SeenRecordListener.handle(SeenStream.LEGACY,projection.getObject(),record,ack,consumer,container);
    }
}
