package com.recsys.recall.channel;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.recsys.common.rank.TowerScorer;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.recsys.recall.persistence.mapper.derived.EmbeddingMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

/**
 * 双塔(Two-Tower / DSSM)召回。
 *
 * <p>离线 {@code train_two_tower.py} 学两座塔:item 塔(itemId+category embedding)产出的向量已灌入
 * {@code item_tower_embedding}(见 {@code ImportTowerJob});user 塔(userId embedding)导出为
 * {@code user_tower.onnx}。本类<strong>在线</strong>加载 user 塔,实时把 userId 算成 query 向量,
 * 再在 {@code item_tower_embedding} 上做 pgvector 余弦 ANN —— 召回的是"行为相似"(协同信号),
 * 与 {@link VectorRecaller} 的"内容相似"(文本 embedding)互补。
 *
 * <p><b>在线/离线一致性契约</b>:user 塔输入是 {@code user_bucket = floorMod(userId, userBuckets)}
 * (与训练侧取模一致,天然跨语言),userBuckets 由训练侧写进 {@code tower_schema.json},在线读同一份。
 * item 向量已离线烘焙进库,故在线<strong>不需要</strong> item vocab / category vocab。
 *
 * <p><b>优雅降级</b>:模型/schema 缺失或加载失败 → {@link #ready} 为 false,recall 返回空,
 * 由其他召回路兜底(架构要求:任一路失败不拖垮整体)。
 */
@Component
public class TwoTowerRecaller implements ChannelRecaller, TowerScorer {

    private static final Logger log = LoggerFactory.getLogger(TwoTowerRecaller.class);

    private final EmbeddingMapper mapper;
    private final RecallProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private int userBuckets;
    private volatile boolean ready = false;

    public TwoTowerRecaller(EmbeddingMapper mapper, RecallProperties props) {   // #3:item_tower_embedding 走派生库
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.TWO_TOWER;
    }

    @PostConstruct
    void load() {
        try {
            JsonNode schema = new ObjectMapper().readTree(readBytes(props.getTwoTower().getSchemaPath()));
            userBuckets = required(schema, "user_buckets").asInt();
            // 输入名:schema 显式给则用,否则默认 "user_bucket"
            inputName = schema.has("input_name") ? schema.get("input_name").asText() : "user_bucket";

            byte[] model = readBytes(props.getTwoTower().getModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("双塔 user 塔加载成功:{};输入={};user_buckets={}",
                    props.getTwoTower().getModelPath(), session.getInputNames(), userBuckets);
        } catch (Throwable t) {
            ready = false;
            log.warn("双塔 user 塔加载失败,TWO_TOWER 召回将返回空(其他路兜底):{}", t.toString());
        }
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        if (!ready) {
            return List.of();
        }
        try {
            float[] userVec = userVector(ctx.userId());
            if (userVec == null) {
                return List.of();
            }
            int limit = props.getQuota().getTwoTower();
            PGvector pv = new PGvector(userVec);
            return mapper.nearestTowerItems(pv, limit).stream()
                    .map(row -> row.toRecallItem(RecallChannel.TWO_TOWER)).collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("双塔召回失败 user={}: {}", ctx.userId(), e.getMessage());
            return List.of();
        }
    }

    // ---- R6:TowerScorer —— 对候选集打双塔点积分,供 PreRankService 做学习型粗排 ----

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public Map<Long, Double> score(long userId, List<Long> itemIds) {
        if (!ready || itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        try {
            float[] userVec = userVector(userId);
            if (userVec == null) {
                return Map.of();
            }
            PGvector pv = new PGvector(userVec);
            java.util.Set<Long> unique = new java.util.HashSet<>(itemIds);
            Map<Long, Double> out = new java.util.HashMap<>(unique.size() * 2);
            // 缺失候选继续缺席，NULL相似度则沿原JDBC映射为零。
            mapper.scoreTowerItems(pv, unique).forEach(row -> out.put(row.itemId(), row.score()));
            return out;
        } catch (Exception e) {
            log.debug("双塔粗排打分失败 user={},退线性: {}", userId, e.getMessage());
            return Map.of();
        }
    }

    /** 用 user 塔 ONNX 把 userId 算成 query 向量。输入 user_bucket[1,1] int64 → user_vec[1,dim]。 */
    private float[] userVector(long userId) throws Exception {
        long bucket = Math.floorMod(userId, userBuckets);
        long[][] in = new long[][]{{bucket}};
        try (OnnxTensor t = OnnxTensor.createTensor(env, in)) {
            try (OrtSession.Result result = session.run(Collections.singletonMap(inputName, t))) {
                for (Map.Entry<String, OnnxValue> e : result) {
                    Object v = e.getValue().getValue();
                    if (v instanceof float[][] p && p.length > 0) {
                        return p[0];
                    }
                }
            }
        }
        return null;
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            throw new IllegalStateException("tower_schema.json 缺字段: " + field);
        }
        return v;
    }

    private static byte[] readBytes(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = TwoTowerRecaller.class.getClassLoader().getResourceAsStream(cp)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 未找到: " + cp);
                }
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(Path.of(path));
    }

    @PreDestroy
    void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
            // 关闭失败无所谓
        }
    }
}
