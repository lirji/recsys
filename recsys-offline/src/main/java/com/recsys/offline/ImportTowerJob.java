package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.TowerEmbeddingStore;
import com.recsys.offline.persistence.mapper.derived.TowerEmbeddingMapper;
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

    private final TowerEmbeddingStore persistence;

    /** 导入解析与派生库写入分开，存储层保持原500行批次及资源归属。 */
    public ImportTowerJob(TowerEmbeddingStore persistence) {
        this.persistence = persistence;
    }

    /** 保持训练产物导入命令入口。 */
    @Override
    public String name() {
        return "import-tower";
    }

    /** 保留原逐项/批次写入，输入错误与部分完成必须让调度方可见。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        String file = JobArguments.text(args, "file", DEFAULT_FILE);
        int dim = JobArguments.integer(args, "dim", 64);
        if (dim <= 0) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        Path path = Path.of(file);
        JobArguments.readableInput(path);

        persistence.rebuild();

        List<TowerEmbeddingMapper.Row> batch = new ArrayList<>(BATCH);
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
                batch.add(new TowerEmbeddingMapper.Row(itemId, new PGvector(vec)));
                if (batch.size() >= BATCH) {
                    total += persistence.writeBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            total += persistence.writeBatch(batch);
        }
        if (bad > 0) throw new PublicErrorException(ErrorCode.JOB_IMPORT_PARTIAL_FAILED);
        Long cnt = persistence.count();
        log.info("import-tower 完成:写入 {} 条(跳过脏行 {}),item_tower_embedding 总数 {}", total, bad, cnt);
    }

}
