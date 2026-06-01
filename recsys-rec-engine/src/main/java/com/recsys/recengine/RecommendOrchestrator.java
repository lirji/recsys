package com.recsys.recengine;

import com.recsys.common.dto.RecommendItem;
import com.recsys.common.dto.RecommendRequest;
import com.recsys.common.dto.RecommendResponse;
import com.recsys.common.rank.RankedItem;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.RankRouter;
import com.recsys.recengine.coldstart.ColdStartDetector;
import com.recsys.recengine.experiment.ExperimentDecision;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.experiment.ExposureLogger;
import com.recsys.recengine.rerank.RerankCandidate;
import com.recsys.recengine.rerank.RerankInput;
import com.recsys.recengine.rerank.RerankRouter;
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
 * 推荐编排:召回 → 排序 → 融合 → 重排 → 曝光埋点。对应 docs/02 §6 时序。
 *
 * <p>分层 A/B 是骨架:{@link ExperimentService} 为每个用户在 recall/rank/rerank 三层独立分桶,
 * 决定本次启用哪些召回路、用哪个排序策略、用哪个重排策略;{@link ColdStartDetector} 命中时
 * 覆盖 recall/rerank 两层为探索配置。每次返回经 {@link ExposureLogger} 带分桶落库,供算分桶 CTR。
 *
 * <p>M1 说明:在线特征稀疏,排序分可能趋 0,最终分由"召回分 + 排序分"融合(权重见融合配置)。
 */
@Service
@EnableConfigurationProperties(RecEngineProperties.class)
public class RecommendOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RecommendOrchestrator.class);

    private final RecallService recallService;
    private final RankRouter rankRouter;
    private final ContentService contentService;
    private final RecCache recCache;
    private final RecEngineProperties props;
    private final ExperimentService experimentService;
    private final RerankRouter rerankRouter;
    private final ExposureLogger exposureLogger;
    private final ColdStartDetector coldStartDetector;

    public RecommendOrchestrator(RecallService recallService,
                                 RankRouter rankRouter,
                                 ContentService contentService,
                                 RecCache recCache,
                                 RecEngineProperties props,
                                 ExperimentService experimentService,
                                 RerankRouter rerankRouter,
                                 ExposureLogger exposureLogger,
                                 ColdStartDetector coldStartDetector) {
        this.recallService = recallService;
        this.rankRouter = rankRouter;
        this.contentService = contentService;
        this.recCache = recCache;
        this.props = props;
        this.experimentService = experimentService;
        this.rerankRouter = rerankRouter;
        this.exposureLogger = exposureLogger;
        this.coldStartDetector = coldStartDetector;
    }

    public RecommendResponse recommend(RecommendRequest req) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        try {
            // 1. 结果缓存
            RecommendResponse cached = recCache.get(req.userId(), req.scene());
            if (cached != null) {
                return cached;
            }

            // 2. 冷启动判定 + 分层实验分桶
            boolean cold = coldStartDetector.isCold(req.userId());
            ExperimentDecision decision = experimentService.assign(req.userId(), req.scene());

            // 冷启动覆盖 recall 层(探索路)与 bucket 标签;否则用实验的 recall 分桶
            List<RecallChannel> channels = cold
                    ? List.of(RecallChannel.COLD, RecallChannel.HOT, RecallChannel.TAG)
                    : decision.recallChannels();
            String bucketTag = cold
                    ? (decision.bucketTag().isEmpty() ? "cold" : "cold;" + decision.bucketTag())
                    : decision.bucketTag();

            // 3. 召回(按本次启用的路)
            List<RecallItem> recalled = recallService.recall(new RecallContext(
                    req.userId(), Math.max(req.size() * 20, 200), req.scene(), channels, Map.of()));
            if (recalled.isEmpty()) {
                return new RecommendResponse(req.userId(), req.scene(), List.of(), traceId);
            }

            Map<Long, Double> recallScore = new HashMap<>();
            Map<Long, String> recallChannel = new LinkedHashMap<>();
            for (RecallItem r : recalled) {
                recallScore.merge(r.itemId(), r.recallScore(), Math::max);
                recallChannel.putIfAbsent(r.itemId(), r.channel().name());
            }
            List<Long> candidateIds = new ArrayList<>(recallScore.keySet());

            // 4. 排序(按实验选中的策略;null 回落全局配置)
            List<RankedItem> ranked = rankRouter.rank(
                    req.userId(), candidateIds, req.scene(), decision.rankStrategy());

            // 5. 融合召回分 + 排序分(归一化召回分,权重可由 rank 层实验覆盖)
            double maxRecall = recallScore.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            final double maxR = maxRecall <= 0 ? 1.0 : maxRecall;
            double recallWeight = decision.doubleParam(
                    ExperimentDecision.LAYER_RANK, "recallWeight", props.getFusion().getRecallWeight());
            double rankWeight = decision.doubleParam(
                    ExperimentDecision.LAYER_RANK, "rankWeight", props.getFusion().getRankWeight());
            List<RerankCandidate> fused = new ArrayList<>(ranked.size());
            for (RankedItem ri : ranked) {
                double rNorm = recallScore.getOrDefault(ri.itemId(), 0.0) / maxR;
                fused.add(new RerankCandidate(ri.itemId(), recallWeight * rNorm + rankWeight * ri.score()));
            }
            fused.sort((a, b) -> Double.compare(b.score(), a.score()));

            // 6. 重排(冷启动强制强多样性;否则按实验选中的策略)
            Map<String, String> rerankParams = new HashMap<>(decision.rerankParams());
            String rerankStrategy;
            if (cold) {
                rerankStrategy = "diversity";
                rerankParams.put("maxSameCategory",
                        String.valueOf(props.getColdStart().getRerankMaxSameCategory()));
            } else {
                rerankStrategy = decision.rerankStrategy();
            }
            List<Long> fusedIds = fused.stream().map(RerankCandidate::itemId).toList();
            Map<Long, Item> itemMap = contentService.findByIds(fusedIds);
            List<RecommendItem> items = rerankRouter.rerank(
                    rerankStrategy, fused, new RerankInput(req.size(), recallChannel, itemMap, rerankParams));

            RecommendResponse resp = new RecommendResponse(req.userId(), req.scene(), items, traceId);

            // 7. 曝光埋点(异步,带分桶)+ 结果缓存
            exposureLogger.log(req.userId(), req.scene(), bucketTag, items);
            recCache.put(req.userId(), req.scene(), resp);
            log.debug("推荐完成 user={} cold={} bucket=[{}] trace={} items={}",
                    req.userId(), cold, bucketTag, traceId, items.size());
            return resp;
        } catch (Exception e) {
            log.error("推荐失败 user={} trace={}: {}", req.userId(), traceId, e.getMessage(), e);
            // 兜底:返回空而非 500
            return new RecommendResponse(req.userId(), req.scene(), List.of(), traceId);
        }
    }
}
