package com.recsys.offline;

import com.recsys.common.error.PublicErrorException;
import com.recsys.common.error.ErrorCode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashSet;

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
    final ListAppender<ILoggingEvent> logs=new ListAppender<>();
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
    @BeforeEach void reset(){logs.start();((Logger)LoggerFactory.getLogger(FtrlTrainJob.class)).addAppender(logs);db.execute("TRUNCATE user_behavior,behavior_log,item,item_local");redis.delete(List.of(RedisKeys.FTRL_WEIGHTS,RedisKeys.FTRL_STATE));}

    @AfterEach void closeLogAndConnections(){
        ((Logger)LoggerFactory.getLogger(FtrlTrainJob.class)).detachAppender(logs);logs.stop();
        source.assertClosed(admin,"ftrl-reader");
    }

    @Test void defaultTrainingPreservesPoolOrderAndEntireServedModelAndState() throws Exception {
        seedPrimary();job.run(new DefaultApplicationArguments());String first=fingerprint();
        assertGolden("default");
        assertFalse(JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS)).path("w").isEmpty());
        job.run(new DefaultApplicationArguments());assertEquals(first,fingerprint());source.assertClosed(admin,"ftrl-reader");
    }

    @Test void bothReplicaSourcesStrictCutoffAndNullPositiveIdsArePreserved() throws Exception {
        db.execute("INSERT INTO item VALUES(999);INSERT INTO item_local VALUES(30),(10),(20)");
        db.execute("INSERT INTO behavior_log SELECT CASE WHEN n=1 THEN NULL ELSE n%3 END,CASE WHEN n=1 THEN NULL ELSE (n%3+1)*10 END,'RATING',3,to_timestamp(100) FROM generate_series(1,100)n");
        db.execute("INSERT INTO behavior_log VALUES(99,999,'CLICK',1,to_timestamp(100.000001));INSERT INTO user_behavior VALUES(999,999,'CLICK',1,to_timestamp(1))");
        job.run(new DefaultApplicationArguments("--behavior-table=behavior_log","--item-table=item_local","--min-rating=3","--max-ts=100"));
        assertGolden("replica");
        assertNotNull(redis.opsForValue().get(RedisKeys.FTRL_STATE));source.assertClosed(admin,"ftrl-reader");
    }

    @Test void incrementalContinuesStateAndMalformedStateFallsBackToSameFreshModel() throws Exception {
        seedPrimary();job.run(new DefaultApplicationArguments());String fresh=fingerprint();
        job.run(new DefaultApplicationArguments("--incremental"));String continued=fingerprint();
        assertGolden("incremental");assertNotEquals(fresh,continued);
        redis.opsForValue().set(RedisKeys.FTRL_STATE,"invalid-json");job.run(new DefaultApplicationArguments("--incremental"));assertEquals(fresh,fingerprint());
        source.assertClosed(admin,"ftrl-reader");
    }

    @Test void emptyPoolAndNinetyNinePositivesKeepBothPublishedValues() {
        oldModel();rejected(job,ErrorCode.JOB_FTRL_NO_INPUT);assertOldModel();
        db.execute("INSERT INTO item VALUES(1);INSERT INTO user_behavior SELECT 1,1,'CLICK',1,NULL FROM generate_series(1,99)");
        rejected(job,ErrorCode.JOB_FTRL_NO_INPUT);assertOldModel();source.assertClosed(admin,"ftrl-reader");
    }

    @Test void zeroEpochsKeepsOriginalEmptyModelPublicationAtHundredSampleThreshold() throws Exception {
        db.execute("INSERT INTO item VALUES(1);INSERT INTO user_behavior SELECT 1,1,'CLICK',1,NULL FROM generate_series(1,100)");
        job.run(new DefaultApplicationArguments("--epochs=0"));
        var weights=JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS));var state=JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_STATE));
        assertEquals(0,weights.path("bias").asDouble());assertTrue(weights.path("w").isEmpty());assertTrue(state.path("c").isEmpty());assertEquals(0,state.path("bias").path(0).asDouble());assertEquals(0,state.path("bias").path(1).asDouble());source.assertClosed(admin,"ftrl-reader");
    }

    @Test void missingOrInvalidCatalogAndNullSamplePoolDoNotReplaceModel() {
        oldModel();assertThrows(PublicErrorException.class,()->job.run(new DefaultApplicationArguments("--item-table=item;DROP TABLE item_local")));
        db.execute("ALTER TABLE item RENAME TO hidden_item");
        try{assertInstanceOf(BadSqlGrammarException.class,rejected(job,ErrorCode.JOB_FTRL_FAILED).getCause());assertOldModel();source.assertClosed(admin,"ftrl-reader");}
        finally{db.execute("ALTER TABLE hidden_item RENAME TO item");}
        db.execute("INSERT INTO item VALUES(NULL);INSERT INTO user_behavior SELECT 1,1,'CLICK',1,NULL FROM generate_series(1,100)");
        assertInstanceOf(NullPointerException.class,rejected(job,ErrorCode.JOB_FTRL_FAILED).getCause());assertOldModel();source.assertClosed(admin,"ftrl-reader");
    }

    @Test void completeModelComparisonRejectsChangedSeedAndSortedNegativePool() throws Exception {
        seedPrimary();job.run(new DefaultApplicationArguments("--seed=43"));
        assertThrows(AssertionError.class,()->assertGolden("default"));
        db.execute("TRUNCATE item;INSERT INTO item VALUES(10),(20),(30),(40),(50)");
        job.run(new DefaultApplicationArguments());
        assertThrows(AssertionError.class,()->assertGolden("default"));
        source.assertClosed(admin,"ftrl-reader");
    }

    @Test void malformedStateIsValidatedCompletelyBeforeSafeFreshFallback() throws Exception {
        seedPrimary();job.run(new DefaultApplicationArguments());String fresh=fingerprint();
        for(String value:List.of("PRIVATE_BAD_JSON","null","{}",
                "{\"bias\":[0,-1],\"c\":{}}", "{\"bias\":[\"0\",0],\"c\":{}}",
                "{\"bias\":[0,0],\"c\":{\"1\":[3,2],\"2\":[0,-1]}}",
                "{\"bias\":[0,0],\"c\":{\"01\":[1,1]}}")) {
            redis.opsForValue().set(RedisKeys.FTRL_STATE,value);logs.list.clear();
            job.run(new DefaultApplicationArguments("--incremental"));assertEquals(fresh,fingerprint());
            assertTrue(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("[JOB_FTRL_MODEL_FALLBACK]")));
            assertFalse(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("PRIVATE_")));
        }
    }
    @Test void invalidParametersAndNonfiniteTrainingDoNotPublish() {
        oldModel();
        for(String option:List.of("--alpha=0","--alpha=NaN","--beta=-1","--l1=-1","--l2=-1","--neg=-1","--epochs=-1","--epochs","--seed=bad")) {
            rejected(job,ErrorCode.JOB_INVALID_ARGUMENT,option);assertOldModel();
        }
        seedPrimary();rejected(job,ErrorCode.JOB_FTRL_MODEL_INVALID,"--alpha=4.9e-324");assertOldModel();
    }
    @Test void redisReadFailureAndSecondWriteFailurePreserveActualPartialResult() throws Exception {
        seedPrimary();oldModel();
        for(String mode:List.of("read","state-write")) {
            String user="ftrl-"+mode;
            String[] rules=mode.equals("read")?new String[]{"~*","+@all","-get"}
                    :new String[]{"%R~*","%W~"+RedisKeys.FTRL_WEIGHTS,"+@all"};
            var command=new java.util.ArrayList<>(List.of("redis-cli","ACL","SETUSER",user,"on",">test-password","resetkeys"));command.addAll(List.of(rules));
            assertEquals(0,REDIS.execInContainer(command.toArray(String[]::new)).getExitCode());
            var config=new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));config.setUsername(user);config.setPassword("test-password");
            var denied=new LettuceConnectionFactory(config);denied.afterPropertiesSet();denied.start();
            try {
                var sessions=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml"));
                var restricted=new FtrlTrainJob(sessions.getMapper(PositiveFeedbackMapper.class),sessions.getMapper(TrainingCatalogMapper.class),new StringRedisTemplate(denied));
                var failure=rejected(restricted,ErrorCode.JOB_FTRL_FAILED,"--incremental");
                assertInstanceOf(DataAccessException.class,failure.getCause());
                if(mode.equals("read"))assertOldModel();
                else {
                    assertNotEquals("keep-weights",redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS));
                    assertEquals("keep-state",redis.opsForValue().get(RedisKeys.FTRL_STATE));
                }
            } finally {denied.destroy();}
        }
        job.run(new DefaultApplicationArguments());assertGolden("default");
    }
    private PublicErrorException rejected(FtrlTrainJob target,ErrorCode expected,String... args) {
        logs.list.clear();var failure=assertThrows(PublicErrorException.class,()->target.run(new DefaultApplicationArguments(args)));
        assertEquals(expected,failure.errorCode());assertEquals(expected.message(),failure.getMessage());
        assertFalse(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("train-ftrl 完成")));
        assertFalse(logs.list.stream().anyMatch(e->e.getFormattedMessage().contains("PRIVATE_")));
        return failure;
    }

    /** 旧JDBC完整模型作为参考；键/数组长度精确一致，浮点仅容忍CPU数学函数累积的尾数误差。 */
    private static void assertGolden(String name) throws Exception {
        try(var input=FtrlTrainPostgresTest.class.getResourceAsStream("/ftrl/"+name+".json")) {
            assertNotNull(input);var expected=JSON.readTree(input);
            assertModelNode(expected.path("weights"),JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS)),"weights");
            assertModelNode(expected.path("state"),JSON.readTree(redis.opsForValue().get(RedisKeys.FTRL_STATE)),"state");
        }
    }
    private static void assertModelNode(JsonNode expected,JsonNode actual,String path) {
        if(expected.isObject()) {
            assertTrue(actual.isObject(),path);var expectedKeys=new HashSet<String>();var actualKeys=new HashSet<String>();
            expected.fieldNames().forEachRemaining(expectedKeys::add);actual.fieldNames().forEachRemaining(actualKeys::add);
            assertEquals(expectedKeys,actualKeys,path);
            expected.fields().forEachRemaining(e->assertModelNode(e.getValue(),actual.get(e.getKey()),path+"."+e.getKey()));
        } else if(expected.isArray()) {
            assertTrue(actual.isArray(),path);assertEquals(expected.size(),actual.size(),path);
            for(int i=0;i<expected.size();i++)assertModelNode(expected.get(i),actual.get(i),path+"["+i+"]");
        } else if(expected.isNumber()) {
            assertTrue(actual.isNumber(),path);double value=expected.doubleValue();
            assertTrue(Double.isFinite(actual.doubleValue()),path);
            assertEquals(value,actual.doubleValue(),Math.max(1e-14,128*Math.ulp(value)),path);
        } else assertEquals(expected,actual,path);
    }

    private static void seedPrimary(){db.execute("INSERT INTO item VALUES(30),(10),(20),(50),(40);INSERT INTO user_behavior SELECT (n-1)%7+1,(n%5+1)*10,'CLICK',1,to_timestamp(n) FROM generate_series(1,200)n");}
    private static void oldModel(){redis.opsForValue().set(RedisKeys.FTRL_WEIGHTS,"keep-weights");redis.opsForValue().set(RedisKeys.FTRL_STATE,"keep-state");}
    private static void assertOldModel(){assertEquals("keep-weights",redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS));assertEquals("keep-state",redis.opsForValue().get(RedisKeys.FTRL_STATE));}
    private static String fingerprint() throws Exception {
        String values=JSON.writeValueAsString(JSON.readValue(redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS),Object.class))+"\n"+JSON.writeValueAsString(JSON.readValue(redis.opsForValue().get(RedisKeys.FTRL_STATE),Object.class));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(values.getBytes(StandardCharsets.UTF_8)));
    }
}
