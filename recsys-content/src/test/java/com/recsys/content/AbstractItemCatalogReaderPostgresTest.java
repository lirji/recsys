package com.recsys.content;

import com.recsys.common.content.ItemCatalogReader.ScoredId;
import com.recsys.common.query.TermWeight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PostgreSQL 16 锁死 R8 FTS 排序、stopword 与 English stemming 契约。 */
@Testcontainers(disabledWithoutDocker = true)
class AbstractItemCatalogReaderPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("recsys").withUsername("recsys").withPassword("recsys");

    static JdbcTemplate jdbc;
    static DbItemCatalogReader reader;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        reader = new DbItemCatalogReader(jdbc);
        jdbc.execute("""
                CREATE TABLE item (
                    item_id BIGINT PRIMARY KEY,
                    title TEXT,
                    category TEXT,
                    title_tsv tsvector GENERATED ALWAYS AS
                        (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED
                )
                """);
        jdbc.execute("CREATE INDEX idx_item_title_tsv ON item USING gin(title_tsv)");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE item");
    }

    @Test
    void highIdfTermChangesOrderAndNeutralWeightsPreserveLegacyScore() {
        insert(1, "space");
        insert(2, "alien");

        List<ScoredId> legacy = reader.lexicalSearch("space alien", 10);
        List<ScoredId> neutral = reader.lexicalSearch("space alien",
                List.of(new TermWeight("space", 1.0), new TermWeight("alien", 1.0)), 10);
        List<ScoredId> weighted = reader.lexicalSearch("space alien",
                List.of(new TermWeight("space", 1.0), new TermWeight("alien", 8.0)), 10);

        assertThat(legacy).containsExactlyElementsOf(neutral);
        assertThat(legacy).extracting(ScoredId::itemId).containsExactly(1L, 2L);
        assertThat(weighted).extracting(ScoredId::itemId).containsExactly(2L, 1L);
        assertThat(weighted.getFirst().score()).isGreaterThan(weighted.getLast().score());
    }

    @Test
    void englishStemmingAndStopwordUseTheSamePostgresSemantics() {
        insert(2, "alien");

        List<ScoredId> stemmed = reader.lexicalSearch("aliens",
                List.of(new TermWeight("aliens", 5.0)), 10);
        List<ScoredId> withoutStopword = reader.lexicalSearch("aliens",
                List.of(new TermWeight("aliens", 5.0)), 10);
        List<ScoredId> withStopword = reader.lexicalSearch("aliens about",
                List.of(new TermWeight("aliens", 5.0), new TermWeight("about", 100.0)), 10);

        assertThat(stemmed).extracting(ScoredId::itemId).containsExactly(2L);
        assertThat(withStopword).containsExactlyElementsOf(withoutStopword);
    }

    @Test
    void invalidWeightsFallBackAndTiesAreStable() {
        insert(2, "space");
        insert(1, "space");

        List<ScoredId> legacy = reader.lexicalSearch("space", 10);
        List<ScoredId> invalid = reader.lexicalSearch("space",
                List.of(new TermWeight("space", Double.NaN), new TermWeight("space", 0.0)), 10);

        assertThat(invalid).containsExactlyElementsOf(legacy);
        assertThat(legacy).extracting(ScoredId::itemId).containsExactly(1L, 2L);
    }

    private static void insert(long id, String title) {
        jdbc.update("INSERT INTO item(item_id,title,category) VALUES(?,?,'Sci-Fi')", id, title);
    }
}
