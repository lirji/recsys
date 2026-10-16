package com.recsys.behavior.events;

import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import com.recsys.behavior.persistence.mapper.primary.BehaviorEventMapper.Pending;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;

/** 只连接测试父进程传入的独占实例，halt不运行finally或关闭钩子。 */
public final class BehaviorEventsCrashProbe {
    private BehaviorEventsCrashProbe() { }
    /** 凭据仅通过环境传递，日志只留事件身份和命中窗口。 */
    public static void main(String[] args) throws Exception {
        var env=System.getenv();String mode=args[0];
        var f=new BehaviorEventsFixture(new DriverManagerDataSource(env.get("S15B_JDBC"),env.get("S15B_DB_USER"),env.get("S15B_DB_PASSWORD")));
        f.repo.verifySchema();String id=mode.equals("final-claim")?f.lastId():f.accept();
        if(mode.equals("before-send")) halt(mode,id,81);
        if(mode.equals("after-claim")||mode.equals("final-claim")) {
            Pending claimed=f.repo.claim(Destination.LIVE).orElseThrow();
            if(mode.equals("final-claim")&&claimed.attempts()!=20)throw new IllegalStateException("未命中最后一次领取");
            halt(mode,id,82);
        }
        var repo=new BehaviorEventRepository(f.sessions,new DataSourceTransactionManager(f.ds)) {
            @Override public boolean published(Pending event) {halt(mode,event.eventId(),83);return false;}
        };
        var config=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers",env.get("S15B_KAFKA"));
        try(var publisher=new BehaviorKafkaPublisher(new KafkaProducer<>(BehaviorEventConfiguration.producerProperties(config)),Destination.LIVE,env.get("S15B_TOPIC"))) {
            new BehaviorEventRelay(repo,publisher,f.codec,new SimpleMeterRegistry(),Destination.LIVE).relayOne();
        }
        throw new IllegalStateException("未到崩溃窗口");
    }
    private static void halt(String mode,String id,int code) {
        System.out.println("S15B_WINDOW="+mode+" eventId="+id);System.out.flush();Runtime.getRuntime().halt(code);
    }
}
