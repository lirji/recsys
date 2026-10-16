package com.recsys.behavior.exposure;

import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper.Pending;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 专用 producer 的有界发送适配，ACK 不是下游业务完成。 */
public class NaturalKafkaPublisher implements AutoCloseable {
    private final Producer<String, String> producer;
    private final String topic;

    /** 物理 topic 必须与自然来源隔离策略一致。 */
    public NaturalKafkaPublisher(Producer<String, String> producer, String topic) {
        NaturalEventConfiguration.validateTopic(topic);
        this.producer = producer; this.topic = topic;
    }

    /** 发送原负载，等待超时属于未知结果，后续允许原事件重发。 */
    public void publish(Pending event) throws Exception {
        producer.send(new ProducerRecord<>(topic, event.key(), event.payload())).get(6, TimeUnit.SECONDS);
    }

    /** 未确认的行仍由数据库租约续办，关闭不无限等待网络。 */
    @Override public void close() { producer.close(Duration.ofSeconds(2)); }
}
