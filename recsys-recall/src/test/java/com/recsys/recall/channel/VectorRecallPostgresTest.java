package com.recsys.recall.channel;

import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.embedding.EmbeddingClient;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallChannel;
import com.recsys.recall.RecallProperties;
import com.recsys.recall.persistence.mapper.derived.EmbeddingMapper;
import com.recsys.recall.persistence.config.RecallDerivedPersistenceConfiguration;
import com.recsys.recall.persistence.config.RecallPersistenceConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 派生库与主库放入互斥标记，验证 pgvector 评分、NULL映射和数据源选择。 */
@Testcontainers
class VectorRecallPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private JdbcTemplate derived;
    private AnnotationConfigApplicationContext context;
    private VectorRecaller vector;
    private SemanticRecaller semantic;
    private EmbeddingClient embedding;
    private ItemCatalogReader catalog;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        JdbcTemplate primary = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        primary.execute("CREATE EXTENSION IF NOT EXISTS vector; CREATE SCHEMA IF NOT EXISTS derived");
        derived = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=derived,public",
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        for (String schema : List.of("public", "derived")) {
            primary.execute("CREATE TABLE IF NOT EXISTS " + schema + ".user_embedding (user_id BIGINT PRIMARY KEY, embedding vector(3));"
                    + "COMMENT ON TABLE " + schema + ".user_embedding IS '向量召回契约测试用户表';"
                    + "COMMENT ON COLUMN " + schema + ".user_embedding.user_id IS '用户标识';"
                    + "COMMENT ON COLUMN " + schema + ".user_embedding.embedding IS '允许缺失的用户向量';"
                    + "CREATE TABLE IF NOT EXISTS " + schema + ".item_embedding (item_id BIGINT PRIMARY KEY, embedding vector(3));"
                    + "COMMENT ON TABLE " + schema + ".item_embedding IS '向量召回契约测试物品表';"
                    + "COMMENT ON COLUMN " + schema + ".item_embedding.item_id IS '物品标识';"
                    + "COMMENT ON COLUMN " + schema + ".item_embedding.embedding IS '允许缺失的物品向量';"
                    + "TRUNCATE " + schema + ".item_embedding, " + schema + ".user_embedding");
        }
        primary.update("INSERT INTO public.item_embedding VALUES (999,'[1,0,0]')");
        derived.update("INSERT INTO user_embedding VALUES (7,'[1,0,0]'),(8,NULL)");
        derived.update("INSERT INTO item_embedding VALUES (101,'[1,0,0]'),(102,'[0,1,0]'),(103,NULL)");
        RecallProperties props = new RecallProperties();
        props.getQuota().setVector(3);
        props.getQuota().setSemantic(1);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, primary::getDataSource, definition -> definition.setPrimary(true));
        context.registerBean("derivedDbDataSource", DataSource.class, derived::getDataSource);
        context.register(RecallPersistenceConfiguration.class, RecallDerivedPersistenceConfiguration.class);
        context.refresh();
        EmbeddingMapper mapper = context.getBean(EmbeddingMapper.class);
        vector = new VectorRecaller(mapper, props);
        catalog = mock(ItemCatalogReader.class);
        embedding = mock(EmbeddingClient.class);
        ObjectProvider<EmbeddingClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embedding);
        when(embedding.embedText(anyString())).thenReturn(new float[]{1, 0, 0});
        semantic = new SemanticRecaller(mapper, catalog, provider, props);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @Test
    void sessionFactoriesKeepSourceIdentityAndMapperOwnership() {
        SqlSessionFactory primary = context.getBean("sqlSessionFactory", SqlSessionFactory.class);
        SqlSessionFactory derivedFactory = context.getBean("derivedSqlSessionFactory", SqlSessionFactory.class);
        assertThat(primary.getConfiguration().getEnvironment().getDataSource()).isSameAs(context.getBean("dataSource"));
        assertThat(derivedFactory.getConfiguration().getEnvironment().getDataSource()).isSameAs(derived.getDataSource());
        assertThat(primary.getConfiguration().hasMapper(EmbeddingMapper.class)).isFalse();
        assertThat(derivedFactory.getConfiguration().hasMapper(EmbeddingMapper.class)).isTrue();
    }

    @Test
    void vectorScoresAndMissingValuesMatchOriginalSemantics() {
        List<RecallItem> rows = vector.recall(new RecallContext(7, 10, "home"));
        assertThat(rows).extracting(RecallItem::itemId).containsExactly(101L, 102L, 103L);
        assertThat(rows).extracting(RecallItem::recallScore).containsExactly(1.0, 0.0, 0.0);
        assertThat(rows).allMatch(row -> row.channel() == RecallChannel.VECTOR);
        assertThat(vector.recall(new RecallContext(8, 10, "home"))).isEmpty();
        assertThat(vector.recall(new RecallContext(99, 10, "home"))).isEmpty();
    }

    @Test
    void semanticQueryUsesTheSameAnnWithItsOwnChannelAndLimit() {
        List<RecallItem> rows = semantic.recall(new RecallContext(7, 10, "search", List.of(), Map.of("query", "space")));
        assertThat(rows).containsExactly(new RecallItem(101, 1, RecallChannel.SEMANTIC));
        verify(embedding).embedText("space");
        verifyNoInteractions(catalog);
    }

    @Test
    void implicitQueryAndFailedEmbeddingRetainTheirFallbacks() {
        when(catalog.recentTitles(7, 10)).thenReturn(List.of("Space", "Alien"));
        assertThat(semantic.recall(new RecallContext(7, 10, "home"))).hasSize(1);
        verify(embedding).embedText("Space Alien");
        when(embedding.embedText(anyString())).thenThrow(new IllegalStateException("test unavailable"));
        assertThat(semantic.recall(new RecallContext(7, 10, "home"))).isEmpty();
    }
}
