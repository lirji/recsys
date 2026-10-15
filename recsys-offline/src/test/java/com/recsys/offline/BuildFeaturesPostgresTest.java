package com.recsys.offline;

import com.recsys.common.error.PublicErrorException;

import com.recsys.common.constant.RedisKeys;
import com.recsys.feature.RedisFeatureService;
import com.recsys.offline.persistence.mapper.primary.FeatureStatsMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessException;
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

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG/Redis保护评分聚合、四位舍入、交叉特征及分阶段发布的失败边界。 */
@Testcontainers
class BuildFeaturesPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory redisFactory;
    static RedisFeatureService features;
    static BuildFeaturesJob job;

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=feature-builder",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        for(String table:List.of("user_behavior","behavior_log")){
            db.execute("CREATE TABLE "+table+"(user_id BIGINT,item_id BIGINT,action TEXT,value DOUBLE PRECISION)");
            db.execute("COMMENT ON TABLE "+table+" IS '离线特征独占行为源'");
            for(String column:List.of("user_id","item_id","action","value"))db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原行为字段'");
        }
        for(String table:List.of("item","item_local")){
            db.execute("CREATE TABLE "+table+"(item_id BIGINT PRIMARY KEY,category TEXT)");
            db.execute("COMMENT ON TABLE "+table+" IS '离线特征独占目录源'");
            for(String column:List.of("item_id","category"))db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原目录字段'");
        }
        redisFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        features=new RedisFeatureService(redis);
        job=new BuildFeaturesJob(new SqlSessionTemplate(SpringSqlSessions.create(source,
                "classpath*:mapper/offline/primary/**/*.xml")).getMapper(FeatureStatsMapper.class),features);
    }
    @AfterAll static void close(){if(redisFactory!=null)redisFactory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE user_behavior,behavior_log,item,item_local");try(var connection=redis.getConnectionFactory().getConnection()){connection.serverCommands().flushDb();}}

    @Test void ratingsPreserveCountsNullAveragesPopulationDeviationAndCrossFeatures() throws Exception {
        db.execute("INSERT INTO item VALUES(1,'A'),(2,'B'),(3,NULL)");
        db.execute("INSERT INTO user_behavior VALUES(1,1,'RATING',5),(1,1,'RATING',3),(1,2,'RATING',NULL),(2,2,'RATING',1),(2,2,'RATING',5),(3,3,'RATING',NULL),(4,1,'CLICK',5)");
        job.run(new DefaultApplicationArguments());
        assertEquals(item(.7925,4,2,1),features.itemFeatures(1));assertEquals(item(1,3,3,2),features.itemFeatures(2));assertEquals(item(.5,0,1,0),features.itemFeatures(3));
        assertEquals(Map.of("user_act_norm",1.0,"user_avg_rating",4.0,"catavg:A",4.0,"catcnt_norm:A",.7925,"catratio:A",.6667,"catavg:B",0.0,"catcnt_norm:B",.5,"catratio:B",.3333),features.userFeatures(1));
        assertEquals(Map.of("user_act_norm",.7925,"user_avg_rating",3.0,"catavg:B",3.0,"catcnt_norm:B",.7925,"catratio:B",1.0),features.userFeatures(2));
        assertEquals(Map.of("user_act_norm",.5,"user_avg_rating",0.0),features.userFeatures(3));assertTrue(features.userFeatures(4).isEmpty());source.assertClosed(admin,"feature-builder");
    }

    @Test void nullAndLargeIdentitiesAndMissingCatalogKeepOriginalZeroSemantics() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(NULL,NULL,'RATING',NULL),(9223372036854775807,9223372036854775807,'RATING',2),(1,999,'RATING',4)");
        db.update("INSERT INTO item VALUES(?,?)",Long.MAX_VALUE,"Big'类");
        job.run(new DefaultApplicationArguments());
        assertEquals(item(1,0,1,0),features.itemFeatures(0));assertEquals(Map.of("user_act_norm",1.0,"user_avg_rating",0.0),features.userFeatures(0));
        assertEquals(item(1,4,1,0),features.itemFeatures(999));assertEquals(Map.of("user_act_norm",1.0,"user_avg_rating",4.0),features.userFeatures(1));
        assertEquals(Map.of("user_act_norm",1.0,"user_avg_rating",2.0,"catavg:Big'类",2.0,"catcnt_norm:Big'类",1.0,"catratio:Big'类",1.0),features.userFeatures(Long.MAX_VALUE));source.assertClosed(admin,"feature-builder");
    }

    @Test void reusedJobSelectsBothSourcesAndLeavesUnmentionedHashFields() throws Exception {
        db.execute("INSERT INTO item VALUES(10,'owned');INSERT INTO item_local VALUES(10,'replica');INSERT INTO user_behavior VALUES(1,10,'RATING',5);INSERT INTO behavior_log VALUES(1,10,'RATING',1)");
        job.run(new DefaultApplicationArguments());assertEquals(5.0,features.userFeatures(1).get("catavg:owned"));
        job.run(new DefaultApplicationArguments("--behavior-table=behavior_log","--item-table=item_local"));
        assertEquals(1.0,features.itemFeatures(10).get("item_avg_rating"));assertEquals(1.0,features.userFeatures(1).get("catavg:replica"));assertEquals(5.0,features.userFeatures(1).get("catavg:owned"));
        job.run(new DefaultApplicationArguments("--item-table=item_local"));assertEquals(5.0,features.userFeatures(1).get("catavg:replica"));source.assertClosed(admin,"feature-builder");
    }

    @Test void noRatingsPreservesAllExistingFeatures() throws Exception {
        features.writeItemFeatures(1,Map.of("old",7.0));features.writeUserFeatures(1,Map.of("old",8.0));
        job.run(new DefaultApplicationArguments());db.execute("INSERT INTO user_behavior VALUES(1,1,'CLICK',5)");job.run(new DefaultApplicationArguments());
        assertEquals(Map.of("old",7.0),features.itemFeatures(1));assertEquals(Map.of("old",8.0),features.userFeatures(1));source.assertClosed(admin,"feature-builder");
    }

    @Test void lateCatalogFailureKeepsAlreadyWrittenItemFeaturesAndOldUserFeatures() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(1,10,'RATING',5);INSERT INTO item VALUES(10,'A')");
        features.writeUserFeatures(1,Map.of("old",8.0));db.execute("ALTER TABLE item RENAME TO hidden_item");
        try{
            assertThrows(BadSqlGrammarException.class,()->job.run(new DefaultApplicationArguments()));
            assertEquals(item(1,5,1,0),features.itemFeatures(10));assertEquals(Map.of("old",8.0),features.userFeatures(1));source.assertClosed(admin,"feature-builder");
        }finally{db.execute("ALTER TABLE hidden_item RENAME TO item");}
        job.run(new DefaultApplicationArguments());assertEquals(5.0,features.userFeatures(1).get("catavg:A"));source.assertClosed(admin,"feature-builder");
    }

    @Test void redisTypeFailureDuringItemRowClosesDatabaseAndSkipsUsers() {
        db.execute("INSERT INTO user_behavior VALUES(1,10,'RATING',5)");redis.opsForValue().set(RedisKeys.itemFeature(10),"wrong-type");
        var error=assertThrows(DataAccessException.class,()->job.run(new DefaultApplicationArguments()));
        assertTrue(error.getMostSpecificCause().getMessage().contains("WRONGTYPE"));assertFalse(redis.hasKey(RedisKeys.userFeature(1)));
        assertEquals("wrong-type",redis.opsForValue().get(RedisKeys.itemFeature(10)));source.assertClosed(admin,"feature-builder");
    }

    @Test void invalidSourceIsRejectedBeforePublishingAnyFeature() {
        features.writeItemFeatures(1,Map.of("old",7.0));
        assertThrows(PublicErrorException.class,()->job.run(new DefaultApplicationArguments("--behavior-table=user_behavior;DROP TABLE item")));
        assertThrows(PublicErrorException.class,()->job.run(new DefaultApplicationArguments("--item-table=item;DROP TABLE item_local")));
        assertEquals(Map.of("old",7.0),features.itemFeatures(1));source.assertClosed(admin,"feature-builder");
    }

    private static Map<String,Double> item(double pop,double avg,double count,double std){return Map.of("item_pop_norm",pop,"item_avg_rating",avg,"item_rating_cnt",count,"item_rating_std",std);}
}
