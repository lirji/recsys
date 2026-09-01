package com.recsys.recall.channel;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MIND 多兴趣召回：时间序列 → ONNX K 个兴趣向量 → 每兴趣 pgvector ANN → item 取最大相似度。 */
@Component
public class MultiInterestRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(MultiInterestRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;
    private OrtEnvironment env;
    private OrtSession session;
    private Map<Long, Long> itemVocab = Map.of();
    private String modelVersion;
    private String inputName;
    private String outputName;
    private int maxHistory;
    private int numInterests;
    private int dim;
    private volatile boolean ready;

    public MultiInterestRecaller(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                                 JdbcTemplate jdbc, RecallProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.MULTI_INTEREST;
    }

    @PostConstruct
    void load() {
        RecallProperties.MultiInterest cfg = props.getMultiInterest();
        try {
            JsonNode schema = new ObjectMapper().readTree(readBytes(cfg.getSchemaPath()));
            if (!"mind".equalsIgnoreCase(required(schema, "algorithm").asText())) {
                throw new IllegalStateException("schema.algorithm 不是 mind");
            }
            modelVersion = required(schema, "model_version").asText();
            maxHistory = required(schema, "max_history").asInt();
            numInterests = required(schema, "num_interests").asInt();
            dim = required(schema, "dim").asInt();
            inputName = schema.path("input_name").asText("history_items");
            outputName = schema.path("output_name").asText("interests");
            if (maxHistory <= 0 || numInterests <= 0 || dim != 64 || modelVersion.isBlank()) {
                throw new IllegalStateException("MIND schema 参数非法");
            }
            itemVocab = loadVocab(cfg.getVocabPath());
            if (itemVocab.isEmpty()) {
                throw new IllegalStateException("MIND item vocab 为空");
            }
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(readBytes(cfg.getModelPath()), new OrtSession.SessionOptions());
            if (!session.getInputNames().contains(inputName)) {
                throw new IllegalStateException("MIND ONNX 缺输入 " + inputName);
            }
            ready = true;
            log.info("MIND 多兴趣模型加载成功:version={},K={},dim={},history={},vocab={}",
                    modelVersion, numInterests, dim, maxHistory, itemVocab.size());
        } catch (Throwable t) {
            ready = false;
            log.warn("MIND 模型加载失败,MULTI_INTEREST 路将返回空: {}", t.toString());
        }
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        if (!ready || ctx.behaviorSequence() == null || ctx.behaviorSequence().isEmpty()) {
            return List.of();
        }
        try {
            long[][] history = encode(ctx.behaviorSequence());
            if (allPad(history[0])) {
                return List.of();
            }
            float[][] interests = predict(history);
            Map<Long, Double> best = new LinkedHashMap<>();
            int perInterest = Math.max(1, props.getMultiInterest().getPerInterestLimit());
            for (float[] interest : interests) {
                if (interest == null || interest.length != dim || !finite(interest)) {
                    continue;
                }
                PGvector vector = new PGvector(interest);
                jdbc.query("SELECT item_id,1-(embedding <=> ?) AS sim " +
                                "FROM multi_interest_item_embedding WHERE model_version=? " +
                                "ORDER BY embedding <=> ? LIMIT ?",
                        ps -> {
                            ps.setObject(1, vector);
                            ps.setString(2, modelVersion);
                            ps.setObject(3, vector);
                            ps.setInt(4, perInterest);
                        },
                        (RowCallbackHandler) rs -> best.merge(
                                rs.getLong("item_id"), rs.getDouble("sim"), Math::max));
            }
            return best.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(props.getQuota().getMultiInterest())
                    .map(e -> new RecallItem(e.getKey(), e.getValue(), RecallChannel.MULTI_INTEREST))
                    .toList();
        } catch (Exception e) {
            log.debug("MIND 召回失败 user={}: {}", ctx.userId(), e.getMessage());
            return List.of();
        }
    }

    long[][] encode(List<Long> oldestToNewest) {
        long[][] out = new long[1][maxHistory];
        int start = Math.max(0, oldestToNewest.size() - maxHistory);
        int dest = maxHistory - (oldestToNewest.size() - start); // 左侧 PAD,保留最新行为
        for (int i = start; i < oldestToNewest.size(); i++) {
            out[0][dest++] = itemVocab.getOrDefault(oldestToNewest.get(i), 0L);
        }
        return out;
    }

    private float[][] predict(long[][] history) throws Exception {
        try (OnnxTensor input = OnnxTensor.createTensor(env, history);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            OnnxValue value = result.get(outputName)
                    .orElseThrow(() -> new IllegalStateException("MIND 输出缺张量 " + outputName));
            Object raw = value.getValue();
            if (raw instanceof float[][][] tensor && tensor.length == 1
                    && tensor[0].length == numInterests) {
                return tensor[0];
            }
            throw new IllegalStateException("MIND 输出形状不是 [1,K,D]");
        }
    }

    private static boolean allPad(long[] values) {
        for (long value : values) if (value != 0L) return false;
        return true;
    }

    private static boolean finite(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static Map<Long, Long> loadVocab(String path) throws Exception {
        Map<Long, Long> out = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length != 2 || "item_id".equalsIgnoreCase(p[0].trim())) continue;
                long item = Long.parseLong(p[0].trim());
                long index = Long.parseLong(p[1].trim());
                if (index <= 0 || out.put(item, index) != null) {
                    throw new IllegalStateException("MIND vocab 重复 item 或非正 index: " + line);
                }
            }
        }
        return Map.copyOf(out);
    }

    private static JsonNode required(JsonNode schema, String field) {
        JsonNode value = schema.get(field);
        if (value == null || value.isNull()) throw new IllegalStateException("MIND schema 缺字段 " + field);
        return value;
    }

    private static InputStream open(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            InputStream in = MultiInterestRecaller.class.getClassLoader()
                    .getResourceAsStream(path.substring("classpath:".length()));
            if (in == null) throw new IllegalStateException("classpath 未找到 " + path);
            return in;
        }
        return Files.newInputStream(Path.of(path));
    }

    private static byte[] readBytes(String path) throws Exception {
        try (InputStream in = open(path)) {
            return in.readAllBytes();
        }
    }

    @PreDestroy
    void close() {
        try { if (session != null) session.close(); } catch (Exception ignore) { }
    }
}
