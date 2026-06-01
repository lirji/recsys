package com.recsys.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
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
 */
public final class SparseFeatureEncoder {

    private final int userBuckets;
    private final int itemBuckets;
    private final Map<String, Integer> categoryVocab;

    private SparseFeatureEncoder(int userBuckets, int itemBuckets, Map<String, Integer> categoryVocab) {
        this.userBuckets = userBuckets;
        this.itemBuckets = itemBuckets;
        this.categoryVocab = categoryVocab;
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
        return new SparseFeatureEncoder(userBuckets, itemBuckets, vocab);
    }

    /** 编码为 [user_bucket, item_bucket, cat_idx](顺序 = train_deepfm.py 的 sparse_order)。 */
    public long[] encode(long userId, long itemId, String category) {
        long catIdx = category == null ? 0 : categoryVocab.getOrDefault(category, 0);
        return new long[]{Math.floorMod(userId, userBuckets), Math.floorMod(itemId, itemBuckets), catIdx};
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
