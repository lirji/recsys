package com.recsys.query;

import com.recsys.common.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.query.persistence.mapper.LexemeMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 PostgreSQL 分词验证；Redis 只提供离线权重，不能用 mock 分词证明词形契约。 */
@Testcontainers
class IdfWeighterPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private IdfWeighter weighter;
    private ObjectProvider<LexemeMapper> mapperProvider;
    private LexemeMapper mapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() throws Exception {
        mapper = new SqlSessionTemplate(SpringSqlSessions.create(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
                "classpath*:mapper/query/primary/**/*.xml")).getMapper(LexemeMapper.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        mapperProvider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(RedisKeys.IDF_TERMS)).thenReturn(Map.of("movie", "2.25"));
        when(hashes.entries(RedisKeys.IDF_LEXEMES)).thenReturn(Map.of("movi", "2.25", "studi", "3.5"));
        weighter = new IdfWeighter(redisProvider, mapperProvider, new QueryProperties());
    }

    @Test
    void inflectionsStopwordsAndMultiLexemeTokensUsePostgresSemantics() {
        assertThat(weighter.weights(List.of("movie", "movies", "studies", "about", "movie study", "unknown")))
                .containsEntry("movie", 2.25).containsEntry("movies", 2.25).containsEntry("studies", 3.5)
                .containsEntry("about", 1.0).containsEntry("movie study", 1.0).containsEntry("unknown", 1.0);
        assertThat(weighter.weights(List.of())).isEmpty();
    }

    @Test
    void missingDatabaseDoesNotPoisonTheAliasCache() {
        when(mapperProvider.getIfAvailable()).thenReturn(null);
        assertThat(weighter.weight("movies")).isEqualTo(1.0);
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);
        assertThat(weighter.weight("movies")).isEqualTo(2.25);
    }
}
