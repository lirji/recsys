package com.recsys.streaming.reliable;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.common.behavior.LegacyBehaviorAccepted.*;
import com.recsys.common.constant.ActionType;
import com.recsys.platform.behavior.LegacyBehaviorCodec;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 每个测试拥有独立topic/namespace/checkpoint；绝不使用共享开发数据。 */
final class FeatureFixture {
    static final String SOURCE = "7948deba-fce8-493b-9626-b56c7333fc30";
    static byte[] event(long id, long item, long ts, ActionType action) {
        var e = new LegacyBehaviorAccepted(LegacyBehaviorAccepted.TYPE, 1, SOURCE, Long.toString(id),
                LegacyBehaviorAccepted.identity(SOURCE, Long.toString(id)), Origin.BEHAVIOR_POST, PriorPublication.NOT_ATTEMPTED,
                new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(Instant.ofEpochMilli(ts)),
                new Behavior("1", Long.toString(item), action, 0, null, null, null, ts), null);
        return new LegacyBehaviorCodec().encode(e).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static void await(BooleanSupplier test, int seconds) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!test.getAsBoolean()) { assertTrue(System.nanoTime() < end, "有界等待未满足业务断言"); Thread.sleep(100); }
    }
    static class Kafka extends GenericContainer<Kafka> {
        Kafka() {
            super("apache/kafka:3.7.1"); withExposedPorts(19092);
            withEnv(Map.of("KAFKA_NODE_ID","1","KAFKA_PROCESS_ROLES","broker,controller","KAFKA_CONTROLLER_QUORUM_VOTERS","1@localhost:9093",
                    "KAFKA_LISTENERS","INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:19092",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP","INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME","INTERNAL","KAFKA_CONTROLLER_LISTENER_NAMES","CONTROLLER",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR","1","KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS","0","KAFKA_AUTO_CREATE_TOPICS_ENABLE","false"));
            withCommand("/bin/bash","-c","while [ ! -f /tmp/feature-kafka-start ]; do sleep 0.1; done; . /tmp/feature-kafka-start; exec /etc/kafka/docker/run");
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(InspectContainerResponse info) {
            copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://" + bootstrap() + "'\n", 0644), "/tmp/feature-kafka-start");
        }
        String bootstrap() { return getHost() + ":" + getMappedPort(19092); }
    }
}
