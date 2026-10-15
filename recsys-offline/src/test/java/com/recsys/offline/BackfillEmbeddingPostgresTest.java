package com.recsys.offline;

import com.recsys.common.embedding.EmbeddingClient;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.embedding.QuotaExhaustedException;
import org.junit.jupiter.api.*;
import com.recsys.offline.persistence.mapper.derived.ItemEmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** pgvector真实落库验证；外部向量服务只提供确定性输入，不访问共享库或模型网络。 */
@Testcontainers
class BackfillEmbeddingPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate admin, db;
    static TrackedDriverDataSource source;
    static SqlSessionTemplate sessions;
    ContentService content;
    EmbeddingClient client;
    BackfillEmbeddingJob job;

    @BeforeAll static void prepare() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        admin.execute("CREATE EXTENSION vector; CREATE SCHEMA embedding_store");
        String url = PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=embedding_store,public&ApplicationName=embedding-backfill-writer";
        source = new TrackedDriverDataSource(url, PG.getUsername(), PG.getPassword());
        db = new JdbcTemplate(source);
        for (var jdbc : List.of(admin, db)) {
            jdbc.execute("CREATE TABLE item_embedding(item_id BIGINT PRIMARY KEY,embedding vector(3),model TEXT)");
            jdbc.execute("COMMENT ON TABLE item_embedding IS '向量回填独占测试源'");
            jdbc.execute("COMMENT ON COLUMN item_embedding.item_id IS '物品标识'");
            jdbc.execute("COMMENT ON COLUMN item_embedding.embedding IS '三维测试向量，允许NULL'");
            jdbc.execute("COMMENT ON COLUMN item_embedding.model IS '原模型标识，允许NULL'");
        }
        admin.execute("INSERT INTO item_embedding VALUES(99,'[9,9,9]','primary-marker')");
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(source, "classpath*:mapper/offline/derived/**/*.xml"));
    }

    @BeforeEach void reset() {
        db.execute("TRUNCATE item_embedding");
        content = mock(ContentService.class); client = mock(EmbeddingClient.class);
        when(client.dimension()).thenReturn(3); when(client.modelName()).thenReturn("model'中文");
        job = new BackfillEmbeddingJob(content, client, sessions.getMapper(ItemEmbeddingMapper.class));
    }

    @Test void upsertPreservesVectorModelTextAndContentTextConstruction() throws Exception {
        db.execute("INSERT INTO item_embedding VALUES(1,'[9,9,9]','old')");
        when(content.allItemIds()).thenReturn(List.of(1L, 2L, 3L));
        when(content.findById(1)).thenReturn(new Item(1, "标题", "科幻", List.of("tag1", "tag2"), "描述", 1));
        when(content.findById(2)).thenReturn(new Item(2, null, null, null, null, 0));
        when(client.embedText("标题 | 科幻 | tag1, tag2 | 描述")).thenReturn(new float[]{1, -0.5f, 0.25f});
        when(client.embedText("")).thenReturn(new float[]{0, 1, 0});
        job.run(new DefaultApplicationArguments("--sleep-ms=0"));
        assertEquals(List.of("[1,-0.5,0.25]", "[0,1,0]"), db.queryForList("SELECT embedding::text FROM item_embedding ORDER BY item_id", String.class));
        assertEquals(List.of("model'中文", "model'中文"), db.queryForList("SELECT model FROM item_embedding ORDER BY item_id", String.class));
        verify(client).embedText("标题 | 科幻 | tag1, tag2 | 描述"); verify(client).embedText("");
        assertEquals("primary-marker", admin.queryForObject("SELECT model FROM item_embedding WHERE item_id=99", String.class));
        source.assertClosed(admin, "embedding-backfill-writer");
    }

    @Test void skipExistingStillSkipsNullVectorWithoutFetchingContent() throws Exception {
        db.execute("INSERT INTO item_embedding VALUES(1,NULL,NULL)");
        items(1, 2);
        when(client.embedText("item2")).thenReturn(new float[]{1, 0, 0});
        job.run(new DefaultApplicationArguments("--skip-existing", "--sleep-ms=0"));
        verify(content, never()).findById(1);
        assertNull(db.queryForObject("SELECT embedding::text FROM item_embedding WHERE item_id=1", String.class));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM item_embedding", Integer.class));
        source.assertClosed(admin, "embedding-backfill-writer");
    }

    @Test void dimensionAndDatabaseFailuresContinueButQuotaStopsRemainingItems() throws Exception {
        items(1, 2, 3, 4, 5);
        when(client.embedText("item1")).thenReturn(new float[]{1, 0, 0});
        when(client.embedText("item2")).thenReturn(new float[]{1, 0});
        when(client.embedText("item3")).thenReturn(new float[]{Float.NaN, 0, 0});
        when(client.embedText("item4")).thenThrow(new QuotaExhaustedException("test quota"));
        job.run(new DefaultApplicationArguments("--sleep-ms=0"));
        assertEquals(List.of(1L), db.queryForList("SELECT item_id FROM item_embedding", Long.class));
        verify(client).embedText("item3"); verify(client).embedText("item4");
        verify(content, never()).findById(5); verify(client, never()).embedText("item5");
        source.assertClosed(admin, "embedding-backfill-writer");
    }

    @Test void outerTransactionRollsBackUpsertsOnOriginalDerivedSource() {
        items(1, 2);
        when(client.embedText(anyString())).thenReturn(new float[]{0, 0, 1});
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            assertEquals("embedding_store", sessions.selectOne("offline.probe.derived.schema"));
            assertDoesNotThrow(() -> job.run(new DefaultApplicationArguments("--sleep-ms=0")));
            assertEquals("embedding_store", sessions.selectOne("offline.probe.derived.schema"));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM item_embedding", Integer.class));
            status.setRollbackOnly();
        });
        assertEquals(0, db.queryForObject("SELECT count(*) FROM item_embedding", Integer.class));
        source.assertClosed(admin, "embedding-backfill-writer");
    }

    private void items(long... ids) {
        when(content.allItemIds()).thenReturn(java.util.Arrays.stream(ids).boxed().toList());
        for (long id : ids) when(content.findById(id)).thenReturn(new Item(id, "item" + id, null, List.of(), null, 0));
    }
}
