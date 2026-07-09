package com.recsys.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * DeepFM 稀疏特征编码器:把 (userId, itemId, category) 编码成模型 embedding 的索引。
 *
 * <p><b>这是深度排序在线/离线一致性的关键</b>:编码规则必须与训练侧
 * {@code train_deepfm.py} 逐位一致,否则 embedding 查错行,线上打分全乱。
 * <ul>
 *   <li>user_bucket = floorMod(userId, userBuckets)</li>
 *   <li>item_bucket = floorMod(itemId, itemBuckets)</li>
 *   <li>cat_idx     = categoryVocab[category](未登录/缺失 → 0)</li>
 * </ul>
 * 取模分桶天然跨语言一致;桶大小与类目 vocab 由训练侧产出的
 * {@code rank_schema.json} / {@code category_vocab.json} 提供(训练侧是唯一真源),
 * 在线从 classpath 读入,避免两端各写一份漂移。
 *
 * <p><b>语义 ID 稀疏特征(可选,schema 驱动、向后兼容)</b>:当 schema 声明 {@code "semantic_id": true}
 * 且给出 {@code "semantic_id_map"}(itemId → [c0,c1,c2] 的 JSON)时,编码在 (user,item,cat) 后追加
 * RQ-VAE 语义 ID 三码字(缺失 item → 0,0,0),使精排也用上生成式召回的语义信号。schema 未声明则
 * width=3、编码不变(旧模型零影响)—— 训练侧 {@code train_deepfm.py} 加 semantic-id 后重训并导出 map 即启用。
 */
public final class SparseFeatureEncoder {

    private final int userBuckets;
    private final int itemBuckets;
    private final Map<String, Integer> categoryVocab;
    /** itemId → [c0,c1,c2];为空表示未启用语义 ID(width=3)。 */
    private final Map<Long, long[]> semanticIdMap;
    /** 稠密特征顺序(schema.dense_order;缺省 FEATURE_ORDER),供在线按模型训练时的维度装配。 */
    private final List<String> denseOrder;

    private SparseFeatureEncoder(int userBuckets, int itemBuckets, Map<String, Integer> categoryVocab,
                                Map<Long, long[]> semanticIdMap, List<String> denseOrder) {
        this.userBuckets = userBuckets;
        this.itemBuckets = itemBuckets;
        this.categoryVocab = categoryVocab;
        this.semanticIdMap = semanticIdMap;
        this.denseOrder = denseOrder;
    }

    /**
     * 从 schema/vocab 资源加载(classpath: 前缀走类路径,否则按文件路径)。
     * 任一资源缺失或字段不全都会抛异常,由 DeepFmRankService 捕获并回退规则排序。
     */
    public static SparseFeatureEncoder load(String schemaPath, String vocabPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(readBytes(schemaPath));
        int userBuckets = required(schema, "user_buckets").asInt();
        int itemBuckets = required(schema, "item_buckets").asInt();

        JsonNode vocabNode = mapper.readTree(readBytes(vocabPath));
        Map<String, Integer> vocab = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = vocabNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            vocab.put(e.getKey(), e.getValue().asInt());
        }
        if (vocab.isEmpty()) {
            throw new IllegalStateException("category_vocab.json 为空: " + vocabPath);
        }

        // 可选:语义 ID 稀疏特征(schema.semantic_id=true 且给出 semantic_id_map 路径)
        Map<Long, long[]> semanticIdMap = new HashMap<>();
        if (schema.path("semantic_id").asBoolean(false)) {
            JsonNode mapPathNode = schema.get("semantic_id_map");
            if (mapPathNode == null || mapPathNode.asText().isBlank()) {
                throw new IllegalStateException("schema 声明 semantic_id=true 但缺 semantic_id_map 路径");
            }
            JsonNode mapNode = mapper.readTree(readBytes(mapPathNode.asText()));
            for (Iterator<Map.Entry<String, JsonNode>> it = mapNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode arr = e.getValue();
                semanticIdMap.put(Long.parseLong(e.getKey()),
                        new long[]{arr.path(0).asLong(), arr.path(1).asLong(), arr.path(2).asLong()});
            }
        }
        // 稠密特征顺序(S2 特征扩充):schema 声明则用它,缺省回基础 FEATURE_ORDER(旧模型零影响)
        List<String> denseOrder = new ArrayList<>();
        JsonNode dord = schema.get("dense_order");
        if (dord != null && dord.isArray()) {
            for (JsonNode n : dord) {
                denseOrder.add(n.asText());
            }
        }
        if (denseOrder.isEmpty()) {
            denseOrder = FeatureAssembler.FEATURE_ORDER;
        }
        return new SparseFeatureEncoder(userBuckets, itemBuckets, vocab, semanticIdMap, denseOrder);
    }

    /** 稠密特征顺序(= 模型训练时的 dense_order);缺省基础 5 维。深度服务据此装配 dense。 */
    public List<String> denseOrder() {
        return denseOrder;
    }

    /**
     * 编码为 [user_bucket, item_bucket, cat_idx (, c0, c1, c2)](顺序 = train_deepfm.py 的 sparse_order)。
     * 启用语义 ID 时 width=6,否则 3。
     */
    public long[] encode(long userId, long itemId, String category) {
        long catIdx = category == null ? 0 : categoryVocab.getOrDefault(category, 0);
        long u = Math.floorMod(userId, userBuckets);
        long i = Math.floorMod(itemId, itemBuckets);
        if (semanticIdMap.isEmpty()) {
            return new long[]{u, i, catIdx};
        }
        long[] sid = semanticIdMap.getOrDefault(itemId, ZERO_SID);   // 缺失 item → 0,0,0
        return new long[]{u, i, catIdx, sid[0], sid[1], sid[2]};
    }

    private static final long[] ZERO_SID = {0, 0, 0};

    /** 稀疏特征宽度:3(基础)或 6(启用语义 ID)。 */
    public int width() {
        return semanticIdMap.isEmpty() ? 3 : 6;
    }

    public int categoryVocabSize() {
        return categoryVocab.size();
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            throw new IllegalStateException("rank_schema.json 缺字段: " + field);
        }
        return v;
    }

    private static byte[] readBytes(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = SparseFeatureEncoder.class.getClassLoader().getResourceAsStream(cp)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 未找到: " + cp);
                }
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(Path.of(path));
    }
}
