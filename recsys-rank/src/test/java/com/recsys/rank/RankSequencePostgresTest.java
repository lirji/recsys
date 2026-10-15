package com.recsys.rank;

import com.recsys.common.feature.FeatureService;
import com.recsys.common.rank.RankService;
import com.recsys.content.ContentService;
import com.recsys.rank.persistence.mapper.RatingSequenceMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 从公开rank结果观察序列输入，并核验回填时间，防止SQL时间转换改变缓存语义。 */
@Testcontainers
class RankSequencePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private RatingSequenceMapper mapper;
    private ZSetOperations<String, String> zsets;
    private ObjectProvider<StringRedisTemplate> redisProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_behavior(user_id BIGINT, item_id BIGINT, action TEXT, value DOUBLE PRECISION, ts TIMESTAMPTZ);
                COMMENT ON TABLE user_behavior IS '排序序列契约测试独占表';
                COMMENT ON COLUMN user_behavior.user_id IS '用户标识';
                COMMENT ON COLUMN user_behavior.item_id IS '物品标识';
                COMMENT ON COLUMN user_behavior.action IS '行为类型';
                COMMENT ON COLUMN user_behavior.value IS '评分';
                COMMENT ON COLUMN user_behavior.ts IS '事件时间';
                TRUNCATE user_behavior;
                INSERT INTO user_behavior VALUES (7,1,'RATING',5,'2026-01-01'),(7,2,'RATING',2,'2026-01-02'),
                    (7,3,'CLICK',1,'2026-01-02'),(7,4,'RATING',4,'2026-01-03 00:00:00.750Z'),(7,5,'RATING',5,'2026-01-04'),
                    (8,6,'RATING',5,NULL),(9,9,'RATING',5,'1969-12-31 23:59:59.750Z');
                """);
        mapper = new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),
                "classpath*:mapper/rank/primary/**/*.xml")).getMapper(RatingSequenceMapper.class);
        redisProvider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        zsets = mock(ZSetOperations.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForZSet()).thenReturn(zsets);
    }

    private RankService ranker(boolean dien) {
        FeatureService features = mock(FeatureService.class);
        ContentService content = mock(ContentService.class);
        when(features.userFeatures(anyLong())).thenReturn(Map.of());
        when(features.itemFeatures(anyList())).thenReturn(Map.of());
        when(content.findByIds(anyList())).thenReturn(Map.of());
        RankProperties props = new RankProperties();
        props.setDinModelPath("classpath:model/sequence-fixture.onnx");
        props.setDienModelPath("classpath:model/sequence-fixture.onnx");
        props.setDienSchemaPath(props.getDinSchemaPath());
        props.setDienCategoryVocabPath(props.getDinCategoryVocabPath());
        if (dien) {
            DienRankService service = new DienRankService(features, content, mapper, redisProvider, props);
            service.load();
            assertThat(service.isReady()).isTrue();
            return service;
        }
        DinRankService service = new DinRankService(features, content, mapper, redisProvider, props);
        service.load();
        assertThat(service.isReady()).isTrue();
        return service;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyStrongRatingsReachTheModelOldestFirstAndBroadcastToAllCandidates(boolean dien) {
        RankService service = ranker(dien);
        try {
            var rows = service.rank(7, List.of(101L, 102L), "home");
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> assertThat(row.featureSnapshot()).containsEntry("pCTR", 0.01).containsEntry("pCVR", 0.03));
            Map<String, Double> warmed = warmedScores();
            assertThat(warmed).containsOnlyKeys("1", "4", "5");
            assertThat(warmed.get("4")).isEqualTo((double) Instant.parse("2026-01-03T00:00:00Z").getEpochSecond());
        } finally {
            close(service);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullAndNegativeFractionalEpochKeepTheOriginalJavaTruncation(boolean dien) {
        RankService service = ranker(dien);
        try {
            assertThat(service.rank(8, List.of(101L), "home")).hasSize(1);
            assertThat(warmedScores()).containsEntry("6", 0.0);
            clearInvocations(zsets);
            assertThat(service.rank(9, List.of(101L), "home")).hasSize(1);
            assertThat(warmedScores()).containsEntry("9", 0.0);
        } finally {
            close(service);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseLimitKeepsTheLatestTwentyEventsBeforeReversing(boolean dien) {
        for (int i = 1; i <= 25; i++) {
            jdbc.update("INSERT INTO user_behavior VALUES (10,?,'RATING',5,to_timestamp(?))", i, i);
        }
        RankService service = ranker(dien);
        try {
            var rows = service.rank(10, List.of(101L), "home");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().featureSnapshot()).containsEntry("pCTR", 0.06).containsEntry("pCVR", 0.20);
            assertThat(warmedScores()).hasSize(20).doesNotContainKey("5").containsKey("6");
        } finally {
            close(service);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Double> warmedScores() {
        ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor = ArgumentCaptor.forClass(Set.class);
        verify(zsets).add(anyString(), captor.capture());
        return captor.getValue().stream().collect(Collectors.toMap(ZSetOperations.TypedTuple::getValue, ZSetOperations.TypedTuple::getScore));
    }

    private static void close(RankService service) {
        if (service instanceof DinRankService din) din.close();
        if (service instanceof DienRankService dien) dien.close();
    }
}
