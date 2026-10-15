package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.R9EmbeddingStore;
import com.recsys.offline.persistence.mapper.manual.R9EmbeddingMapper.Row;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R9 向量发布作业。先把三个 CSV 全量读入并校验，再在 derived DB 单事务中替换同一 model_version，
 * 不 TRUNCATE 旧版本；任一文件脏、版本不一致或事务失败都不会破坏线上版本。
 */
@Component
public class ImportR9EmbeddingsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ImportR9EmbeddingsJob.class);
    private static final int DIM = 64;
    /** 既有近似单位向量容忍区间，使用平方范数避免额外开方误差。 */
    private static final double MIN_NORM_SQUARED = 0.81;
    private static final double MAX_NORM_SQUARED = 1.21;
    private final R9EmbeddingStore persistence;

    /** 作业负责输入验证，存储层保持原三组共同的手动发布事务。 */
    public ImportR9EmbeddingsJob(R9EmbeddingStore persistence) {
        this.persistence = persistence;
    }

    /** 保持原R9训练产物发布命令。 */
    @Override public String name() { return "import-r9-embeddings"; }

    /** 所有文件校验成功后才触发建表与版本替换。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path mindPath = Path.of(JobArguments.text(args, "mind-file", "train/mind_item_embedding.csv"));
        Path graphUserPath = Path.of(JobArguments.text(args, "graph-user-file", "train/graph_user_embedding.csv"));
        Path graphItemPath = Path.of(JobArguments.text(args, "graph-item-file", "train/graph_item_embedding.csv"));
        boolean requireAll = JobArguments.bool(args, "require-all", true);

        List<Row> mind = read(mindPath, requireAll);
        List<Row> graphUsers = read(graphUserPath, requireAll);
        List<Row> graphItems = read(graphItemPath, requireAll);
        if (requireAll && (mind.isEmpty() || graphUsers.isEmpty() || graphItems.isEmpty())) {
            throw new PublicErrorException(ErrorCode.JOB_R9_REQUIRED_GROUP_EMPTY);
        }
        if (mind.isEmpty() && graphUsers.isEmpty() && graphItems.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_R9_NO_INPUT);
        }
        String version = singleVersion(mind, graphUsers, graphItems);
        persistence.publish(version, mind, graphUsers, graphItems);
        log.info("R9 向量发布成功 version={}:mind-item={},graph-user={},graph-item={}",
                version, mind.size(), graphUsers.size(), graphItems.size());
    }

    static List<Row> read(Path path, boolean required) throws Exception {
        if (!Files.exists(path)) {
            if (required) throw new PublicErrorException(ErrorCode.JOB_INPUT_UNAVAILABLE);
            return List.of();
        }
        JobArguments.readableInput(path);
        List<Row> out = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(",");
                if (parts.length > 0 && "model_version".equalsIgnoreCase(parts[0].trim())) continue;
                if (parts.length != DIM + 2) {
                    throw new PublicErrorException(ErrorCode.JOB_R9_INVALID_VECTOR);
                }
                String version = parts[0].trim();
                long id = Long.parseLong(parts[1].trim());
                if (version.isBlank() || !ids.add(id)) {
                    throw new PublicErrorException(ErrorCode.JOB_R9_INVALID_IDENTITY);
                }
                float[] vector = new float[DIM];
                double norm = 0.0;
                for (int i = 0; i < DIM; i++) {
                    vector[i] = Float.parseFloat(parts[i + 2].trim());
                    if (!Float.isFinite(vector[i])) throw new PublicErrorException(ErrorCode.JOB_R9_INVALID_VECTOR);
                    norm += vector[i] * vector[i];
                }
                if (norm < MIN_NORM_SQUARED || norm > MAX_NORM_SQUARED) {
                    throw new PublicErrorException(ErrorCode.JOB_R9_INVALID_VECTOR);
                }
                out.add(new Row(version, id, new PGvector(vector)));
            }
        } catch (java.io.IOException failure) {
            throw new PublicErrorException(ErrorCode.JOB_INPUT_UNAVAILABLE, failure);
        } catch (NumberFormatException failure) {
            throw new PublicErrorException(ErrorCode.JOB_INPUT_INVALID, failure);
        }
        return List.copyOf(out);
    }

    @SafeVarargs
    private static String singleVersion(List<Row>... groups) {
        Set<String> versions = new HashSet<>();
        for (List<Row> rows : groups) for (Row row : rows) versions.add(row.version());
        if (versions.size() != 1) throw new PublicErrorException(ErrorCode.JOB_R9_VERSION_MISMATCH);
        return versions.iterator().next();
    }

}
