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
import com.recsys.recall.persistence.mapper.derived.EmbeddingMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** LightGCN 在线图召回：取版本化 user 图向量，再对同版本 item 图向量做 ANN。 */
@Component
public class GraphRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(GraphRecaller.class);
    private final EmbeddingMapper mapper;
    private final RecallProperties props;
    private String modelVersion;
    private volatile boolean ready;

    public GraphRecaller(EmbeddingMapper mapper, RecallProperties props) {
        this.mapper = mapper;
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
            List<String> users = mapper.graphUserEmbedding(modelVersion, ctx.userId());
            if (users.isEmpty()) return List.of();
            PGvector user = new PGvector(users.get(0));
            return mapper.nearestGraphItems(user, modelVersion, props.getQuota().getGraph()).stream()
                    .map(row -> row.toRecallItem(RecallChannel.GRAPH)).collect(Collectors.toList());
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
