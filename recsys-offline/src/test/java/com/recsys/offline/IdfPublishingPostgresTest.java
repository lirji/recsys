package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;


import com.recsys.common.constant.RedisKeys;
import com.recsys.offline.persistence.mapper.primary.IdfCorpusMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 真实PG全文检索与Redis发布保护，覆盖2000词批次和单Hash发布的实际失败边界。 */
@Testcontainers
class IdfPublishingPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static JdbcTemplate admin,db;
    static TrackedDriverDataSource source;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory redisFactory;
    static IdfJob job;
    static IdfCorpusMapper mapper;

    @BeforeAll static void prepare() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        source=new TrackedDriverDataSource(PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=idf-reader",PG.getUsername(),PG.getPassword());
        db=new JdbcTemplate(source);
        for(String table:List.of("item","item_local")){
            db.execute("CREATE TABLE "+table+"(item_id BIGINT PRIMARY KEY,title TEXT,category TEXT,title_tsv TSVECTOR GENERATED ALWAYS AS(to_tsvector('english',coalesce(title,'')||' '||coalesce(category,''))) STORED)");
            db.execute("COMMENT ON TABLE "+table+" IS 'IDF独占目录测试'");
            for(String column:List.of("item_id","title","category","title_tsv"))db.execute("COMMENT ON COLUMN "+table+"."+column+" IS '原目录/检索字段'");
        }
        redisFactory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        mapper=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml")).getMapper(IdfCorpusMapper.class);
        job=new IdfJob(mapper,redis);
    }
    @AfterAll static void close(){if(redisFactory!=null)redisFactory.destroy();}
    @BeforeEach void reset(){db.execute("TRUNCATE item,item_local");try(var connection=redis.getConnectionFactory().getConnection()){connection.serverCommands().flushDb();}}

    @Test void publishesCanonicalAndInflectedAliasesWithDocumentFrequencyAndNullDocuments() throws Exception {
        db.execute("INSERT INTO item(item_id,title,category) VALUES(1,'Movie studies rare rare','about'),(2,'Movie studying','about'),(3,'Movies studies',NULL),(4,NULL,NULL)");
        oldSnapshot();job.run(new DefaultApplicationArguments());
        assertEquals(Map.of("movi","1.223144","studi","1.223144","rare","1.916291"),entries(RedisKeys.IDF_LEXEMES));
        assertEquals(Map.of("movi","1.223144","movie","1.223144","movies","1.223144","studi","1.223144","studies","1.223144","studying","1.223144","rare","1.916291"),entries(RedisKeys.IDF_TERMS));
        assertEquals("4",redis.opsForValue().get(RedisKeys.IDF_DOC_COUNT));assertTrue(redis.keys("idf:*:tmp:*").isEmpty());
        job.run(new DefaultApplicationArguments("--min-df=2"));assertFalse(entries(RedisKeys.IDF_LEXEMES).containsKey("rare"));assertFalse(entries(RedisKeys.IDF_TERMS).containsKey("rare"));
        source.assertClosed(admin,"idf-reader");
    }

    @Test void replicaSourceUsesItsOwnCountAndLexemes() throws Exception {
        db.execute("INSERT INTO item(item_id,title) VALUES(1,'primary primary');INSERT INTO item_local(item_id,title) VALUES(1,'Robot'),(2,'Robots')");
        job.run(new DefaultApplicationArguments("--item-table=item_local"));
        assertEquals(Map.of("robot","1.000000"),entries(RedisKeys.IDF_LEXEMES));
        assertEquals(Map.of("robot","1.000000","robots","1.000000"),entries(RedisKeys.IDF_TERMS));
        assertEquals("2",redis.opsForValue().get(RedisKeys.IDF_DOC_COUNT));source.assertClosed(admin,"idf-reader");
    }

    @Test void emptyCorpusKeepsOldSnapshotButNoQualifyingTermsDeletesBothHashes() throws Exception {
        oldSnapshot();assertEquals(ErrorCode.JOB_NO_ITEMS, assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments())).errorCode());assertOldSnapshot();
        db.execute("INSERT INTO item(item_id,title) VALUES(1,'about the')");
        job.run(new DefaultApplicationArguments());assertFalse(redis.hasKey(RedisKeys.IDF_LEXEMES));assertFalse(redis.hasKey(RedisKeys.IDF_TERMS));assertEquals("1",redis.opsForValue().get(RedisKeys.IDF_DOC_COUNT));
        oldSnapshot();db.update("UPDATE item SET title='movie'");job.run(new DefaultApplicationArguments("--min-df=2"));
        assertFalse(redis.hasKey(RedisKeys.IDF_LEXEMES));assertFalse(redis.hasKey(RedisKeys.IDF_TERMS));source.assertClosed(admin,"idf-reader");
    }

    @Test void moreThanTwoThousandTokensCrossTheOriginalArrayBatchBoundary() throws Exception {
        Set<String> tokens=IntStream.range(0,3001).mapToObj(i->"token"+i).collect(Collectors.toSet());
        db.update("INSERT INTO item(item_id,title) VALUES(1,?)",String.join(" ",tokens));
        job.run(new DefaultApplicationArguments());
        var expected=tokens.stream().collect(Collectors.toMap(s->s,s->"1.000000"));
        assertEquals(expected,entries(RedisKeys.IDF_LEXEMES));assertEquals(expected,entries(RedisKeys.IDF_TERMS));
        assertTrue(redis.keys("idf:*:tmp:*").isEmpty());source.assertClosed(admin,"idf-reader");
    }

    @Test void invalidSourceAndMissingTsvFailBeforeRedisChanges() {
        oldSnapshot();assertThrows(PublicErrorException.class,()->job.run(new DefaultApplicationArguments("--item-table=item;DROP TABLE item_local")));
        db.execute("INSERT INTO item(item_id,title) VALUES(1,'movie');ALTER TABLE item RENAME COLUMN title_tsv TO hidden_tsv");
        try{
            assertThrows(BadSqlGrammarException.class,()->job.run(new DefaultApplicationArguments()));assertOldSnapshot();source.assertClosed(admin,"idf-reader");
        }finally{db.execute("ALTER TABLE item RENAME COLUMN hidden_tsv TO title_tsv");}
    }

    @Test void secondHashRenameFailureKeepsFirstPublicationAndCleansTemporaryKey() {
        oldSnapshot();db.execute("INSERT INTO item(item_id,title) VALUES(1,'movie')");
        var failure=new IllegalStateException("injected alias rename failure");
        var faultRedis=new StringRedisTemplate(redisFactory){
            @Override public void rename(String oldKey,String newKey){
                if(RedisKeys.IDF_TERMS.equals(newKey))throw failure;
                super.rename(oldKey,newKey);
            }
        };
        assertSame(failure,assertThrows(IllegalStateException.class,()->new IdfJob(mapper,faultRedis).run(new DefaultApplicationArguments())));
        assertEquals(Map.of("movi","1.000000"),entries(RedisKeys.IDF_LEXEMES));
        assertEquals(Map.of("old","7"),entries(RedisKeys.IDF_TERMS));assertEquals("99",redis.opsForValue().get(RedisKeys.IDF_DOC_COUNT));
        assertTrue(redis.keys("idf:*:tmp:*").isEmpty());source.assertClosed(admin,"idf-reader");
    }

    private static Map<Object,Object> entries(String key){return redis.opsForHash().entries(key);}
    private static void oldSnapshot(){redis.opsForHash().put(RedisKeys.IDF_LEXEMES,"old","7");redis.opsForHash().put(RedisKeys.IDF_TERMS,"old","7");redis.opsForValue().set(RedisKeys.IDF_DOC_COUNT,"99");}
    private static void assertOldSnapshot(){assertEquals(Map.of("old","7"),entries(RedisKeys.IDF_LEXEMES));assertEquals(Map.of("old","7"),entries(RedisKeys.IDF_TERMS));assertEquals("99",redis.opsForValue().get(RedisKeys.IDF_DOC_COUNT));}
}
