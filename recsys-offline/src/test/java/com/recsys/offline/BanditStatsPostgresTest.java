package com.recsys.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.bandit.BanditModelDto;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.mapper.primary.BanditImpressionMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.rank.FeatureAssembler;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实PG/Redis保护曝光输入及模型矩阵；特征服务替身只固定输入，不替代SQL验证。 */
@Testcontainers
class BanditStatsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory redisFactory;
    static FeatureService features;
    static ContentService content;
    static BanditStatsJob job;
    static final ObjectMapper JSON=new ObjectMapper();

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=bandit-stats-reader",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        for(String table:List.of("user_behavior","behavior_log")){
            db.execute("CREATE TABLE "+table+"(user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION,ts TIMESTAMPTZ)");
            db.execute("COMMENT ON TABLE "+table+" IS 'Bandit模型独占行为源'");
            for(String column:List.of("user_id","item_id","action","value","ts"))db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原行为字段'");
        }
        redisFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        features=mock(FeatureService.class);content=mock(ContentService.class);
        // 固定x=(1,0,0,0,0)，可独立逐项校验A与b，避免复用训练算法生成期望。
        when(features.itemFeatures(anyCollection())).thenAnswer(call->{
            java.util.Collection<Long> ids=call.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id->id,id->Map.of("item_pop_norm",1.0,"item_avg_rating",0.0)));
        });
        when(features.userFeatures(anyLong())).thenReturn(Map.of("user_act_norm",0.0,"user_avg_rating",0.0));
        when(content.findByIds(anyList())).thenReturn(Map.of());
        var sessions=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml"));
        job=new BanditStatsJob(sessions.getMapper(PositiveFeedbackMapper.class),sessions.getMapper(BanditImpressionMapper.class),redis,features,content);
    }
    @AfterAll static void close(){if(redisFactory!=null)redisFactory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE user_behavior,behavior_log");redis.delete(RedisKeys.BANDIT_MODEL);clearInvocations(features,content);}

    @Test void repeatedImpressionsAndNullableIdentitiesKeepOriginalRewards() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(1,10,'IMPRESSION',NULL,NULL),(1,10,'IMPRESSION',NULL,NULL),(2,20,'IMPRESSION',NULL,NULL),(NULL,NULL,'IMPRESSION',NULL,NULL),(9223372036854775807,9223372036854775807,'IMPRESSION',NULL,NULL),(1,10,'CLICK',1,NULL),(NULL,NULL,'LIKE',1,NULL),(2,20,'RATING',3.9,NULL)");
        job.run(new DefaultApplicationArguments("--days=0"));assertModel(5,3,1);
        verify(features,times(1)).userFeatures(1L);verify(features).userFeatures(0L);verify(features).userFeatures(Long.MAX_VALUE);
        source.assertClosed(admin,"bandit-stats-reader");
    }

    @Test void replicaAndStrictCutoffFilterImpressionsAndRewards() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(99,99,'IMPRESSION',1,to_timestamp(1))");
        db.execute("INSERT INTO behavior_log VALUES(1,10,'IMPRESSION',NULL,to_timestamp(99.999999)),(2,20,'IMPRESSION',NULL,to_timestamp(100)),(3,30,'IMPRESSION',NULL,to_timestamp(100.000001)),(1,10,'RATING',3,to_timestamp(100)),(2,20,'CLICK',1,to_timestamp(100.000001))");
        job.run(new DefaultApplicationArguments("--days=0","--behavior-table=behavior_log","--max-ts=100","--min-rating=3"));
        assertModel(2,1,1);source.assertClosed(admin,"bandit-stats-reader");
    }

    @Test void defaultThirtyDaysOnlyLimitsImpressionsAndStillIncludesFutureRows() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(9,9,'IMPRESSION',1,now()-interval '40 days'),(1,10,'IMPRESSION',1,now()-interval '12 hours'),(2,20,'IMPRESSION',1,now()+interval '1 day'),(1,10,'CLICK',1,now()-interval '40 days')");
        job.run(new DefaultApplicationArguments());assertModel(2,1,1);
        job.run(new DefaultApplicationArguments("--days=0"));assertModel(3,1,1);source.assertClosed(admin,"bandit-stats-reader");
    }

    @Test void incrementalKeepsLoadedLambdaAndMalformedOrWrongDimensionStartsFresh() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(1,10,'IMPRESSION',1,NULL),(2,20,'IMPRESSION',1,NULL),(1,10,'CLICK',1,NULL)");
        job.run(new DefaultApplicationArguments("--days=0","--lambda=2"));assertModel(2,1,2);
        job.run(new DefaultApplicationArguments("--days=0","--incremental","--lambda=99"));assertModel(4,2,2);
        redis.opsForValue().set(RedisKeys.BANDIT_MODEL,"invalid-json");
        job.run(new DefaultApplicationArguments("--days=0","--incremental","--lambda=3"));assertModel(2,1,3);
        redis.opsForValue().set(RedisKeys.BANDIT_MODEL,JSON.writeValueAsString(new BanditModelDto(List.of("one"),1,99,new double[][]{{100}},new double[]{1})));
        job.run(new DefaultApplicationArguments("--days=0","--incremental","--lambda=4"));assertModel(2,1,4);
        source.assertClosed(admin,"bandit-stats-reader");
    }

    @Test void emptyInputAndInvalidSourceKeepExistingModel() {
        redis.opsForValue().set(RedisKeys.BANDIT_MODEL,"keep");
        job.run(new DefaultApplicationArguments());
        assertThrows(IllegalArgumentException.class,()->job.run(new DefaultApplicationArguments("--behavior-table=user_behavior;DROP TABLE behavior_log")));
        assertEquals("keep",redis.opsForValue().get(RedisKeys.BANDIT_MODEL));verifyNoInteractions(features,content);
        source.assertClosed(admin,"bandit-stats-reader");
    }

    @Test void missingSourcePropagatesBeforeReplacingExistingModel() {
        redis.opsForValue().set(RedisKeys.BANDIT_MODEL,"keep");db.execute("ALTER TABLE user_behavior RENAME TO hidden_behavior");
        try{
            assertThrows(BadSqlGrammarException.class,()->job.run(new DefaultApplicationArguments()));
            assertEquals("keep",redis.opsForValue().get(RedisKeys.BANDIT_MODEL));source.assertClosed(admin,"bandit-stats-reader");
        }finally{db.execute("ALTER TABLE hidden_behavior RENAME TO user_behavior");}
    }

    private static void assertModel(long samples,long positives,double lambda) throws Exception {
        var model=JSON.readValue(redis.opsForValue().get(RedisKeys.BANDIT_MODEL),BanditModelDto.class);
        assertEquals(FeatureAssembler.FEATURE_ORDER,model.order());assertEquals(samples,model.n());assertEquals(lambda,model.lambda());
        for(int row=0;row<5;row++){
            assertEquals(row==0?positives:0,model.b()[row],1e-12);
            for(int col=0;col<5;col++)assertEquals((row==col?lambda:0)+(row==0&&col==0?samples:0),model.a()[row][col],1e-12);
        }
    }
}
