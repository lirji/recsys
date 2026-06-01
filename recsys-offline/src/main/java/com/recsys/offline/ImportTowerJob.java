package com.recsys.offline;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 作业 import-tower:把 {@code train_two_tower.py} 离线产出的 item 塔向量 CSV 灌入
 * {@code item_tower_embedding}(供在线 {@link com.recsys.recall} 的双塔召回做 pgvector ANN)。
 *
 * <p>CSV 格式:每行 {@code item_id,v0,v1,...,v63}(无表头),维度 64,与 schema 的 vector(64) 一致。
 *
 * <p>建表:自带 {@code CREATE TABLE IF NOT EXISTS}(已存在 pgdata 卷不会自动跑 02_two_tower.sql),
 * 然后 TRUNCATE 整表重建 + 批量 upsert。
 *
 * <p>参数:--file(默认 {@code train/item_tower.csv})、--dim(默认 64,校验用)。
 */
@Component
public class ImportTowerJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ImportTowerJob.class);
    private static final String DEFAULT_FILE = "train/item_tower.csv";
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public ImportTowerJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "import-tower";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String file = stringArg(args, "file", DEFAULT_FILE);
        int dim = intArg(args, "dim", 64);
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            log.warn("item 塔向量文件不存在: {};先跑 train_two_tower.py", path.toAbsolutePath());
            return;
        }

        ensureTable();
        jdbc.update("TRUNCATE item_tower_embedding");

        List<long[]> ids = new ArrayList<>();   // 仅用于日志计数
        List<Object[]> batch = new ArrayList<>(BATCH);
        int total = 0, bad = 0;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length != dim + 1) {
                    bad++;
                    continue;   // 维度不符(可能含表头/脏行),跳过
                }
                long itemId;
                float[] vec = new float[dim];
                try {
                    itemId = Long.parseLong(parts[0].trim());
                    for (int i = 0; i < dim; i++) {
                        vec[i] = Float.parseFloat(parts[i + 1].trim());
                    }
                } catch (NumberFormatException e) {
                    bad++;
                    continue;
                }
                batch.add(new Object[]{itemId, new PGvector(vec)});
                if (batch.size() >= BATCH) {
                    total += flush(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            total += flush(batch);
        }
        Long cnt = jdbc.queryForObject("SELECT count(*) FROM item_tower_embedding", Long.class);
        log.info("import-tower 完成:写入 {} 条(跳过脏行 {}),item_tower_embedding 总数 {}", total, bad, cnt);
    }

    private int flush(List<Object[]> batch) {
        // upsert:item 表外键约束要求 item 已存在(import-items 先跑)
        int[] r = jdbc.batchUpdate(
                "INSERT INTO item_tower_embedding(item_id,embedding) VALUES(?,?) " +
                "ON CONFLICT(item_id) DO UPDATE SET embedding=EXCLUDED.embedding",
                batch);
        return r.length;
    }

    private void ensureTable() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE IF NOT EXISTS item_tower_embedding (" +
                "item_id BIGINT PRIMARY KEY REFERENCES item(item_id), embedding vector(64))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_item_tower_hnsw " +
                "ON item_tower_embedding USING hnsw (embedding vector_cosine_ops)");
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0).trim()) : def;
    }
}
