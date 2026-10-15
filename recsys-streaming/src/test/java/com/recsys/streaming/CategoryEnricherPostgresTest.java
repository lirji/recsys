package com.recsys.streaming;

import org.apache.flink.configuration.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 旧JDBC与Mapper共用的类目快照保护：真实PG、算子序列化与失败后的连接释放。 */
@Testcontainers
class CategoryEnricherPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String READER = "category-enricher-test";

    @BeforeEach void prepare() throws Exception {
        sql("""
                CREATE TABLE IF NOT EXISTS item (item_id BIGINT, category TEXT);
                COMMENT ON TABLE item IS '独占测试目录快照，允许历史空ID和重复行';
                COMMENT ON COLUMN item.item_id IS '目录身份，测试覆盖旧getLong空值兼容';
                COMMENT ON COLUMN item.category IS '允许空值和空字符串的类目';
                TRUNCATE item;
                INSERT INTO item VALUES (1,'first'),(2,NULL),(3,''),(NULL,'null-id'),
                    (9007199254740993,'precise'),(9223372036854775807,'maximum'),(1,'last');
                """);
    }

    @Test void snapshotPreservesNullDuplicateAndFullWidthIdentities() throws Exception {
        Map<Long, String> expected = new HashMap<>();
        try (var c = PG.createConnection(""); var s = c.createStatement();
             var r = s.executeQuery("SELECT item_id, category FROM item")) {
            while (r.next()) expected.put(r.getLong(1), r.getString(2));
        }
        var enricher = enricher();
        enricher.open(new Configuration());
        assertEquals(expected, snapshot(enricher));
        assertEquals("null-id", enricher.map(event(0, "CLICK", 0)).category);
        assertEquals("precise", enricher.map(event(9007199254740993L, "LIKE", 0)).category);
        assertEquals("maximum", enricher.map(event(Long.MAX_VALUE, "PLAY", 0)).category);
        assertEquals("", enricher.map(event(3, "CLICK", 0)).category);
        assertNull(enricher.map(event(99, "CLICK", 0)).category);
        assertReleased();
    }

    @Test void reopenReplacesSnapshotAndEmptyTableDoesNotRetainOldCategories() throws Exception {
        var enricher = enricher();
        enricher.open(new Configuration());
        sql("TRUNCATE item; INSERT INTO item VALUES (1,'replacement');");
        enricher.open(new Configuration());
        assertEquals(Map.of(1L, "replacement"), snapshot(enricher));
        sql("TRUNCATE item");
        enricher.open(new Configuration());
        assertTrue(snapshot(enricher).isEmpty());
        assertReleased();
    }

    @Test void missingTableIsCaughtAndFailedReopenClearsOldSnapshot() throws Exception {
        var enricher = enricher();
        enricher.open(new Configuration());
        sql("DROP TABLE item");
        assertDoesNotThrow(() -> enricher.open(new Configuration()));
        assertTrue(snapshot(enricher).isEmpty());
        assertNull(enricher.map(event(1, "CLICK", 0)).category);
        assertReleased();
    }

    @Test void operatorSerializationCarriesConfigurationButNeverConnectionsOrSnapshot() throws Exception {
        var initial = copy(enricher());
        assertNull(initial.map(event(1, "CLICK", 0)).category);
        initial.open(new Configuration());
        var restored = copy(initial);
        assertNull(restored.map(event(1, "CLICK", 0)).category);
        restored.open(new Configuration());
        assertEquals(snapshot(initial), snapshot(restored));
        assertReleased();
    }

    @Test void jsonFallbacksAndFeedbackWeightsRemainUnchanged() {
        var enricher = enricher();
        assertNull(enricher.map("invalid-json"));
        // Jackson空输入返回MissingNode：旧路径生成空事件，再由正反馈filter丢弃。
        assertFalse(enricher.map("").isPositive());
        assertEquals(0, enricher.map("").itemId);
        assertFalse(enricher.map("{}").isPositive());
        assertFalse(enricher.map(event(1, "IMPRESSION", 0)).isPositive());
        for (String action : new String[]{"CLICK", "PLAY", "LIKE", "RATING"}) {
            assertTrue(enricher.map(event(1, action, 0)).isPositive());
        }
        assertEquals(2.0, enricher.map(event(1, "LIKE", 0)).weight());
        assertEquals(1.0, enricher.map(event(1, "RATING", -1)).weight());
        assertEquals(4.5, enricher.map(event(1, "RATING", 4.5)).weight());
        assertEquals(1.0, enricher.map(event(1, "PLAY", 20)).weight());
        assertEquals(17, enricher.map(event(1, "CLICK", 0)).userId);
    }

    @Test void resultHandlerFailureKeepsEarlierRowsAndClosesSession() throws Exception {
        var partial = new HashMap<Long, String>() {
            private int seen;
            @Override public String put(Long key, String value) {
                if (++seen == 3) throw new IllegalStateException("injected-category-consumer-failure");
                return super.put(key, value);
            }
        };
        var error = assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,
                () -> com.recsys.streaming.persistence.ItemCategoryLoader.loadInto(
                        PG.getJdbcUrl() + "&ApplicationName=" + READER, PG.getUsername(), PG.getPassword(), partial));
        assertTrue(error.getMessage().contains("injected-category-consumer-failure"));
        assertEquals(2, partial.size());
        assertEquals("first", partial.get(1L));
        assertTrue(partial.containsKey(2L));
        assertNull(partial.get(2L));
        assertReleased();
    }

    private static RealtimeFeatureJob.CategoryEnricher enricher() {
        return new RealtimeFeatureJob.CategoryEnricher(
                PG.getJdbcUrl() + "&ApplicationName=" + READER, PG.getUsername(), PG.getPassword());
    }

    private static String event(long item, String action, double value) {
        return "{\"userId\":17,\"itemId\":" + item + ",\"action\":\"" + action + "\",\"value\":" + value + "}";
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, String> snapshot(RealtimeFeatureJob.CategoryEnricher enricher) throws Exception {
        var field = RealtimeFeatureJob.CategoryEnricher.class.getDeclaredField("catMap");
        field.setAccessible(true);
        return new HashMap<>((Map<Long, String>) field.get(enricher));
    }

    private static RealtimeFeatureJob.CategoryEnricher copy(RealtimeFeatureJob.CategoryEnricher source) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) { out.writeObject(source); }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (RealtimeFeatureJob.CategoryEnricher) in.readObject();
        }
    }

    private static void sql(String sql) throws Exception {
        try (var c = PG.createConnection(""); var s = c.createStatement()) { s.execute(sql); }
    }

    /** PG后端异步退出只允许有界观察，不能重试业务加载来掩盖泄漏。 */
    private static void assertReleased() throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var s = c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name=?")) {
            s.setString(1, READER);
            long active;
            do {
                try (var r = s.executeQuery()) { r.next(); active = r.getLong(1); }
                if (active == 0) return;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertEquals(0, active, "类目读取连接应在open返回前关闭");
        }
    }
}
