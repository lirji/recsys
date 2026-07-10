package com.recsys.ad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.FeatureAssembler;
import com.recsys.rank.SparseFeatureEncoder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 延迟反馈 DFM ad-CVR 在线打分(A6)。加载离线 {@code train_dfm.py}(Chapelle 2014 带删失联合训练)
 * 导出的 {@code model_dfm_cvr.onnx}——**去偏 pCVR**(该点击终将转化的概率),替代复用推荐 MMoE 头的
 * 有删失偏置 pCVR。
 *
 * <p>在线契约与 {@link DeepFmRankService} 完全一致:双输入 {@code dense}[N,5] + {@code sparse}[N,3],
 * **单输出** {@code pcvr}[N,1];稠密用共享 {@link FeatureAssembler} 装配、稀疏用 {@link SparseFeatureEncoder}
 * 编码(与 train_dfm.py 逐位一致),广告经 {@code ad.item_id} 借用真实 item 的特征。故本类是
 * {@code DeepFmRankService} 的镜像,只是**返回 itemId→pCVR 的 map**、单输出。
 *
 * <p>恒装配、{@link #isReady()} 门控:模型/编码器缺失或加载失败 → 未就绪,{@link #pcvr} 返回空 map,
 * 由 {@code SearchAdsOrchestrator} 保留复用头的 pCVR(优雅降级,架构硬约束:模型失败绝不让请求挂掉)。
 * 是否启用由编排层读 {@code recsys.ad.cvr.enabled} 决定;本服务只负责"能不能打分"。
 */
@Service
@EnableConfigurationProperties(AdProperties.class)
public class DfmCvrService {

    private static final Logger log = LoggerFactory.getLogger(DfmCvrService.class);
    private static final String DENSE_INPUT = "dense";
    private static final String SPARSE_INPUT = "sparse";

    private final FeatureService featureService;
    private final ContentService contentService;
    private final AdProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private SparseFeatureEncoder encoder;
    private volatile boolean ready = false;

    public DfmCvrService(FeatureService featureService, ContentService contentService, AdProperties props) {
        this.featureService = featureService;
        this.contentService = contentService;
        this.props = props;
    }

    @PostConstruct
    void load() {
        try {
            AdProperties.Cvr cfg = props.getCvr();
            encoder = SparseFeatureEncoder.load(cfg.getSchemaPath(), cfg.getVocabPath());
            byte[] model = readModel(cfg.getModelPath());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model, new OrtSession.SessionOptions());
            ready = true;
            log.info("DFM ad-CVR 模型加载成功:{};输入={};稠密维度={},类目 vocab={}",
                    cfg.getModelPath(), session.getInputNames(),
                    FeatureAssembler.dim(), encoder.categoryVocabSize());
        } catch (Throwable t) {
            ready = false;
            log.warn("DFM ad-CVR 模型加载失败,pCVR 将退回复用 MMoE 头:{}", t.toString());
        }
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 批量算候选广告(经 item)的去偏 pCVR∈[0,1]。未就绪/异常 → 空 map(调用方保留复用头 pCVR)。
     *
     * @param userId  用户
     * @param itemIds 候选广告关联的 item id(已去重)
     * @return itemId → pCVR
     */
    public Map<Long, Double> pcvr(long userId, List<Long> itemIds) {
        if (!ready || itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, Item> items = contentService.findByIds(itemIds);
            Map<String, Double> userFeat = featureService.userFeatures(userId);
            Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(itemIds);

            int n = itemIds.size();
            List<String> denseOrder = encoder.denseOrder();
            int dim = denseOrder.size();
            float[][] dense = new float[n][dim];
            long[][] sparse = new long[n][3];
            for (int i = 0; i < n; i++) {
                long itemId = itemIds.get(i);
                Item it = items.get(itemId);
                String cat = it == null ? null : it.category();
                double[] f = FeatureAssembler.assemble(userFeat, itemFeats.getOrDefault(itemId, Map.of()), cat, denseOrder);
                for (int d = 0; d < dim; d++) {
                    dense[i][d] = (float) f[d];
                }
                sparse[i] = encoder.encode(userId, itemId, cat);
            }

            float[] pcvr = predict(dense, sparse);
            Map<Long, Double> result = new HashMap<>(n);
            for (int i = 0; i < n; i++) {
                result.put(itemIds.get(i), (double) pcvr[i]);
            }
            return result;
        } catch (Exception e) {
            log.warn("DFM ad-CVR 打分异常,本次退回空(调用方保留复用头 pCVR):{}", e.getMessage());
            return Map.of();
        }
    }

    /** 双输入批量推理,返回 pCVR;DFM CVR 头输出形如 [N,1] 或 [N]。 */
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
                throw new IllegalStateException("DFM CVR 输出无可用浮点张量");
            }
        }
    }

    private static byte[] readModel(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = DfmCvrService.class.getClassLoader().getResourceAsStream(cp)) {
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
