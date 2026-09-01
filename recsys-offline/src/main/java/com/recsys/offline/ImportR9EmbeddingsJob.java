package com.recsys.offline;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
    private final JdbcTemplate jdbc;

    public ImportR9EmbeddingsJob(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                                 JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String name() { return "import-r9-embeddings"; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path mindPath = Path.of(arg(args, "mind-file", "train/mind_item_embedding.csv"));
        Path graphUserPath = Path.of(arg(args, "graph-user-file", "train/graph_user_embedding.csv"));
        Path graphItemPath = Path.of(arg(args, "graph-item-file", "train/graph_item_embedding.csv"));
        boolean requireAll = Boolean.parseBoolean(arg(args, "require-all", "true"));

        List<Row> mind = read(mindPath, requireAll);
        List<Row> graphUsers = read(graphUserPath, requireAll);
        List<Row> graphItems = read(graphItemPath, requireAll);
        if (requireAll && (mind.isEmpty() || graphUsers.isEmpty() || graphItems.isEmpty())) {
            throw new IllegalArgumentException("R9 require-all=true 时三组向量都必须非空");
        }
        if (mind.isEmpty() && graphUsers.isEmpty() && graphItems.isEmpty()) {
            log.warn("R9 没有可导入向量");
            return;
        }
        String version = singleVersion(mind, graphUsers, graphItems);
        ensureTables();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                replace(connection.prepareStatement(
                        "DELETE FROM multi_interest_item_embedding WHERE model_version=?"),
                        connection.prepareStatement("INSERT INTO multi_interest_item_embedding" +
                                "(model_version,item_id,embedding) VALUES(?,?,?)"), version, mind);
                replace(connection.prepareStatement(
                        "DELETE FROM graph_user_embedding WHERE model_version=?"),
                        connection.prepareStatement("INSERT INTO graph_user_embedding" +
                                "(model_version,user_id,embedding) VALUES(?,?,?)"), version, graphUsers);
                replace(connection.prepareStatement(
                        "DELETE FROM graph_item_embedding WHERE model_version=?"),
                        connection.prepareStatement("INSERT INTO graph_item_embedding" +
                                "(model_version,item_id,embedding) VALUES(?,?,?)"), version, graphItems);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof SQLException sql) throw sql;
                throw new SQLException("R9 向量事务发布失败", e);
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
            return null;
        });
        log.info("R9 向量发布成功 version={}:mind-item={},graph-user={},graph-item={}",
                version, mind.size(), graphUsers.size(), graphItems.size());
    }

    private static void replace(PreparedStatement delete, PreparedStatement insert,
                                String version, List<Row> rows) throws SQLException {
        try (delete; insert) {
            if (rows.isEmpty()) return;
            delete.setString(1, version);
            delete.executeUpdate();
            int queued = 0;
            for (Row row : rows) {
                insert.setString(1, row.version());
                insert.setLong(2, row.id());
                insert.setObject(3, new PGvector(row.vector()));
                insert.addBatch();
                if (++queued % 500 == 0) insert.executeBatch();
            }
            if (queued % 500 != 0) insert.executeBatch();
        }
    }

    static List<Row> read(Path path, boolean required) throws Exception {
        if (!Files.exists(path)) {
            if (required) throw new IllegalArgumentException("R9 向量文件不存在: " + path.toAbsolutePath());
            return List.of();
        }
        List<Row> out = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(",");
                if (parts.length > 0 && "model_version".equalsIgnoreCase(parts[0].trim())) continue;
                if (parts.length != DIM + 2) {
                    throw new IllegalArgumentException(path + ":" + number + " 维度不是 " + DIM);
                }
                String version = parts[0].trim();
                long id = Long.parseLong(parts[1].trim());
                if (version.isBlank() || !ids.add(id)) {
                    throw new IllegalArgumentException(path + ":" + number + " 版本为空或 ID 重复");
                }
                float[] vector = new float[DIM];
                double norm = 0.0;
                for (int i = 0; i < DIM; i++) {
                    vector[i] = Float.parseFloat(parts[i + 2].trim());
                    if (!Float.isFinite(vector[i])) throw new IllegalArgumentException(path + ":" + number + " 非有限值");
                    norm += vector[i] * vector[i];
                }
                if (norm < 0.81 || norm > 1.21) {
                    throw new IllegalArgumentException(path + ":" + number + " 向量未近似 L2 归一化,norm2=" + norm);
                }
                out.add(new Row(version, id, vector));
            }
        }
        return List.copyOf(out);
    }

    @SafeVarargs
    private static String singleVersion(List<Row>... groups) {
        Set<String> versions = new HashSet<>();
        for (List<Row> rows : groups) for (Row row : rows) versions.add(row.version());
        if (versions.size() != 1) throw new IllegalArgumentException("R9 三组向量必须只有一个共同版本: " + versions);
        return versions.iterator().next();
    }

    private void ensureTables() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE IF NOT EXISTS multi_interest_item_embedding(" +
                "model_version TEXT NOT NULL,item_id BIGINT NOT NULL,embedding vector(64) NOT NULL," +
                "PRIMARY KEY(model_version,item_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS graph_user_embedding(" +
                "model_version TEXT NOT NULL,user_id BIGINT NOT NULL,embedding vector(64) NOT NULL," +
                "PRIMARY KEY(model_version,user_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS graph_item_embedding(" +
                "model_version TEXT NOT NULL,item_id BIGINT NOT NULL,embedding vector(64) NOT NULL," +
                "PRIMARY KEY(model_version,item_id))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_multi_interest_item_hnsw ON " +
                "multi_interest_item_embedding USING hnsw(embedding vector_cosine_ops)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_graph_item_hnsw ON " +
                "graph_item_embedding USING hnsw(embedding vector_cosine_ops)");
    }

    private static String arg(ApplicationArguments args, String key, String def) {
        List<String> values = args.getOptionValues(key);
        return values == null || values.isEmpty() ? def : values.get(0).trim();
    }

    record Row(String version, long id, float[] vector) { }
}
