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
import java.util.List;
import java.util.Map;

/**
 * DeepFM 深度排序(深度排序 Track 产出消费方)。
 *
 * <p>加载离线 PyTorch DeepFM 导出的 {@code model_deepfm.onnx}(双输入:稠密 {@code dense}[N,5]
 * + 稀疏 {@code sparse}[N,3]),稠密特征用共享 {@link FeatureAssembler} 装配(与 LightGBM/训练侧
 * 完全一致),稀疏 id 用 {@link SparseFeatureEncoder} 编码(与 train_deepfm.py 逐位一致)。
 *
 * <p>仅当 {@code recsys.rank.strategy=deepfm} 时创建;模型或编码器加载失败则 {@link #isReady()}
 * 为 false,由 {@link RankRouter} 回退规则排序(架构要求:模型失败绝不让请求挂掉)。
 */
@Service
@ConditionalOnProperty(name = "recsys.rank.strategy", havingValue = "deepfm")
@EnableConfigurationProperties(RankProperties.class)
public class DeepFmRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(DeepFmRankService.class);
    private static final String DENSE_INPUT = "dense";
    private static final String SPARSE_INPUT = "sparse";

    private final FeatureService featureService;
    private final ContentService contentService;
    private final RankProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private SparseFeatureEncoder encoder;
    private volatile boolean ready = false;

    public DeepFmRankService(FeatureService featureService, ContentService contentService,
                             RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            encoder = SparseFeatureEncoder.load(props.getRankSchemaPath(), props.getCategoryVocabPath());
            byte[] model = readModel(props.getDeepfmModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("DeepFM 排序模型加载成功:{};输入={};稠密维度={},类目 vocab={}",
                    props.getDeepfmModelPath(), session.getInputNames(),
                    FeatureAssembler.dim(), encoder.categoryVocabSize());
        } catch (Throwable t) {
            ready = false;
            log.warn("DeepFM 模型加载失败,将回退规则排序:{}", t.toString());
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

            float[] ctr = predict(dense, sparse);
            List<RankedItem> ranked = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ranked.add(new RankedItem(candidateItemIds.get(i), ctr[i],
                        FeatureAssembler.snapshot(raw[i], denseOrder)));
            }
            ranked.sort(Comparator.comparingDouble(RankedItem::score).reversed());
            return ranked;
        } catch (Exception e) {
            log.warn("DeepFM 打分异常,本次回退空结果(由上层兜底):{}", e.getMessage());
            return List.of();
        }
    }

    /** 双输入批量推理,返回 CTR;DeepFM 输出形如 [N,1] 或 [N]。 */
    private float[] predict(float[][] dense, long[][] sparse) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor d = OnnxTensor.createTensor(env, dense);
             OnnxTensor s = OnnxTensor.createTensor(env, sparse)) {
            inputs.put(DENSE_INPUT, d);
            inputs.put(SPARSE_INPUT, s);
            try (OrtSession.Result result = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> e : result) {
                    Object v = e.getValue().getValue();
                    if (v instanceof float[][] p) {
                        float[] out = new float[p.length];
                        for (int i = 0; i < p.length; i++) {
                            out[i] = p[i][p[i].length - 1];
                        }
                        return out;
                    }
                    if (v instanceof float[] p1) {
                        return p1;
                    }
                }
                throw new IllegalStateException("DeepFM 输出无可用浮点张量");
            }
        }
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = DeepFmRankService.class.getClassLoader().getResourceAsStream(cp)) {
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
