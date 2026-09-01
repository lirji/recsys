package com.recsys.recall.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** LightGCN 在线图召回：取版本化 user 图向量，再对同版本 item 图向量做 ANN。 */
@Component
public class GraphRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(GraphRecaller.class);
    private final JdbcTemplate jdbc;
    private final RecallProperties props;
    private String modelVersion;
    private volatile boolean ready;

    public GraphRecaller(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                         JdbcTemplate jdbc, RecallProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() { return RecallChannel.GRAPH; }

    @PostConstruct
    void load() {
        try {
            JsonNode schema = new ObjectMapper().readTree(readBytes(props.getGraph().getSchemaPath()));
            if (!"lightgcn".equalsIgnoreCase(schema.path("algorithm").asText())
                    || schema.path("dim").asInt() != 64) {
                throw new IllegalStateException("LightGCN schema algorithm/dim 非法");
            }
            modelVersion = schema.path("model_version").asText();
            if (modelVersion.isBlank()) throw new IllegalStateException("LightGCN schema 缺 model_version");
            ready = true;
            log.info("LightGCN schema 加载成功:version={}", modelVersion);
        } catch (Throwable t) {
            ready = false;
            log.warn("LightGCN schema 加载失败,GRAPH 路将返回空: {}", t.toString());
        }
    }

    public boolean isReady() { return ready; }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        if (!ready) return List.of();
        try {
            List<PGvector> users = jdbc.query(
                    "SELECT embedding FROM graph_user_embedding WHERE model_version=? AND user_id=?",
                    (rs, n) -> new PGvector(rs.getString(1)), modelVersion, ctx.userId());
            if (users.isEmpty()) return List.of();
            PGvector user = users.get(0);
            return jdbc.query("SELECT item_id,1-(embedding <=> ?) AS sim FROM graph_item_embedding " +
                            "WHERE model_version=? ORDER BY embedding <=> ? LIMIT ?",
                    ps -> {
                        ps.setObject(1, user);
                        ps.setString(2, modelVersion);
                        ps.setObject(3, user);
                        ps.setInt(4, props.getQuota().getGraph());
                    },
                    (rs, n) -> new RecallItem(rs.getLong("item_id"), rs.getDouble("sim"), RecallChannel.GRAPH));
        } catch (Exception e) {
            log.debug("LightGCN 召回失败 user={}: {}", ctx.userId(), e.getMessage());
            return List.of();
        }
    }

    private static byte[] readBytes(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = GraphRecaller.class.getClassLoader().getResourceAsStream(cp)) {
                if (in == null) throw new IllegalStateException("classpath 未找到 " + cp);
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(Path.of(path));
    }
}
