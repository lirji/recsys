package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import com.recsys.offline.persistence.mapper.derived.LookalikeMapper;
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
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实pgvector/Redis保护质心去重、可空投影、扩散上限和失败前旧包保留。 */
@Testcontainers
class LookalikePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory redisFactory;
    static LookalikeJob job;

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=lookalike-reader",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        db.execute("CREATE EXTENSION vector");
        db.execute("CREATE TABLE ad_audience_seed(audience_id BIGINT,user_id BIGINT)");
        db.execute("COMMENT ON TABLE ad_audience_seed IS '独占人群种子测试，验证重复与可空身份'");
        db.execute("COMMENT ON COLUMN ad_audience_seed.audience_id IS '人群身份'");
        db.execute("COMMENT ON COLUMN ad_audience_seed.user_id IS '种子用户身份'");
        db.execute("CREATE TABLE user_embedding(user_id BIGINT UNIQUE,embedding vector(2))");
        db.execute("COMMENT ON TABLE user_embedding IS '独占用户向量测试，可空旧行投影兼容'");
        db.execute("COMMENT ON COLUMN user_embedding.user_id IS '用户身份'");
        db.execute("COMMENT ON COLUMN user_embedding.embedding IS '两维测试向量'");
        redisFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        job=new LookalikeJob(new SqlSessionTemplate(SpringSqlSessions.create(source,
                "classpath*:mapper/offline/derived/**/*.xml")).getMapper(LookalikeMapper.class),redis);
    }
    @AfterAll static void close(){if(redisFactory!=null)redisFactory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE ad_audience_seed,user_embedding");try(var connection=redis.getConnectionFactory().getConnection()){connection.serverCommands().flushDb();}}

    @Test void distinctSeedVectorsDriveCentroidAndSeedUnionReplacesOldPackage() throws Exception {
        db.execute("INSERT INTO ad_audience_seed VALUES(7,1),(7,1),(7,2),(7,99),(8,5),(NULL,77)");
        db.execute("INSERT INTO user_embedding VALUES(1,'[1,0]'),(2,'[0,1]'),(3,'[1,1]'),(4,'[-1,0]'),(5,NULL)");
        redis.opsForSet().add(key(7),"old");redis.opsForSet().add(key(8),"keep");
        job.run(new DefaultApplicationArguments("--top-n=1"));
        assertEquals(Set.of("1","2","99","3"),members(7));
        assertEquals(Set.of("keep"),members(8));assertFalse(redis.hasKey(RedisKeys.adAudience(0L)));
        source.assertClosed(admin,"lookalike-reader");
    }

    @Test void zeroExpansionKeepsNullAndLargeSeedIdentityAndOnlyRequestedAudience() throws Exception {
        db.execute("INSERT INTO ad_audience_seed VALUES(7,1),(7,NULL),(7,9223372036854775807),(8,2)");
        db.execute("INSERT INTO user_embedding VALUES(1,'[1,0]'),(2,'[0,1]')");
        redis.opsForSet().add(key(8),"untouched");
        job.run(new DefaultApplicationArguments("--audience=7","--top-n=0"));
        assertEquals(Set.of("1","null",Long.toString(Long.MAX_VALUE)),members(7));
        assertEquals(Set.of("untouched"),members(8));source.assertClosed(admin,"lookalike-reader");
    }

    @Test void nullableAnnIdentityKeepsOriginalGetLongZeroConversion() throws Exception {
        db.execute("INSERT INTO ad_audience_seed VALUES(7,1),(7,2)");
        db.execute("INSERT INTO user_embedding VALUES(1,'[1,0]'),(2,'[0,1]'),(NULL,'[1,1]')");
        job.run(new DefaultApplicationArguments("--audience=7","--top-n=1"));
        assertEquals(Set.of("1","2","0"),members(7));source.assertClosed(admin,"lookalike-reader");
    }

    @Test void absentSeedsOrAllNullVectorsLeaveExistingPackage() throws Exception {
        redis.opsForSet().add(key(7),"keep");
        job.run(new DefaultApplicationArguments());job.run(new DefaultApplicationArguments("--audience=7"));
        db.execute("INSERT INTO ad_audience_seed VALUES(7,1),(7,2);INSERT INTO user_embedding VALUES(1,NULL)");
        job.run(new DefaultApplicationArguments("--audience=7"));
        assertEquals(Set.of("keep"),members(7));source.assertClosed(admin,"lookalike-reader");
    }

    @Test void defaultThousandLimitKeepsActualNearestUsers() throws Exception {
        db.execute("INSERT INTO ad_audience_seed VALUES(7,1)");
        db.execute("INSERT INTO user_embedding SELECT n,('[1,'||((n-1)::double precision/1101)||']')::vector FROM generate_series(1,1101)n");
        job.run(new DefaultApplicationArguments());
        assertEquals(LongStream.rangeClosed(1,1000).mapToObj(Long::toString).collect(Collectors.toSet()),members(7));
        source.assertClosed(admin,"lookalike-reader");
    }

    @Test void invalidLimitFailsBeforeRedisReplacementAndConnectionCanBeReused() throws Exception {
        db.execute("INSERT INTO ad_audience_seed VALUES(7,1);INSERT INTO user_embedding VALUES(1,'[1,0]')");
        redis.opsForSet().add(key(7),"keep");
        assertThrows(DataAccessException.class,()->job.run(new DefaultApplicationArguments("--top-n=-1")));
        assertEquals(Set.of("keep"),members(7));source.assertClosed(admin,"lookalike-reader");
        job.run(new DefaultApplicationArguments("--top-n=1"));assertEquals(Set.of("1"),members(7));
        source.assertClosed(admin,"lookalike-reader");
    }

    @Test void missingVectorTablePropagatesFailureAndKeepsOldPackage() {
        db.execute("INSERT INTO ad_audience_seed VALUES(7,1)");redis.opsForSet().add(key(7),"keep");
        db.execute("ALTER TABLE user_embedding RENAME TO hidden_embedding");
        try{
            assertThrows(BadSqlGrammarException.class,()->job.run(new DefaultApplicationArguments()));
            assertEquals(Set.of("keep"),members(7));source.assertClosed(admin,"lookalike-reader");
        }finally{db.execute("ALTER TABLE hidden_embedding RENAME TO user_embedding");}
    }

    private static String key(long audience){return RedisKeys.adAudience(audience);}
    private static Set<String> members(long audience){return redis.opsForSet().members(key(audience));}
}
