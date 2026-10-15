package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.SemanticIdStore;
import com.recsys.offline.persistence.mapper.derived.SemanticIdMapper;
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

    private final SemanticIdStore persistence;

    /** 解析逻辑保留在作业，500行写入和bootstrap交给同源持久层。 */
    public ImportSemanticIdJob(SemanticIdStore persistence) {
        this.persistence = persistence;
    }

    /** 保留既有训练产物导入入口。 */
    @Override
    public String name() {
        return "import-semantic-id";
    }

    /** 保留缺文件返回、清表、宽松CSV解析与重复编码后写覆盖。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        String file = stringArg(args, "file", DEFAULT_FILE);
        String model = stringArg(args, "model", "rqvae");
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            log.warn("语义 ID 文件不存在: {};先跑 train_rqvae.py", path.toAbsolutePath());
            return;
        }

        persistence.rebuild();

        List<SemanticIdMapper.Row> batch = new ArrayList<>(BATCH);
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
                    batch.add(new SemanticIdMapper.Row(itemId, c0, c1, c2, model));
                } catch (NumberFormatException e) {
                    bad++;   // 表头/脏行,跳过
                    continue;
                }
                if (batch.size() >= BATCH) {
                    total += persistence.writeBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            total += persistence.writeBatch(batch);
        }
        Long cnt = persistence.count();
        log.info("import-semantic-id 完成:写入 {} 条(跳过脏行 {}),item_semantic_id 总数 {}", total, bad, cnt);
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }
}
