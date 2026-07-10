package com.recsys.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final FeatureService featureService;
    private final ContentService contentService;
    private final ObjectMapper mapper = new ObjectMapper();

    public BanditStatsJob(JdbcTemplate jdbc, StringRedisTemplate redis,
                          FeatureService featureService, ContentService contentService) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.featureService = featureService;
        this.contentService = contentService;
    }

    @Override
    public String name() {
        return "bandit-stats";
    }

    @Override
    public void run(ApplicationArguments args) {
        int days = intArg(args, "days", 30);
        double lambda = dblArg(args, "lambda", 1.0);
        double minRating = dblArg(args, "min-rating", 4.0);
        boolean incremental = args.containsOption("incremental");
        Long maxTs = BehaviorQuery.maxTs(args);
        String bt = BehaviorQuery.table(args);   // #2
        List<String> order = FeatureAssembler.FEATURE_ORDER;   // 5 维,与在线 featureSnapshot 同源
        int d = order.size();

        // 曝光上下文:user_behavior 的 IMPRESSION 行(days 下界 + 可选 max-ts 上切)
        StringBuilder win = new StringBuilder("action='IMPRESSION'");
        if (days > 0) {
            win.append(" AND ts >= now() - interval '").append(days).append(" days'");
        }
        if (maxTs != null) {
            win.append(" AND extract(epoch from ts) <= ").append(maxTs);
        }
        List<long[]> impressions = new ArrayList<>();
        jdbc.query("SELECT user_id, item_id FROM " + bt + " WHERE " + win,
                (RowCallbackHandler) rs -> impressions.add(new long[]{rs.getLong("user_id"), rs.getLong("item_id")}));
        if (impressions.isEmpty()) {
            log.warn("无 IMPRESSION 行(推荐曝光闭环);先跑 --job=sim-rec-events 造曝光+点击,或灌真实曝光");
            return;
        }

        // 正反馈集合作 reward 标签(与 gen-samples/FtrlTrainJob 同口径:CLICK/LIKE/PLAY 或 RATING≥min)
        Set<String> positives = new HashSet<>();
        jdbc.query(BehaviorQuery.positiveFeedbackSql(bt, "user_id, item_id", maxTs),
                (RowCallbackHandler) rs -> positives.add(rs.getLong("user_id") + ":" + rs.getLong("item_id")),
                BehaviorQuery.params(minRating, maxTs));

        // 模型:warm-start(增量)或全新
        LinUcbModel model = incremental ? loadOrFresh(d, lambda) : LinUcbModel.create(d, lambda);

        // 批量预取候选特征 + 类目(消除逐条 N+1)
        Set<Long> distinctItems = new HashSet<>();
        for (long[] e : impressions) {
            distinctItems.add(e[1]);
        }
        Map<Long, Map<String, Double>> itemFeats = featureService.itemFeatures(distinctItems);
        Map<Long, Item> items = contentService.findByIds(new ArrayList<>(distinctItems));
        Map<Long, Map<String, Double>> userCache = new HashMap<>();

        long posCount = 0;
        for (long[] e : impressions) {
            long u = e[0], it = e[1];
            Map<String, Double> uf = userCache.computeIfAbsent(u, featureService::userFeatures);
            Item item = items.get(it);
            String cat = item == null ? null : item.category();
            double[] x = FeatureAssembler.assemble(uf, itemFeats.getOrDefault(it, Map.of()), cat, order);
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

    /** 增量:从 Redis warm-start;维度不符/缺失/解析失败则从零。 */
    private LinUcbModel loadOrFresh(int d, double lambda) {
        try {
            String json = redis.opsForValue().get(RedisKeys.BANDIT_MODEL);
            if (json == null || json.isBlank()) {
                log.info("无 {},--incremental 退化为从零", RedisKeys.BANDIT_MODEL);
                return LinUcbModel.create(d, lambda);
            }
            BanditModelDto dto = mapper.readValue(json, BanditModelDto.class);
            if (dto.order() == null || dto.order().size() != d) {
                log.warn("已存模型维度({})与当前({})不符,从零", dto.order() == null ? -1 : dto.order().size(), d);
                return LinUcbModel.create(d, lambda);
            }
            log.info("warm-start:载入已有 bandit 模型 n={}", dto.n());
            return dto.toModel();
        } catch (Exception ex) {
            log.warn("载入 {} 失败,从零: {}", RedisKeys.BANDIT_MODEL, ex.getMessage());
            return LinUcbModel.create(d, lambda);
        }
    }

    private void writeModel(List<String> order, LinUcbModel model) {
        try {
            redis.opsForValue().set(RedisKeys.BANDIT_MODEL,
                    mapper.writeValueAsString(BanditModelDto.from(order, model)));
        } catch (Exception e) {
            log.warn("写 {} 失败: {}", RedisKeys.BANDIT_MODEL, e.getMessage());
        }
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
