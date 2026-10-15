package com.recsys.content;

import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.content.ItemCatalogReader.CatId;
import com.recsys.common.content.ItemCatalogReader.CatCount;
import com.recsys.common.query.TermWeight;
import com.recsys.content.persistence.mapper.ItemCatalogMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 两个来源使用互斥标识，防止 Mapper 路由错误仍因相同测试数据而通过。 */
@Testcontainers
class ItemCatalogQueriesPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ItemCatalogMapper mapper;

    @BeforeEach
    void prepare() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        for (String table : List.of("item", "item_local")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " (item_id BIGINT PRIMARY KEY, title TEXT, category TEXT, "
                    + "popularity DOUBLE PRECISION, title_tsv TSVECTOR GENERATED ALWAYS AS "
                    + "(to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED)");
            jdbc.execute("COMMENT ON TABLE " + table + " IS '目录检索契约测试独占表'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".item_id IS '物品标识'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".title IS '物品标题'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".category IS '物品类别'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".popularity IS '物品热度，可为空'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".title_tsv IS '英文全文检索向量'");
            jdbc.execute("TRUNCATE " + table);
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_behavior (user_id BIGINT, item_id BIGINT, action TEXT, value DOUBLE PRECISION, ts TIMESTAMPTZ);
                COMMENT ON TABLE user_behavior IS '目录历史查询测试独占事实';
                COMMENT ON COLUMN user_behavior.user_id IS '用户标识';
                COMMENT ON COLUMN user_behavior.item_id IS '物品标识';
                COMMENT ON COLUMN user_behavior.action IS '行为类型';
                COMMENT ON COLUMN user_behavior.value IS '行为分值';
                COMMENT ON COLUMN user_behavior.ts IS '行为发生时间';
                TRUNCATE user_behavior;
                """);
        mapper = new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),
                "classpath*:mapper/content/primary/**/*.xml")).getMapper(ItemCatalogMapper.class);
        seed("item", 0);
        seed("item_local", 100);
    }

    private void seed(String table, long offset) {
        jdbc.update("INSERT INTO " + table + "(item_id,title,category,popularity) VALUES "
                        + "(?,'Space','SciFi',10),(?,'Alien','SciFi',20),(?,'Drama','Drama',5),(?,NULL,NULL,1),(?,'Unknown','SciFi',NULL)",
                offset + 1, offset + 2, offset + 3, offset + 4, offset + 5);
        jdbc.update("INSERT INTO user_behavior VALUES (?,?,'CLICK',1,'2026-01-03'),(?,?,'RATING',5,'2026-01-02'),"
                        + "(?,?,'RATING',3,'2026-01-04'),(?,?,'CLICK',1,'2026-01-05')",
                offset + 7, offset + 1, offset + 7, offset + 2, offset + 7, offset + 3, offset + 7, offset + 4);
    }

    private ItemCatalogReader reader(boolean replica) {
        return replica ? new ReplicaItemCatalogReader(mapper) : new DbItemCatalogReader(mapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void categoryHotAndWindowQueriesPreserveNullOrderingAndNumericDefaults(boolean replica) {
        ItemCatalogReader reader = reader(replica);
        long offset = replica ? 100 : 0;

        assertThat(reader.byCategories(List.of("SciFi"), 2)).extracting(ItemCatalogReader.CatItem::itemId)
                .containsExactly(offset + 5, offset + 2);
        assertThat(reader.byCategories(List.of("SciFi"), 2).getFirst().popularity()).isZero();
        assertThat(reader.coldStartByCategory(1, 10)).extracting(ItemCatalogReader.ColdItem::itemId)
                .containsExactly(offset + 5, offset + 3);
        assertThat(reader.coldStartByCategory(1, 10)).allMatch(row -> row.rank() == 1);
        assertThat(reader.hotByPopularity(2)).extracting(ItemCatalogReader.ScoredId::itemId)
                .containsExactly(offset + 5, offset + 2);
        assertThat(reader.hotByPopularity(2).getFirst().score()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recentQueriesKeepOriginalActionsRatingThresholdAndOrder(boolean replica) {
        ItemCatalogReader reader = reader(replica);
        long offset = replica ? 100 : 0;

        assertThat(reader.recentTitles(offset + 7, 10)).containsExactly("Drama", "Space", "Alien");
        assertThat(reader.recentTitles(offset + 7, 2)).containsExactly("Drama", "Space");
        assertThat(reader.recentRatedCategories(offset + 7, 10)).containsExactly(new CatId(offset + 2, "SciFi"));
        assertThat(reader.recentTitles(999, 10)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void patternsAreBoundAsArrayAndDistinctCategoriesExcludeNull(boolean replica) {
        ItemCatalogReader reader = reader(replica);

        assertThat(reader.categoryCountsByTitleLike(List.of("%space%", "%alien%"), 10))
                .containsExactly(new CatCount("SciFi", 2));
        assertThat(reader.categoryCountsByTitleLike(List.of("%' OR true --"), 10)).isEmpty();
        assertThat(reader.categoryCountsByTitleLike(List.of(), 10)).isEmpty();
        assertThat(reader.distinctCategories()).containsExactlyInAnyOrder("SciFi", "Drama");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void weightedAndUnweightedFullTextQueriesUseTheSelectedTable(boolean replica) {
        ItemCatalogReader reader = reader(replica);
        long offset = replica ? 100 : 0;

        assertThat(reader.lexicalSearch("aliens", 10)).extracting(ItemCatalogReader.ScoredId::itemId)
                .containsExactly(offset + 2);
        assertThat(reader.lexicalSearch("space alien", List.of(new TermWeight("alien", 8)), 10))
                .extracting(ItemCatalogReader.ScoredId::itemId).containsExactly(offset + 2, offset + 1);
        assertThat(reader.lexicalSearch("about", 10)).isEmpty();
        assertThat(reader.hotByPopularity(0)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyCategoryInputKeepsTheExistingFailureBoundary(boolean replica) {
        assertThatThrownBy(() -> reader(replica).byCategories(List.of(), 10)).isInstanceOf(DataAccessException.class);
    }
}
