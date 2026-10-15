package com.recsys.recengine.rerank;

import com.recsys.common.dto.RecommendItem;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.recsys.recengine.persistence.mapper.derived.EngineEmbeddingMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MMR 重排(strategy=mmr):最大边际相关,在"相关性"与"与已选结果的新颖度"间权衡。
 *
 * <pre>
 *   next = argmax_{i ∉ S} [ λ·rel(i) - (1-λ)·max_{j∈S} sim(i,j) ]
 * </pre>
 * λ 越大越偏相关、越小越偏多样(默认 0.7)。
 *
 * <p>物品相似度 sim:两者都有向量 → 余弦(读 {@code item_embedding},用 ::text 解析避免
 * PGvector 类型注册问题,与召回侧降级一致);否则退化为"同类目=1,异类目=0",
 * 保证未灌向量的物品仍可参与多样性计算。为控开销,只对相关性 Top-{@code poolSize} 候选做 MMR。
 */
@Component
public class MmrReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(MmrReranker.class);

    private final EngineEmbeddingMapper mapper;

    /** 向量读取委托派生库Mapper，算法与逐行失败降级策略保持原样。 */
    public MmrReranker(EngineEmbeddingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mmr";
    }

    @Override
    public List<RecommendItem> rerank(List<RerankCandidate> fused, RerankInput in) {
        double lambda = in.doubleParam("lambda", 0.7);
        int poolSize = in.intParam("poolSize", 200);
        int size = in.size();

        List<RerankCandidate> pool = fused.size() > poolSize ? fused.subList(0, poolSize) : fused;
        if (pool.size() <= size) {
            // 候选不足,无需 MMR,直接按相关性输出
            List<RecommendItem> out = new ArrayList<>(pool.size());
            for (RerankCandidate c : pool) {
                out.add(build(c, in));
            }
            return out;
        }

        // 相关性归一化到 [0,1]
        double maxRel = pool.get(0).score();
        double minRel = pool.get(pool.size() - 1).score();
        double span = maxRel - minRel;
        Map<Long, Double> rel = new HashMap<>();
        for (RerankCandidate c : pool) {
            rel.put(c.itemId(), span > 0 ? (c.score() - minRel) / span : 1.0);
        }

        List<Long> ids = pool.stream().map(RerankCandidate::itemId).toList();
        Map<Long, float[]> vecs = loadEmbeddings(ids);

        List<RerankCandidate> remaining = new ArrayList<>(pool);
        List<Long> selected = new ArrayList<>(size);
        List<RecommendItem> out = new ArrayList<>(size);

        // 第一个直接取相关性最高(列表已降序)
        RerankCandidate first = remaining.remove(0);
        selected.add(first.itemId());
        out.add(build(first, in));

        while (out.size() < size && !remaining.isEmpty()) {
            double bestMmr = -Double.MAX_VALUE;
            int bestIdx = 0;
            for (int k = 0; k < remaining.size(); k++) {
                RerankCandidate c = remaining.get(k);
                double maxSim = 0.0;
                for (long s : selected) {
                    maxSim = Math.max(maxSim, sim(c.itemId(), s, vecs, in.itemMap()));
                }
                double mmr = lambda * rel.get(c.itemId()) - (1 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestIdx = k;
                }
            }
            RerankCandidate picked = remaining.remove(bestIdx);
            selected.add(picked.itemId());
            out.add(build(picked, in));
        }
        return out;
    }

    /** 物品对相似度:有向量取余弦,否则同类目=1/异类目=0。 */
    private double sim(long a, long b, Map<Long, float[]> vecs, Map<Long, Item> itemMap) {
        float[] va = vecs.get(a);
        float[] vb = vecs.get(b);
        if (va != null && vb != null) {
            return cosine(va, vb);
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
        try {
            mapper.itemVectors(unique, context -> {
                var row = context.getResultObject();
                if (row.embedding() != null) out.put(row.itemId(), parseVector(row.embedding()));
            });
        } catch (Exception e) {
            log.warn("MMR 读取候选向量失败,退化为类目相似: {}", e.getMessage());
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
