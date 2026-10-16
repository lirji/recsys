package com.recsys.behavior.events;

import com.recsys.behavior.BehaviorService;
import com.recsys.behavior.persistence.mapper.primary.BehaviorMapper;
import com.recsys.behavior.events.BehaviorEventRepository.Destination;
import com.recsys.common.constant.ActionType;
import com.recsys.common.dto.BehaviorEvent;
import com.recsys.persistence.spring.SpringSqlSessions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 独占PG schema，归因使用真实IMPRESSION回查；Redis替身只代表缓存miss，不证明消费效果。 */
class BehaviorEventsFixture {
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final SqlSessionTemplate sessions;
    final BehaviorEventRepository repo;
    final BehaviorEventCodec codec=new BehaviorEventCodec();
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    BehaviorEventsFixture(PostgreSQLContainer<?> pg, boolean migrate) throws Exception {
        this(schema(pg));
        jdbc.execute("""
            CREATE TABLE user_behavior(id BIGSERIAL PRIMARY KEY,user_id BIGINT,item_id BIGINT,action TEXT,
                value DOUBLE PRECISION,scene TEXT,bucket TEXT,ts TIMESTAMP DEFAULT now(),position INT,exposure_id TEXT);
            CREATE UNIQUE INDEX uq_behavior_click_exposure ON user_behavior(exposure_id) WHERE action='CLICK' AND exposure_id IS NOT NULL;
            COMMENT ON TABLE user_behavior IS '独占行为测试源表';
            """);
        for (String col:List.of("id","user_id","item_id","action","value","scene","bucket","ts","position","exposure_id"))
            jdbc.execute("COMMENT ON COLUMN user_behavior."+col+" IS '原行为语义测试字段'");
        if(migrate) migrate(null,BehaviorEventRepository.migrationSha256());
    }
    BehaviorEventsFixture(DriverManagerDataSource ds) throws Exception {
        this.ds=ds; jdbc=new JdbcTemplate(ds);
        sessions=new SqlSessionTemplate(SpringSqlSessions.create(ds,"classpath*:mapper/behavior/primary/**/*.xml"));
        repo=new BehaviorEventRepository(sessions,new DataSourceTransactionManager(ds));
    }
    private static DriverManagerDataSource schema(PostgreSQLContainer<?> pg) {
        var admin=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
        String schema="behavior_"+UUID.randomUUID().toString().replace("-",""); admin.execute("CREATE SCHEMA "+schema);
        return new DriverManagerDataSource(pg.getJdbcUrl()+(pg.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema,pg.getUsername(),pg.getPassword());
    }
    void migrate(String override,String hash) throws Exception {
        try(var in=new ClassPathResource(BehaviorEventRepository.MIGRATION).getInputStream();var c=ds.getConnection();var s=c.createStatement()) {
            s.execute("SET recsys.migration_sha256='"+hash+"'");
            s.execute(override==null?new String(in.readAllBytes(),StandardCharsets.UTF_8):override);
        }
    }
    @SuppressWarnings("unchecked")
    BehaviorService service() {
        var redis=mock(StringRedisTemplate.class); var values=mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values);
        var service=new BehaviorService(sessions.getMapper(BehaviorMapper.class),mock(ObjectProvider.class),redis,metrics);
        service.configureReliableCapture(repo,new BehaviorEventConfiguration.Properties(true,false,false,null,null)); return service;
    }
    BehaviorEvent event(ActionType action,String exposure) { return new BehaviorEvent(7,42,action,1,"home",null,1700000012345L,exposure); }
    void impression(String exposure) { jdbc.update("INSERT INTO user_behavior(user_id,item_id,action,value,bucket,exposure_id) VALUES(7,42,'IMPRESSION',1,'rank:original',?)",exposure); }
    String accept() { repo.accept(event(ActionType.RATING,null)); return lastId(); }
    String lastId() { return jdbc.queryForObject("SELECT event_id FROM behavior_event_outbox ORDER BY created_at DESC,event_id DESC LIMIT 1",String.class); }
    String payload(String id) { return jdbc.queryForObject("SELECT wire_payload_json FROM behavior_event_outbox WHERE event_id=?",String.class,id); }
    String state(String id) { return jdbc.queryForObject("SELECT status FROM behavior_event_outbox WHERE event_id=?",String.class,id); }
    int count(String table) {
        if(!Set.of("user_behavior","behavior_event_outbox","behavior_event_replay").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);
    }
    void due(String id) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(35).toNanos();
        while(!jdbc.queryForObject("SELECT available_at<=clock_timestamp() FROM behavior_event_outbox WHERE event_id=?",Boolean.class,id)) {
            assertTrue(System.nanoTime()<deadline,"真实DB租约未按期到期"); Thread.sleep(50);
        }
    }
    void dead(String id) { var p=repo.claim(Destination.LIVE).orElseThrow(); assertEquals(id,p.eventId()); assertTrue(repo.failed(p,1000,BehaviorEventRepository.Error.INVALID_EVENT)); }
}
