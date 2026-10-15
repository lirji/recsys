package com.recsys.recengine.experiment;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.RecommendItem;
import com.recsys.recengine.PersonalizationScorer;
import com.recsys.recengine.RecEngineProperties;
import com.recsys.recengine.coldstart.ColdStartDetector;
import com.recsys.recengine.filter.DbSeenItemsSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.recsys.recengine.persistence.config.EnginePersistenceConfiguration;
import com.recsys.recengine.persistence.mapper.primary.EngineBehaviorMapper;
import com.recsys.recengine.persistence.mapper.derived.EngineEmbeddingMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实pgvector和互斥schema保护推荐输入源绑定、降级及异步曝光的批次原子性。 */
@Testcontainers
class EngineInputsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate primary, derived;
    static AnnotationConfigApplicationContext context;

    @BeforeAll static void schema() {
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        admin.execute("CREATE EXTENSION vector");
        admin.execute("CREATE SCHEMA engine_primary"); admin.execute("CREATE SCHEMA engine_derived");
        primary=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl()+"&currentSchema=engine_primary,public",PG.getUsername(),PG.getPassword()));
        derived=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl()+"&currentSchema=engine_derived,public",PG.getUsername(),PG.getPassword()));
        create(primary,"user_behavior","user_id bigint,item_id bigint CHECK(item_id>0),action text,value float8,scene text,bucket text,position int,exposure_id text");
        create(primary,"user_embedding","user_id bigint PRIMARY KEY,embedding vector(3)");
        create(derived,"user_behavior","user_id bigint,item_id bigint,action text");
        create(derived,"user_embedding","user_id bigint PRIMARY KEY,embedding vector(3)");
        create(derived,"item_embedding","item_id bigint PRIMARY KEY,embedding vector(3)");
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource",primary.getDataSource());
        context.getBeanFactory().registerSingleton("derivedDbDataSource",derived.getDataSource());
        context.register(EnginePersistenceConfiguration.class); context.refresh();
    }
    private static void create(JdbcTemplate db,String table,String columns) {
        db.execute("CREATE TABLE "+table+"("+columns+")");
        db.execute("COMMENT ON TABLE "+table+" IS '推荐引擎迁移隔离测试表'");
        // 固定schema元数据中的列名，不接收请求输入。
        for(String name:db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=?",String.class,table))
            db.execute("COMMENT ON COLUMN "+table+"."+name+" IS '推荐输入测试字段'");
    }
    @AfterAll static void close() { if(context != null) context.close(); }
    @BeforeEach void reset() {
        primary.execute("TRUNCATE user_behavior,user_embedding");
        derived.execute("TRUNCATE user_behavior,user_embedding,item_embedding");
    }
    private ColdStartDetector cold() {
        var props=new RecEngineProperties(); props.getColdStart().setMinBehaviors(2);
        return new ColdStartDetector(context.getBean(EngineBehaviorMapper.class),context.getBean(EngineEmbeddingMapper.class),props);
    }
    private PersonalizationScorer scorer() { return new PersonalizationScorer(context.getBean(EngineEmbeddingMapper.class)); }
    private DbSeenItemsSource seen() { return new DbSeenItemsSource(context.getBean(EngineBehaviorMapper.class)); }

    @Test void sessionFactoriesUseTheExactOriginalSources() {
        assertThat(context.getBean("sqlSessionTemplate",SqlSessionTemplate.class).getSqlSessionFactory()
                .getConfiguration().getEnvironment().getDataSource()).isSameAs(primary.getDataSource());
        assertThat(context.getBean("derivedSqlSessionTemplate",SqlSessionTemplate.class).getSqlSessionFactory()
                .getConfiguration().getEnvironment().getDataSource()).isSameAs(derived.getDataSource());
    }
    @Test void seenOnlyUsesPositiveActionsAndKeepsDistinctNullableIds() {
        primary.execute("INSERT INTO user_behavior(user_id,item_id,action) VALUES(7,1,'CLICK'),(7,1,'LIKE'),(7,2,'PLAY'),(7,3,'RATING'),(7,4,'IMPRESSION'),(7,5,'OTHER'),(8,6,'CLICK'),(7,NULL,'LIKE')");
        derived.execute("INSERT INTO user_behavior VALUES(7,99,'CLICK')");
        assertThat(seen().seenItems(7)).containsExactlyInAnyOrder(1L,2L,3L,null);
        assertThat(seen().seenItems(99)).isEmpty();
    }
    @Test void coldUsesPrimaryBehaviorThresholdAndDerivedVectorExistence() {
        primary.execute("INSERT INTO user_embedding VALUES(7,'[1,0,0]')");
        derived.execute("INSERT INTO user_behavior VALUES(7,99,'CLICK'),(7,100,'LIKE')");
        primary.execute("INSERT INTO user_behavior(user_id,item_id,action) VALUES(7,1,'IMPRESSION'),(7,2,'IMPRESSION')");
        assertThat(cold().isCold(7)).isTrue();
        primary.execute("INSERT INTO user_behavior(user_id,item_id,action) VALUES(7,1,'CLICK')");
        assertThat(cold().isCold(7)).isTrue();
        primary.execute("INSERT INTO user_behavior(user_id,item_id,action) VALUES(7,2,'LIKE')");
        assertThat(cold().isCold(7)).isFalse();
        derived.execute("INSERT INTO user_embedding VALUES(8,NULL)");
        assertThat(cold().isCold(8)).isFalse();
        assertThat(cold().isCold(9)).isTrue();
    }
    @Test void affinityHandlesCosineMissingUserNullVectorAndRepeatedCandidates() {
        derived.execute("INSERT INTO user_embedding VALUES(7,'[1,0,0]'),(8,NULL)");
        derived.execute("INSERT INTO item_embedding VALUES(1,'[1,0,0]'),(2,'[0,1,0]'),(3,'[-1,0,0]'),(4,NULL)");
        var result=scorer().affinity(7,List.of(1L,2L,3L,4L,999L,1L));
        assertThat(result).containsOnly(entry(1L,1.0),entry(2L,0.0),entry(3L,-1.0));
        assertThat(scorer().affinity(8,List.of(1L))).isEmpty();
        assertThat(scorer().affinity(9,List.of(1L))).isEmpty();
        assertThat(scorer().affinity(7,List.of())).isEmpty();
        assertThat(scorer().affinity(7,null)).isEmpty();
    }
    @Test void primaryAndDerivedFailuresKeepOriginalOpenFallbacks() {
        primary.execute("ALTER TABLE user_behavior RENAME TO unavailable_behavior");
        try { assertThat(seen().seenItems(7)).isEmpty(); assertThat(cold().isCold(7)).isFalse(); }
        finally { primary.execute("ALTER TABLE unavailable_behavior RENAME TO user_behavior"); }
        derived.execute("ALTER TABLE user_embedding RENAME TO unavailable_embedding");
        try { assertThat(cold().isCold(7)).isFalse(); assertThat(scorer().affinity(7,List.of(1L))).isEmpty(); }
        finally { derived.execute("ALTER TABLE unavailable_embedding RENAME TO user_embedding"); }
    }
    @Test @SuppressWarnings("unchecked") void exposureKeepsSynchronousAttributionAndOneBasedPositions() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        ValueOperations<String,String> values=mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values);
        var registry=new SimpleMeterRegistry();
        var logger=new ExposureLogger(context.getBean(EngineBehaviorMapper.class),redis,registry);
        try {
            logger.log(7,"home","recall:plus;rank:v1",List.of(item(11,"exp-11"),item(12,null)));
            verify(values).set(eq(RedisKeys.exposure("exp-11")),anyString(),any(Duration.class));
            verify(values).set(eq(RedisKeys.latestExposure(7,11)),eq("exp-11"),any(Duration.class));
        } finally { drain(logger); }
        assertThat(primary.queryForList("SELECT item_id FROM user_behavior ORDER BY position",Long.class)).containsExactly(11L,12L);
        assertThat(primary.queryForList("SELECT value FROM user_behavior ORDER BY position",Double.class)).containsExactly(1.0,2.0);
        assertThat(primary.queryForList("SELECT position FROM user_behavior ORDER BY position",Integer.class)).containsExactly(1,2);
        assertThat(primary.queryForList("SELECT action FROM user_behavior",String.class)).containsOnly("IMPRESSION");
        assertThat(primary.queryForObject("SELECT exposure_id FROM user_behavior WHERE item_id=12",String.class)).isNull();
        assertThat(registry.get("recsys.exposure").counter().count()).isEqualTo(2);
    }
    @Test void failedExposureBatchDoesNotLeaveTheFirstItemCommitted() throws Exception {
        var registry=new SimpleMeterRegistry(); var logger=new ExposureLogger(context.getBean(EngineBehaviorMapper.class),mock(StringRedisTemplate.class),registry);
        try { logger.log(7,null,null,List.of(item(11,"first"),item(-1,"invalid"))); }
        finally { drain(logger); }
        assertThat(primary.queryForObject("SELECT count(*) FROM user_behavior",Long.class)).isZero();
        // 原打点发生在异步写库之前；此次迁移不改变已有指标口径。
        assertThat(registry.get("recsys.exposure").counter().count()).isEqualTo(2);
    }
    @Test void emptyExposureDoesNotTouchStorageOrAttribution() throws Exception {
        var redis=mock(StringRedisTemplate.class); var registry=new SimpleMeterRegistry(); var logger=new ExposureLogger(context.getBean(EngineBehaviorMapper.class),redis,registry);
        try { logger.log(7,"home",null,List.of()); logger.log(7,"home",null,null); }
        finally { drain(logger); }
        verifyNoInteractions(redis); assertThat(registry.find("recsys.exposure").counter()).isNull();
        assertThat(primary.queryForObject("SELECT count(*) FROM user_behavior",Long.class)).isZero();
    }
    private static RecommendItem item(long id,String exposure) { return new RecommendItem(id,1,List.of("HOT"),"test",exposure); }
    private static void drain(ExposureLogger logger) throws Exception {
        logger.shutdown();
        var executor=(ExecutorService)ReflectionTestUtils.getField(logger,"executor");
        assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
    }
}
