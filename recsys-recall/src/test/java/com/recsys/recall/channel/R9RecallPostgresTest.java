package com.recsys.recall.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.BehaviorSequenceSource;
import com.recsys.recall.RecallProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R9 真链路：实际 ONNX/schema 与 pgvector PostgreSQL，不以 mock JDBC 代替 ANN。 */
@Testcontainers
class R9RecallPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recsys")
            .withUsername("recsys")
            .withPassword("recsys");

    static JdbcTemplate jdbc;
    static String version;

    @BeforeAll
    static void prepare() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode graphSchema = mapper.readTree(R9RecallPostgresTest.class.getClassLoader()
                .getResourceAsStream("model/lightgcn_schema.json"));
        JsonNode mindSchema = mapper.readTree(R9RecallPostgresTest.class.getClassLoader()
                .getResourceAsStream("model/mind_schema.json"));
        version = graphSchema.path("model_version").asText();
        assertFalse(version.isBlank());
        assertEquals(version, mindSchema.path("model_version").asText(),
                "MIND/LightGCN 必须用共同发布版本");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE graph_user_embedding(" +
                "model_version text NOT NULL,user_id bigint NOT NULL,embedding vector(64) NOT NULL," +
                "PRIMARY KEY(model_version,user_id))");
        jdbc.execute("CREATE TABLE graph_item_embedding(" +
                "model_version text NOT NULL,item_id bigint NOT NULL,embedding vector(64) NOT NULL," +
                "PRIMARY KEY(model_version,item_id))");
        jdbc.execute("CREATE TABLE multi_interest_item_embedding(" +
                "model_version text NOT NULL,item_id bigint NOT NULL,embedding vector(64) NOT NULL," +
                "PRIMARY KEY(model_version,item_id))");
        jdbc.execute("CREATE TABLE user_behavior(" +
                "user_id bigint NOT NULL,item_id bigint NOT NULL,action text NOT NULL," +
                "value double precision,ts timestamp NOT NULL)");
    }

    @Test
    void lightGcnUserVectorRunsVersionMatchedAnn() {
        jdbc.update("INSERT INTO graph_user_embedding VALUES(?,?,?::vector)", version, 7L, basis(0));
        jdbc.update("INSERT INTO graph_item_embedding VALUES(?,?,?::vector)", version, 101L, basis(0));
        jdbc.update("INSERT INTO graph_item_embedding VALUES(?,?,?::vector)", version, 102L, basis(1));

        RecallProperties props = new RecallProperties();
        GraphRecaller recaller = new GraphRecaller(jdbc, props);
        recaller.load();
        assertTrue(recaller.isReady());

        List<RecallItem> items = recaller.recall(new RecallContext(
                7L, 10, "home", List.of(RecallChannel.GRAPH), Map.of()));

        assertEquals(2, items.size());
        assertEquals(101L, items.getFirst().itemId());
        assertEquals(RecallChannel.GRAPH, items.getFirst().channel());
        assertTrue(items.getFirst().recallScore() > items.get(1).recallScore());
    }

    @Test
    void mindOnnxInterestsRunFourAnnQueriesAndMergeItems() {
        for (int i = 0; i < 8; i++) {
            jdbc.update("INSERT INTO multi_interest_item_embedding VALUES(?,?,?::vector)",
                    version, 200L + i, basis(i));
        }
        jdbc.update("INSERT INTO user_behavior VALUES(9,1,'CLICK',1,'2026-08-01 00:00:00')");
        jdbc.update("INSERT INTO user_behavior VALUES(9,2,'RATING',5,'2026-08-02 00:00:00')");
        jdbc.update("INSERT INTO user_behavior VALUES(9,3,'RATING',2,'2026-08-03 00:00:00')");
        jdbc.update("INSERT INTO user_behavior VALUES(9,1,'LIKE',1,'2026-08-04 00:00:00')");
        jdbc.update("INSERT INTO user_behavior VALUES(9,3,'PLAY',1,'2026-08-05 00:00:00')");
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(null);
        List<Long> sequence = new BehaviorSequenceSource(jdbc, redis).sequence(9L, 50);
        assertEquals(List.of(2L, 1L, 3L), sequence,
                "DB 序列应过滤弱反馈、按 item 最后正反馈去重并返回 oldest→newest");
        RecallProperties props = new RecallProperties();
        props.getMultiInterest().setPerInterestLimit(3);
        MultiInterestRecaller recaller = new MultiInterestRecaller(jdbc, props);
        recaller.load();
        Assumptions.assumeTrue(recaller.isReady(),
                "需先运行 train_mind.py 生成被 gitignore 的 mind_user.onnx");

        List<RecallItem> items = recaller.recall(new RecallContext(
                9L, 10, "home", List.of(RecallChannel.MULTI_INTEREST), Map.of())
                .withBehaviorSequence(sequence));

        assertFalse(items.isEmpty());
        assertTrue(items.size() <= 8, "四路 ANN 合并后必须按 item 去重");
        assertTrue(items.stream().allMatch(i -> i.channel() == RecallChannel.MULTI_INTEREST));
        assertEquals(items.size(), items.stream().map(RecallItem::itemId).distinct().count());
    }

    private static String basis(int index) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < 64; i++) {
            if (i > 0) out.append(',');
            out.append(i == index ? "1" : "0");
        }
        return out.append(']').toString();
    }
}
