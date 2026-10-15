package com.recsys.recall;

import com.recsys.common.recall.RecallContext;
import com.recsys.recall.persistence.mapper.primary.RecallInputMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.recall.channel.DbUserProfileReader;
import com.recsys.recall.channel.I2iRecaller;
import com.recsys.recall.channel.RecentPositiveItemsSource;
import com.recsys.recall.channel.SwingRecaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 以真实行为事实区分种子排序和模型时间序列，防止迁移时合并了不同语义。 */
@Testcontainers
class RecallInputsPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private RecallInputMapper mapper;
    private ObjectProvider<StringRedisTemplate> noRedis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        mapper = new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),
                "classpath*:mapper/recall/primary/**/*.xml")).getMapper(RecallInputMapper.class);
        noRedis = mock(ObjectProvider.class);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_behavior (user_id BIGINT, item_id BIGINT, action TEXT, value DOUBLE PRECISION, ts TIMESTAMPTZ);
                COMMENT ON TABLE user_behavior IS '召回输入契约测试独占表';
                COMMENT ON COLUMN user_behavior.user_id IS '用户标识';
                COMMENT ON COLUMN user_behavior.item_id IS '物品标识';
                COMMENT ON COLUMN user_behavior.action IS '行为类型';
                COMMENT ON COLUMN user_behavior.value IS '评分';
                COMMENT ON COLUMN user_behavior.ts IS '事件时间';
                CREATE TABLE IF NOT EXISTS app_user (user_id BIGINT PRIMARY KEY, profile JSONB);
                COMMENT ON TABLE app_user IS '召回画像契约测试独占表';
                COMMENT ON COLUMN app_user.user_id IS '用户标识';
                COMMENT ON COLUMN app_user.profile IS '用户画像';
                TRUNCATE user_behavior, app_user;
                INSERT INTO user_behavior VALUES
                    (7,1,'CLICK',1,'2026-01-01'),(7,2,'RATING',5,'2026-01-02'),
                    (7,3,'RATING',2,'2026-01-03'),(7,1,'LIKE',1,'2026-01-04'),
                    (7,4,'PLAY',1,'2026-01-05'),(7,9,'EXPOSURE',1,'2026-01-06'),
                    (8,99,'CLICK',1,'2026-01-07');
                INSERT INTO app_user VALUES (7,'{"categories":["SciFi","Drama","SciFi",null]}'),
                    (8,'{}'),(9,NULL),(10,'{"categories":42}');
                """);
    }

    @Test
    void sequenceUsesLatestPositiveEventPerItemAndReturnsOldestFirst() {
        BehaviorSequenceSource source = new BehaviorSequenceSource(mapper, noRedis);
        assertThat(source.sequence(7, 50)).containsExactly(2L, 1L, 4L);
        assertThat(source.sequence(7, 2)).containsExactly(1L, 4L);
        assertThat(source.sequence(7, 0)).containsExactly(4L);
        assertThat(source.sequence(999, 10)).isEmpty();
    }

    @Test
    void legacySeedsIncludeAllRatingsAndSortByItemId() {
        RecentPositiveItemsSource source = new RecentPositiveItemsSource(mapper);
        assertThat(source.recentPositiveItems(7, 10)).containsExactly(4L, 3L, 2L, 1L);
        assertThat(source.recentPositiveItems(7, 2)).containsExactly(4L, 3L);
        assertThat(source.recentPositiveItems(7, 0)).isEmpty();
        assertThat(source.recentPositiveItems(999, 10)).isEmpty();
    }

    @Test
    void profilePreservesDuplicatesNullElementsAndFailureFallback() {
        DbUserProfileReader reader = new DbUserProfileReader(mapper);
        assertThat(reader.categories(7)).containsExactly("SciFi", "Drama", "SciFi", null);
        for (long user : List.of(8L, 9L, 10L, 999L)) {
            assertThat(reader.categories(user)).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void i2iAndSwingUseDatabaseSeedsOnlyWhenNotPrefetched() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        RecallProperties props = new RecallProperties();
        props.getQuota().setI2iSeed(2);
        I2iRecaller i2i = new I2iRecaller(new RecentPositiveItemsSource(mapper), redis, props);
        SwingRecaller swing = new SwingRecaller(new RecentPositiveItemsSource(mapper), redis, props);
        RecallContext context = new RecallContext(7, 10, "home");
        i2i.recall(context);
        swing.recall(context);
        verify(zsets).reverseRangeWithScores(eq("i2i:4"), anyLong(), anyLong());
        verify(zsets).reverseRangeWithScores(eq("i2i:3"), anyLong(), anyLong());
        verify(zsets).reverseRangeWithScores(eq("swing:4"), anyLong(), anyLong());
        verify(zsets).reverseRangeWithScores(eq("swing:3"), anyLong(), anyLong());
        clearInvocations(zsets);
        i2i.recall(context.withRecentPositiveItems(List.of()));
        swing.recall(context.withRecentPositiveItems(List.of()));
        verifyNoInteractions(zsets);
    }

    @Test
    void invalidLimitRetainsTheCallerSpecificErrorBoundary() {
        assertThatThrownBy(() -> new RecentPositiveItemsSource(mapper).recentPositiveItems(7, -1))
                .isInstanceOf(DataAccessException.class);
        assertThat(new BehaviorSequenceSource(mapper, noRedis).sequence(7, -1)).containsExactly(4L);
    }
}
