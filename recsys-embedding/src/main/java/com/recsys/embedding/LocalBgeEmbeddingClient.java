package com.recsys.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.recsys.common.embedding.EmbeddingClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 降级实现:本地 BGE(ONNX,CPU)向量化。断网 / Gemini 超额或失效时的离线兜底,
 * 也是把全量 9742 物品向量灌满(不受外部 API 限额)的正路。
 *
 * <p>仅当 {@code recsys.embedding.provider=local} 时装配。
 *
 * <p>链路:{@link BgeTokenizer}(纯 Java BERT WordPiece)→ onnxruntime 推理(输出
 * last_hidden_state[1,L,H])→ CLS / mean 池化 → L2 归一化(对齐 {@link GeminiEmbeddingClient}
 * 的归一化口径,保证两种 provider 的向量在同一余弦空间可比,换 provider 后全量重灌即可)。
 *
 * <p>模型与词表由 {@code recsys-embedding/train/export_bge_onnx.py} 离线导出到
 * {@code recsys.embedding.local.model-path/vocab-path}(默认 ~/.recsys/models/bge-base-en-v1.5)。
 * 缺文件 → {@link #isReady()} 为 false、{@link #embedText} 抛异常并提示运行导出脚本
 * (上层 query 理解会 catch 后降级 embedding=null;backfill 作业则应显式失败)。
 */
@Component
@ConditionalOnProperty(prefix = "recsys.embedding", name = "provider", havingValue = "local")
public class LocalBgeEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LocalBgeEmbeddingClient.class);

    private final EmbeddingProperties props;

    private OrtEnvironment env;
    private OrtSession session;
    private BgeTokenizer tokenizer;
    private volatile boolean ready = false;

    public LocalBgeEmbeddingClient(EmbeddingProperties props) {
        this.props = props;
    }

    @PostConstruct
    void load() {
        EmbeddingProperties.Local cfg = props.getLocal();
        try {
            Path modelPath = Path.of(cfg.getModelPath());
            Path vocabPath = Path.of(cfg.getVocabPath());
            if (!Files.exists(modelPath) || !Files.exists(vocabPath)) {
                ready = false;
                log.warn("本地 BGE 模型/词表缺失(model={}, vocab={}),向量化不可用。"
                                + "请先运行 recsys-embedding/train/export_bge_onnx.py 导出。",
                        modelPath, vocabPath);
                return;
            }
            tokenizer = new BgeTokenizer(vocabPath, cfg.isLowercase());
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(Files.readAllBytes(modelPath), new OrtSession.SessionOptions());
            ready = true;
            log.info("本地 BGE 向量化加载成功:model={}, 维度={}, 池化={}, maxSeqLen={}",
                    modelPath, dimension(), cfg.getPooling(), cfg.getMaxSeqLen());
        } catch (Throwable t) {
            ready = false;
            log.warn("本地 BGE 加载失败,向量化不可用:{}", t.toString());
        }
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public float[] embedText(String text) {
        if (!ready) {
            throw new IllegalStateException(
                    "本地 BGE 模型未就绪,请先运行 recsys-embedding/train/export_bge_onnx.py 导出模型");
        }
        if (text == null || text.isBlank()) {
            return new float[dimension()];
        }
        EmbeddingProperties.Local cfg = props.getLocal();
        String input = cfg.getQueryInstruction().isEmpty() ? text : cfg.getQueryInstruction() + text;
        try {
            BgeTokenizer.Encoded enc = tokenizer.encode(input, cfg.getMaxSeqLen());
            float[] pooled = infer(enc, cfg.getPooling());
            return VectorMath.l2Normalize(pooled);
        } catch (Exception e) {
            throw new IllegalStateException("本地 BGE 推理失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimension() {
        return props.getDimension();
    }

    @Override
    public String modelName() {
        return "bge-base-en-v1.5-local";
    }

    // ---- 内部 ----

    /** batch=1 推理 → 取 last_hidden_state[1,L,H] → 池化为 [H]。 */
    private float[] infer(BgeTokenizer.Encoded enc, String pooling) throws Exception {
        int len = enc.length();
        long[][] inputIds = {enc.inputIds()};
        long[][] mask = {enc.attentionMask()};
        long[][] types = {enc.tokenTypeIds()};

        Map<String, OnnxTensor> inputs = new HashMap<>(4);
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, inputIds));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, mask));
            inputs.put("token_type_ids", OnnxTensor.createTensor(env, types));
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = extractHidden(result);
                float[][] tokens = hidden[0]; // [L][H]
                return "mean".equalsIgnoreCase(pooling)
                        ? meanPool(tokens, enc.attentionMask(), len)
                        : tokens[0].clone(); // cls:取 [CLS]
            }
        } finally {
            for (OnnxTensor t : inputs.values()) {
                t.close();
            }
        }
    }

    private static float[][][] extractHidden(OrtSession.Result result) throws ai.onnxruntime.OrtException {
        for (Map.Entry<String, OnnxValue> e : result) {
            Object v = e.getValue().getValue();
            if (v instanceof float[][][] h) {
                return h;
            }
        }
        throw new IllegalStateException("ONNX 输出无 last_hidden_state[B,L,H] 浮点张量");
    }

    private static float[] meanPool(float[][] tokens, long[] mask, int len) {
        int h = tokens[0].length;
        float[] out = new float[h];
        double denom = 0;
        for (int i = 0; i < len; i++) {
            if (mask[i] == 0) {
                continue;
            }
            denom += 1;
            for (int d = 0; d < h; d++) {
                out[d] += tokens[i][d];
            }
        }
        if (denom > 0) {
            for (int d = 0; d < h; d++) {
                out[d] /= (float) denom;
            }
        }
        return out;
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
