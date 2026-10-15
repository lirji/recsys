package com.recsys.offline;

import com.recsys.common.error.PublicErrorException;
import com.recsys.common.error.ErrorCode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import com.recsys.common.constant.RedisKeys;
import com.recsys.offline.persistence.mapper.primary.OcpcFeedbackMapper;
import com.recsys.offline.persistence.mapper.sharded.OcpcTargetMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实双分片、主源及Redis保护oCPC聚合、延迟权重和部分发布边界。 */
@Testcontainers
class OcpcCalibratePostgresTest {
    @Container static final PostgreSQLContainer<?> ZERO = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final PostgreSQLContainer<?> ONE = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static HikariDataSource ds0, ds1;
    static DataSource sharded;
    static TrackedDriverDataSource primary;
    static JdbcTemplate zero, one, events, observer;
    static LettuceConnectionFactory redisFactory;
    static StringRedisTemplate redis;
    static OcpcFeedbackMapper feedback;
    static OcpcTargetMapper targets;
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeAll static void prepare() throws Exception {
        ds0 = pool(ZERO); ds1 = pool(ONE); zero = new JdbcTemplate(ds0); one = new JdbcTemplate(ds1);
        for (var db : List.of(zero, one)) {
            create(db, "advertiser", "advertiser_id BIGINT PRIMARY KEY");
            create(db, "ad", "ad_id BIGINT PRIMARY KEY,advertiser_id BIGINT,optimization_type TEXT,target_cpa DOUBLE PRECISION");
            create(db, "bidword", "id BIGINT PRIMARY KEY,ad_id BIGINT");
            create(db, "ad_creative", "creative_id BIGINT PRIMARY KEY,ad_id BIGINT,status TEXT");
        }
        zero.execute("CREATE SCHEMA ocpc");
        primary = new TrackedDriverDataSource(ZERO.getJdbcUrl() + "&currentSchema=ocpc&ApplicationName=ocpc-reader", ZERO.getUsername(), ZERO.getPassword());
        events = new JdbcTemplate(primary);
        observer = new JdbcTemplate(new DriverManagerDataSource(ZERO.getJdbcUrl() + "&ApplicationName=ocpc-observer", ZERO.getUsername(), ZERO.getPassword()));
        for (String table : List.of("ad_event", "ad_event_log")) create(events, table,
                "request_id TEXT,ad_id BIGINT,event_type TEXT,charged_price DOUBLE PRECISION,ts TIMESTAMP,offset_s DOUBLE PRECISION");
        String yaml;
        try (var input = OcpcCalibratePostgresTest.class.getResourceAsStream("/sharding.yaml")) {
            yaml = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        sharded = YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0", ds0, "ds_1", ds1),
                yaml.substring(yaml.indexOf("\nrules:") + 1).getBytes(StandardCharsets.UTF_8));
        redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet(); redisFactory.start(); redis = new StringRedisTemplate(redisFactory);
        feedback = new SqlSessionTemplate(SpringSqlSessions.create(primary,"classpath*:mapper/offline/primary/**/*.xml")).getMapper(OcpcFeedbackMapper.class);
        targets = new SqlSessionTemplate(SpringSqlSessions.create(sharded,"classpath*:mapper/offline/sharded/**/*.xml")).getMapper(OcpcTargetMapper.class);
    }
    @AfterAll static void close() throws Exception {
        if (redisFactory != null) redisFactory.destroy();
        if (sharded instanceof AutoCloseable closeable) closeable.close();
    }
    @BeforeEach void reset() {
        logs.start(); ((Logger) LoggerFactory.getLogger(OcpcCalibrateJob.class)).addAppender(logs);
        zero.execute("TRUNCATE ad"); one.execute("TRUNCATE ad"); events.execute("TRUNCATE ad_event,ad_event_log");
        try (var connection = redisFactory.getConnection()) { connection.serverCommands().flushDb(); }
    }
    @AfterEach void connectionsReturnedBeforePoolShutdown() {
        ((Logger) LoggerFactory.getLogger(OcpcCalibrateJob.class)).detachAppender(logs); logs.stop();
        primary.assertClosed(observer, "ocpc-reader");
        assertEquals(0, ds0.getHikariPoolMXBean().getActiveConnections());
        assertEquals(0, ds1.getHikariPoolMXBean().getActiveConnections());
    }
    @Test void bothShardsMeanTargetsDuplicatesAndHeldAdvertisersRemainExact() {
        seedAds(); trip("a",11,10,-86400,0); event("a",11,"CLICK",-86400,null); event("a",11,"CONVERSION",0,null);
        trip("b",13,20,-604800,0); trip("c",20,4,-86400,0);
        event("d",31,"IMPRESSION",-86400,10.0); event("d",31,"CLICK",-86400,null);
        trip("e",40,0,-86400,0); trip("ignored",51,100,-86400,0);
        redis.opsForValue().set(RedisKeys.adOcpc(2),"2"); redis.opsForValue().set(RedisKeys.adOcpc(3),"3"); redis.opsForValue().set(RedisKeys.adOcpc(4),"4");
        run(redis);
        assertEquals("1.5811", k(1)); assertEquals("2.0", k(2)); assertEquals("3", k(3)); assertEquals("4", k(4)); assertNull(k(5)); assertNull(k(6));
    }
    @Test void delayCompletionUsesClickAgeAndFutureClicksRespectWeightCap() {
        singleAd(); trip("now",20,4,-86400,0); redis.opsForValue().set(RedisKeys.AD_DELAY_LAMBDA,Double.toString(Math.log(2)));
        run(redis); assertEquals("1.4142",k(2));
        redis.delete(RedisKeys.adOcpc(2)); run(redis,"--delay-correct=false"); assertEquals("1.0",k(2));
        events.execute("TRUNCATE ad_event"); trip("future-click",20,4,86400,0); redis.delete(RedisKeys.adOcpc(2));
        run(redis); assertEquals("4.4721",k(2));
        redis.delete(RedisKeys.adOcpc(2)); run(redis,"--min-completion=0.25"); assertEquals("2.0",k(2));
    }
    @Test void clickWindowIsInclusiveAndNonpositiveDaysStillUseEpochFloor() {
        singleAd(); trip("edge",20,4,-604800,0); trip("outside",20,8,-604800.000001,0); trip("old",20,12,-691200,0);
        trip("pre-epoch",20,100,0,0); events.update("UPDATE ad_event SET offset_s=NULL,ts='1969-12-31' WHERE request_id='pre-epoch' AND event_type='CLICK'");
        event("null",20,"IMPRESSION",0,100.0); event("null",20,"CLICK",null,null); event("null",20,"CONVERSION",0,null);
        run(redis); assertEquals("1.0",k(2)); redis.delete(RedisKeys.adOcpc(2));
        run(redis,"--days=0"); assertEquals("0.7071",k(2)); redis.delete(RedisKeys.adOcpc(2));
        run(redis,"--days=-1"); assertEquals("0.7071",k(2));
    }
    @Test void regularJoinsPreserveBothExposureAndConversionMultiplicity() {
        singleAd(); trip("dup",20,4,-86400,0); event("dup",20,"IMPRESSION",86400,8.0);
        event("dup",20,"CLICK",-86400,null); event("dup",20,"CONVERSION",0,null);
        run(redis); assertEquals("0.8165",k(2));
    }
    @Test void futureConversionsMissingInputsAndNonOcpcAdsDoNotPublish() {
        rejected(ErrorCode.JOB_NO_OCPC_TARGET); assertTrue(redis.keys("ad:ocpc:*").isEmpty()); seedAds();
        trip("future",20,4,-86400,0.000001); trip("non-ocpc",51,100,-86400,0); trip("zero-target",60,100,-86400,0);
        event("missing-exposure",11,"CLICK",-86400,null); event("missing-exposure",11,"CONVERSION",0,null);
        run(redis); assertTrue(redis.keys("ad:ocpc:*").isEmpty());
    }
    @Test void replicaAndLargeIdentitiesKeepExactArrayMembership() {
        one.execute("INSERT INTO ad VALUES(9223372036854775807,9007199254740993,'OCPC',5)"); trip("large",Long.MAX_VALUE,5,-86400,0);
        events.execute("INSERT INTO ad_event_log SELECT * FROM ad_event"); events.execute("TRUNCATE ad_event");
        run(redis,"--ad-event-table=ad_event_log"); assertEquals("1.0",k(9007199254740993L)); assertNull(k(9007199254740992L));
    }
    @Test void damagedModelsNeverMasqueradeAsMissingAndOriginalClampsRemain() {
        singleAd(); trip("simple",20,4,-86400,0);
        for (String value : List.of("PRIVATE_BAD_MODEL","NaN","Infinity","0","-1"," ")) {
            redis.opsForValue().set(RedisKeys.AD_DELAY_LAMBDA,value);
            redis.opsForValue().set(RedisKeys.adOcpc(2),"2");
            rejected(ErrorCode.JOB_OCPC_INVALID_DATA); assertEquals("2",k(2));
            redis.delete(RedisKeys.AD_DELAY_LAMBDA);
            redis.opsForValue().set(RedisKeys.adOcpc(2),value);
            rejected(ErrorCode.JOB_OCPC_INVALID_DATA); assertEquals(value,k(2));
        }
        redis.opsForValue().set(RedisKeys.adOcpc(2),"1e20"); run(redis); assertEquals("5.0",k(2));
        redis.opsForValue().set(RedisKeys.adOcpc(2),"10"); run(redis); assertEquals("5.0",k(2));
        redis.opsForValue().set(RedisKeys.adOcpc(2),"0.01"); run(redis); assertEquals("0.2",k(2));
        redis.opsForValue().set(RedisKeys.adOcpc(2),"3"); run(redis,"--alpha=0","--min=0.5","--max=2"); assertEquals("2.0",k(2));
    }
    @Test void missingModelsRemainLegitimateAndDelayFallbackIsVisible() {
        singleAd(); trip("simple",20,4,-86400,0);
        run(redis,"--delay-correct"); assertEquals("1.0",k(2));
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("[JOB_OCPC_DELAY_MISSING]")));
        logs.list.clear(); redis.delete(RedisKeys.adOcpc(2));
        redis.opsForValue().set(RedisKeys.AD_DELAY_LAMBDA,"PRIVATE_BAD_MODEL");
        run(redis,"--delay-correct=false"); assertEquals("1.0",k(2));
        assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("JOB_OCPC_DELAY_MISSING")));
    }
    @Test void invalidArgumentsFailBeforeDependenciesOrPublication() {
        redis.opsForValue().set(RedisKeys.adOcpc(2),"old");
        for (String option : List.of("--days", "--days=no", "--alpha=NaN", "--alpha=-1", "--min=0",
                "--max=0.1", "--min-completion=0", "--min-completion=1.01", "--delay-correct=maybe")) {
            rejected(ErrorCode.JOB_INVALID_ARGUMENT,option); assertEquals("old",k(2));
        }
    }
    @Test void nonfiniteStatsAndOverflowCannotPublishClampedOrRoundedFakeValues() {
        singleAd(); trip("simple",20,4,-86400,0); redis.opsForValue().set(RedisKeys.adOcpc(2),"2");
        for (String cost : List.of("NaN","Infinity","-1")) {
            events.update("UPDATE ad_event SET charged_price=?::float8 WHERE event_type='IMPRESSION'",cost);
            rejected(ErrorCode.JOB_OCPC_INVALID_DATA); assertEquals("2",k(2));
        }
        events.execute("UPDATE ad_event SET charged_price=4 WHERE event_type='IMPRESSION'");
        for (String target : List.of("NaN","Infinity")) {
            zero.update("UPDATE ad SET target_cpa=?::float8",target);
            // ShardingSphere 的 AVG 合并先用 BigDecimal 解析，非有限目标在回调前即被驱动拒绝。
            var failure=rejected(ErrorCode.JOB_OCPC_FAILED);
            Throwable cause=failure;
            while (cause.getCause()!=null) cause=cause.getCause();
            assertInstanceOf(NumberFormatException.class,cause);
            assertEquals("2",k(2));
        }
        zero.execute("UPDATE ad SET target_cpa=8");
        rejected(ErrorCode.JOB_OCPC_INVALID_DATA,"--alpha=1e308"); assertEquals("2",k(2));
        zero.execute("UPDATE ad SET target_cpa=4");
        rejected(ErrorCode.JOB_OCPC_INVALID_DATA,"--min=0.000001","--max=0.000001"); assertEquals("2",k(2));
        rejected(ErrorCode.JOB_OCPC_INVALID_DATA,"--min=1e20","--max=1e20"); assertEquals("2",k(2));
        run(redis); assertEquals("2.0",k(2));
    }
    @Test void queryAndArgumentFailuresPreserveExistingRedisValues() {
        singleAd(); trip("simple",20,4,-86400,0); redis.opsForValue().set(RedisKeys.adOcpc(2),"old");
        assertThrows(PublicErrorException.class,()->run(redis,"--ad-event-table=ad_event;DROP TABLE ad"));
        events.execute("ALTER TABLE ad_event RENAME COLUMN charged_price TO hidden_price");
        try { var failure=rejected(ErrorCode.JOB_OCPC_FAILED); assertInstanceOf(DataAccessException.class,failure.getCause()); assertEquals("old",k(2)); }
        finally { events.execute("ALTER TABLE ad_event RENAME COLUMN hidden_price TO charged_price"); }
    }
    @Test void redisSecondWriteFailurePreservesFirstAdvertiserAndClosesDatabaseResources() throws Exception {
        one.execute("INSERT INTO ad VALUES(11,1,'OCPC',4)"); singleAd(); trip("first",11,4,-86400,0); trip("second",20,4,-86400,0);
        redis.opsForValue().set(RedisKeys.adOcpc(2),"2");
        var acl = REDIS.execInContainer("redis-cli","ACL","SETUSER","ocpc-test","on",">test-only-password","resetkeys","%R~*","%W~"+RedisKeys.adOcpc(1),"+@all"); assertEquals(0,acl.getExitCode());
        var config = new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379)); config.setUsername("ocpc-test"); config.setPassword("test-only-password");
        var restrictedFactory = new LettuceConnectionFactory(config); restrictedFactory.afterPropertiesSet(); restrictedFactory.start();
        try {
            var failure = assertThrows(PublicErrorException.class,()->run(new StringRedisTemplate(restrictedFactory)));
            assertEquals(ErrorCode.JOB_OCPC_FAILED,failure.errorCode());
            var cause=assertInstanceOf(DataAccessException.class,failure.getCause());
            assertTrue(cause.getMostSpecificCause().getMessage().contains("NOPERM"));
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("ad-ocpc 完成")));
        }
        finally { restrictedFactory.destroy(); }
        assertEquals("1.0",k(1)); assertEquals("2",k(2));
        run(redis); assertEquals("2.0",k(2));
    }
    @Test void redisReadFailureIsNotAColdStartAndWrongTypeIsNotMissing() throws Exception {
        singleAd(); trip("simple",20,4,-86400,0); redis.opsForValue().set(RedisKeys.adOcpc(2),"2");
        redis.opsForHash().put(RedisKeys.AD_DELAY_LAMBDA,"field","PRIVATE_VALUE");
        var wrongType=rejected(ErrorCode.JOB_OCPC_FAILED); assertInstanceOf(DataAccessException.class,wrongType.getCause());
        assertEquals("2",k(2)); redis.delete(RedisKeys.AD_DELAY_LAMBDA);
        var acl=REDIS.execInContainer("redis-cli","ACL","SETUSER","ocpc-read-test","on",">test-only-password","resetkeys","~*","+@all","-get");
        assertEquals(0,acl.getExitCode());
        var config=new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));
        config.setUsername("ocpc-read-test"); config.setPassword("test-only-password");
        var denied=new LettuceConnectionFactory(config); denied.afterPropertiesSet(); denied.start();
        try {
            for (String option : List.of("--delay-correct=true","--delay-correct=false")) {
                var failure=assertThrows(PublicErrorException.class,()->run(new StringRedisTemplate(denied),option));
                assertEquals(ErrorCode.JOB_OCPC_FAILED,failure.errorCode()); assertEquals("2",k(2));
            }
        } finally { denied.destroy(); }
        assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("ad-ocpc 完成")));
        run(redis); assertEquals("2.0",k(2));
    }
    private PublicErrorException rejected(ErrorCode code,String... args) {
        logs.list.clear();
        var failure=assertThrows(PublicErrorException.class,()->run(redis,args));
        assertEquals(code,failure.errorCode()); assertEquals(code.message(),failure.getMessage());
        assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("ad-ocpc 完成")));
        assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("PRIVATE_")));
        return failure;
    }
    private static void seedAds() {
        one.execute("INSERT INTO ad VALUES(11,1,'OCPC',10),(13,1,'OCPC',30),(31,3,'OCPC',10),(51,5,'CPC',100)");
        zero.execute("INSERT INTO ad VALUES(20,2,'OCPC',4),(40,4,'OCPC',10),(60,6,'OCPC',0)");
    }
    private static void singleAd() { zero.execute("INSERT INTO ad VALUES(20,2,'OCPC',4)"); }
    private static void trip(String request,long ad,double cost,double clickOffset,double conversionOffset) {
        event(request,ad,"IMPRESSION",-2592000,cost); event(request,ad,"CLICK",clickOffset,null); event(request,ad,"CONVERSION",conversionOffset,null);
    }
    private static void event(String request,long ad,String type,Number offset,Number cost) {
        events.update("INSERT INTO ad_event(request_id,ad_id,event_type,charged_price,offset_s) VALUES(?,?,?,?,?)",request,ad,type,cost,offset);
    }
    private static String k(long advertiser) { return redis.opsForValue().get(RedisKeys.adOcpc(advertiser)); }
    private static void run(StringRedisTemplate target,String... args) {
        var job = new OcpcCalibrateJob(feedback,targets,target);
        new TransactionTemplate(new DataSourceTransactionManager(primary)).executeWithoutResult(status -> {
            for (String table : List.of("ad_event","ad_event_log")) events.execute("UPDATE "+table+" SET ts=now()+offset_s*interval '1 second' WHERE offset_s IS NOT NULL");
            job.run(new DefaultApplicationArguments(args));
        });
    }
    private static HikariDataSource pool(PostgreSQLContainer<?> pg) {
        var source = new HikariDataSource(); source.setJdbcUrl(pg.getJdbcUrl()); source.setUsername(pg.getUsername()); source.setPassword(pg.getPassword()); source.setMaximumPoolSize(2); return source;
    }
    private static void create(JdbcTemplate db,String table,String columns) {
        db.execute("CREATE TABLE "+table+"("+columns+")"); db.execute("COMMENT ON TABLE "+table+" IS 'oCPC迁移独占测试'");
        for(String column:columns.split(",")) db.execute("COMMENT ON COLUMN "+table+"."+column.strip().split(" ")[0]+" IS '测试输入字段'");
    }
}
