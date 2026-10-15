package com.recsys.recall.channel;

import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import com.recsys.recall.persistence.mapper.derived.SemanticCodeMapper;
import com.recsys.recall.persistence.mapper.primary.RecallInputMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 主库只存行为，派生schema只存语义码；端到端检验前缀评分和生成后的三级回退。 */
@Testcontainers
class SemanticCodesPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate primary;
    private JdbcTemplate derived;
    private SemanticIdRecaller semantic;
    private TigerRecaller tiger;

    @BeforeEach
    void prepare() throws Exception {
        primary = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        primary.execute("CREATE SCHEMA IF NOT EXISTS derived");
        derived = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=derived",
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        primary.execute("""
                CREATE TABLE IF NOT EXISTS user_behavior(user_id BIGINT, item_id BIGINT, action TEXT, value DOUBLE PRECISION, ts TIMESTAMPTZ);
                COMMENT ON TABLE user_behavior IS '语义召回契约测试行为表';
                COMMENT ON COLUMN user_behavior.user_id IS '用户标识';
                COMMENT ON COLUMN user_behavior.item_id IS '物品标识';
                COMMENT ON COLUMN user_behavior.action IS '行为类型';
                COMMENT ON COLUMN user_behavior.value IS '评分';
                COMMENT ON COLUMN user_behavior.ts IS '事件时间';
                TRUNCATE user_behavior;
                INSERT INTO user_behavior VALUES (7,1,'CLICK',1,'2026-01-01'),(7,2,'RATING',2,'2026-01-02');
                """);
        derived.execute("""
                CREATE TABLE IF NOT EXISTS item_semantic_id(item_id BIGINT PRIMARY KEY, c0 INTEGER, c1 INTEGER, c2 INTEGER);
                COMMENT ON TABLE item_semantic_id IS '语义召回契约测试派生表';
                COMMENT ON COLUMN item_semantic_id.item_id IS '物品标识';
                COMMENT ON COLUMN item_semantic_id.c0 IS '第一级码';
                COMMENT ON COLUMN item_semantic_id.c1 IS '第二级码';
                COMMENT ON COLUMN item_semantic_id.c2 IS '第三级码';
                TRUNCATE item_semantic_id;
                """);
        RecallProperties props = new RecallProperties();
        props.getTiger().setModelPath("classpath:model/tiger-fixture.onnx");
        props.getTiger().setSchemaPath("classpath:model/tiger-fixture-schema.json");
        props.getTiger().setBeam(1);
        RecallInputMapper inputMapper = new SqlSessionTemplate(SpringSqlSessions.create(primary.getDataSource(),
                "classpath*:mapper/recall/primary/**/*.xml")).getMapper(RecallInputMapper.class);
        SemanticCodeMapper codeMapper = new SqlSessionTemplate(SpringSqlSessions.create(derived.getDataSource(),
                "classpath*:mapper/recall/derived/**/*.xml")).getMapper(SemanticCodeMapper.class);
        semantic = new SemanticIdRecaller(new RecentPositiveItemsSource(inputMapper), codeMapper, props);
        tiger = new TigerRecaller(inputMapper, codeMapper, props);
        tiger.load();
    }

    @AfterEach
    void close() {
        if (tiger != null) tiger.close();
    }

    @Test
    void semanticUsesLongestPrefixAndExcludesSeeds() {
        derived.update("INSERT INTO item_semantic_id VALUES (1,1,NULL,NULL),(2,2,2,2),(101,1,0,0),(102,1,0,1),(103,1,1,1),(104,9,9,9)");
        Map<Long, Double> scores = semantic.recall(new RecallContext(7, 10, "home")).stream()
                .collect(Collectors.toMap(RecallItem::itemId, RecallItem::recallScore));
        assertThat(scores).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 1.0, 102L, 2.0 / 3, 103L, 1.0 / 3));
        assertThat(semantic.recall(new RecallContext(7, 10, "home").withRecentPositiveItems(List.of()))).isEmpty();
        assertThat(semantic.recall(new RecallContext(999, 10, "home"))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0,0,0", "0,0,1", "0,1,1"})
    void tigerResolvesExactThenTwoThenOneLevelPrefix(int c0, int c1, int c2) {
        derived.update("INSERT INTO item_semantic_id VALUES (1,1,1,1),(100,?,?,?)", c0, c1, c2);
        List<RecallItem> rows = tiger.recall(new RecallContext(7, 10, "home"));
        assertThat(rows).extracting(RecallItem::itemId).containsExactly(100L);
        assertThat(rows.getFirst().recallScore()).isGreaterThan(0).isLessThanOrEqualTo(1);
    }

    @Test
    void tigerExcludesOnlyPositiveHistoryWhileLegacySemanticSeedsIncludeLowRatings() {
        derived.update("INSERT INTO item_semantic_id VALUES (1,0,0,0),(2,0,0,0),(3,0,0,0)");
        assertThat(tiger.recall(new RecallContext(7, 10, "home"))).extracting(RecallItem::itemId)
                .containsExactlyInAnyOrder(2L, 3L);
        assertThat(semantic.recall(new RecallContext(7, 10, "home"))).extracting(RecallItem::itemId).containsExactly(3L);
    }
}
