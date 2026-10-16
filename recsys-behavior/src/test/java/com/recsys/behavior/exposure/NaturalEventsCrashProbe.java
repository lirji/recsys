package com.recsys.behavior.exposure;

import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper.Pending;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/** 只在隔离验收的子 JVM 中运行；halt 模拟提交窗口进程突然退出，不执行 finally/关闭钩子。 */
public final class NaturalEventsCrashProbe {
    private NaturalEventsCrashProbe() { }

    /** 测试凭据通过子进程环境传入，不出现在命令行或证据日志。 */
    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        var ds = new DriverManagerDataSource(env.get("S15_JDBC"), env.get("S15_DB_USER"), env.get("S15_DB_PASSWORD"));
        var f = new NaturalEventsFixture(ds); f.outbox.verifySchema();
        var accepted = f.accept();
        System.out.println("S15_COMMITTED=" + accepted.result().eventId()); System.out.flush();
        if ("before-send".equals(args[0])) Runtime.getRuntime().halt(71);
        var repository = new NaturalOutboxRepository(f.sessions, new DataSourceTransactionManager(ds)) {
            @Override public boolean published(Pending event) {
                System.out.println("S15_ACK_BEFORE_MARK=" + event.eventId()); System.out.flush();
                Runtime.getRuntime().halt(72); return false;
            }
        };
        var config = new MockEnvironment().withProperty("spring.kafka.bootstrap-servers", env.get("S15_KAFKA"));
        try (var publisher = new NaturalKafkaPublisher(new KafkaProducer<>(NaturalEventConfiguration.producerProperties(config)), env.get("S15_TOPIC"))) {
            new NaturalOutboxRelay(repository, publisher, f.codec, new SimpleMeterRegistry()).relayOne();
        }
        throw new IllegalStateException("测试未到指定崩溃窗口");
    }
}
