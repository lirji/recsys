package com.recsys.recall.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import com.recsys.recall.persistence.mapper.derived.EmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定ONNX输出搭配真实pgvector，CI无需训练模型即可验证版本过滤、评分和合并。 */
@Testcontainers
class ModelEmbeddingQueriesPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private JdbcTemplate jdbc;
    private EmbeddingMapper embeddingMapper;
    private String version;
    private TwoTowerRecaller tower;
    private MultiInterestRecaller mind;

    @BeforeEach
    void prepare() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        try (var input = getClass().getResourceAsStream("/model/lightgcn_schema.json")) {
            version = new ObjectMapper().readTree(input).path("model_version").asText();
        }
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        for (String table : List.of("graph_user_embedding", "graph_item_embedding", "multi_interest_item_embedding", "item_tower_embedding")) {
            String key = table.equals("graph_user_embedding") ? "user_id" : "item_id";
            boolean versioned = !table.equals("item_tower_embedding");
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " (" + key + " BIGINT NOT NULL, embedding vector(64)"
                    + (versioned ? ",model_version TEXT NOT NULL, PRIMARY KEY(model_version," + key + ")" : ", PRIMARY KEY(" + key + ")") + ");"
                    + "COMMENT ON TABLE " + table + " IS '模型向量查询契约测试独占表';"
                    + "COMMENT ON COLUMN " + table + "." + key + " IS '业务标识';"
                    + "COMMENT ON COLUMN " + table + ".embedding IS '测试向量';"
                    + (versioned ? "COMMENT ON COLUMN " + table + ".model_version IS '模型版本';" : "")
                    + "TRUNCATE " + table);
        }
        embeddingMapper = new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),
                "classpath*:mapper/recall/derived/**/*.xml")).getMapper(EmbeddingMapper.class);
        RecallProperties props = new RecallProperties();
        props.getTwoTower().setModelPath("classpath:model/tower-fixture.onnx");
        props.getMultiInterest().setModelPath("classpath:model/mind-fixture.onnx");
        props.getMultiInterest().setPerInterestLimit(1);
        props.getQuota().setMultiInterest(3);
        props.getQuota().setTwoTower(2);
        tower = new TwoTowerRecaller(embeddingMapper, props);
        mind = new MultiInterestRecaller(embeddingMapper, props);
        tower.load();
        mind.load();
        assertThat(tower.isReady()).isTrue();
        assertThat(mind.isReady()).isTrue();
    }

    @AfterEach
    void close() {
        if (tower != null) tower.close();
        if (mind != null) mind.close();
    }

    @Test
    void graphFiltersBothUserAndItemModelVersions() {
        jdbc.update("INSERT INTO graph_user_embedding VALUES (7,?::vector,?),(7,?::vector,'other')", basis(0), version, basis(1));
        jdbc.update("INSERT INTO graph_item_embedding VALUES (101,?::vector,?),(102,?::vector,?),(999,?::vector,'other')",
                basis(0), version, basis(1), version, basis(0));
        GraphRecaller graph = new GraphRecaller(embeddingMapper, new RecallProperties());
        graph.load();
        assertThat(graph.isReady()).isTrue();
        assertThat(graph.recall(new RecallContext(7, 10, "home"))).extracting(RecallItem::itemId).containsExactly(101L, 102L);
        assertThat(graph.recall(new RecallContext(99, 10, "home"))).isEmpty();
    }

    @Test
    void mindQueriesEachInterestInTheSameVersionAndAppliesGlobalQuota() {
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO multi_interest_item_embedding VALUES (?,?::vector,?)", 201 + i, basis(i), version);
        }
        jdbc.update("INSERT INTO multi_interest_item_embedding VALUES (999,?::vector,'other')", basis(0));
        var result = mind.recall(new RecallContext(7, 10, "home").withBehaviorSequence(List.of(1L, 2L)));
        assertThat(result).hasSize(3).allMatch(row -> row.recallScore() == 1.0);
        assertThat(result).extracting(RecallItem::itemId).doesNotHaveDuplicates().allMatch(id -> id >= 201 && id <= 204);
        assertThat(mind.recall(new RecallContext(7, 10, "home").withBehaviorSequence(List.of()))).isEmpty();
    }

    @Test
    void towerRanksAndScoresOnlyExistingCandidatesWithNullMappedToZero() {
        jdbc.update("INSERT INTO item_tower_embedding VALUES (301,?::vector),(302,?::vector),(303,NULL)", basis(0), basis(1));
        assertThat(tower.recall(new RecallContext(7, 10, "home"))).extracting(RecallItem::itemId).containsExactly(301L, 302L);
        assertThat(tower.score(7, List.of(301L, 301L, 303L, 999L))).containsExactlyInAnyOrderEntriesOf(Map.of(301L, 1.0, 303L, 0.0));
        assertThat(tower.score(7, List.of())).isEmpty();
        assertThat(tower.score(7, null)).isEmpty();
    }

    private static String basis(int index) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < 64; i++) {
            if (i > 0) out.append(',');
            out.append(i == index ? '1' : '0');
        }
        return out.append(']').toString();
    }
}
