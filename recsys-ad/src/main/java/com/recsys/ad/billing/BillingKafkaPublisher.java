package com.recsys.ad.billing;

import org.apache.kafka.clients.producer.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 专用 producer 的成功必须来自 broker ACK；Future 超时仍属于未知结果，可重复投递。 */
public class BillingKafkaPublisher implements AutoCloseable {
    private final Producer<String,String> producer;
    private final String topic;
    public BillingKafkaPublisher(Producer<String,String> producer,String topic) { this.producer=producer;this.topic=topic; }
    /** 最长 max.block.ms 加六秒等待，小于数据库租约，不占用资金事务。 */
    public void publish(BillingOutboxRepository.Pending event) throws Exception {
        producer.send(new ProducerRecord<>(topic,event.key(),event.payload())).get(6,TimeUnit.SECONDS);
    }
    /** 有界关闭不会把尚未确认的事件标记成功。 */
    @Override public void close() { producer.close(Duration.ofSeconds(2)); }
}
