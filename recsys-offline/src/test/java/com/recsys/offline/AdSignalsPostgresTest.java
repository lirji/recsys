package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.offline.persistence.mapper.primary.AdCalibrationMapper;
import com.recsys.offline.persistence.mapper.primary.AdExplorationMapper;
import com.recsys.offline.persistence.mapper.primary.AdDelayMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.common.constant.RedisKeys;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG/Redis保护广告校准、探索与延迟模型的输入口径及分步发布边界。 */
@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class AdSignalsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate db,admin;static TrackedDriverDataSource source;static StringRedisTemplate redis;
    static SqlSessionTemplate sessions;static LettuceConnectionFactory factory;static AdCalibrateJob calibrate;static AdExploreStatsJob explore;static DelayModelJob delay;
    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+"&ApplicationName=ad-signals-reader",PG.getUsername(),PG.getPassword());db=new JdbcTemplate(source);
        for(String table:List.of("ad_event","ad_event_log")) {
            db.execute("CREATE TABLE "+table+"(request_id TEXT,ad_id BIGINT,creative_id BIGINT,event_type TEXT,pctr DOUBLE PRECISION,ts TIMESTAMPTZ)");
            db.execute("COMMENT ON TABLE "+table+" IS '广告信号迁移独占测试'");
            for(String col:List.of("request_id","ad_id","creative_id","event_type","pctr","ts"))db.execute("COMMENT ON COLUMN "+table+"."+col+" IS '原广告事件字段'");
        }
        factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);
        sessions=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml"));
        calibrate=new AdCalibrateJob(sessions.getMapper(AdCalibrationMapper.class),redis);explore=new AdExploreStatsJob(sessions.getMapper(AdExplorationMapper.class),redis);delay=new DelayModelJob(sessions.getMapper(AdDelayMapper.class),redis);
    }
    @AfterAll static void close(){if(factory!=null)factory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE ad_event,ad_event_log");try(var c=factory.getConnection()){c.serverCommands().flushDb();}}
    @AfterEach void noLeakedConnections(){source.assertClosed(admin,"ad-signals-reader");}
    @Test void calibrationDefaultThresholdBinsAndDescendingObservedRatesArePreserved() throws Exception {
        seedCalibration(100);calibrate.run(args());var model=new ObjectMapper().readTree(value(RedisKeys.adCalib("deepfm")));
        assertEquals(20,model.get("x").size());assertEquals(0.003,model.get("x").get(0).asDouble());assertEquals(0.098,model.get("x").get(19).asDouble());
        for(var y:model.get("y"))assertEquals(0.5,y.asDouble());
    }
    @Test void calibrationDistinctClicksNullPredictionAndCustomModelMatchWholeJson() {
        db.execute("INSERT INTO ad_event SELECT 'r'||n,1,NULL,'IMPRESSION',CASE WHEN n<=5 THEN 0.1 ELSE 0.9 END,now() FROM generate_series(1,10) n");
        db.execute("INSERT INTO ad_event SELECT 'r'||n,1,NULL,'CLICK',NULL,now() FROM generate_series(1,5) n");
        db.execute("INSERT INTO ad_event VALUES('r1',1,NULL,'CLICK',NULL,now()),('r6',2,NULL,'CLICK',NULL,now()),('null',1,NULL,'IMPRESSION',NULL,now())");
        calibrate.run(args("--bins=2","--model=custom"));assertEquals("{\"x\":[0.1,0.9],\"y\":[0.5,0.5]}",value(RedisKeys.adCalib("custom")));assertNull(value(RedisKeys.adCalib("deepfm")));
    }
    @Test void insufficientCalibrationLeavesOldCurveAndZeroBinsKeepsOriginalLateFailure() {
        redis.opsForValue().set(RedisKeys.adCalib("deepfm"),"keep");seedCalibration(99);calibrate.run(args());assertEquals("keep",value(RedisKeys.adCalib("deepfm")));
        assertThrows(ArrayIndexOutOfBoundsException.class,()->calibrate.run(args("--bins=0")));assertEquals("{\"x\":[],\"y\":[]}",value(RedisKeys.adCalib("deepfm")));
    }
    @Test void explorationKeepsDuplicateJoinMultiplicityNullIdentityAndLargeIds() {
        seedExplore();explore.run(args());assertEquals("4,3",value(RedisKeys.adStats(1)));assertEquals("0,1",value(RedisKeys.adStats(2)));assertEquals("0,0",value(RedisKeys.adStats(3)));
        assertEquals("1,0",value(RedisKeys.adStats(Long.MAX_VALUE)));assertNull(value(RedisKeys.adStats(0)));assertEquals("5",value(RedisKeys.AD_STATS_TOTAL));
        assertEquals("2,4",value(RedisKeys.adCreativeStats(1,11)));assertEquals("1,2",value(RedisKeys.adCreativeStats(1,12)));assertEquals("1,0",value(RedisKeys.adCreativeStats(0,77)));assertEquals("1,0",value(RedisKeys.adCreativeStats(Long.MAX_VALUE,Long.MAX_VALUE)));
    }
    @Test void emptyExplorationResetsTotalButKeepsMissingAdAndCreativeKeys(org.springframework.boot.test.system.CapturedOutput output) {
        redis.opsForValue().set(RedisKeys.adStats(99),"9,8");redis.opsForValue().set(RedisKeys.adCreativeStats(99,999),"7,6");redis.opsForValue().set(RedisKeys.AD_STATS_TOTAL,"9");
        explore.run(args());assertTrue(output.toString().contains("[JOB_EXPLORATION_EMPTY_SNAPSHOT]"));assertEquals("0",value(RedisKeys.AD_STATS_TOTAL));assertEquals("9,8",value(RedisKeys.adStats(99)));assertEquals("7,6",value(RedisKeys.adCreativeStats(99,999)));
    }
    @Test void lateCreativeSqlFailurePreservesAlreadyPublishedAdStatistics() {
        seedExplore();redis.opsForValue().set(RedisKeys.adCreativeStats(1,11),"keep");db.execute("ALTER TABLE ad_event RENAME COLUMN creative_id TO hidden_creative");
        try{assertThrows(BadSqlGrammarException.class,()->explore.run(args()));assertEquals("4,3",value(RedisKeys.adStats(1)));assertEquals("5",value(RedisKeys.AD_STATS_TOTAL));assertEquals("keep",value(RedisKeys.adCreativeStats(1,11)));}
        finally{db.execute("ALTER TABLE ad_event RENAME COLUMN hidden_creative TO creative_id");}
    }
    @Test void delayIncludesZeroAndUpperBoundButExcludesNegativeFutureAndOldClick() {
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            seedDelay();delay.run(args("--min-samples=6"));assertEquals("1.5",value(RedisKeys.AD_DELAY_MEAN_DAYS));assertEquals("0.666667",value(RedisKeys.AD_DELAY_LAMBDA));
            delay.run(args("--min-samples=3","--max-delay-days=1"));assertEquals("0.6667",value(RedisKeys.AD_DELAY_MEAN_DAYS));assertEquals("1.5",value(RedisKeys.AD_DELAY_LAMBDA));
            delay.run(args("--days=0","--min-samples=8"));assertEquals("4.875",value(RedisKeys.AD_DELAY_MEAN_DAYS));assertEquals("0.205128",value(RedisKeys.AD_DELAY_LAMBDA));
        });
    }
    @Test void exactClickWindowAndDefaultMinimumKeepPreviousModelWhenInsufficient() {
        redis.opsForValue().set(RedisKeys.AD_DELAY_LAMBDA,"keep");redis.opsForValue().set(RedisKeys.AD_DELAY_MEAN_DAYS,"keep");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            db.execute("INSERT INTO ad_event VALUES('edge',1,NULL,'CLICK',NULL,now()-interval '10 days'),('edge',1,NULL,'CONVERSION',NULL,now()-interval '9 days'),('outside',1,NULL,'CLICK',NULL,now()-interval '10 days 0.000001 seconds'),('outside',1,NULL,'CONVERSION',NULL,now()-interval '8 days')");
            assertEquals(ErrorCode.JOB_DELAY_SAMPLES_INSUFFICIENT, assertThrows(PublicErrorException.class, () -> delay.run(args())).errorCode());assertEquals("keep",value(RedisKeys.AD_DELAY_LAMBDA));delay.run(args("--days=10","--min-samples=1"));assertEquals("1.0",value(RedisKeys.AD_DELAY_MEAN_DAYS));assertEquals("1.0",value(RedisKeys.AD_DELAY_LAMBDA));
        });
        db.execute("TRUNCATE ad_event;INSERT INTO ad_event VALUES('zero',1,NULL,'CLICK',NULL,now()),('zero',1,NULL,'CONVERSION',NULL,now())");assertEquals(ErrorCode.JOB_DELAY_MODEL_INVALID, assertThrows(PublicErrorException.class,
                () -> delay.run(args("--min-samples=1"))).errorCode());assertEquals("1.0",value(RedisKeys.AD_DELAY_LAMBDA));
    }
    @Test void allThreeJobsCanReadReplicaAndRejectUnknownSourceBeforeMutation() {
        seedCalibration(100);db.execute("INSERT INTO ad_event_log SELECT * FROM ad_event;TRUNCATE ad_event");
        calibrate.run(args("--ad-event-table=ad_event_log"));assertNotNull(value(RedisKeys.adCalib("deepfm")));explore.run(args("--ad-event-table=ad_event_log"));assertEquals("100",value(RedisKeys.AD_STATS_TOTAL));
        db.execute("TRUNCATE ad_event_log");seedDelay();db.execute("INSERT INTO ad_event_log SELECT * FROM ad_event;TRUNCATE ad_event");delay.run(args("--ad-event-table=ad_event_log","--min-samples=6"));assertEquals("1.5",value(RedisKeys.AD_DELAY_MEAN_DAYS));
        for(var job:List.of(calibrate,explore,delay))assertThrows(IllegalArgumentException.class,()->job.run(args("--ad-event-table=ad_event;DROP TABLE ad_event")));
        assertEquals("100",value(RedisKeys.AD_STATS_TOTAL));
    }
    @Test void missingSourceFailsBeforePublishingAnyOfTheThreeSignals() {
        redis.opsForValue().set(RedisKeys.AD_STATS_TOTAL,"keep");db.execute("ALTER TABLE ad_event RENAME TO hidden_events");
        try{for(var job:List.of(calibrate,explore,delay))assertThrows(BadSqlGrammarException.class,()->job.run(args()));assertEquals("keep",value(RedisKeys.AD_STATS_TOTAL));}
        finally{db.execute("ALTER TABLE hidden_events RENAME TO ad_event");}
    }
    @Test void redisCallbackFailureStillClosesDatabaseAndSecondDelaySetIsNotAtomic() throws Exception {
        var acl=REDIS.execInContainer("redis-cli","ACL","SETUSER","signal-test","on",">test-only-password","+@all","~"+RedisKeys.AD_DELAY_LAMBDA);assertEquals(0,acl.getExitCode());
        var config=new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));config.setUsername("signal-test");config.setPassword("test-only-password");
        var limitedFactory=new LettuceConnectionFactory(config);limitedFactory.afterPropertiesSet();limitedFactory.start();var limited=new StringRedisTemplate(limitedFactory);
        try {
            seedExplore();var failure=assertThrows(DataAccessException.class,()->new AdExploreStatsJob(sessions.getMapper(AdExplorationMapper.class),limited).run(args()));assertTrue(root(failure).getMessage().contains("NOPERM"));
            db.execute("TRUNCATE ad_event");seedDelay();redis.opsForValue().set(RedisKeys.AD_DELAY_MEAN_DAYS,"keep");
            assertThrows(DataAccessException.class,()->new DelayModelJob(sessions.getMapper(AdDelayMapper.class),limited).run(args("--min-samples=6")));
            assertEquals("0.666667",value(RedisKeys.AD_DELAY_LAMBDA));assertEquals("keep",value(RedisKeys.AD_DELAY_MEAN_DAYS));
        } finally {limitedFactory.destroy();}
    }
    @Test void invalidDelayArgumentsAndEmptySamplesLeaveBothOldModelKeys() {
        redis.opsForValue().set(RedisKeys.AD_DELAY_LAMBDA,"keep");
        redis.opsForValue().set(RedisKeys.AD_DELAY_MEAN_DAYS,"keep-mean");
        for (String option : List.of("--min-samples=0", "--min-samples=-1", "--min-samples",
                "--max-delay-days=NaN", "--max-delay-days=-1", "--days=bad")) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                    () -> delay.run(args(option))).errorCode());
        }
        assertEquals(ErrorCode.JOB_DELAY_SAMPLES_INSUFFICIENT, assertThrows(PublicErrorException.class,
                () -> delay.run(args("--min-samples=1"))).errorCode());
        assertEquals("keep",value(RedisKeys.AD_DELAY_LAMBDA));assertEquals("keep-mean",value(RedisKeys.AD_DELAY_MEAN_DAYS));
        seedDelay();delay.run(args("--min-samples=6"));
        assertEquals("0.666667",value(RedisKeys.AD_DELAY_LAMBDA));assertEquals("1.5",value(RedisKeys.AD_DELAY_MEAN_DAYS));
    }
    @Test void delayRateRoundedToZeroCannotReplaceUsableOldModel() {
        redis.opsForValue().set(RedisKeys.AD_DELAY_LAMBDA,"keep");
        redis.opsForValue().set(RedisKeys.AD_DELAY_MEAN_DAYS,"keep-mean");
        // 极端但PG可表示的历史输入，保护发布精度把正lambda舍入为0的边界。
        db.execute("INSERT INTO ad_event VALUES('ancient',1,NULL,'CLICK',NULL,now()-interval '2100000 days'),('ancient',1,NULL,'CONVERSION',NULL,now())");
        double observed = db.queryForObject("SELECT EXTRACT(EPOCH FROM (cvt.ts-clk.ts))/86400.0 FROM ad_event cvt JOIN ad_event clk ON cvt.request_id=clk.request_id WHERE cvt.event_type='CONVERSION' AND clk.event_type='CLICK'", Double.class);
        System.out.println("DELAY_PRECISION_FIXTURE_DAYS=" + observed);
        assertTrue(observed > 2_000_000 && observed < 2_200_000, Double.toString(observed));
        assertEquals(ErrorCode.JOB_DELAY_MODEL_INVALID, assertThrows(PublicErrorException.class,
                () -> delay.run(args("--days=0", "--min-samples=1", "--max-delay-days=2200000"))).errorCode());
        assertEquals("keep",value(RedisKeys.AD_DELAY_LAMBDA));assertEquals("keep-mean",value(RedisKeys.AD_DELAY_MEAN_DAYS));
    }
    private static Throwable root(Throwable error){while(error.getCause()!=null)error=error.getCause();return error;}
    private static String value(String key){return redis.opsForValue().get(key);}
    private static DefaultApplicationArguments args(String...args){return new DefaultApplicationArguments(args);}
    private static void seedCalibration(int n){db.execute("INSERT INTO ad_event SELECT 'r'||n,1,NULL,'IMPRESSION',n/1000.0,now() FROM generate_series(1,"+n+") n");db.execute("INSERT INTO ad_event SELECT 'r'||n,1,NULL,'CLICK',NULL,now() FROM generate_series(1,50) n");}
    private static void seedExplore(){db.execute("INSERT INTO ad_event VALUES('r',1,11,'IMPRESSION',NULL,now()),('r',1,11,'IMPRESSION',NULL,now()),('r',1,12,'IMPRESSION',NULL,now()),('r',1,NULL,'CLICK',NULL,now()),('r',1,NULL,'CLICK',NULL,now()),('q',1,NULL,'IMPRESSION',NULL,now()),('q',1,NULL,'CLICK',NULL,now()),('c',2,NULL,'CLICK',NULL,now()),('v',3,NULL,'CONVERSION',NULL,now()),('null',NULL,77,'IMPRESSION',NULL,now()),('max',9223372036854775807,9223372036854775807,'IMPRESSION',NULL,now())");}
    private static void seedDelay(){db.execute("INSERT INTO ad_event(request_id,ad_id,event_type,ts) VALUES('r',1,'CLICK',now()-interval '10 days'),('r',1,'CLICK',now()-interval '10 days'),('r',1,'CONVERSION',now()-interval '9 days'),('r',1,'CONVERSION',now()-interval '8 days'),('negative',1,'CLICK',now()-interval '1 day'),('negative',1,'CONVERSION',now()-interval '2 days'),('three',1,'CLICK',now()-interval '3 days'),('three',1,'CONVERSION',now()),('future',1,'CLICK',now()-interval '1 day'),('future',1,'CONVERSION',now()+interval '1 day'),('old',1,'CLICK',now()-interval '31 days'),('old',1,'CONVERSION',now()-interval '1 day'),('zero',1,'CLICK',now()-interval '2 days'),('zero',1,'CONVERSION',now()-interval '2 days'),('null',1,'CLICK',NULL),('null',1,'CONVERSION',now()-interval '1 day')");}
}
