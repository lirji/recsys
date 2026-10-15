package com.recsys.query;

import com.recsys.common.constant.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.query.persistence.mapper.LexemeMapper;
import com.recsys.query.persistence.model.LexemeAlias;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
        ObjectProvider<LexemeMapper> mapperProvider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        LexemeMapper mapper = mock(LexemeMapper.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(mapperProvider.getIfAvailable()).thenReturn(mapper);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(RedisKeys.IDF_TERMS)).thenReturn(Map.of("movie", "2.250000"));
        when(hashes.entries(RedisKeys.IDF_LEXEMES))
                .thenReturn(Map.of("movi", "2.250000", "studi", "3.500000"));
        when(mapper.canonicalize(any()))
                .thenReturn(List.of(
                        new LexemeAlias("movies", "movi"),
                        new LexemeAlias("studies", "studi")));

        IdfWeighter weighter = new IdfWeighter(redisProvider, mapperProvider, new QueryProperties());

        assertThat(weighter.weight("movie")).isEqualTo(2.25);
        assertThat(weighter.weights(List.of("movies", "studies")))
                .containsEntry("movies", 2.25).containsEntry("studies", 3.5);
        assertThat(weighter.weight("unseen")).isEqualTo(1.0);
        verify(mapper, times(2)).canonicalize(any());
    }
}
