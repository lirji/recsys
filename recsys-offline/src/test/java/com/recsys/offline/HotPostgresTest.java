package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.offline.persistence.mapper.primary.HotScoreMapper;
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

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG/Redis冻结热榜权重、来源、严格时间上界及空结果/查询失败的旧榜保留。 */
@Testcontainers
class HotPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory redisFactory;
    static HotJob job;
    static HotScoreMapper mapper;

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=hot-reader",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        for(String table:new String[]{"user_behavior","behavior_log"}){
            db.execute("CREATE TABLE "+table+"(item_id BIGINT,action TEXT,value DOUBLE PRECISION,ts TIMESTAMPTZ)");
            db.execute("COMMENT ON TABLE "+table+" IS '热榜独占测试行为源'");
            for(String column:new String[]{"item_id","action","value","ts"})db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原行为字段'");
        }
        redisFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        mapper=new SqlSessionTemplate(SpringSqlSessions.create(source,
                "classpath*:mapper/offline/primary/**/*.xml")).getMapper(HotScoreMapper.class);
        job=new HotJob(mapper,redis);
    }
    @AfterAll static void close(){if(redisFactory!=null)redisFactory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE user_behavior,behavior_log");redis.delete(RedisKeys.HOT_RECALL);}

    @Test void weightsAllOriginalActionsAndRetainsNullIdentityAndNonPositiveScores() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(1,'CLICK',NULL,NULL),(1,'LIKE',NULL,NULL),(1,'PLAY',NULL,NULL),(1,'RATING',4,NULL),(1,'RATING',NULL,NULL),(2,'RATING',3.5,NULL),(2,'CLICK',1,NULL),(3,'OTHER',NULL,NULL),(3,NULL,NULL,NULL),(NULL,'LIKE',1,NULL),(4,'RATING',-2,NULL)");
        redis.opsForZSet().add(RedisKeys.HOT_RECALL,"old",999);
        job.run(new DefaultApplicationArguments());
        assertEquals(Map.of("1",9.0,"2",4.5,"3",0.0,"0",2.0,"4",-2.0),scores());
        job.run(new DefaultApplicationArguments("--topn=2"));assertEquals(Map.of("1",9.0,"2",4.5),scores());
        source.assertClosed(admin,"hot-reader");
    }

    @Test void alternateSourceAndStrictMicrosecondCutoffArePreserved() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(99,'LIKE',1,to_timestamp(1))");
        db.execute("INSERT INTO behavior_log VALUES(1,'LIKE',1,to_timestamp(99.999999)),(2,'CLICK',1,to_timestamp(100)),(3,'LIKE',1,to_timestamp(100.000001)),(4,'LIKE',1,NULL)");
        job.run(new DefaultApplicationArguments("--behavior-table=behavior_log","--max-ts=100"));
        assertEquals(Map.of("1",2.0,"2",1.0),scores());source.assertClosed(admin,"hot-reader");
    }

    @Test void recentWindowKeepsOriginalFutureRowsAndFiltersNullTimestamps() throws Exception {
        db.execute("INSERT INTO user_behavior VALUES(1,'CLICK',1,now()-interval '12 hours'),(2,'CLICK',1,now()-interval '2 days'),(3,'LIKE',1,now()+interval '1 day'),(4,'LIKE',1,NULL)");
        job.run(new DefaultApplicationArguments("--recent-days=1"));
        assertEquals(Map.of("1",1.0,"3",2.0),scores());source.assertClosed(admin,"hot-reader");
    }

    @Test void emptyZeroLimitAndInvalidInputsDoNotReplaceOldRanking() throws Exception {
        redis.opsForZSet().add(RedisKeys.HOT_RECALL,"old",7);
        job.run(new DefaultApplicationArguments());
        db.execute("INSERT INTO user_behavior VALUES(1,'LIKE',1,now())");
        job.run(new DefaultApplicationArguments("--topn=0"));
        assertThrows(DataAccessException.class,()->job.run(new DefaultApplicationArguments("--topn=-1")));
        assertThrows(IllegalArgumentException.class,()->job.run(new DefaultApplicationArguments("--behavior-table=user_behavior;DROP TABLE behavior_log")));
        assertEquals(Map.of("old",7.0),scores());source.assertClosed(admin,"hot-reader");
    }

    @Test void defaultLimitSelectsTheThousandHighestScores() throws Exception {
        db.execute("INSERT INTO user_behavior SELECT n,'RATING',n,now() FROM generate_series(1,1105)n");
        job.run(new DefaultApplicationArguments());
        var expected=LongStream.rangeClosed(106,1105).boxed().collect(Collectors.toMap(Object::toString,Long::doubleValue));
        assertEquals(expected,scores());source.assertClosed(admin,"hot-reader");
    }

    @Test void missingSourceFailsBeforeRedisMutationAndClosesResources() {
        redis.opsForZSet().add(RedisKeys.HOT_RECALL,"old",7);
        db.execute("ALTER TABLE user_behavior RENAME TO hidden_behavior");
        try{
            assertThrows(BadSqlGrammarException.class,()->job.run(new DefaultApplicationArguments()));
            assertEquals(Map.of("old",7.0),scores());source.assertClosed(admin,"hot-reader");
        }finally{db.execute("ALTER TABLE hidden_behavior RENAME TO user_behavior");}
    }

    @Test void nullIdentityAndActualZeroRemainSeparateSqlGroups() {
        db.execute("INSERT INTO user_behavior VALUES(NULL,'RATING',2,NULL),(0,'RATING',9,NULL)");
        var oldRows=db.query("SELECT item_id,sum(value) hot FROM user_behavior GROUP BY item_id ORDER BY hot DESC",
                (rs,n)->rs.getLong("item_id")+":"+rs.getDouble("hot"));
        assertEquals(java.util.List.of("0:9.0","0:2.0"),oldRows);
        var current=new java.util.ArrayList<String>();
        mapper.scan(com.recsys.offline.persistence.model.BehaviorSource.USER_BEHAVIOR,0,null,1000,
                context->{var row=context.getResultObject();current.add(row.itemId()+":"+row.hot());});
        assertEquals(oldRows,current);source.assertClosed(admin,"hot-reader");
    }

    private static Map<String,Double> scores(){return redis.opsForZSet().rangeWithScores(RedisKeys.HOT_RECALL,0,-1).stream().collect(Collectors.toMap(t->t.getValue(),t->t.getScore()));}
}
