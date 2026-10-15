package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.offline.persistence.mapper.primary.ColdBanditMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
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

import static org.junit.jupiter.api.Assertions.*;

/** 冷启动类目统计的真实PG/Redis保护，固定关联过滤、正反馈口径与旧键保留规则。 */
@Testcontainers
class ColdBanditPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory redisFactory;
    static ColdBanditStatsJob job;

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=cold-bandit-reader",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        for(String table:List.of("user_behavior","behavior_log")){
            db.execute("CREATE TABLE "+table+"(item_id BIGINT,action TEXT,value DOUBLE PRECISION)");
            db.execute("COMMENT ON TABLE "+table+" IS '冷启动统计独占行为源'");
            for(String column:List.of("item_id","action","value"))db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原行为字段'");
        }
        for(String table:List.of("item","item_local")){
            db.execute("CREATE TABLE "+table+"(item_id BIGINT PRIMARY KEY,category TEXT)");
            db.execute("COMMENT ON TABLE "+table+" IS '冷启动统计独占目录源'");
            db.execute("COMMENT ON COLUMN "+table+".item_id IS '物品身份'");
            db.execute("COMMENT ON COLUMN "+table+".category IS '原类目，可空或空串'");
        }
        redisFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        job=new ColdBanditStatsJob(new SqlSessionTemplate(SpringSqlSessions.create(source,
                "classpath*:mapper/offline/primary/**/*.xml")).getMapper(ColdBanditMapper.class),redis);
    }
    @AfterAll static void close(){if(redisFactory!=null)redisFactory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE user_behavior,behavior_log,item,item_local");try(var connection=redis.getConnectionFactory().getConnection()){connection.serverCommands().flushDb();}}

    @Test void countsAllJoinedActionsAndOnlyOriginalPositiveFeedback() {
        db.execute("INSERT INTO item VALUES(1,'电影'),(2,'电影'),(3,'图文'),(4,NULL),(5,'')");
        db.execute("INSERT INTO user_behavior VALUES(1,'CLICK',NULL),(1,'CLICK',1),(1,'LIKE',1),(1,'PLAY',1),(2,'RATING',4),(2,'RATING',3.9),(2,'RATING',NULL),(2,'IMPRESSION',1),(2,NULL,NULL),(3,'IMPRESSION',1),(4,'CLICK',1),(5,'RATING',4),(99,'LIKE',1),(NULL,'LIKE',1)");
        job.run(new DefaultApplicationArguments());
        assertEquals("9,5",stat("电影"));assertEquals("1,0",stat("图文"));assertEquals("1,1",stat(""));
        assertEquals("11",redis.opsForValue().get(RedisKeys.COLD_CAT_TOTAL));source.assertClosed(admin,"cold-bandit-reader");
    }

    @Test void bothSourceSelectorsAndCustomThresholdAreAppliedIndependently() {
        db.execute("INSERT INTO item VALUES(1,'主目录');INSERT INTO item_local VALUES(1,'本地目录')");
        db.execute("INSERT INTO user_behavior VALUES(1,'LIKE',1);INSERT INTO behavior_log VALUES(1,'RATING',3),(1,'IMPRESSION',1)");
        job.run(new DefaultApplicationArguments("--behavior-table=behavior_log","--item-table=item_local","--min-rating=3"));
        assertEquals("2,1",stat("本地目录"));assertNull(stat("主目录"));
        job.run(new DefaultApplicationArguments("--behavior-table=behavior_log","--min-rating=3.1"));
        assertEquals("2,0",stat("主目录"));assertEquals("2,1",stat("本地目录"));
        job.run(new DefaultApplicationArguments("--item-table=item_local"));assertEquals("1,1",stat("本地目录"));
        source.assertClosed(admin,"cold-bandit-reader");
    }

    @Test void emptyResultsAndDisappearedCategoriesLeaveExistingKeys() {
        redis.opsForValue().set(RedisKeys.coldCatStats("old"),"99,88");redis.opsForValue().set(RedisKeys.COLD_CAT_TOTAL,"99");
        job.run(new DefaultApplicationArguments());assertEquals("99",redis.opsForValue().get(RedisKeys.COLD_CAT_TOTAL));
        db.execute("INSERT INTO item VALUES(1,'new');INSERT INTO user_behavior VALUES(1,'CLICK',1)");
        job.run(new DefaultApplicationArguments());assertEquals("1",redis.opsForValue().get(RedisKeys.COLD_CAT_TOTAL));
        assertEquals("99,88",stat("old"));assertEquals("1,1",stat("new"));source.assertClosed(admin,"cold-bandit-reader");
    }

    @Test void rejectsUntrustedSourceNamesBeforeRedisMutation() {
        redis.opsForValue().set(RedisKeys.COLD_CAT_TOTAL,"99");
        assertThrows(IllegalArgumentException.class,()->job.run(new DefaultApplicationArguments("--behavior-table=user_behavior;DROP TABLE item")));
        assertThrows(IllegalArgumentException.class,()->job.run(new DefaultApplicationArguments("--item-table=item;DROP TABLE item_local")));
        assertEquals("99",redis.opsForValue().get(RedisKeys.COLD_CAT_TOTAL));source.assertClosed(admin,"cold-bandit-reader");
    }

    @Test void missingCatalogFailsBeforeAnyRedisWrite() {
        redis.opsForValue().set(RedisKeys.COLD_CAT_TOTAL,"99");db.execute("ALTER TABLE item RENAME TO hidden_item");
        try{
            assertThrows(BadSqlGrammarException.class,()->job.run(new DefaultApplicationArguments()));
            assertEquals("99",redis.opsForValue().get(RedisKeys.COLD_CAT_TOTAL));source.assertClosed(admin,"cold-bandit-reader");
        }finally{db.execute("ALTER TABLE hidden_item RENAME TO item");}
    }

    private static String stat(String category){return redis.opsForValue().get(RedisKeys.coldCatStats(category));}
}
