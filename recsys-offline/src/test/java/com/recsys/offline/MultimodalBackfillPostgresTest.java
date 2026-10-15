package com.recsys.offline;

import com.recsys.common.embedding.EmbeddingClient;
import org.junit.jupiter.api.*;
import com.recsys.offline.persistence.mapper.derived.ItemEmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 多模态回填保留文件优先级、NULL/缺失跳过、融合限制与部分失败语义。 */
@Testcontainers
class MultimodalBackfillPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate admin, db;
    static TrackedDriverDataSource source;
    static SqlSessionTemplate sessions;
    @TempDir Path posters;
    EmbeddingClient client;
    MultimodalBackfillJob job;

    @BeforeAll static void prepare() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        admin.execute("CREATE EXTENSION vector");
        source = new TrackedDriverDataSource(PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "ApplicationName=multimodal-backfill-writer", PG.getUsername(), PG.getPassword());
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE item_embedding(item_id BIGINT PRIMARY KEY,embedding vector(3),model TEXT)");
        db.execute("COMMENT ON TABLE item_embedding IS '多模态回填独占测试'");
        db.execute("COMMENT ON COLUMN item_embedding.item_id IS '物品ID'");
        db.execute("COMMENT ON COLUMN item_embedding.embedding IS '三维测试向量'");
        db.execute("COMMENT ON COLUMN item_embedding.model IS '模型标识'");
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(source, "classpath*:mapper/offline/derived/**/*.xml"));
    }

    @BeforeEach void reset() {
        db.execute("TRUNCATE item_embedding; INSERT INTO item_embedding VALUES(1,'[1,0,0]','old'),(2,'[0,1,0]','old'),(3,NULL,NULL),(4,'[0,0,1]','old')");
        client = mock(EmbeddingClient.class); when(client.modelName()).thenReturn("test'模型");
        job = new MultimodalBackfillJob(sessions.getMapper(ItemEmbeddingMapper.class), client);
    }

    @Test void fuseUsesFirstPosterExtensionAndLeavesMissingPosterOrNullVectorUntouched() throws Exception {
        poster("1.jpg", 1); poster("1.jpeg", 99); poster("2.png", 2); poster("3.jpg", 3);
        when(client.embedImage(new byte[]{1})).thenReturn(new float[]{0, 1, 0});
        when(client.embedImage(new byte[]{2})).thenReturn(new float[]{0, -1, 0});
        job.run(args());
        assertEquals(List.of("[0.70710677,0.70710677,0]", "[0,0,0]"), db.queryForList("SELECT embedding::text FROM item_embedding WHERE item_id IN(1,2) ORDER BY item_id", String.class));
        assertEquals(List.of("test'模型+img", "test'模型+img"), db.queryForList("SELECT model FROM item_embedding WHERE item_id IN(1,2) ORDER BY item_id", String.class));
        assertNull(db.queryForObject("SELECT embedding::text FROM item_embedding WHERE item_id=3", String.class));
        assertEquals("[0,0,1]", db.queryForObject("SELECT embedding::text FROM item_embedding WHERE item_id=4", String.class));
        verify(client, never()).embedImage(new byte[]{99}); verify(client, never()).embedImage(new byte[]{3});
        source.assertClosed(admin, "multimodal-backfill-writer");
    }

    @Test void mismatchSkipsAndLimitCountsSuccessfulFusionsOnly() throws Exception {
        poster("1.jpg", 1); poster("2.jpeg", 2); poster("4.png", 4);
        when(client.embedImage(new byte[]{1})).thenReturn(new float[]{1, 0});
        when(client.embedImage(new byte[]{2})).thenReturn(new float[]{1, 0, 0});
        job.run(args("--limit=1", "--alpha=0"));
        assertEquals("old", db.queryForObject("SELECT model FROM item_embedding WHERE item_id=1", String.class));
        assertEquals("[1,0,0]", db.queryForObject("SELECT embedding::text FROM item_embedding WHERE item_id=2", String.class));
        verify(client, never()).embedImage(new byte[]{4});
        source.assertClosed(admin, "multimodal-backfill-writer");
    }

    @Test void unsupportedClientStopsAtFirstFailureAndKeepsPriorSuccessfulUpdates() throws Exception {
        poster("1.jpg", 1); poster("2.jpg", 2); poster("4.jpg", 4);
        var before = db.queryForList("SELECT * FROM item_embedding ORDER BY item_id");
        when(client.embedImage(any())).thenThrow(new UnsupportedOperationException("test unsupported"));
        job.run(args());
        assertEquals(before, db.queryForList("SELECT * FROM item_embedding ORDER BY item_id"));
        org.mockito.Mockito.reset(client); when(client.modelName()).thenReturn("supported-first");
        when(client.embedImage(new byte[]{1})).thenReturn(new float[]{0, 1, 0});
        when(client.embedImage(new byte[]{2})).thenThrow(new UnsupportedOperationException("late unsupported"));
        job.run(args());
        assertEquals("supported-first+img", db.queryForObject("SELECT model FROM item_embedding WHERE item_id=1", String.class));
        assertEquals("old", db.queryForObject("SELECT model FROM item_embedding WHERE item_id=2", String.class));
        verify(client, never()).embedImage(new byte[]{4});
        source.assertClosed(admin, "multimodal-backfill-writer");
    }

    @Test void databaseFailurePropagatesAfterPriorAutocommitUpdate() throws Exception {
        poster("1.jpg", 1); poster("2.jpg", 2);
        when(client.embedImage(new byte[]{1})).thenReturn(new float[]{0, 1, 0});
        when(client.embedImage(new byte[]{2})).thenReturn(new float[]{Float.NaN, 1, 0});
        assertThrows(DataAccessException.class, () -> job.run(args()));
        assertEquals("test'模型+img", db.queryForObject("SELECT model FROM item_embedding WHERE item_id=1", String.class));
        assertEquals("old", db.queryForObject("SELECT model FROM item_embedding WHERE item_id=2", String.class));
        source.assertClosed(admin, "multimodal-backfill-writer");
    }

    @Test void outerRollbackRestoresOriginalVectors() throws Exception {
        poster("1.jpg", 1);
        when(client.embedImage(any())).thenReturn(new float[]{0, 1, 0});
        var before = db.queryForList("SELECT * FROM item_embedding ORDER BY item_id");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            assertEquals("public", sessions.selectOne("offline.probe.derived.schema"));
            assertDoesNotThrow(() -> job.run(args()));
            assertEquals("public", sessions.selectOne("offline.probe.derived.schema"));
            assertEquals("test'模型+img", db.queryForObject("SELECT model FROM item_embedding WHERE item_id=1", String.class));
            status.setRollbackOnly();
        });
        assertEquals(before, db.queryForList("SELECT * FROM item_embedding ORDER BY item_id"));
        source.assertClosed(admin, "multimodal-backfill-writer");
    }

    @Test void itemDeletedDuringImageCallIsNotRecreatedByUpdate() throws Exception {
        poster("1.jpg", 1);
        when(client.embedImage(any())).thenAnswer(invocation -> {
            db.update("DELETE FROM item_embedding WHERE item_id=1");
            return new float[]{0, 1, 0};
        });
        job.run(args());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM item_embedding WHERE item_id=1", Integer.class));
        source.assertClosed(admin, "multimodal-backfill-writer");
    }

    @Test void missingDirectoryAndEmptyTableDoNotCallImageProvider() throws Exception {
        job.run(new DefaultApplicationArguments("--poster-dir=" + posters.resolve("missing")));
        db.execute("TRUNCATE item_embedding");
        job.run(args());
        verify(client, never()).embedImage(any());
        source.assertClosed(admin, "multimodal-backfill-writer");
    }

    private void poster(String name, int value) throws Exception { Files.write(posters.resolve(name), new byte[]{(byte) value}); }
    private DefaultApplicationArguments args(String... extra) {
        var values = new java.util.ArrayList<String>(); values.add("--poster-dir=" + posters); values.addAll(List.of(extra));
        return new DefaultApplicationArguments(values.toArray(String[]::new));
    }
}
