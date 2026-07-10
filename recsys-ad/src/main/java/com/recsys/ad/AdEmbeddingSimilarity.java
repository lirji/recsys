package com.recsys.ad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * List-wise 外部性的物品相似度来源:批量预载候选广告关联 item 的 {@code item_embedding},返回一个
 * {@link ListwiseExternality.Sim} 闭包供贪心排版按需取余弦(docs/05 §7 M7)。
 *
 * <p>复用召回/重排一致的读法({@code embedding::text} 解析,绕开 PGvector 类型注册;与 {@code MmrReranker}
 * 同款降级)。<b>优雅降级</b>:某 item 无向量 → 该对相似度取 0(不施加外部性衰减,等于这条不参与去重);
 * 整表读失败 → 全 0(List-wise 退化为逐条 eCPM)。一次请求只查一次,候选间在内存里复用。
 */
@Component
public class AdEmbeddingSimilarity {

    private static final Logger log = LoggerFactory.getLogger(AdEmbeddingSimilarity.class);

    private final JdbcTemplate jdbc;

    public AdEmbeddingSimilarity(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                                 JdbcTemplate jdbc) {   // #3:item_embedding 走派生库
        this.jdbc = jdbc;
    }

    /** 为给定 item 集合预载向量,返回相似度闭包(余弦;缺向量则 0)。 */
    public ListwiseExternality.Sim forItems(Collection<Long> itemIds) {
        Map<Long, float[]> vecs = load(itemIds);
        return (a, b) -> {
            float[] va = vecs.get(a);
            float[] vb = vecs.get(b);
            return (va == null || vb == null) ? 0.0 : Math.max(0.0, cosine(va, vb));
        };
    }

    private Map<Long, float[]> load(Collection<Long> itemIds) {
        Map<Long, float[]> out = new HashMap<>();
        Set<Long> unique = new HashSet<>(itemIds);
        if (unique.isEmpty()) {
            return out;
        }
        String placeholders = String.join(",", unique.stream().map(x -> "?").toList());
        try {
            jdbc.query(
                    "SELECT item_id, embedding::text FROM item_embedding WHERE item_id IN (" + placeholders + ")",
                    rs -> {
                        String s = rs.getString(2);
                        if (s != null) {
                            out.put(rs.getLong(1), parseVector(s));
                        }
                    },
                    unique.toArray());
        } catch (Exception e) {
            log.warn("List-wise 读取候选向量失败,外部性退化为 0(等同逐条 eCPM): {}", e.getMessage());
        }
        return out;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static float[] parseVector(String s) {
        String body = s.replace("[", "").replace("]", "");
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }
}
