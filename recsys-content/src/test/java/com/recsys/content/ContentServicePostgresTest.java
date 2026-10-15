package com.recsys.content;

import com.recsys.content.persistence.config.ContentPersistenceConfiguration;
import com.recsys.content.persistence.mapper.ItemMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖权威目录和本地副本的真实 SQL 行为，迁移时不把缓存、NULL 或数组映射变化带入业务。 */
@Testcontainers
class ContentServicePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ContentService content;
    private ContentService replica;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void prepare() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("item", "item_local")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " (item_id BIGINT PRIMARY KEY, title TEXT, category TEXT, "
                    + "tags TEXT[], description TEXT, popularity DOUBLE PRECISION)");
            jdbc.execute("COMMENT ON TABLE " + table + " IS '内容持久化契约测试独占表'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".item_id IS '物品标识'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".title IS '物品标题'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".category IS '物品类别'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".tags IS '物品标签数组'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".description IS '物品描述'");
            jdbc.execute("COMMENT ON COLUMN " + table + ".popularity IS '物品热度'");
            jdbc.execute("TRUNCATE " + table);
        }
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", javax.sql.DataSource.class, () -> dataSource);
        context.register(ContentPersistenceConfiguration.class);
        context.refresh();
        ItemMapper mapper = context.getBean(ItemMapper.class);
        content = new MybatisContentService(mapper);
        replica = new ReplicaMybatisContentService(mapper);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void upsertPreservesArrayElementsAndInvalidatesCachedItem() {
        Item initial = new Item(1, "初始", "Action", List.of("old"), "原描述", 3.5);
        content.save(initial);
        assertThat(content.findById(1)).isEqualTo(initial);
        Item replacement = new Item(1, "O'Reilly", null, Arrays.asList("科幻", "a,b", "{x}", null), null, 4.5);

        content.save(replacement);

        assertThat(content.findById(1)).isEqualTo(replacement);
        assertThat(content.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cardinality(tags) FROM item WHERE item_id=1", Integer.class)).isEqualTo(4);
    }

    @Test
    void nullTagsAreSavedAsEmptyArrayAndDatabaseNullsKeepLegacyDefaults() {
        content.save(new Item(1, null, null, null, null, 0));
        jdbc.update("INSERT INTO item(item_id,tags,popularity) VALUES (2,NULL,NULL)");

        assertThat(content.findById(1).tags()).isEmpty();
        assertThat(content.findById(2)).isEqualTo(new Item(2, null, null, List.of(), null, 0));
        assertThat(jdbc.queryForObject("SELECT tags = ARRAY[]::text[] FROM item WHERE item_id=1", Boolean.class)).isTrue();
    }

    @Test
    void batchReadOmitsMissingIdsAndHandlesEmptyRequests() {
        content.save(new Item(2, "two", "Drama", List.of(), "", 2));
        content.save(new Item(1, "one", "Comedy", List.of(), "", 1));

        assertThat(content.findByIds(List.of(2L, 1L, 2L, 999L))).containsOnlyKeys(1L, 2L);
        assertThat(content.findByIds(List.of())).isEmpty();
        assertThat(content.findByIds(null)).isEmpty();
        assertThat(content.findById(999)).isNull();
        assertThat(content.allItemIds()).containsExactly(1L, 2L);
    }

    @Test
    void replicaReadsAndWritesOnlyItsSelectedTable() {
        content.save(new Item(1, "owner", "Drama", List.of(), "", 1));
        replica.save(new Item(1, "replica", "Comedy", List.of(), "", 2));

        assertThat(content.findById(1).title()).isEqualTo("owner");
        assertThat(replica.findById(1).title()).isEqualTo("replica");
        assertThat(replica.allItemIds()).containsExactly(1L);
        assertThat(replica.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM item WHERE item_id=1", String.class)).isEqualTo("owner");
    }

    @Test
    void writesJoinTheOwnersTransaction() {
        TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            content.save(new Item(8, "rollback", null, List.of(), null, 0));
            throw new IllegalStateException("模拟事务后续步骤失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE item_id=8", Long.class)).isZero();
    }
}
