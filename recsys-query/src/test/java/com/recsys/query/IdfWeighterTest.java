package com.recsys.query;

import com.recsys.common.constant.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdfWeighterTest {

    @Test
    @SuppressWarnings("unchecked")
    void unknownRawInflectionFallsBackToCanonicalPostgresLexeme() {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbc);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(RedisKeys.IDF_TERMS)).thenReturn(Map.of("movie", "2.250000"));
        when(hashes.entries(RedisKeys.IDF_LEXEMES))
                .thenReturn(Map.of("movi", "2.250000", "studi", "3.500000"));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(
                        new IdfWeighter.LexemeAlias("movies", "movi"),
                        new IdfWeighter.LexemeAlias("studies", "studi")));

        IdfWeighter weighter = new IdfWeighter(redisProvider, jdbcProvider, new QueryProperties());

        assertThat(weighter.weight("movie")).isEqualTo(2.25);
        assertThat(weighter.weights(List.of("movies", "studies")))
                .containsEntry("movies", 2.25).containsEntry("studies", 3.5);
        assertThat(weighter.weight("unseen")).isEqualTo(1.0);
        verify(jdbc, times(2)).query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));
    }
}
