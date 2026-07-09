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
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MMoE + ESMM 多目标排序。
 *
 * <p>加载离线 {@code train_mmoe.py} 导出的 {@code model_mmoe.onnx}:双输入(稠密 {@code dense}[N,5]
 * + 稀疏 {@code sparse}[N,3])、<b>双输出</b> {@code ctr}[N,1] + {@code cvr}[N,1]。稠密走共享
 * {@link FeatureAssembler}、稀疏走 {@link SparseFeatureEncoder}(与 DeepFM 同一套编码契约)。
 *
 * <p><b>多目标融合</b>:finalScore = pCTR · (cvrBias + cvrWeight · pCVR)。
 * cvrBias=0、cvrWeight=1 时退化为 ESMM 的 pCTCVR;调大 cvrBias 让高曝光低满意物品保留排面。
 * 融合权重在 {@link RankProperties.MultiTask}(env/yml 可调,无需重训)。
 *
 * <p>仅当 {@code recsys.rank.strategy=mmoe} 时创建;加载失败 {@link #isReady()}=false,
 * 由 {@link RankRouter} 回退规则排序(架构要求:模型失败绝不让请求挂掉)。
 */
@Service
@ConditionalOnProperty(name = "recsys.rank.strategy", havingValue = "mmoe")
@EnableConfigurationProperties(RankProperties.class)
public class MmoeRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(MmoeRankService.class);
    private static final String DENSE_INPUT = "dense";
    private static final String SPARSE_INPUT = "sparse";
    private static final String CTR_OUTPUT = "ctr";
    private static final String CVR_OUTPUT = "cvr";

    private final FeatureService featureService;
    private final ContentService contentService;
    private final RankProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private SparseFeatureEncoder encoder;
    private volatile boolean ready = false;

    public MmoeRankService(FeatureService featureService, ContentService contentService,
                           RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            encoder = SparseFeatureEncoder.load(props.getMmoeSchemaPath(), props.getMmoeCategoryVocabPath());
            byte[] model = readModel(props.getMmoeModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("MMoE 多目标模型加载成功:{};输入={};输出={};融合 cvrBias={},cvrWeight={}",
                    props.getMmoeModelPath(), session.getInputNames(), session.getOutputNames(),
                    props.getMultiTask().getCvrBias(), props.getMultiTask().getCvrWeight());
        } catch (Throwable t) {
            ready = false;
            log.warn("MMoE 模型加载失败,将回退规则排序:{}", t.toString());
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
            Map<Long, Item> items = contentService.findByIds(candidateItemIds);
            Map<String, Double> userFeat = featureService.userFeatures(userId);
            // 批量预取候选特征(一次 pipeline),消除候选循环里逐个 HGETALL 的 N+1
            Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(candidateItemIds);

            int n = candidateItemIds.size();
            List<String> denseOrder = encoder.denseOrder();   // 模型训练时的 dense_order(缺省基础 5 维)
            int dim = denseOrder.size();
            float[][] dense = new float[n][dim];
            long[][] sparse = new long[n][3];
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
                sparse[i] = encoder.encode(userId, itemId, cat);
            }

            float[][] heads = predict(dense, sparse);  // [0]=ctr, [1]=cvr
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
            log.warn("MMoE 打分异常,本次回退空结果(由上层兜底):{}", e.getMessage());
            return List.of();
        }
    }

    /** 双输入双输出推理,返回 [ctr[N], cvr[N]]。 */
    private float[][] predict(float[][] dense, long[][] sparse) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor d = OnnxTensor.createTensor(env, dense);
             OnnxTensor s = OnnxTensor.createTensor(env, sparse)) {
            inputs.put(DENSE_INPUT, d);
            inputs.put(SPARSE_INPUT, s);
            try (OrtSession.Result result = session.run(inputs)) {
                float[] ctr = column(result, CTR_OUTPUT);
                float[] cvr = column(result, CVR_OUTPUT);
                return new float[][]{ctr, cvr};
            }
        }
    }

    /** 取指定名输出的首列([N,1] → float[N]);兼容 [N] 一维。 */
    private static float[] column(OrtSession.Result result, String name) throws Exception {
        Optional<OnnxValue> v = result.get(name);
        if (v.isEmpty()) {
            throw new IllegalStateException("MMoE 输出缺张量: " + name);
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
        throw new IllegalStateException("MMoE 输出 " + name + " 非浮点张量");
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = MmoeRankService.class.getClassLoader().getResourceAsStream(cp)) {
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
