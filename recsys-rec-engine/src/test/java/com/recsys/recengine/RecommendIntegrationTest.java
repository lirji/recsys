package com.recsys.recengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.dto.RecommendItem;
import com.recsys.common.dto.RecommendResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核心在线链路集成测试(E2)—— 用真实 pgvector + redis 容器,端到端验证
 * {@code /api/recommend} 的 召回→粗排→精排→重排 漏斗跑通,且降级路径(HOT 兜底、已看过滤)有效。
 *
 * <p>是"部署即崩溃"防线的第二道(第一道是 E1 编码器契约测试):契约测试保证特征逐位一致,
 * 本测试保证整条编排在真实存储上不抛异常、返回合法有序结果。
 *
 * <p>关键设计:
 * <ul>
 *   <li>schema 用仓库真实 {@code recsys-offline/sql/01_schema.sql} 在容器 init 阶段(psql,正确处理
 *       {@code CREATE EXTENSION vector} 与 dollar-quoted 块)应用 —— 不复制 schema,避免漂移;</li>
 *   <li>只种 item(带 popularity),学习型召回路(缺 job 产出表)各自 safeRecall 降级为空,
 *       HOT 直接查库 popularity 兜底 —— 正是"永不空手"契约;</li>
 *   <li>rank 用 v1 规则,不依赖 ONNX;广告 ShardingSphere 数据源是懒连接且 /api/recommend 不碰广告表,
 *       故不需要 ds_0/ds_1。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
// disabledWithoutDocker:无可用 Docker 的机器(或 CI 未装 Docker)自动跳过而非让 `mvn install` 失败;
// CI(标准 Docker)与本地(装了 Docker)照常运行。
@Testcontainers(disabledWithoutDocker = true)
class RecommendIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("recsys").withUsername("recsys").withPassword("recsys")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(
                            Paths.get("..", "recsys-offline", "sql", "01_schema.sql")
                                    .toAbsolutePath().normalize().toString()),
                    "/docker-entrypoint-initdb.d/01_schema.sql");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("recsys.rank.strategy", () -> "v1");     // 规则排序,不依赖 ONNX
        r.add("recsys.query.llm.enabled", () -> "false"); // 关掉 LLM query 理解
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;

    private static final int SEEDED = 12;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE item, item_embedding, app_user, user_behavior, user_embedding "
                + "RESTART IDENTITY CASCADE");
        String[] cats = {"Action", "Drama", "Comedy", "SciFi"};
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= SEEDED; i++) {
            rows.add(new Object[]{i, "Movie " + i, cats[i % cats.length], (double) (SEEDED - i + 1)});
        }
        jdbc.batchUpdate("INSERT INTO item(item_id,title,category,popularity) VALUES (?,?,?,?)", rows);

        // 用户 1:有画像 + 点击过 item 1、2(CLICK 属"已看",应被过滤出结果)
        jdbc.update("INSERT INTO app_user(user_id,profile) VALUES (1, ?::jsonb)",
                "{\"categories\":[\"Action\"]}");
        jdbc.update("INSERT INTO user_behavior(user_id,item_id,action,value) VALUES (1,1,'CLICK',1)");
        jdbc.update("INSERT INTO user_behavior(user_id,item_id,action,value) VALUES (1,2,'CLICK',1)");
    }

    @Test
    void recommend_returnsNonEmptyDedupedResults_andFiltersSeen() throws Exception {
        RecommendResponse resp = recommend(1L, 8);

        assertThat(resp.items()).as("核心漏斗应产出非空结果").isNotEmpty();
        assertThat(resp.items().size()).isLessThanOrEqualTo(8);

        List<Long> ids = resp.items().stream().map(RecommendItem::itemId).toList();
        assertThat(ids).as("合并去重后无重复 itemId").doesNotHaveDuplicates();
        assertThat(ids).as("结果都来自已灌入的 item").allMatch(id -> id >= 1 && id <= SEEDED);
        assertThat(ids).as("已看(CLICK)物品被过滤").doesNotContain(1L, 2L);
        assertThat(resp.items()).allMatch(it -> Double.isFinite(it.score()));
        assertThat(resp.traceId()).as("链路应带 traceId 供样本回流").isNotBlank();
    }

    @Test
    void brandNewUser_stillNonEmpty_viaHotFallback() throws Exception {
        // 全新用户 + 空 redis:所有学习型召回路为空,仅 HOT 查库 popularity 兜底 → 仍非空(永不空手)
        RecommendResponse resp = recommend(999_999L, 5);
        assertThat(resp.items()).as("HOT 兜底保证冷用户也非空").isNotEmpty();
        assertThat(resp.items().size()).isLessThanOrEqualTo(5);
    }

    private RecommendResponse recommend(long userId, int size) throws Exception {
        String json = mockMvc.perform(get("/api/recommend")
                        .param("userId", String.valueOf(userId))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, RecommendResponse.class);
    }
}
