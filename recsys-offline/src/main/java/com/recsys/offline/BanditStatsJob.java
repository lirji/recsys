package com.recsys.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.common.bandit.BanditModelDto;
import com.recsys.common.bandit.LinUcbModel;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.FeatureAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.offline.persistence.mapper.primary.BanditImpressionMapper;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.model.PositiveFeedbackFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作业 bandit-stats:R7 全量 contextual bandit 的**离线充分统计聚合**。从推荐曝光闭环
 * ({@code user_behavior} 的 IMPRESSION 行 = 上下文,正反馈 = reward)重建每条 (user,item) 的排序稠密
 * 上下文 {@code x},累积岭回归充分统计 {@code A += x·xᵀ}、{@code b += reward·x},写 Redis
 * {@link RedisKeys#BANDIT_MODEL}(JSON)。在线 {@code BanditScorer} 读它出 LinUCB/Thompson 探索加成。
 *
 * <p><b>在线/离线一致性</b>:{@code x} 用与在线**同一** {@link FeatureAssembler}(默认 {@code FEATURE_ORDER}
 * 5 维)从 {@link FeatureService}(feat:* Redis,在线同源)+ item 类目装配 —— 与在线 {@code RankedItem.featureSnapshot()}
 * 逐位一致(同 {@code FtrlHashing} 的契约角色)。{@code order} 一并写进模型 JSON 作契约。
 *
 * <p><b>近线增量</b>:{@code --incremental} 从 {@link RedisKeys#BANDIT_MODEL} warm-start 继续累积
 * (维度不符则从零),配 {@code --max-ts} 可按小时切片刷新。数据来源与 PAL/{@code gen-samples-impr}
 * 同为曝光日志;无 IMPRESSION 行则提示先跑 {@code sim-rec-events} 或灌真实曝光。
 *
 * <p>参数:--days(窗口,默认 30;&le;0 全量)、--lambda(岭正则,默认 1.0)、--min-rating(默认 4.0)、
 * --incremental、--max-ts(epoch 秒,上切)。
 */
@Component
public class BanditStatsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(BanditStatsJob.class);

    private final BanditImpressionMapper impressionMapper;
    private final PositiveFeedbackMapper feedback;
    private final StringRedisTemplate redis;
    private final FeatureService featureService;
    private final ContentService contentService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 曝光与正反馈都绑定主源Mapper，模型算法和Redis发布职责保持。 */
    public BanditStatsJob(PositiveFeedbackMapper feedback, BanditImpressionMapper impressionMapper,
                          StringRedisTemplate redis, FeatureService featureService, ContentService contentService) {
        this.impressionMapper = impressionMapper;
        this.feedback = feedback;
        this.redis = redis;
        this.featureService = featureService;
        this.contentService = contentService;
    }

    /** 保持离线Bandit充分统计命令。 */
    @Override
    public String name() {
        return "bandit-stats";
    }

    /** 曝光为空时保留原模型；窗口只约束曝光，奖励沿原独立上界。 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            aggregate(args);
        } catch (PublicErrorException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.JOB_BANDIT_FAILED, failure);
        }
    }

    private void aggregate(ApplicationArguments args) {
        int days = JobArguments.integer(args, "days", 30);
        double lambda = JobArguments.decimal(args, "lambda", 1.0);
        double minRating = JobArguments.decimal(args, "min-rating", 4.0);
        if (lambda <= 0) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        boolean incremental = args.containsOption("incremental");
        Long maxTs = BehaviorQuery.maxTs(args);
        var source = BehaviorQuery.source(args);
        List<String> order = FeatureAssembler.FEATURE_ORDER;   // 5 维,与在线 featureSnapshot 同源
        int d = order.size();

        List<BanditImpressionMapper.Impression> impressions = impressionMapper.load(source, days, maxTs);
        if (impressions.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_BANDIT_NO_INPUT);
        }

        // 正反馈集合作 reward 标签(与 gen-samples/FtrlTrainJob 同口径:CLICK/LIKE/PLAY 或 RATING≥min)
        Set<String> positives = new HashSet<>();
        feedback.scanPairs(new PositiveFeedbackFilter(source, minRating, maxTs), context -> {
            var row = context.getResultObject();
            positives.add(row.userId() + ":" + row.itemId());
        });

        // 模型:warm-start(增量)或全新
        LinUcbModel model = incremental ? loadOrFresh(order, lambda) : LinUcbModel.create(d, lambda);

        if (model.getN() > Long.MAX_VALUE - impressions.size()) {
            throw new PublicErrorException(ErrorCode.JOB_BANDIT_MODEL_INVALID);
        }

        // 批量预取候选特征 + 类目(消除逐条 N+1)
        Set<Long> distinctItems = new HashSet<>();
        for (var impression : impressions) {
            distinctItems.add(impression.itemId());
        }
        Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(distinctItems);
        Map<Long, Item> items = contentService.findByIds(new ArrayList<>(distinctItems));
        Map<Long, Map<String, Double>> userCache = new HashMap<>();

        long posCount = 0;
        for (var impression : impressions) {
            long u = impression.userId(), it = impression.itemId();
            Map<String, Double> uf = userCache.computeIfAbsent(u, featureService::userFeatures);
            Item item = items.get(it);
            String cat = item == null ? null : item.category();
            double[] x = FeatureAssembler.assemble(uf, itemFeats.getOrDefault(it, Map.of()), cat, order);
            for (double value : x) {
                if (!Double.isFinite(value)) throw new PublicErrorException(ErrorCode.JOB_BANDIT_MODEL_INVALID);
            }
            double reward = positives.contains(u + ":" + it) ? 1.0 : 0.0;
            if (reward > 0) {
                posCount++;
            }
            model.accumulate(x, reward);
        }

        writeModel(order, model);
        log.info("bandit-stats 完成:曝光样本 {}(正反馈 {}),dim={},λ={},累计 n={},incremental={}。已写 {}",
                impressions.size(), posCount, d, lambda, model.getN(), incremental, RedisKeys.BANDIT_MODEL);
    }

    /** 缺失或损坏模型保留原从零策略；依赖不可读不能当成缺失。 */
    private LinUcbModel loadOrFresh(List<String> order, double lambda) {
        String json = redis.opsForValue().get(RedisKeys.BANDIT_MODEL);
        if (json != null && !json.isBlank()) {
            try {
                BanditModelDto dto = mapper.readValue(json, BanditModelDto.class);
                if (validModel(dto, order)) {
                    log.info("warm-start:载入已有 bandit 模型 n={}", dto.n());
                    return dto.toModel();
                }
            } catch (JsonProcessingException | IllegalArgumentException ignored) {
                // 仅解析失败进入下方固定码降级；不暴露模型内容或解析器异常消息。
            }
        }
        ErrorCode code = ErrorCode.JOB_BANDIT_MODEL_FALLBACK;
        log.warn("[{}] {}", code.code(), code.message());
        return LinUcbModel.create(order.size(), lambda);
    }

    /** 校验真正参与在线计算的结构和有限数；相同维度不代表相同特征顺序。 */
    private static boolean validModel(BanditModelDto dto, List<String> order) {
        int dimension = order.size();
        if (dto == null || !order.equals(dto.order()) || !Double.isFinite(dto.lambda()) || dto.lambda() <= 0
                || dto.n() < 0 || dto.a() == null || dto.a().length != dimension
                || dto.b() == null || dto.b().length != dimension) return false;
        for (int i = 0; i < dimension; i++) {
            if (!Double.isFinite(dto.b()[i]) || dto.a()[i] == null || dto.a()[i].length != dimension) return false;
            for (double value : dto.a()[i]) if (!Double.isFinite(value)) return false;
        }
        return true;
    }

    private void writeModel(List<String> order, LinUcbModel model) {
        BanditModelDto dto = BanditModelDto.from(order, model);
        if (!validModel(dto, order)) throw new PublicErrorException(ErrorCode.JOB_BANDIT_MODEL_INVALID);
        try {
            redis.opsForValue().set(RedisKeys.BANDIT_MODEL, mapper.writeValueAsString(dto));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.JOB_BANDIT_FAILED, failure);
        }
    }
}
