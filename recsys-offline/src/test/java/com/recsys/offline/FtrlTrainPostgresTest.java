package com.recsys.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recsys.common.constant.RedisKeys;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.mapper.primary.TrainingCatalogMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG/Redis冻结物品池原顺序、负采样模型输出、严格窗口和失败保留边界。 */
@Testcontainers
class FtrlTrainPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory redisFactory;
    static FtrlTrainJob job;
    static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=ftrl-reader",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        for(String table:List.of("user_behavior","behavior_log")){
            db.execute("CREATE TABLE "+table+"(user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION,ts TIMESTAMPTZ)");
            db.execute("COMMENT ON TABLE "+table+" IS 'FTRL独占行为源'");
            for(String column:List.of("user_id","item_id","action","value","ts"))db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原行为字段'");
        }
        for(String table:List.of("item","item_local")){
            db.execute("CREATE TABLE "+table+"(item_id BIGINT)");
            db.execute("COMMENT ON TABLE "+table+" IS 'FTRL独占物品池，验证原无序扫描与可空旧行'");
            db.execute("COMMENT ON COLUMN "+table+".item_id IS '候选物品身份'");
        }
        redisFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        var sessions=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml"));
        job=new FtrlTrainJob(sessions.getMapper(PositiveFeedbackMapper.class),sessions.getMapper(TrainingCatalogMapper.class),redis);
    }
    @AfterAll static void close(){if(redisFactory!=null)redisFactory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE user_behavior,behavior_log,item,item_local");redis.delete(List.of(RedisKeys.FTRL_WEIGHTS,RedisKeys.FTRL_STATE));}

    @Test void defaultTrainingPreservesPoolOrderAndEntireServedModelAndState() throws Exception {
        seedPrimary();job.run(new DefaultApplicationArguments());String first=fingerprint();
        assertEquals("cd2da7d0353ea49dfe292bbf2c5b605a93301cafd5e0e9b1f77f347368b13b12", first);
        assertFalse(JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS)).path("w").isEmpty());
        job.run(new DefaultApplicationArguments());assertEquals(first,fingerprint());source.assertClosed(admin,"ftrl-reader");
    }

    @Test void bothReplicaSourcesStrictCutoffAndNullPositiveIdsArePreserved() throws Exception {
        db.execute("INSERT INTO item VALUES(999);INSERT INTO item_local VALUES(30),(10),(20)");
        db.execute("INSERT INTO behavior_log SELECT CASE WHEN n=1 THEN NULL ELSE n%3 END,CASE WHEN n=1 THEN NULL ELSE (n%3+1)*10 END,'RATING',3,to_timestamp(100) FROM generate_series(1,100)n");
        db.execute("INSERT INTO behavior_log VALUES(99,999,'CLICK',1,to_timestamp(100.000001));INSERT INTO user_behavior VALUES(999,999,'CLICK',1,to_timestamp(1))");
        job.run(new DefaultApplicationArguments("--behavior-table=behavior_log","--item-table=item_local","--min-rating=3","--max-ts=100"));
        assertEquals("485f4975ab3fe33ceac272b0474467a8717f33b4171a6596d31b9cbcb8d462ae", fingerprint());
        assertNotNull(redis.opsForValue().get(RedisKeys.FTRL_STATE));source.assertClosed(admin,"ftrl-reader");
    }

    @Test void incrementalContinuesStateAndMalformedStateFallsBackToSameFreshModel() throws Exception {
        seedPrimary();job.run(new DefaultApplicationArguments());String fresh=fingerprint();
        job.run(new DefaultApplicationArguments("--incremental"));String continued=fingerprint();
        assertEquals("e247772706416d5c0cc99d8df24cc2def1e71965e237edf59f14f9be6d93258e", continued);assertNotEquals(fresh,continued);
        redis.opsForValue().set(RedisKeys.FTRL_STATE,"invalid-json");job.run(new DefaultApplicationArguments("--incremental"));assertEquals(fresh,fingerprint());
        source.assertClosed(admin,"ftrl-reader");
    }

    @Test void emptyPoolAndNinetyNinePositivesKeepBothPublishedValues() {
        oldModel();job.run(new DefaultApplicationArguments());assertOldModel();
        db.execute("INSERT INTO item VALUES(1);INSERT INTO user_behavior SELECT 1,1,'CLICK',1,NULL FROM generate_series(1,99)");
        job.run(new DefaultApplicationArguments());assertOldModel();source.assertClosed(admin,"ftrl-reader");
    }

    @Test void zeroEpochsKeepsOriginalEmptyModelPublicationAtHundredSampleThreshold() throws Exception {
        db.execute("INSERT INTO item VALUES(1);INSERT INTO user_behavior SELECT 1,1,'CLICK',1,NULL FROM generate_series(1,100)");
        job.run(new DefaultApplicationArguments("--epochs=0"));
        var weights=JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS));var state=JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_STATE));
        assertEquals(0,weights.path("bias").asDouble());assertTrue(weights.path("w").isEmpty());assertTrue(state.path("c").isEmpty());assertEquals(0,state.path("bias").path(0).asDouble());assertEquals(0,state.path("bias").path(1).asDouble());source.assertClosed(admin,"ftrl-reader");
    }

    @Test void missingOrInvalidCatalogAndNullSamplePoolDoNotReplaceModel() {
        oldModel();assertThrows(IllegalArgumentException.class,()->job.run(new DefaultApplicationArguments("--item-table=item;DROP TABLE item_local")));
        db.execute("ALTER TABLE item RENAME TO hidden_item");
        try{assertThrows(BadSqlGrammarException.class,()->job.run(new DefaultApplicationArguments()));assertOldModel();source.assertClosed(admin,"ftrl-reader");}
        finally{db.execute("ALTER TABLE hidden_item RENAME TO item");}
        db.execute("INSERT INTO item VALUES(NULL);INSERT INTO user_behavior SELECT 1,1,'CLICK',1,NULL FROM generate_series(1,100)");
        assertThrows(NullPointerException.class,()->job.run(new DefaultApplicationArguments()));assertOldModel();source.assertClosed(admin,"ftrl-reader");
    }

    private static void seedPrimary(){db.execute("INSERT INTO item VALUES(30),(10),(20),(50),(40);INSERT INTO user_behavior SELECT (n-1)%7+1,(n%5+1)*10,'CLICK',1,to_timestamp(n) FROM generate_series(1,200)n");}
    private static void oldModel(){redis.opsForValue().set(RedisKeys.FTRL_WEIGHTS,"keep-weights");redis.opsForValue().set(RedisKeys.FTRL_STATE,"keep-state");}
    private static void assertOldModel(){assertEquals("keep-weights",redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS));assertEquals("keep-state",redis.opsForValue().get(RedisKeys.FTRL_STATE));}
    private static String fingerprint() throws Exception {
        String values=JSON.writeValueAsString(JSON.readValue(redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS),Object.class))+"\n"+JSON.writeValueAsString(JSON.readValue(redis.opsForValue().get(RedisKeys.FTRL_STATE),Object.class));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(values.getBytes(StandardCharsets.UTF_8)));
    }
}
