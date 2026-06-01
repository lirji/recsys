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
 * DIN 行为序列建模排序(+ MMoE 多目标头)。
 *
 * <p>加载 {@code train_din.py} 导出的 {@code model_din.onnx}:四输入(稠密 {@code dense}[N,5]
 * + 稀疏 {@code sparse}[N,3] + 行为序列 {@code seq}[N,L] + 有效长度 {@code seq_len}[N]),
 * 双输出 {@code ctr}+{@code cvr}。候选 item 对用户历史序列做 target-attention,再走 MMoE 多目标头。
 *
 * <p><b>在线序列来源</b>:请求时直连 {@link JdbcTemplate} 查该用户近 {@code seqLen} 条
 * <b>≥4 的 RATING</b>(与 train_din.py 的序列定义一致:gen-samples-mt 用 value≥4 正反馈构造序列),
 * 按 ts 升序(oldest→newest)喂 {@link SequenceEncoder}。序列<b>每用户查一次</b>、广播到全部候选
 * (DIN 的注意力在模型内对每个候选单独算)。
 * <blockquote>脚手架取数走 DB;生产会迁到 Redis 行为流 / 特征平台以降时延,这里保持简单。</blockquote>
 *
 * <p>多目标融合同 {@link MmoeRankService}:finalScore = pCTR·(cvrBias + cvrWeight·pCVR)。
 * 仅当 {@code recsys.rank.strategy=din} 时创建;加载失败回退规则排序。
 */
@Service
@ConditionalOnProperty(name = "recsys.rank.strategy", havingValue = "din")
@EnableConfigurationProperties(RankProperties.class)
public class DinRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(DinRankService.class);
    private static final String SEQ_SQL =
            "SELECT item_id FROM user_behavior WHERE user_id=? AND action='RATING' AND value>=4 "
                    + "ORDER BY ts DESC LIMIT ?";

    private final FeatureService featureService;
    private final ContentService contentService;
    private final JdbcTemplate jdbc;
    private final RankProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private SparseFeatureEncoder sparseEncoder;
    private SequenceEncoder seqEncoder;
    private volatile boolean ready = false;

    public DinRankService(FeatureService featureService, ContentService contentService,
                          JdbcTemplate jdbc, RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            sparseEncoder = SparseFeatureEncoder.load(props.getDinSchemaPath(), props.getDinCategoryVocabPath());
            seqEncoder = SequenceEncoder.load(props.getDinSchemaPath());
            byte[] model = readModel(props.getDinModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("DIN 序列模型加载成功:{};输入={};输出={};seqLen={}",
                    props.getDinModelPath(), session.getInputNames(), session.getOutputNames(), seqEncoder.seqLen());
        } catch (Throwable t) {
            ready = false;
            log.warn("DIN 模型加载失败,将回退规则排序:{}", t.toString());
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
            // 用户历史序列(每用户查一次,广播到全部候选)
            SequenceEncoder.Encoded enc = seqEncoder.encode(recentPositiveItems(userId, seqEncoder.seqLen()));

            Map<Long, Item> items = contentService.findByIds(candidateItemIds);
            Map<String, Double> userFeat = featureService.userFeatures(userId);

            int n = candidateItemIds.size();
            int dim = FeatureAssembler.dim();
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
                double[] f = FeatureAssembler.assemble(userFeat, featureService.itemFeatures(itemId), cat);
                raw[i] = f;
                for (int d = 0; d < dim; d++) {
                    dense[i][d] = (float) f[d];
                }
                sparse[i] = sparseEncoder.encode(userId, itemId, cat);
                seq[i] = enc.seq();      // 同一用户序列,广播
                seqLen[i] = enc.len();
            }

            float[][] heads = predict(dense, sparse, seq, seqLen);  // [0]=ctr,[1]=cvr
            double cvrBias = props.getMultiTask().getCvrBias();
            double cvrWeight = props.getMultiTask().getCvrWeight();
            List<RankedItem> ranked = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double pctr = heads[0][i];
                double pcvr = heads[1][i];
                double score = pctr * (cvrBias + cvrWeight * pcvr);
                Map<String, Double> snap = new LinkedHashMap<>(FeatureAssembler.snapshot(raw[i]));
                snap.put("pCTR", round(pctr));
                snap.put("pCVR", round(pcvr));
                ranked.add(new RankedItem(candidateItemIds.get(i), score, snap));
            }
            ranked.sort(Comparator.comparingDouble(RankedItem::score).reversed());
            return ranked;
        } catch (Exception e) {
            log.warn("DIN 打分异常,本次回退空结果(由上层兜底):{}", e.getMessage());
            return List.of();
        }
    }

    /** 用户近 limit 条 ≥4 正反馈物品,返回 oldest→newest(与训练侧序列顺序一致)。 */
    private List<Long> recentPositiveItems(long userId, int limit) {
        try {
            List<Long> recentFirst = jdbc.queryForList(SEQ_SQL, Long.class, userId, limit);
            Collections.reverse(recentFirst);  // DESC 查出最近在前 → 反转成 oldest→newest
            return recentFirst;
        } catch (Exception e) {
            log.debug("查询用户序列失败 user={}, 视作空序列: {}", userId, e.getMessage());
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
            throw new IllegalStateException("DIN 输出缺张量: " + name);
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
        throw new IllegalStateException("DIN 输出 " + name + " 非浮点张量");
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = DinRankService.class.getClassLoader().getResourceAsStream(cp)) {
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
