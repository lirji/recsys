package com.recsys.recall.channel;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整 TIGER 生成式召回(docs/04 §14):decoder-only Transformer **自回归 beam search 生成**
 * 用户下一个 item 的语义 ID,再映射回 item。区别于 {@link SemanticIdRecaller}(语义 ID 前缀检索),
 * 这里是真正的"读历史 → 生成下一个 ID"。
 *
 * <p><b>在线流程</b>:用户近期正反馈 item(oldest→newest,≤max_hist)→ 查语义 ID → 拼成 token 前缀
 * {@code [hist 码 token..., BOS]} → 逐级(L=3)beam search:每级把所有 beam 批量喂 ONNX,取末位 logits、
 * 限制到该级码 token 区间、log-softmax 取 top-B → 扩展 → 全局保留 top-B → 得 B 个生成的 (c0,c1,c2)
 * → 映射回 item(精确命中,无则按 c0c1 / c0 前缀兜底)。
 *
 * <p><b>在线/离线契约</b>:token 化(PAD/BOS/`码 token=base+level*K+code`)、max_hist、L 必须与
 * {@code train_tiger.py} 一致(由 {@code tiger_schema.json} 携带,在线读同一份),类比 SparseFeatureEncoder。
 * <b>优雅降级</b>:模型/schema 缺失 → {@code ready=false},返回空;无历史/无语义 ID → 空;其它路兜底。
 */
@Component
public class TigerRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(TigerRecaller.class);

    private final JdbcTemplate jdbc;
    private final RecallProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private int levels, codes, base, bos, maxHist;
    private volatile boolean ready = false;

    public TigerRecaller(JdbcTemplate jdbc, RecallProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.TIGER;
    }

    @PostConstruct
    void load() {
        try {
            JsonNode s = new ObjectMapper().readTree(readBytes(props.getTiger().getSchemaPath()));
            levels = s.get("levels").asInt();
            codes = s.get("codes").asInt();
            base = s.get("code_token_base").asInt();
            bos = s.get("bos").asInt();
            maxHist = s.get("max_hist").asInt();
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(readBytes(props.getTiger().getModelPath()), new OrtSession.SessionOptions());
            inputName = session.getInputNames().iterator().next();
            ready = true;
            log.info("TIGER 模型加载成功:{};L={} K={} maxHist={} 输入={}",
                    props.getTiger().getModelPath(), levels, codes, maxHist, inputName);
        } catch (Throwable t) {
            ready = false;
            log.warn("TIGER 模型加载失败,TIGER 召回返回空(其他路兜底):{}", t.toString());
        }
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        if (!ready) {
            return List.of();
        }
        try {
            List<Long> hist = recentItems(ctx.userId(), maxHist);          // oldest→newest
            Map<Long, int[]> semid = loadCodes(hist);
            // 历史码 token(顺序保持 oldest→newest)
            List<Long> prefix = new ArrayList<>();
            java.util.Set<Long> histSet = new java.util.HashSet<>(hist);
            for (Long iid : hist) {
                int[] c = semid.get(iid);
                if (c == null) {
                    continue;
                }
                for (int lvl = 0; lvl < levels; lvl++) {
                    prefix.add((long) codeToken(lvl, c[lvl]));
                }
            }
            prefix.add((long) bos);

            int beamW = props.getTiger().getBeam();
            List<long[]> beams = new ArrayList<>();
            beams.add(toArray(prefix));
            double[] scores = {0.0};

            for (int lvl = 0; lvl < levels; lvl++) {
                float[][] last = runLastLogits(beams);                      // [numBeams, vocab]
                int lo = codeToken(lvl, 0), hi = lo + codes;
                List<long[]> childSeq = new ArrayList<>();
                List<Double> childScore = new ArrayList<>();
                for (int b = 0; b < beams.size(); b++) {
                    double[] logp = logSoftmaxRange(last[b], lo, hi);       // 长度 codes
                    for (int c = 0; c < codes; c++) {
                        long[] ns = append(beams.get(b), (long) (lo + c));
                        childSeq.add(ns);
                        childScore.add(scores[b] + logp[c]);
                    }
                }
                // 全局保留 top-beamW
                Integer[] order = topIndices(childScore, beamW);
                List<long[]> nb = new ArrayList<>();
                double[] nsc = new double[order.length];
                for (int i = 0; i < order.length; i++) {
                    nb.add(childSeq.get(order[i]));
                    nsc[i] = childScore.get(order[i]);
                }
                beams = nb;
                scores = nsc;
            }

            // 生成的 (c0,c1,c2) → item(精确,无则前缀兜底);beam 越靠前分越高
            Map<Long, Double> best = new LinkedHashMap<>();
            for (int b = 0; b < beams.size(); b++) {
                long[] seq = beams.get(b);
                int[] gen = new int[levels];
                for (int lvl = 0; lvl < levels; lvl++) {
                    gen[lvl] = (int) (seq[seq.length - levels + lvl] - codeToken(lvl, 0));
                }
                double score = Math.exp(scores[b]);                        // 联合概率,单调
                for (long itemId : itemsForCodes(gen)) {
                    if (!histSet.contains(itemId)) {
                        best.merge(itemId, score, Math::max);
                    }
                }
            }
            int limit = props.getQuota().getTiger();
            log.info("TIGER diag user={} hist={} prefixLen={} beams={} genItems={} sample={}",
                    ctx.userId(), hist.size(), prefix.size(), beams.size(), best.size(),
                    best.keySet().stream().limit(5).toList());
            return best.entrySet().stream()
                    .sorted((a, c) -> Double.compare(c.getValue(), a.getValue()))
                    .limit(limit)
                    .map(e -> new RecallItem(e.getKey(), e.getValue(), RecallChannel.TIGER))
                    .toList();
        } catch (Exception e) {
            log.warn("TIGER 召回失败 user={}: {}", ctx.userId(), e.toString(), e);
            return List.of();
        }
    }

    /** 把所有(等长)beam 批量喂 ONNX,取每个 beam 末位的 logits。 */
    private float[][] runLastLogits(List<long[]> beams) throws Exception {
        int n = beams.size();
        int t = beams.get(0).length;
        long[][] batch = new long[n][];
        for (int i = 0; i < n; i++) {
            batch[i] = beams.get(i);
        }
        try (OnnxTensor in = OnnxTensor.createTensor(env, batch);
             OrtSession.Result res = session.run(Collections.singletonMap(inputName, in))) {
            float[][][] logits = null;
            for (Map.Entry<String, OnnxValue> e : res) {
                Object v = e.getValue().getValue();
                if (v instanceof float[][][] p) {
                    logits = p;
                    break;
                }
            }
            if (logits == null) {
                throw new IllegalStateException("TIGER 输出非 [N,T,V]");
            }
            float[][] out = new float[n][];
            for (int i = 0; i < n; i++) {
                out[i] = logits[i][t - 1];                                  // 末位
            }
            return out;
        }
    }

    private int codeToken(int level, int code) {
        return base + level * codes + code;
    }

    /** [lo,hi) 区间内做 log-softmax,返回长度 (hi-lo) 的对数概率。 */
    private static double[] logSoftmaxRange(float[] logits, int lo, int hi) {
        int n = hi - lo;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, logits[lo + i]);
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += Math.exp(logits[lo + i] - max);
        }
        double logSum = max + Math.log(sum);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = logits[lo + i] - logSum;
        }
        return out;
    }

    /** 取 scores 中最大的 k 个下标(降序)。 */
    private static Integer[] topIndices(List<Double> scores, int k) {
        Integer[] idx = new Integer[scores.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(scores.get(b), scores.get(a)));
        int n = Math.min(k, idx.length);
        Integer[] out = new Integer[n];
        System.arraycopy(idx, 0, out, 0, n);
        return out;
    }

    /** 生成的语义 ID → item:精确 (c0,c1,c2),无则 (c0,c1),再无则 (c0) 前缀兜底。 */
    private List<Long> itemsForCodes(int[] gen) {
        List<Long> ids = jdbc.queryForList(
                "SELECT item_id FROM item_semantic_id WHERE c0=? AND c1=? AND c2=? LIMIT 20",
                Long.class, gen[0], gen[1], gen[2]);
        if (!ids.isEmpty()) {
            return ids;
        }
        ids = jdbc.queryForList(
                "SELECT item_id FROM item_semantic_id WHERE c0=? AND c1=? LIMIT 20",
                Long.class, gen[0], gen[1]);
        if (!ids.isEmpty()) {
            return ids;
        }
        return jdbc.queryForList(
                "SELECT item_id FROM item_semantic_id WHERE c0=? LIMIT 20", Long.class, gen[0]);
    }

    private Map<Long, int[]> loadCodes(List<Long> itemIds) {
        Map<Long, int[]> out = new java.util.HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        Long[] ids = itemIds.toArray(new Long[0]);
        jdbc.query(
                "SELECT item_id, c0, c1, c2 FROM item_semantic_id WHERE item_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getLong("item_id"),
                        new int[]{rs.getInt("c0"), rs.getInt("c1"), rs.getInt("c2")}));
        return out;
    }

    /** 用户最近 ≤n 个 ≥4 正反馈 item,返回 oldest→newest(与训练序列方向一致)。 */
    private List<Long> recentItems(long userId, int n) {
        List<Long> desc = jdbc.queryForList(
                "SELECT item_id FROM user_behavior WHERE user_id=? " +
                "AND ((action='RATING' AND value>=4) OR action IN ('CLICK','LIKE','PLAY')) " +
                "ORDER BY ts DESC LIMIT ?",
                Long.class, userId, n);
        Collections.reverse(desc);
        return desc;
    }

    private static long[] toArray(List<Long> list) {
        long[] a = new long[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    private static long[] append(long[] arr, long v) {
        long[] out = java.util.Arrays.copyOf(arr, arr.length + 1);
        out[arr.length] = v;
        return out;
    }

    private static byte[] readBytes(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = TigerRecaller.class.getClassLoader().getResourceAsStream(cp)) {
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
