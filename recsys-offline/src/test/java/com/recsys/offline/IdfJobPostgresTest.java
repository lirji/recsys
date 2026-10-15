package com.recsys.offline;

import com.recsys.offline.persistence.mapper.primary.IdfCorpusMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.content.persistence.model.CatalogTable;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PG 验证 raw alias 的 IDF 来自同一 English lexeme df，而不是表面词频。 */
@Testcontainers(disabledWithoutDocker = true)
class IdfJobPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("recsys").withUsername("recsys").withPassword("recsys");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void seed() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("""
                CREATE TABLE item (
                    item_id BIGINT PRIMARY KEY, title TEXT, category TEXT,
                    title_tsv tsvector GENERATED ALWAYS AS
                        (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(category,''))) STORED)
                """);
        jdbc.execute("COMMENT ON TABLE item IS 'IDF英文词干独占契约测试'");
        for (String column : java.util.List.of("item_id", "title", "category", "title_tsv")) {
            jdbc.execute("COMMENT ON COLUMN item." + column + " IS '原目录检索字段'");
        }
        jdbc.update("INSERT INTO item(item_id,title,category) VALUES (1,'Movie studies','about')");
        jdbc.update("INSERT INTO item(item_id,title,category) VALUES (2,'Movie studying','about')");
        jdbc.update("INSERT INTO item(item_id,title,category) VALUES (3,'Movies studies','about')");
    }

    @Test
    void rawInflectionsShareCanonicalDfAndStopwordsDisappear() throws Exception {
        var mapper = new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),
                "classpath*:mapper/offline/primary/**/*.xml")).getMapper(IdfCorpusMapper.class);
        IdfJob.IdfSnapshot snapshot = new IdfJob(mapper, null).compute(CatalogTable.OWNED, 1);

        assertThat(snapshot.totalDocuments()).isEqualTo(3);
        assertThat(snapshot.lexemes()).containsEntry("movi", "1.000000")
                .containsEntry("studi", "1.000000").doesNotContainKey("about");
        assertThat(snapshot.rawTerms().get("movie")).isEqualTo(snapshot.rawTerms().get("movies"));
        assertThat(snapshot.rawTerms().get("studies")).isEqualTo(snapshot.rawTerms().get("studying"));
        assertThat(snapshot.rawTerms()).doesNotContainKey("about");
    }
}
