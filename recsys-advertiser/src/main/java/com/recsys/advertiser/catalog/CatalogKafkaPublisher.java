package com.recsys.advertiser.catalog;

import com.recsys.common.ad.VersionedAdCatalogEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 真实Kafka适配：绑定原集群/topic身份，发送原字节，不把等待超时推断为未送达。 */
public class CatalogKafkaPublisher implements AutoCloseable {
    private final KafkaProducer<byte[],byte[]> producer;
    private final String bootstrap;
    private final Identity expected;

    public CatalogKafkaPublisher(String bootstrap,String clusterId,String topicId) {
        this.bootstrap=bootstrap;expected=new Identity(clusterId,topicId);
        Identity observed=identity(bootstrap);
        if(!observed.clusterId().equals(clusterId) || !observed.topicId().equals(topicId))
            throw CatalogProtocol.failure(CatalogProtocol.Failure.INCONSISTENT);
        producer=new KafkaProducer<>(properties(bootstrap));
    }

    /** 初始化仅观察预先创建的主题，绝不自动建同名替代品。 */
    public static Identity identity(String bootstrap) {
        var properties=Map.<String,Object>of("bootstrap.servers",bootstrap,"request.timeout.ms",3000,"default.api.timeout.ms",5000,"retries",0);
        var admin=AdminClient.create(properties);
        try {
            String cluster=admin.describeCluster().clusterId().get(5,TimeUnit.SECONDS);
            var topic=admin.describeTopics(List.of(VersionedAdCatalogEvent.TOPIC)).allTopicNames().get(5,TimeUnit.SECONDS).get(VersionedAdCatalogEvent.TOPIC);
            return new Identity(cluster,topic.topicId().toString());
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw CatalogProtocol.failure(CatalogProtocol.Failure.RESULT_UNKNOWN);}
        finally{admin.close(Duration.ofSeconds(2));}
    }

    /** 发送在事务外；超时不取消仍在客户端delivery deadline内的原发送。 */
    public RecordMetadata send(CatalogOutboxRepository.EventRow row) throws Exception {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Kafka不得占用目录事务");
        if(!VersionedAdCatalogEvent.TOPIC.equals(row.topic()))throw CatalogProtocol.failure(CatalogProtocol.Failure.INCONSISTENT);
        // 长期存活的producer不能把同名重建topic当作原日志；每次副作用前有界核验身份。
        if(!identity(bootstrap).equals(expected))throw CatalogProtocol.failure(CatalogProtocol.Failure.INCONSISTENT);
        return producer.send(new ProducerRecord<>(row.topic(),row.keyBytes(),row.payloadBytes())).get(5,TimeUnit.SECONDS);
    }
    /** 冻结首版保护预算，调用者不能无限叠加自动重试。 */
    public static Map<String,Object> properties(String bootstrap) {
        var p=new HashMap<String,Object>();p.put("bootstrap.servers",bootstrap);
        p.put("key.serializer",ByteArraySerializer.class.getName());p.put("value.serializer",ByteArraySerializer.class.getName());
        p.put("acks","all");p.put("enable.idempotence",true);p.put("max.in.flight.requests.per.connection",1);p.put("retries",2);
        p.put("request.timeout.ms",3000);p.put("delivery.timeout.ms",10000);p.put("max.block.ms",1000);p.put("linger.ms",0);
        p.put("max.request.size",1048576);return Map.copyOf(p);
    }
    /** 有界退出，未确认的数据库租约由同一原事件恢复。 */
    @Override public void close(){producer.close(Duration.ofSeconds(2));}
    public record Identity(String clusterId,String topicId) { }
}
