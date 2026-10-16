package com.recsys.behavior.events;

import com.recsys.behavior.BehaviorService;
import com.recsys.behavior.persistence.mapper.primary.BehaviorMapper;
import com.recsys.common.constant.ActionType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 验证真实Spring装配调用可靠仓储，不仅验证测试手动调用setter。 */
@Tag("behavior-reliability") @Testcontainers
class BehaviorEventWiringPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test @SuppressWarnings("unchecked")
    void legacyFourFlagCombinationsStillPublishAtMostOnceAndNeedNoMigration() throws Exception {
        var f=new BehaviorEventsFixture(PG,false);
        for(boolean use:java.util.List.of(false,true)) for(boolean publish:java.util.List.of(false,true)) {
            var kafka=mock(org.springframework.kafka.core.KafkaTemplate.class);
            org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            new ApplicationContextRunner().withUserConfiguration(BehaviorEventConfiguration.class)
                    .withBean(BehaviorMapper.class,()->f.sessions.getMapper(BehaviorMapper.class))
                    .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
                    .withBean(StringRedisTemplate.class,()->mock(StringRedisTemplate.class))
                    .withBean(org.springframework.kafka.core.KafkaTemplate.class,()->kafka)
                    .withBean(BehaviorService.class)
                    .withPropertyValues("recsys.behavior.use-kafka="+use,"recsys.behavior.publish-events="+publish)
                    .run(c->{
                        assertNull(c.getStartupFailure());c.getBean(BehaviorService.class).record(f.event(ActionType.RATING,null));
                        org.mockito.Mockito.verify(kafka,org.mockito.Mockito.times(use||publish?1:0))
                                .send(org.mockito.ArgumentMatchers.eq("behavior-events"),org.mockito.ArgumentMatchers.eq("7"),org.mockito.ArgumentMatchers.anyString());
                    });
        }
        assertEquals(4,f.count("user_behavior"));
        assertNull(f.jdbc.queryForObject("SELECT to_regclass('behavior_event_outbox')",String.class));
    }

    @Test void enabledCaptureUsesActualPrimaryRepositoryAndDefaultKeepsOldPath() throws Exception {
        var f=new BehaviorEventsFixture(PG,true);
        var runner=new ApplicationContextRunner().withUserConfiguration(BehaviorEventConfiguration.class)
                .withBean("dataSource",DataSource.class,()->f.ds)
                .withBean("sqlSessionTemplate",org.mybatis.spring.SqlSessionTemplate.class,()->f.sessions)
                .withBean(BehaviorMapper.class,()->f.sessions.getMapper(BehaviorMapper.class))
                .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
                .withBean(StringRedisTemplate.class,()->mock(StringRedisTemplate.class))
                .withBean(BehaviorService.class);
        runner.withPropertyValues("recsys.behavior.events.capture-enabled=true").run(c->{
            assertNull(c.getStartupFailure());c.getBean(BehaviorService.class).record(f.event(ActionType.RATING,null));
            assertEquals(1,f.count("user_behavior"));assertEquals(1,f.count("behavior_event_outbox"));
            assertEquals(0,c.getBeansOfType(BehaviorEventWorker.class).size());
        });
        runner.run(c->{
            assertNull(c.getStartupFailure());c.getBean(BehaviorService.class).record(f.event(ActionType.RATING,null));
            assertEquals(2,f.count("user_behavior"));assertEquals(1,f.count("behavior_event_outbox"));
        });
        runner.withPropertyValues("recsys.behavior.events.capture-enabled=true","recsys.behavior.publish-events=true").run(c->{
            assertNotNull(c.getStartupFailure());assertEquals(2,f.count("user_behavior"));assertEquals(1,f.count("behavior_event_outbox"));
        });
    }
}
