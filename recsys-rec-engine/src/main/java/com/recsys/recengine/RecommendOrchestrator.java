package com.recsys.recengine;

import com.recsys.common.dto.RecommendExplain;
import com.recsys.common.dto.RecommendItem;
import com.recsys.common.dto.RecommendRequest;
import com.recsys.common.dto.RecommendResponse;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.rank.RankedItem;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallExplain;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.recall.RecallService;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.content.Item;
import com.recsys.rank.PreRankService;
import com.recsys.rank.RankRouter;
import com.recsys.recengine.coldstart.ColdStartDetector;
import com.recsys.recengine.experiment.ExperimentDecision;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.experiment.ExposureLogger;
import com.recsys.recengine.filter.SeenItemsFilter;
import com.recsys.recengine.rerank.RerankCandidate;
import com.recsys.recengine.rerank.RerankInput;
import com.recsys.recengine.rerank.RerankRouter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final PreRankService preRankService;
    private final ContentGateway contentGateway;
    private final RecCache recCache;
    private final RecEngineProperties props;
    private final ExperimentService experimentService;
    private final RerankRouter rerankRouter;
    private final ExposureLogger exposureLogger;
    private final ColdStartDetector coldStartDetector;
    private final SeenItemsFilter seenItemsFilter;
    private final QueryUnderstandingService queryService;
    private final PersonalizationScorer personalizationScorer;
    private final RecScoreCalibrator recScoreCalibrator;
    private final FtrlScorer ftrlScorer;
    private final BanditScorer banditScorer;
    private final DynamicTuningService tuning;
    private final MeterRegistry meterRegistry;
    private final ShadowRankRunner shadowRankRunner;

    public RecommendOrchestrator(RecallService recallService,
                                 RankRouter rankRouter,
                                 PreRankService preRankService,
                                 ShadowRankRunner shadowRankRunner,
                                 ContentGateway contentGateway,
                                 RecCache recCache,
                                 RecEngineProperties props,
                                 ExperimentService experimentService,
                                 RerankRouter rerankRouter,
                                 ExposureLogger exposureLogger,
                                 ColdStartDetector coldStartDetector,
                                 SeenItemsFilter seenItemsFilter,
                                 QueryUnderstandingService queryService,
                                 PersonalizationScorer personalizationScorer,
                                 RecScoreCalibrator recScoreCalibrator,
                                 FtrlScorer ftrlScorer,
                                 BanditScorer banditScorer,
                                 DynamicTuningService tuning,
                                 MeterRegistry meterRegistry) {
        this.recallService = recallService;
        this.rankRouter = rankRouter;
        this.preRankService = preRankService;
        this.shadowRankRunner = shadowRankRunner;
        this.contentGateway = contentGateway;
        this.recCache = recCache;
        this.props = props;
        this.experimentService = experimentService;
        this.rerankRouter = rerankRouter;
        this.exposureLogger = exposureLogger;
        this.coldStartDetector = coldStartDetector;
        this.seenItemsFilter = seenItemsFilter;
        this.queryService = queryService;
        this.personalizationScorer = personalizationScorer;
        this.recScoreCalibrator = recScoreCalibrator;
        this.ftrlScorer = ftrlScorer;
        this.banditScorer = banditScorer;
        this.tuning = tuning;
        this.meterRegistry = meterRegistry;
    }

    /** 兼容入口:不带 explain。既有调用点(FeedOrchestrator 等)零改动。 */
    public RecommendResponse recommend(RecommendRequest req) {
        return recommend(req, false);
    }

    /**
     * 推荐编排主流程。{@code explain=true} 时额外收集逐阶段候选数 / 去重前每路原始召回数 /
     * 去重后每路贡献 / 每条打分分解,填进 {@link RecommendExplain} 随响应返回;并**旁路结果缓存**
     * (读写都跳过),避免 explain 载荷污染普通请求缓存。热路径(explain=false)零额外分配。
     */
    public RecommendResponse recommend(RecommendRequest req, boolean explain) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        // explain 收集器:非 explain 时全为 null,后续均 if(explain) 守卫,热路径无额外开销。
        RecallExplain recallExplain = explain ? new RecallExplain() : null;
        List<RecommendExplain.Stage> explainStages = explain ? new ArrayList<>() : null;
        Map<Long, RecommendExplain.ScoreBreakdown> explainScores = explain ? new LinkedHashMap<>() : null;
        // 观测:整条编排耗时(P99 是核心 SLA 指标),tag 区分排序策略/冷启动/结果状态
        Timer.Sample sample = Timer.start(meterRegistry);
        String rankTag = "na";
        boolean coldTag = false;
        String outcome = "ok";
        try {
            // 结构化日志:把业务上下文放 MDC(traceId/spanId 由 micrometer-tracing 自动注入);finally 清理
            org.slf4j.MDC.put("userId", String.valueOf(req.userId()));
            org.slf4j.MDC.put("scene", req.scene() == null ? "" : req.scene());
            // 1. 结果缓存(搜索请求按 userId+scene 缓存会让不同 query 串味,故 query 驱动时跳过缓存;
            //    explain 请求也旁路缓存 —— 既不命中普通缓存、也不把 explain 载荷写回污染普通请求)
            if (!req.hasQuery() && !explain) {
                RecommendResponse cached = recCache.get(req.userId(), req.scene());
                if (cached != null) {
                    meterRegistry.counter("recsys.recommend.cache", "result", "hit").increment();
                    outcome = "cache";
                    return cached;
                }
                meterRegistry.counter("recsys.recommend.cache", "result", "miss").increment();
            }

            // 2. 冷启动判定 + 分层实验分桶
            boolean cold = coldStartDetector.isCold(req.userId());
            ExperimentDecision decision = experimentService.assign(req.userId(), req.scene());
            // 搜索(query 驱动)场景:query 即明确意图,冷用户也不该被冷启动覆盖冲淡 —— 走 query 主导链路。
            boolean queryDriven = req.hasQuery();
            boolean coldOverride = cold && !(queryDriven && props.getSearch().isBypassColdStart());
            coldTag = coldOverride;
            // 实验未指定排序策略时,编排回落全局配置,这里以 default 标记(实际策略由 RankRouter 决定)
            rankTag = decision.rankStrategy() != null ? decision.rankStrategy() : "default";

            // 冷启动覆盖 recall 层(探索路)与 bucket 标签;否则用实验的 recall 分桶
            List<RecallChannel> channels = coldOverride
                    ? List.of(RecallChannel.COLD, RecallChannel.HOT, RecallChannel.TAG)
                    : decision.recallChannels();
            String bucketTag = coldOverride
                    ? (decision.bucketTag().isEmpty() ? "cold" : "cold;" + decision.bucketTag())
                    : decision.bucketTag();

            // 2b. Query 理解:有 query 则解析,把归一化串喂 SEMANTIC、意图类目喂 TAG,
            // 并确保这两路在本次启用(即便实验把召回路限定成了别的子集)。
            Map<String, String> recallParams = Map.of();
            if (req.hasQuery()) {
                StructuredQuery sq = queryService.parse(req.query(), req.userId());
                recallParams = buildRecallParams(sq);
                channels = withQueryChannels(channels);
                log.debug("query 驱动召回 user={} q=[{}] intents={} channels={}",
                        req.userId(), sq.normalized(), sq.intents(), channels);
            }

            // 3. 召回(按本次启用的路)。explain 时挂 sink 收去重前每路原始召回数(null 则召回热路径零改动)。
            List<RecallItem> recalled = recallService.recall(new RecallContext(
                    req.userId(), Math.max(req.size() * 20, 200), req.scene(), channels, recallParams, recallExplain));
            if (recalled.isEmpty()) {
                meterRegistry.counter("recsys.recommend.empty", "stage", "recall").increment();
                outcome = "empty";
                return new RecommendResponse(req.userId(), req.scene(), List.of(), traceId);
            }

            // 已看过滤:剔除用户已正反馈过的物品。冷启动用户行为本就 < 阈值,过滤是空操作。
            Set<Long> seen = props.getFilter().isSeenEnabled()
                    ? seenItemsFilter.seenItems(req.userId()) : Set.of();

            Map<Long, Double> recallScore = new HashMap<>();
            Map<Long, List<String>> recallChannel = new LinkedHashMap<>();
            for (RecallItem r : recalled) {
                if (seen.contains(r.itemId())) {
                    continue;
                }
                recallScore.merge(r.itemId(), r.recallScore(), Math::max);
                mergeChannels(recallChannel, r);
            }
            // 极端情况:用户几乎看遍召回池,过滤后空了 —— 放弃过滤回落原始召回,
            // 宁可推少量重复也不返回空(已看过滤是质量优化,不该把可用结果清零)。
            if (recallScore.isEmpty()) {
                meterRegistry.counter("recsys.recommend.seen_cleared").increment();
                log.debug("用户 {} 召回结果被已看过滤清空(recalled={}, seen={}),本次跳过过滤",
                        req.userId(), recalled.size(), seen.size());
                for (RecallItem r : recalled) {
                    recallScore.merge(r.itemId(), r.recallScore(), Math::max);
                    mergeChannels(recallChannel, r);
                }
            }
            List<Long> candidateIds = new ArrayList<>(recallScore.keySet());

            // 3b. 粗排:候选多时用轻量打分砍到 top-K,再进精排(补齐 召回→粗排→精排 漏斗;候选少则原样放行)。
            int preRankIn = candidateIds.size();
            candidateIds = preRankService.preRank(req.userId(), candidateIds, recallScore, req.scene());
            if (candidateIds.size() < preRankIn) {
                meterRegistry.counter("recsys.prerank", "result", "applied").increment();
            }

            // 4. 排序(按实验选中的策略;null 回落全局配置)
            List<RankedItem> ranked = rankRouter.rank(
                    req.userId(), candidateIds, req.scene(), decision.rankStrategy());
            // 4b. 影子流量(P5):按比例异步用影子策略再排一次,只对比打点、不影响返回(零风险灰度评估)
            shadowRankRunner.maybeShadow(req.userId(), candidateIds, req.scene(),
                    decision.rankStrategy(), ranked);

            // 5. 融合召回分 + 排序分(归一化召回分,权重可由 rank 层实验覆盖)。
            // 召回分已在 MultiChannelRecallService 内按路归一化到 [0,1],这里再做一次全局归一化兜底。
            double maxRecall = recallScore.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            final double maxR = maxRecall <= 0 ? 1.0 : maxRecall;
            // 搜索场景用 search.* 一组权重/boost(query 相关性主导),否则用默认 fusion.*。
            // 实验参数仍可覆盖权重(doubleParam 的默认值按场景取)。
            RecEngineProperties.Search search = props.getSearch();
            double defRecallW = queryDriven ? search.getRecallWeight() : props.getFusion().getRecallWeight();
            double defRankW = queryDriven ? search.getRankWeight() : props.getFusion().getRankWeight();
            double recallWeight = decision.doubleParam(ExperimentDecision.LAYER_RANK, "recallWeight", defRecallW);
            double rankWeight = decision.doubleParam(ExperimentDecision.LAYER_RANK, "rankWeight", defRankW);
            Map<String, Double> boostMap = queryDriven
                    ? search.getChannelBoost() : props.getFusion().getChannelBoost();
            // 搜索温和个性化:用 BGE user_embedding 对候选算余弦亲和度,作乘性加成微调次序
            // (相关性仍主导;冷用户无向量则空表 = 无个性化)。权重 0 关闭。仅 query 驱动场景生效。
            double persW = search.getPersonalizationWeight();
            Map<Long, Double> affinity = (queryDriven && persW > 0)
                    ? personalizationScorer.affinity(req.userId(), candidateIds) : Map.of();
            // 召回路融合加权:按物品命中的召回路取最大 boost,乘到融合分上。
            // 默认让 TAG(含实时类目偏好 rt:user)不被 HOT/CF 热度压过;搜索场景则抬升 SEMANTIC/意图 TAG。
            // 流行度去偏:再乘 1/(1+item_pop_norm)^beta,系统性压低高热度、相对抬升长尾/语义(替代 channel-boost 打补丁)。
            // S5 热更新:融合旋钮读 Redis 覆盖层(recsys:tuning),缺则回退静态 yml —— 改权重免重启。
            RecEngineProperties.Fusion.PopDebias popCfg = props.getFusion().getPopDebias();
            double popBeta = tuning.getDouble("fusion.pop-debias.beta", popCfg.getBeta());
            boolean popDebias = tuning.getBoolean("fusion.pop-debias.enabled", popCfg.isEnabled()) && popBeta > 0;
            // 精排分数校准:把 rank 原始分映射成可比概率再进融合(isotonic 单调,不改单策略内排序,
            // 但让 recallWeight·rNorm + rankWeight·score 两项量纲一致;无表则原样返回,安全)。
            RecEngineProperties.Fusion.Calibration calibCfg = props.getFusion().getCalibration();
            boolean calibrate = tuning.getBoolean("fusion.calibration.enabled", calibCfg.isEnabled());
            // 近线增量学习 FTRL 信号:模型就绪时,融合分再加 ftrlWeight·pFtrl(user,item)(协同过滤味的近线学习分)。
            RecEngineProperties.Fusion.Ftrl ftrlCfg = props.getFusion().getFtrl();
            double ftrlWeight = tuning.getDouble("fusion.ftrl.weight", ftrlCfg.getWeight());
            boolean useFtrl = tuning.getBoolean("fusion.ftrl.enabled", ftrlCfg.isEnabled())
                    && ftrlWeight != 0 && ftrlScorer.isReady();
            // R7 全量 contextual bandit(LinUCB/Thompson):融合分再加 banditWeight·探索加成(用排序特征空间
            // 不确定性驱动欠曝上下文探索;模型缺失打分 0,默认关)。Thompson 每请求采一次 θ̃(整批候选共用)。
            RecEngineProperties.Fusion.Bandit banditCfg = props.getFusion().getBandit();
            double banditWeight = tuning.getDouble("fusion.bandit.weight", banditCfg.getWeight());
            double banditAlpha = tuning.getDouble("fusion.bandit.alpha", banditCfg.getAlpha());
            boolean useBandit = tuning.getBoolean("fusion.bandit.enabled", banditCfg.isEnabled())
                    && banditWeight != 0 && banditScorer.isReady();
            BanditScorer.Session banditSession = useBandit
                    ? banditScorer.forRequest(banditCfg.getMode(), banditAlpha) : null;
            List<RerankCandidate> fused = new ArrayList<>(ranked.size());
            for (RankedItem ri : ranked) {
                double rNorm = recallScore.getOrDefault(ri.itemId(), 0.0) / maxR;
                double rankScore = calibrate
                        ? recScoreCalibrator.calibrate(ri.score(), calibCfg.getModel()) : ri.score();
                // ftrl/bandit 抽成局部量(等价重构:未启用即 0),便于 explain 分项暴露、且不改数学。
                double ftrlTerm = useFtrl ? ftrlWeight * ftrlScorer.score(req.userId(), ri.itemId()) : 0.0;
                double banditTerm = useBandit ? banditWeight * banditSession.score(ri.featureSnapshot()) : 0.0;
                double base = recallWeight * rNorm + rankWeight * rankScore + ftrlTerm + banditTerm;
                double boost = RecEngineProperties.Fusion.boostFor(recallChannel.get(ri.itemId()), boostMap);
                double persBoost = 1.0 + persW * Math.max(0.0, affinity.getOrDefault(ri.itemId(), 0.0));
                double debias = 1.0;
                if (popDebias) {
                    double popNorm = ri.featureSnapshot() == null ? 0.0
                            : ri.featureSnapshot().getOrDefault("item_pop_norm", 0.0);
                    debias = 1.0 / Math.pow(1.0 + Math.max(0.0, popNorm), popBeta);
                }
                double finalScore = base * boost * persBoost * debias;
                if (explain) {
                    explainScores.put(ri.itemId(), new RecommendExplain.ScoreBreakdown(
                            rNorm, rankScore, ftrlTerm, banditTerm, base, boost, persBoost, debias, finalScore));
                }
                fused.add(new RerankCandidate(ri.itemId(), finalScore));
            }
            fused.sort((a, b) -> Double.compare(b.score(), a.score()));

            // 6. 重排(冷启动强制强多样性;否则按实验选中的策略)
            Map<String, String> rerankParams = new HashMap<>(decision.rerankParams());
            String rerankStrategy;
            if (coldOverride) {
                rerankStrategy = "diversity";
                rerankParams.put("maxSameCategory",
                        String.valueOf(props.getColdStart().getRerankMaxSameCategory()));
            } else {
                rerankStrategy = decision.rerankStrategy();
            }
            List<Long> fusedIds = fused.stream().map(RerankCandidate::itemId).toList();
            Map<Long, Item> itemMap = contentGateway.findByIds(fusedIds);
            List<RecommendItem> items = rerankRouter.rerank(
                    rerankStrategy, fused, new RerankInput(req.size(), recallChannel, itemMap, rerankParams));

            // explain:用上面本已算出的真实局部量组装逐阶段计数 / 去重前每路原始召回 / 去重后每路贡献 / 打分分解。
            RecommendExplain explainObj = explain
                    ? buildExplain(explainStages, recallExplain, recallChannel, explainScores,
                            recalled.size(), recallScore.size(), preRankIn, candidateIds.size(),
                            ranked.size(), fused.size(), items.size())
                    : null;
            RecommendResponse resp = new RecommendResponse(req.userId(), req.scene(), items, traceId, explainObj);

            // 7. 曝光埋点(异步,带分桶)+ 结果缓存(explain 请求旁路缓存写,避免污染普通请求)
            exposureLogger.log(req.userId(), req.scene(), bucketTag, items);
            if (!explain) {
                recCache.put(req.userId(), req.scene(), resp);
            }
            log.debug("推荐完成 user={} cold={} bucket=[{}] trace={} items={}",
                    req.userId(), cold, bucketTag, traceId, items.size());
            return resp;
        } catch (Exception e) {
            outcome = "error";
            log.error("推荐失败 user={} trace={}: {}", req.userId(), traceId, e.getMessage(), e);
            // 兜底:返回空而非 500
            return new RecommendResponse(req.userId(), req.scene(), List.of(), traceId);
        } finally {
            sample.stop(Timer.builder("recsys.recommend.duration")
                    .description("推荐编排端到端耗时")
                    .tag("rank", rankTag)
                    .tag("cold", String.valueOf(coldTag))
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
            org.slf4j.MDC.remove("userId");
            org.slf4j.MDC.remove("scene");
        }
    }

    /**
     * 组装 explain:逐阶段候选 in/out、去重前每路原始召回数(来自召回 sink)、去重后每路对候选池的贡献数、
     * 打分分解。全部用编排主流程本已算出的真实局部量,无任何虚构。
     */
    private static RecommendExplain buildExplain(
            List<RecommendExplain.Stage> stages,
            RecallExplain recallExplain,
            Map<Long, List<String>> recallChannel,
            Map<Long, RecommendExplain.ScoreBreakdown> scores,
            int recalledSize, int filteredSize, int preRankIn, int preRankOut,
            int rankedSize, int fusedSize, int finalSize) {
        stages.add(new RecommendExplain.Stage("recall", recalledSize, recalledSize));
        stages.add(new RecommendExplain.Stage("filter", recalledSize, filteredSize));
        stages.add(new RecommendExplain.Stage("preRank", preRankIn, preRankOut));
        stages.add(new RecommendExplain.Stage("rank", preRankOut, rankedSize));
        stages.add(new RecommendExplain.Stage("fusion", rankedSize, fusedSize));
        stages.add(new RecommendExplain.Stage("rerank", fusedSize, finalSize));

        // 去重前每路原始召回数(sink 真值)
        List<RecommendExplain.ChannelRecall> channelRecall = new ArrayList<>();
        if (recallExplain != null) {
            for (var e : recallExplain.perChannel().entrySet()) {
                channelRecall.add(new RecommendExplain.ChannelRecall(e.getKey().name(), e.getValue()));
            }
        }
        // 去重后每路对候选池的贡献数(同一 item 命中多路则各路各计一次)
        Map<String, Integer> contrib = new LinkedHashMap<>();
        for (List<String> chs : recallChannel.values()) {
            for (String ch : chs) {
                contrib.merge(ch, 1, Integer::sum);
            }
        }
        List<RecommendExplain.ChannelContribution> channelContribution = new ArrayList<>();
        for (var e : contrib.entrySet()) {
            channelContribution.add(new RecommendExplain.ChannelContribution(e.getKey(), e.getValue()));
        }
        return new RecommendExplain(stages, channelRecall, channelContribution, scores);
    }

    /** 召回路里属于 query 驱动的两路:SEMANTIC 用归一化 query,TAG 叠加意图类目。 */
    private static final List<RecallChannel> QUERY_CHANNELS =
            List.of(RecallChannel.SEMANTIC, RecallChannel.LEXICAL, RecallChannel.TAG);

    /**
     * 把结构化 query 转成召回参数:
     * {@code query} = 归一化串(SEMANTIC 读),{@code intentCategories} = "类目:分,..."(TAG 读)。
     */
    private static Map<String, String> buildRecallParams(StructuredQuery sq) {
        Map<String, String> params = new HashMap<>();
        // 搜索:词法(LEXICAL/BM25)+ 向量(SEMANTIC)等多路按 RRF 融合(混合检索)
        params.put("recall-fusion", "rrf");
        if (sq.normalized() != null && !sq.normalized().isBlank()) {
            params.put("query", sq.normalized());
        }
        if (!sq.intents().isEmpty()) {
            params.put("intentCategories", sq.intents().stream()
                    .map(cs -> cs.category() + ":" + cs.score())
                    .collect(Collectors.joining(",")));
        }
        return params;
    }

    /**
     * 确保 query 驱动的召回路在本次启用。channels 为空(全开)时无需改动;
     * 非空(实验限定了子集)时把 SEMANTIC/TAG 并入,使 query 信号不被实验子集挡掉。
     */
    private static List<RecallChannel> withQueryChannels(List<RecallChannel> channels) {
        if (channels.isEmpty()) {
            return channels; // 全开,SEMANTIC/TAG 本就在内
        }
        LinkedHashSet<RecallChannel> set = new LinkedHashSet<>(channels);
        set.addAll(QUERY_CHANNELS);
        return new ArrayList<>(set);
    }

    /**
     * 合并一条召回结果的来源到 itemId -> 召回路名列表(主路在首位,去重)。
     * 多路命中同一物品时,各路的 channels 全集都并入,recallFrom 因此能反映"几路同时命中"。
     */
    private static void mergeChannels(Map<Long, List<String>> recallChannel, RecallItem r) {
        List<String> names = recallChannel.computeIfAbsent(r.itemId(), k -> new ArrayList<>());
        for (RecallChannel ch : r.channels()) {
            String name = ch.name();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
    }
}
