package com.recsys.advertiser.catalog;

import com.recsys.common.ad.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import java.util.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** 为S17保留真实Kafka乱序/重复/异摘要原文证据；只验证wire，不冒充尚未实现的消费防复活。 */
@Testcontainers @Tag("catalog-reliability")
class CatalogWireKafkaTest {
    @Container static final CatalogPhysicalTransactionTest.KafkaFixture BROKER=CatalogPhysicalTransactionTest.KAFKA;
    @Test void rawKafkaRetainsHighLowDuplicateAndConflictingDigestForTheSameIdentity() throws Exception {
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",BROKER.bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(VersionedAdCatalogEvent.TOPIC,1,(short)1))).all().get(10,TimeUnit.SECONDS);
        }
        var sample=new CatalogSnapshotCodecTest();var codec=sample.codec;
        byte[] high=codec.encode(sample.deleted());
        byte[] low=codec.encode(sample.snapshot("active","approved",sample.unknown(10L),List.of()));
        byte[] conflict=new String(low,java.nio.charset.StandardCharsets.UTF_8).replace("\"title\":\"title\"","\"title\":\"different-title\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] key=CatalogEventCodec.key(sample.adId);var input=List.of(high,low,low,conflict);
        var coordinates=new ArrayList<Long>();
        try(var producer=new KafkaProducer<byte[],byte[]>(CatalogKafkaPublisher.properties(BROKER.bootstrap()))) {
            for(byte[] bytes:input)coordinates.add(producer.send(new ProducerRecord<>(VersionedAdCatalogEvent.TOPIC,key,bytes)).get(5,TimeUnit.SECONDS).offset());
        }
        var records=new ArrayList<ConsumerRecord<byte[],byte[]>>();
        try(var consumer=new KafkaConsumer<byte[],byte[]>(Map.of("bootstrap.servers",BROKER.bootstrap(),"group.id","catalog-wire-"+UUID.randomUUID(),"enable.auto.commit",false,
                "key.deserializer","org.apache.kafka.common.serialization.ByteArrayDeserializer","value.deserializer","org.apache.kafka.common.serialization.ByteArrayDeserializer"))) {
            consumer.assign(List.of(new TopicPartition(VersionedAdCatalogEvent.TOPIC,0)));consumer.seekToBeginning(List.of(new TopicPartition(VersionedAdCatalogEvent.TOPIC,0)));
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(records.size()<4 && System.nanoTime()<deadline)consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        assertThat(records).hasSize(4);var evidence=new ArrayList<Map<String,Object>>();
        for(int i=0;i<4;i++) {
            var record=records.get(i);assertThat(record.key()).isEqualTo(key);assertThat(record.value()).isEqualTo(input.get(i));assertThat(record.offset()).isEqualTo(coordinates.get(i));
            var event=codec.decode(record.key(),record.value());
            evidence.add(Map.of("partition",record.partition(),"offset",record.offset(),"eventId",event.eventId(),"version",event.aggregateVersion(),"sha256",CatalogEventCodec.digest(record.value()),
                    "keyBase64",Base64.getEncoder().encodeToString(record.key()),"payloadBase64",Base64.getEncoder().encodeToString(record.value())));
        }
        assertThat(codec.decode(key,high).aggregateVersion()).isGreaterThan(codec.decode(key,low).aggregateVersion());
        assertThat(codec.decode(key,low).eventId()).isEqualTo(codec.decode(key,conflict).eventId());
        assertThat(CatalogEventCodec.digest(low)).isNotEqualTo(CatalogEventCodec.digest(conflict));
        Path path=Path.of("target/catalog-wire-evidence",UUID.randomUUID()+".json");Files.createDirectories(path.getParent());
        Files.writeString(path,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
}
