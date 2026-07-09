package com.recsys.recengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FtrlHashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 近线增量学习 FTRL-LR 在线打分:读离线 {@code train-ftrl} 写的 {@code ftrl:weights}(稀疏权重),
 * 用共享 {@link FtrlHashing} 契约对 (userId, itemId) 算 {@code p = sigmoid(bias + Σ w[feat])}。
 *
 * <p>是一个近线刷新(小时级)的协同过滤味信号,补 T+1 批模型的时效短板;在融合阶段作为一项加权信号。
 * 权重表按 {@link #REFRESH_MS} 缓存刷新;表缺失/解析失败 → {@link #isReady()}=false,打分返回 0(不影响融合)。
 */
@Component
public class FtrlScorer {

    private static final Logger log = LoggerFactory.getLogger(FtrlScorer.class);
    private static final long REFRESH_MS = 60_000;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Model model;
    private volatile long loadedAt = -1;

    public FtrlScorer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isReady() {
        return model() != null;
    }

    /** 正反馈概率 ∈ (0,1);模型缺失 → 0(调用方据此不加该信号)。 */
    public double score(long userId, long itemId) {
        Model m = model();
        if (m == null) {
            return 0.0;
        }
        double wtx = m.bias;
        for (int f : FtrlHashing.features(userId, itemId)) {
            Double w = m.weights.get(f);
            if (w != null) {
                wtx += w;
            }
        }
        double z = wtx > 35 ? 35 : (wtx < -35 ? -35 : wtx);
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private Model model() {
        long now = System.currentTimeMillis();
        if (model != null && now - loadedAt < REFRESH_MS) {
            return model;
        }
        // 允许并发下多次加载(幂等),结果一致
        Model loaded = load();
        model = loaded;
        loadedAt = now;
        return loaded;
    }

    private Model load() {
        try {
            String json = redis.opsForValue().get(RedisKeys.FTRL_WEIGHTS);
            if (json == null || json.isBlank()) {
                return null;
            }
            JsonNode root = mapper.readTree(json);
            double bias = root.path("bias").asDouble(0.0);
            Map<Integer, Double> w = new HashMap<>();
            JsonNode wn = root.path("w");
            wn.fieldNames().forEachRemaining(k -> w.put(Integer.parseInt(k), wn.get(k).asDouble()));
            log.debug("加载 FTRL 权重:{} 个非零, bias={}", w.size(), bias);
            return new Model(bias, w);
        } catch (Exception e) {
            log.debug("加载 FTRL 权重失败: {}", e.getMessage());
            return null;
        }
    }

    private record Model(double bias, Map<Integer, Double> weights) {
    }
}
