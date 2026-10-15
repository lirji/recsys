package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.BehaviorImportMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 作业 import-behavior:把 MovieLens ratings.csv 灌入 user_behavior,作为反馈闭环的
 * 冷启动数据来源(线上真实上报后续也进同一张表)。
 *
 * <p>映射:ratings.csv(userId,movieId,rating,timestamp 秒)→
 * user_behavior(action=RATING, value=rating, ts=timestamp*1000, scene=ml-import)。
 * 评分本身即正反馈强度,ItemCF / 热度 / user_embedding 三个下游作业据此构建。
 *
 * <p>幂等:每次先删 scene='ml-import' 的旧数据再批量插入,可重复跑。
 * 线上真实行为用其它 scene,不受影响。
 */
@Component
public class ImportBehaviorJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ImportBehaviorJob.class);
    private static final String SCENE = "ml-import";
    private static final int BATCH = 1000;

    private final BehaviorImportMapper persistence;

    @Value("${recsys.offline.movielens-path:./data/ml-latest-small}")
    private String movielensPath;

    /** 复用主源Mapper，导入批次和文件处理仍由作业编排。 */
    public ImportBehaviorJob(BehaviorImportMapper persistence) {
        this.persistence = persistence;
    }

    @Override
    public String name() {
        return "import-behavior";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path ratings = Path.of(movielensPath).resolve("ratings.csv");
        if (!Files.exists(ratings)) {
            log.error("未找到 {};请先跑 --job=import-items(会自动下载 MovieLens)", ratings);
            return;
        }

        int deleted = persistence.deleteScene(SCENE);
        log.info("清理旧的 {} 行为 {} 条", SCENE, deleted);

        List<BehaviorImportMapper.Rating> batch = new ArrayList<>(BATCH);
        long total = 0;
        try (BufferedReader br = Files.newBufferedReader(ratings, StandardCharsets.UTF_8)) {
            br.readLine(); // 表头 userId,movieId,rating,timestamp
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = line.split(",");
                if (c.length < 4) {
                    continue;
                }
                long userId = Long.parseLong(c[0].trim());
                long itemId = Long.parseLong(c[1].trim());
                double rating = Double.parseDouble(c[2].trim());
                long tsMs = Long.parseLong(c[3].trim()) * 1000L;
                batch.add(new BehaviorImportMapper.Rating(userId, itemId, rating, SCENE, new Timestamp(tsMs)));
                if (batch.size() >= BATCH) {
                    total += flush(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            total += flush(batch);
        }
        log.info("import-behavior 完成:写入 {} 条行为;user_behavior 总数 {}",
                total, persistence.count());
    }

    private int flush(List<BehaviorImportMapper.Rating> batch) {
        // PG原JDBC批次失败时整批回滚；单次有界VALUES保留该边界，外层事务仍可统一回滚。
        persistence.insertRatings(batch);
        return batch.size();
    }
}
