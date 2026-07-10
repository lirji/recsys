package com.recsys.offline;

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
 * 作业 import-semantic-id:把 {@code train_rqvae.py} 离线产出的 RQ-VAE 语义 ID CSV 灌入
 * {@code item_semantic_id}(供在线 {@link com.recsys.recall} 的生成式召回做前缀检索,docs/04 §14)。
 *
 * <p>CSV 格式:每行 {@code item_id,c0,c1,c2}(可含表头,自动跳过非数字行),3 层 codeword。
 *
 * <p>建表:自带 {@code CREATE TABLE IF NOT EXISTS}(已存在 pgdata 卷不会自动跑 03_semantic_id.sql),
 * 然后 TRUNCATE 整表重建 + 批量 upsert。
 *
 * <p>参数:--file(默认 {@code train/item_semantic_id.csv})、--model(默认 {@code rqvae},写入 model 列)。
 */
@Component
public class ImportSemanticIdJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ImportSemanticIdJob.class);
    private static final String DEFAULT_FILE = "train/item_semantic_id.csv";
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public ImportSemanticIdJob(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "import-semantic-id";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String file = stringArg(args, "file", DEFAULT_FILE);
        String model = stringArg(args, "model", "rqvae");
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            log.warn("语义 ID 文件不存在: {};先跑 train_rqvae.py", path.toAbsolutePath());
            return;
        }

        ensureTable();
        jdbc.update("TRUNCATE item_semantic_id");

        List<Object[]> batch = new ArrayList<>(BATCH);
        int total = 0, bad = 0;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split(",");
                if (p.length < 4) {
                    bad++;
                    continue;
                }
                try {
                    long itemId = Long.parseLong(p[0].trim());
                    int c0 = Integer.parseInt(p[1].trim());
                    int c1 = Integer.parseInt(p[2].trim());
                    int c2 = Integer.parseInt(p[3].trim());
                    batch.add(new Object[]{itemId, c0, c1, c2, model});
                } catch (NumberFormatException e) {
                    bad++;   // 表头/脏行,跳过
                    continue;
                }
                if (batch.size() >= BATCH) {
                    total += flush(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            total += flush(batch);
        }
        Long cnt = jdbc.queryForObject("SELECT count(*) FROM item_semantic_id", Long.class);
        log.info("import-semantic-id 完成:写入 {} 条(跳过脏行 {}),item_semantic_id 总数 {}", total, bad, cnt);
    }

    private int flush(List<Object[]> batch) {
        // upsert:item 外键要求 item 已存在(import-items 先跑)
        int[] r = jdbc.batchUpdate(
                "INSERT INTO item_semantic_id(item_id,c0,c1,c2,model) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(item_id) DO UPDATE SET c0=EXCLUDED.c0, c1=EXCLUDED.c1, " +
                "c2=EXCLUDED.c2, model=EXCLUDED.model",
                batch);
        return r.length;
    }

    private void ensureTable() {
        // #3:去 REFERENCES item 外键(派生库拆分后跨库不成立)
        jdbc.execute("CREATE TABLE IF NOT EXISTS item_semantic_id (" +
                "item_id BIGINT PRIMARY KEY, " +
                "c0 INT NOT NULL, c1 INT NOT NULL, c2 INT NOT NULL, model TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_semid_prefix ON item_semantic_id (c0, c1, c2)");
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }
}
