package com.recsys.recengine.experiment;

import com.recsys.common.recall.RecallChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次请求的分层实验判定结果:每层命中的 variant 名 + 该 variant 的参数。
 * 提供按层语义取值的便捷方法,并能生成写入曝光的紧凑分桶标签。
 */
public final class ExperimentDecision {

    public static final String LAYER_RECALL = "recall";
    public static final String LAYER_RANK = "rank";
    public static final String LAYER_RERANK = "rerank";
    /** 广告分层 A/B 层名(由广告链路单独消费,不进推荐 bucketTag,见 ExperimentService.adVariant)。 */
    public static final String LAYER_AD = "ad";

    /** layer -> 命中的 variant。 */
    private final Map<String, Chosen> byLayer = new LinkedHashMap<>();

    public void put(String layer, String variantName, Map<String, String> params) {
        byLayer.put(layer, new Chosen(variantName, params == null ? Map.of() : params));
    }

    private Map<String, String> params(String layer) {
        Chosen c = byLayer.get(layer);
        return c == null ? Map.of() : c.params();
    }

    /** recall 层启用的召回路;params.channels 缺省/为空 → 返回空列表(表示全开)。 */
    public List<RecallChannel> recallChannels() {
        String csv = params(LAYER_RECALL).get("channels");
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<RecallChannel> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(RecallChannel.valueOf(t.toUpperCase()));
            } catch (IllegalArgumentException ignore) {
                // 配置里写了未知召回路,忽略该项
            }
        }
        return out;
    }

    /** rank 层选中的排序策略(如 v1 / onnx);缺省 null → 编排回落全局配置。 */
    public String rankStrategy() {
        return params(LAYER_RANK).get("strategy");
    }

    /** rerank 层选中的重排策略(diversity / mmr / none);缺省 diversity。 */
    public String rerankStrategy() {
        return params(LAYER_RERANK).getOrDefault("strategy", "diversity");
    }

    /** rerank 层的完整参数(maxSameCategory / lambda 等),交给具体 Reranker 解析。 */
    public Map<String, String> rerankParams() {
        return params(LAYER_RERANK);
    }

    /** 取某层某参数的 double 值,缺省回退 def。 */
    public double doubleParam(String layer, String key, double def) {
        String v = params(layer).get(key);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 紧凑分桶标签,写入 user_behavior.bucket,如 {@code recall:plus;rank:onnx;rerank:mmr}。 */
    public String bucketTag() {
        StringBuilder sb = new StringBuilder();
        for (var e : byLayer.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append(':').append(e.getValue().name());
        }
        return sb.toString();
    }

    private record Chosen(String name, Map<String, String> params) {
    }
}
