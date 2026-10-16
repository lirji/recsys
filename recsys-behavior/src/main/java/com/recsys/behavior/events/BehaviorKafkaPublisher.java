package com.recsys.behavior.events;

import com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper.Pending;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 专用 producer 的有界发送适配，ACK 不是下游业务完成。 */
public class BehaviorKafkaPublisher implements AutoCloseable {
    private final Producer<String, String> producer;
    private final String topic;
    private final BehaviorEventRepository.Destination destination;

    /** 物理 topic 必须与行为来源隔离策略一致。 */
    public BehaviorKafkaPublisher(Producer<String, String> producer, BehaviorEventRepository.Destination destination, String topic) {
        BehaviorEventConfiguration.validateTopic(destination,topic);
        this.producer = producer; this.topic = topic; this.destination=destination;
    }

    /** 发送原负载，等待超时属于未知结果，后续允许原事件重发。 */
    public void publish(Pending event) throws Exception {
        if (!destination.topic().equals(event.destination())) throw new IllegalArgumentException("行为事件发送流不匹配");
        producer.send(new ProducerRecord<>(topic, event.key(), event.payload())).get(6, TimeUnit.SECONDS);
    }

    /** 未确认的行仍由数据库租约续办，关闭不无限等待网络。 */
    @Override public void close() { producer.close(Duration.ofSeconds(2)); }
}
