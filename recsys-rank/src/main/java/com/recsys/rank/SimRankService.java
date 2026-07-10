package com.recsys.rank;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.recsys.common.feature.FeatureService;
import com.recsys.common.rank.RankService;
import com.recsys.common.rank.RankedItem;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SIM 长序列建模排序(R3)。两阶段 = GSU 类目硬检索 + ESU(DIN 注意力):
 * <ol>
 *   <li><b>GSU</b>({@link SimGsu}):请求时拉用户<b>长</b>历史(近 {@code longHistory} 条正反馈,带类目),
 *       对<b>每个候选</b>按其类目从长历史检索最近 {@code esuK} 个同类目 item —— 每候选各自的相关子序列;</li>
 *   <li><b>ESU</b>:把 GSU 子序列喂给 DIN 架构模型(四输入 dense/sparse/seq/seq_len,双输出 ctr/cvr)。</li>
 * </ol>
 * 突破 DIN 只吃近 ≤20 短序列的限制:用极小在线开销(一次长历史查询 + 内存过滤)把有效历史拉长一个量级。
 *
 * <p><b>模型/契约</b>:ESU 与 DIN 同架构,故复用 {@link SparseFeatureEncoder}/{@link SequenceEncoder};
 * 模型由 {@code train_sim.py}(= 在 GSU 检索序列上训 DIN)产出 {@code model_sim.onnx},缺失回退规则。
 * 与 DIN 的差异只在"喂什么序列":DIN 广播近 20,SIM 逐候选 GSU 检索。仅 {@code strategy=sim} 时创建。
 */
@Service
@ConditionalOnProperty(name = "recsys.rank.strategy", havingValue = "sim")
@EnableConfigurationProperties(RankProperties.class)
public class SimRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(SimRankService.class);
    // 长历史:近 longHistory 条正反馈(带类目),供 GSU 类目检索
    private static final String HIST_SQL =
            "SELECT b.item_id AS item_id, i.category AS category FROM user_behavior b " +
            "JOIN item i ON i.item_id = b.item_id " +
            "WHERE b.user_id=? AND b.action='RATING' AND b.value>=4 ORDER BY b.ts DESC LIMIT ?";

    private final FeatureService featureService;
    private final ContentService contentService;
    private final JdbcTemplate jdbc;
    private final RankProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private SparseFeatureEncoder sparseEncoder;
    private SequenceEncoder seqEncoder;
    private volatile boolean ready = false;

    public SimRankService(FeatureService featureService, ContentService contentService,
                          JdbcTemplate jdbc, RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            sparseEncoder = SparseFeatureEncoder.load(props.getSimSchemaPath(), props.getSimCategoryVocabPath());
            seqEncoder = SequenceEncoder.load(props.getSimSchemaPath());
            byte[] model = readModel(props.getSimModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("SIM 长序列模型加载成功:{};输入={};输出={};seqLen(ESU)={};longHistory={}",
                    props.getSimModelPath(), session.getInputNames(), session.getOutputNames(),
                    seqEncoder.seqLen(), props.getSimLongHistory());
        } catch (Throwable t) {
            ready = false;
            log.warn("SIM 模型加载失败,将回退规则排序:{}", t.toString());
        }
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public List<RankedItem> rank(long userId, List<Long> candidateItemIds, String scene) {
        if (!ready || candidateItemIds == null || candidateItemIds.isEmpty()) {
            return List.of();
        }
        try {
            List<SimGsu.Hist> history = longHistory(userId, props.getSimLongHistory());  // 每用户查一次

            Map<Long, Item> items = contentService.findByIds(candidateItemIds);
            Map<String, Double> userFeat = featureService.userFeatures(userId);
            Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(candidateItemIds);

            int n = candidateItemIds.size();
            List<String> denseOrder = sparseEncoder.denseOrder();
            int dim = denseOrder.size();
            int L = seqEncoder.seqLen();
            float[][] dense = new float[n][dim];
            long[][] sparse = new long[n][3];
            long[][] seq = new long[n][L];
            long[] seqLen = new long[n];
            double[][] raw = new double[n][];
            for (int i = 0; i < n; i++) {
                long itemId = candidateItemIds.get(i);
                Item it = items.get(itemId);
                String cat = it == null ? null : it.category();
                double[] f = FeatureAssembler.assemble(userFeat, itemFeats.getOrDefault(itemId, Map.of()), cat, denseOrder);
                raw[i] = f;
                for (int d = 0; d < dim; d++) {
                    dense[i][d] = (float) f[d];
                }
                sparse[i] = sparseEncoder.encode(userId, itemId, cat);
                // GSU:逐候选按类目从长历史检索 top-esuK(esuK 上限即 ESU 序列定长 L)
                SequenceEncoder.Encoded enc = seqEncoder.encode(SimGsu.retrieve(history, cat, L));
                seq[i] = enc.seq();
                seqLen[i] = enc.len();
            }

            float[][] heads = predict(dense, sparse, seq, seqLen);
            double cvrBias = props.getMultiTask().getCvrBias();
            double cvrWeight = props.getMultiTask().getCvrWeight();
            List<RankedItem> ranked = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double pctr = heads[0][i];
                double pcvr = heads[1][i];
                double score = pctr * (cvrBias + cvrWeight * pcvr);
                Map<String, Double> snap = new LinkedHashMap<>(FeatureAssembler.snapshot(raw[i], denseOrder));
                snap.put("pCTR", round(pctr));
                snap.put("pCVR", round(pcvr));
                ranked.add(new RankedItem(candidateItemIds.get(i), score, snap));
            }
            ranked.sort(Comparator.comparingDouble(RankedItem::score).reversed());
            return ranked;
        } catch (Exception e) {
            log.warn("SIM 打分异常,本次回退空结果(由上层兜底):{}", e.getMessage());
            return List.of();
        }
    }

    /** 用户近 limit 条 ≥4 正反馈(带类目),oldest→newest 供 GSU 检索。异常 → 空历史(冷用户不崩)。 */
    private List<SimGsu.Hist> longHistory(long userId, int limit) {
        try {
            List<SimGsu.Hist> recentFirst = jdbc.query(HIST_SQL,
                    (rs, n) -> new SimGsu.Hist(rs.getLong("item_id"), rs.getString("category")),
                    userId, limit);
            Collections.reverse(recentFirst);   // DESC → oldest→newest
            return recentFirst;
        } catch (Exception e) {
            log.debug("查询用户长历史失败 user={}, 视作空: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private float[][] predict(float[][] dense, long[][] sparse, long[][] seq, long[] seqLen) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor d = OnnxTensor.createTensor(env, dense);
             OnnxTensor s = OnnxTensor.createTensor(env, sparse);
             OnnxTensor q = OnnxTensor.createTensor(env, seq);
             OnnxTensor l = OnnxTensor.createTensor(env, seqLen)) {
            inputs.put("dense", d);
            inputs.put("sparse", s);
            inputs.put("seq", q);
            inputs.put("seq_len", l);
            try (OrtSession.Result result = session.run(inputs)) {
                return new float[][]{column(result, "ctr"), column(result, "cvr")};
            }
        }
    }

    private static float[] column(OrtSession.Result result, String name) throws Exception {
        Optional<OnnxValue> v = result.get(name);
        if (v.isEmpty()) {
            throw new IllegalStateException("SIM 输出缺张量: " + name);
        }
        Object val = v.get().getValue();
        if (val instanceof float[][] p) {
            float[] out = new float[p.length];
            for (int i = 0; i < p.length; i++) {
                out[i] = p[i][p[i].length - 1];
            }
            return out;
        }
        if (val instanceof float[] p1) {
            return p1;
        }
        throw new IllegalStateException("SIM 输出 " + name + " 非浮点张量");
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = SimRankService.class.getClassLoader().getResourceAsStream(cp)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 未找到模型: " + cp);
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
