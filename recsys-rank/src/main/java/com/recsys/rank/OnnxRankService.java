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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * v3 ONNX 排序(Track C3 / Track D 产出消费方)。
 *
 * <p>加载离线 LightGBM 训练并导出的 {@code model.onnx},用共享 {@link FeatureAssembler}
 * 装配候选特征(与训练侧 gen-samples 完全一致),onnxruntime 批量预测 CTR 后降序。
 *
 * <p>仅当 {@code recsys.rank.strategy=onnx} 时创建;模型加载失败则 {@link #isReady()} 为 false,
 * 由 {@link RankRouter} 回退规则排序(架构要求:模型加载失败回退规则打分,绝不让请求挂掉)。
 */
@Service
@ConditionalOnProperty(name = "recsys.rank.strategy", havingValue = "onnx")
@EnableConfigurationProperties(RankProperties.class)
public class OnnxRankService implements RankService {

    private static final Logger log = LoggerFactory.getLogger(OnnxRankService.class);

    private final FeatureService featureService;
    private final ContentService contentService;
    private final RankProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private volatile boolean ready = false;

    public OnnxRankService(FeatureService featureService, ContentService contentService,
                           RankProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            byte[] model = readModel(props.getOnnxModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            inputName = session.getInputNames().iterator().next();
            ready = true;
            log.info("ONNX 排序模型加载成功:{};输入名={}, 特征维度={}",
                    props.getOnnxModelPath(), inputName, FeatureAssembler.dim());
        } catch (Throwable t) {
            ready = false;
            log.warn("ONNX 模型加载失败,将回退规则排序:{}", t.toString());
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
            int dim = FeatureAssembler.dim();
            float[][] x = new float[n][dim];
            double[][] raw = new double[n][];
            for (int i = 0; i < n; i++) {
                long itemId = candidateItemIds.get(i);
                Item it = items.get(itemId);
                String cat = it == null ? null : it.category();
                double[] f = FeatureAssembler.assemble(userFeat, itemFeats.getOrDefault(itemId, Map.of()), cat);
                raw[i] = f;
                for (int d = 0; d < dim; d++) {
                    x[i][d] = (float) f[d];
                }
            }

            float[] ctr = predict(x);
            List<RankedItem> ranked = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ranked.add(new RankedItem(candidateItemIds.get(i), ctr[i],
                        FeatureAssembler.snapshot(raw[i])));
            }
            ranked.sort(Comparator.comparingDouble(RankedItem::score).reversed());
            return ranked;
        } catch (Exception e) {
            log.warn("ONNX 打分异常,本次回退空结果(由上层用召回分兜底):{}", e.getMessage());
            return List.of();
        }
    }

    /** 批量推理,返回正类(点击)概率。 */
    private float[] predict(float[][] x) throws Exception {
        try (OnnxTensor in = OnnxTensor.createTensor(env, x);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, in))) {
            // 取第一个「浮点张量」输出作为概率;LightGBM→ONNX 通常含 label + probabilities,
            // probabilities 形如 [N,2](zipmap=False),取正类列。
            for (Map.Entry<String, OnnxValue> e : result) {
                Object v = e.getValue().getValue();
                if (v instanceof float[][] p) {
                    int cols = p.length > 0 ? p[0].length : 0;
                    float[] out = new float[p.length];
                    int posCol = cols >= 2 ? cols - 1 : 0;
                    for (int i = 0; i < p.length; i++) {
                        out[i] = p[i][posCol];
                    }
                    return out;
                }
                if (v instanceof float[] p1) {
                    return p1;
                }
            }
            throw new IllegalStateException("ONNX 输出无可用浮点张量");
        }
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = OnnxRankService.class.getClassLoader().getResourceAsStream(cp)) {
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
