package com.recsys.recengine;

import com.recsys.common.dto.RecommendItem;
import com.recsys.common.dto.RecommendRequest;
import com.recsys.common.dto.RecommendResponse;
import com.recsys.common.rank.RankedItem;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.common.rank.RankService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 推荐编排:召回 → 排序 → 重排 → 截断 → 组装理由。对应 docs/02 §6 时序。
 *
 * M1 说明:在线特征尚稀疏,排序分可能趋于 0,故最终分由"召回分 + 排序分"融合,
 * 保证排序有意义(融合权重见 recsys.fusion.*)。后续上线模型后以排序分为主。
 */
@Service
@EnableConfigurationProperties(RecEngineProperties.class)
public class RecommendOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RecommendOrchestrator.class);

    private final RecallService recallService;
    private final RankService rankService;
    private final ContentService contentService;
    private final RecCache recCache;
    private final RecEngineProperties props;

    public RecommendOrchestrator(RecallService recallService,
                                 RankService rankService,
                                 ContentService contentService,
                                 RecCache recCache,
                                 RecEngineProperties props) {
        this.recallService = recallService;
        this.rankService = rankService;
        this.contentService = contentService;
        this.recCache = recCache;
        this.props = props;
    }

    public RecommendResponse recommend(RecommendRequest req) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        try {
            // 1. 结果缓存
            RecommendResponse cached = recCache.get(req.userId(), req.scene());
            if (cached != null) {
                return cached;
            }

            // 2. 召回(多路合并,带 channel)
            List<RecallItem> recalled = recallService.recall(
                    new RecallContext(req.userId(), Math.max(req.size() * 20, 200), req.scene()));
            if (recalled.isEmpty()) {
                return new RecommendResponse(req.userId(), req.scene(), List.of(), traceId);
            }

            // 记录每个候选的召回信息(分数 + 来源)
            Map<Long, Double> recallScore = new HashMap<>();
            Map<Long, String> recallChannel = new LinkedHashMap<>();
            for (RecallItem r : recalled) {
                recallScore.merge(r.itemId(), r.recallScore(), Math::max);
                recallChannel.putIfAbsent(r.itemId(), r.channel().name());
            }
            List<Long> candidateIds = new ArrayList<>(recallScore.keySet());

            // 3. 排序
            List<RankedItem> ranked = rankService.rank(req.userId(), candidateIds, req.scene());

            // 4. 融合召回分 + 排序分(归一化召回分,避免不同路量纲问题)
            double maxRecall = recallScore.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            if (maxRecall <= 0) {
                maxRecall = 1.0;
            }
            final double maxR = maxRecall;
            List<Scored> fused = new ArrayList<>(ranked.size());
            for (RankedItem ri : ranked) {
                double rNorm = recallScore.getOrDefault(ri.itemId(), 0.0) / maxR;
                double finalScore = props.getFusion().getRecallWeight() * rNorm
                        + props.getFusion().getRankWeight() * ri.score();
                fused.add(new Scored(ri.itemId(), finalScore));
            }
            fused.sort((a, b) -> Double.compare(b.score, a.score));

            // 5. 重排:类目打散 + 截断
            List<RecommendItem> items = rerankAndBuild(fused, recallChannel, req.size());

            RecommendResponse resp = new RecommendResponse(req.userId(), req.scene(), items, traceId);
            recCache.put(req.userId(), req.scene(), resp);
            return resp;
        } catch (Exception e) {
            log.error("推荐失败 user={} trace={}: {}", req.userId(), traceId, e.getMessage(), e);
            // 兜底:返回空而非 500
            return new RecommendResponse(req.userId(), req.scene(), List.of(), traceId);
        }
    }

    /** 类目打散重排 + 组装展示字段。 */
    private List<RecommendItem> rerankAndBuild(List<Scored> fused, Map<Long, String> recallChannel, int size) {
        List<Long> ids = fused.stream().map(s -> s.itemId).toList();
        Map<Long, Item> itemMap = contentService.findByIds(ids);

        int maxSameCat = props.getRerank().getMaxSameCategory();
        Map<String, Integer> catCount = new HashMap<>();
        List<RecommendItem> out = new ArrayList<>(size);

        for (Scored s : fused) {
            if (out.size() >= size) {
                break;
            }
            Item item = itemMap.get(s.itemId);
            String cat = item != null && item.category() != null ? item.category() : "Unknown";
            int seen = catCount.getOrDefault(cat, 0);
            if (seen >= maxSameCat) {
                continue; // 类目超额,打散跳过
            }
            catCount.put(cat, seen + 1);

            String channel = recallChannel.getOrDefault(s.itemId, "unknown");
            out.add(new RecommendItem(
                    s.itemId,
                    round(s.score),
                    List.of(channel),
                    buildReason(item, channel)));
        }

        // 若打散后不足 size(类目高度集中),放宽补齐
        if (out.size() < size) {
            for (Scored s : fused) {
                if (out.size() >= size) {
                    break;
                }
                boolean already = out.stream().anyMatch(o -> o.itemId() == s.itemId);
                if (already) {
                    continue;
                }
                Item item = itemMap.get(s.itemId);
                String channel = recallChannel.getOrDefault(s.itemId, "unknown");
                out.add(new RecommendItem(s.itemId, round(s.score), List.of(channel), buildReason(item, channel)));
            }
        }
        return out;
    }

    private String buildReason(Item item, String channel) {
        String name = item != null ? item.title() : ("物品#");
        return switch (channel) {
            case "VECTOR" -> "与你的兴趣语义相近";
            case "I2I" -> "看过相似内容的人也喜欢";
            case "TAG" -> "来自你偏好的类目";
            case "HOT" -> "热门推荐";
            default -> "为你推荐";
        };
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record Scored(long itemId, double score) {
    }
}
