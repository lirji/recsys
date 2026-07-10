package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DPP 重排(strategy=dpp,R4):行列式点过程做<b>整页</b>多样性——比 MMR 的"逐条贪心减去最大相似"
 * 更全局(体积/行列式度量整个集合的多样性,自然抑制"两两不最像但整体扎堆")。
 *
 * <p>核 {@code L_ij = q_i·q_j·S_ij}:质量 {@code q_i = exp(alpha·relNorm_i)}(相关性,alpha 越大越偏相关)、
 * 相似度 {@code S_ij = max(0, cos)}(读 {@code item_embedding},无向量退同类目=1/异类目=0,与 MMR 一致)。
 * 用 <b>Chen et al. 2018 快速贪心 MAP 推断</b>(Cholesky 增量,O(k²N)):每步选让子集行列式增益最大的物品,
 * 首位=相关性最高(与 MMR 对齐)。参数 {@code dppAlpha}(默认 1.0)调相关性/多样性;只对 Top-{@code poolSize} 做。
 *
 * <p>优雅降级:候选不足直接按相关性输出;向量读取失败退类目相似;数值兜底 {@code d² ≥ 0}。
 */
@Component
public class DppReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(DppReranker.class);
    private static final double EPS = 1e-10;

    private final JdbcTemplate jdbc;

    public DppReranker(@org.springframework.beans.factory.annotation.Qualifier("derivedJdbc")
                       JdbcTemplate jdbc) {   // #3:item_embedding 走派生库
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "dpp";
    }

    @Override
    public List<RecommendItem> rerank(List<RerankCandidate> fused, RerankInput in) {
        double alpha = in.doubleParam("dppAlpha", 1.0);
        int poolSize = in.intParam("poolSize", 200);
        int size = in.size();

        List<RerankCandidate> pool = fused.size() > poolSize ? fused.subList(0, poolSize) : fused;
        if (pool.size() <= size) {
            List<RecommendItem> out = new ArrayList<>(pool.size());
            for (RerankCandidate c : pool) {
                out.add(build(c, in));
            }
            return out;
        }

        int n = pool.size();
        // 相关性归一化到 [0,1](列表已降序)→ 质量 q_i = exp(alpha·relNorm)
        double maxRel = pool.get(0).score();
        double minRel = pool.get(n - 1).score();
        double span = maxRel - minRel;
        long[] ids = new long[n];
        double[] q = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = pool.get(i).itemId();
            double relNorm = span > 0 ? (pool.get(i).score() - minRel) / span : 1.0;
            q[i] = Math.exp(alpha * relNorm);
        }
        Map<Long, float[]> vecs = loadEmbeddings(pool.stream().map(RerankCandidate::itemId).toList());

        // 快速贪心 MAP:d²[i]=L_ii=q_i²;c[i] 累积每步的 Cholesky 分量(定长 size)
        double[] d2 = new double[n];
        double[][] c = new double[n][size];
        boolean[] sel = new boolean[n];
        for (int i = 0; i < n; i++) {
            d2[i] = q[i] * q[i];
        }
        List<Integer> order = new ArrayList<>(size);
        int j = argmax(d2, sel);            // 首位 = 质量最高 = 相关性最高
        sel[j] = true;
        order.add(j);

        for (int step = 0; order.size() < size; step++) {
            double dj = Math.sqrt(Math.max(d2[j], 0));
            if (dj <= EPS) {
                break;                       // 剩余物品几乎不增加多样性体积
            }
            for (int i = 0; i < n; i++) {
                if (sel[i]) {
                    continue;
                }
                double lji = q[j] * q[i] * similarity(ids[j], ids[i], vecs, in.itemMap());
                double dot = 0;
                for (int t = 0; t < step; t++) {
                    dot += c[j][t] * c[i][t];
                }
                double ei = (lji - dot) / dj;
                c[i][step] = ei;
                d2[i] = Math.max(0, d2[i] - ei * ei);   // 数值兜底(相似度矩阵近似 PSD)
            }
            int next = argmax(d2, sel);
            if (next < 0 || d2[next] <= EPS) {
                break;
            }
            sel[next] = true;
            order.add(next);
            j = next;
        }

        List<RecommendItem> out = new ArrayList<>(order.size());
        for (int idx : order) {
            out.add(build(pool.get(idx), in));
        }
        return out;
    }

    /** 未选中里 d² 最大的下标;全选中返回 -1。 */
    private static int argmax(double[] d2, boolean[] sel) {
        int best = -1;
        double bestVal = -Double.MAX_VALUE;
        for (int i = 0; i < d2.length; i++) {
            if (!sel[i] && d2[i] > bestVal) {
                bestVal = d2[i];
                best = i;
            }
        }
        return best;
    }

    /** 物品对相似度:有向量取 max(0,cos)(保证核近似 PSD),否则同类目=1/异类目=0。 */
    private double similarity(long a, long b, Map<Long, float[]> vecs, Map<Long, Item> itemMap) {
        float[] va = vecs.get(a);
        float[] vb = vecs.get(b);
        if (va != null && vb != null) {
            return Math.max(0.0, cosine(va, vb));
        }
        Item ia = itemMap.get(a);
        Item ib = itemMap.get(b);
        if (ia != null && ib != null && ia.category() != null && ia.category().equals(ib.category())) {
            return 1.0;
        }
        return 0.0;
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
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private Map<Long, float[]> loadEmbeddings(List<Long> ids) {
        Map<Long, float[]> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        Set<Long> unique = new HashSet<>(ids);
        String placeholders = String.join(",", unique.stream().map(x -> "?").toList());
        try {
            jdbc.query(
                    "SELECT item_id, embedding::text FROM item_embedding WHERE item_id IN (" + placeholders + ")",
                    rs -> {
                        long id = rs.getLong(1);
                        String s = rs.getString(2);
                        if (s != null) {
                            out.put(id, parseVector(s));
                        }
                    },
                    unique.toArray());
        } catch (Exception e) {
            log.warn("DPP 读取候选向量失败,退化为类目相似: {}", e.getMessage());
        }
        return out;
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
