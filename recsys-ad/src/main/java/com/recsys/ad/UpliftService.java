package com.recsys.ad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.AdCandidate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 双头 TARNet/IPW uplift 在线服务：同一候选输出 mu0、mu1，按 adId 返回因果增量估计。 */
@Service
public class UpliftService {

    private static final Logger log = LoggerFactory.getLogger(UpliftService.class);
    private final AdProperties props;
    private final RelevanceGate relevanceGate;
    private OrtEnvironment env;
    private OrtSession session;
    private int userBuckets, itemBuckets, adBuckets, advertiserBuckets;
    private String modelVersion;
    private volatile boolean ready;

    public UpliftService(AdProperties props, RelevanceGate relevanceGate) {
        this.props = props;
        this.relevanceGate = relevanceGate;
    }

    @PostConstruct
    void load() {
        try {
            JsonNode schema = new ObjectMapper().readTree(read(props.getUplift().getSchemaPath()));
            if (!"tarnet_ipw".equalsIgnoreCase(schema.path("algorithm").asText())) {
                throw new IllegalStateException("uplift schema.algorithm 非 tarnet_ipw");
            }
            modelVersion = requiredText(schema, "model_version");
            userBuckets = requiredPositive(schema, "user_buckets");
            itemBuckets = requiredPositive(schema, "item_buckets");
            adBuckets = requiredPositive(schema, "ad_buckets");
            advertiserBuckets = requiredPositive(schema, "advertiser_buckets");
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(read(props.getUplift().getModelPath()), new OrtSession.SessionOptions());
            if (!session.getInputNames().containsAll(List.of("dense", "sparse"))) {
                throw new IllegalStateException("uplift ONNX 输入必须是 dense+sparse");
            }
            ready = true;
            log.info("uplift 模型加载成功:version={}", modelVersion);
        } catch (Throwable t) {
            ready = false;
            log.warn("uplift 模型加载失败,增量竞价将回退基线: {}", t.toString());
        }
    }

    public boolean isReady() { return ready; }

    public Map<Long, UpliftEstimate> estimate(long userId, List<AdCandidate> candidates,
                                               Map<Long, Double> pctrByItem,
                                               Map<Long, Double> pcvrByItem) {
        if (!props.getUplift().isScoringEnabled() || !ready || candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        try {
            float[][] dense = new float[candidates.size()][5];
            long[][] sparse = new long[candidates.size()][4];
            for (int i = 0; i < candidates.size(); i++) {
                AdCandidate c = candidates.get(i);
                dense[i][0] = (float) prob(pctrByItem.getOrDefault(c.itemId(), 0.0));
                dense[i][1] = (float) prob(pcvrByItem.getOrDefault(c.itemId(), 0.0));
                dense[i][2] = finiteFloat(c.quality());
                dense[i][3] = finiteFloat(relevanceGate.relevance(c));
                dense[i][4] = (float) Math.log1p(Math.max(0.0, c.bid()));
                sparse[i][0] = Math.floorMod(userId, userBuckets);
                sparse[i][1] = Math.floorMod(c.itemId(), itemBuckets);
                sparse[i][2] = Math.floorMod(c.adId(), adBuckets);
                sparse[i][3] = Math.floorMod(c.advertiserId(), advertiserBuckets);
            }
            float[][][] potential = predict(dense, sparse);
            float[][] mu0 = potential[0];
            float[][] mu1 = potential[1];
            Map<Long, UpliftEstimate> out = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                double zero = prob(mu0[i][0]);
                double one = prob(mu1[i][0]);
                out.put(candidates.get(i).adId(), new UpliftEstimate(zero, one, one - zero, modelVersion));
            }
            return Map.copyOf(out);
        } catch (Exception e) {
            log.warn("uplift 推理失败,本次逐候选回退基线: {}", e.getMessage());
            return Map.of();
        }
    }

    private float[][][] predict(float[][] dense, long[][] sparse) throws Exception {
        try (OnnxTensor d = OnnxTensor.createTensor(env, dense);
             OnnxTensor s = OnnxTensor.createTensor(env, sparse)) {
            Map<String, OnnxTensor> inputs = Map.of("dense", d, "sparse", s);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue zero = result.get("mu0").orElseThrow(() -> new IllegalStateException("uplift 缺输出 mu0"));
                OnnxValue one = result.get("mu1").orElseThrow(() -> new IllegalStateException("uplift 缺输出 mu1"));
                if (zero.getValue() instanceof float[][] mu0 && one.getValue() instanceof float[][] mu1) {
                    return new float[][][]{mu0, mu1};
                }
                throw new IllegalStateException("uplift 输出不是 float[N,1]");
            }
        }
    }

    private static double prob(double value) { return Math.max(0.0, Math.min(1.0, value)); }
    private static float finiteFloat(double value) { return Double.isFinite(value) ? (float) value : 0f; }
    private static int requiredPositive(JsonNode schema, String name) {
        int value = schema.path(name).asInt();
        if (value <= 0) throw new IllegalStateException("uplift schema 缺/非法 " + name);
        return value;
    }
    private static String requiredText(JsonNode schema, String name) {
        String value = schema.path(name).asText();
        if (value.isBlank()) throw new IllegalStateException("uplift schema 缺 " + name);
        return value;
    }
    private static byte[] read(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            try (InputStream in = UpliftService.class.getClassLoader().getResourceAsStream(path.substring(10))) {
                if (in == null) throw new IllegalStateException("classpath 未找到 " + path);
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(Path.of(path));
    }
    @PreDestroy void close() { try { if (session != null) session.close(); } catch (Exception ignore) { } }
}
