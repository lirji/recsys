package com.recsys.offline;

import com.recsys.feature.RedisFeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.FeatureStatsMapper;
import com.recsys.offline.persistence.model.BehaviorSource;
import com.recsys.content.persistence.model.CatalogTable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 作业 build-features:把离线特征物化进 Redis feat:item / feat:user,
 * 供在线排序(规则 v1 与 ONNX v3)直接查表打分。
 *
 * <p>特征定义与 {@code FeatureAssembler.FEATURE_ORDER} 对齐:
 * <ul>
 *   <li>item:item_pop_norm(评分数 log 归一)、item_avg_rating(平均分)、item_rating_cnt;</li>
 *   <li>user:user_act_norm(活跃度 log 归一)、user_avg_rating(评分均值/打分偏置);</li>
 *   <li>交叉:catavg:&lt;genre&gt; —— 用户对各主类目的历史平均分(排序最有用的交叉特征)。</li>
 * </ul>
 *
 * <p>这些数即训练样本的特征来源(gen-samples 经同一 FeatureAssembler 读取),
 * 保证在线/离线特征严格一致。
 */
@Component
public class BuildFeaturesJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(BuildFeaturesJob.class);

    private final FeatureStatsMapper mapper;
    private final RedisFeatureService featureService;

    /** 聚合Mapper绑定原主源，特征写入继续复用在线Redis字段协议。 */
    public BuildFeaturesJob(FeatureStatsMapper mapper, RedisFeatureService featureService) {
        this.mapper = mapper;
        this.featureService = featureService;
    }

    /** 保持离线特征物化命令。 */
    @Override
    public String name() {
        return "build-features";
    }

    /** 来源在单次运行内显式传递，避免作业实例留下上一轮的可变SQL来源。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        var behavior = BehaviorQuery.source(args);
        var items = ItemQuery.source(args);
        buildItemFeatures(behavior);
        buildUserFeatures(behavior, items);
        log.info("build-features 完成");
    }

    private void buildItemFeatures(BehaviorSource behavior) {
        Double maxCntD = mapper.maxItemRatingCount(behavior);
        double lnMax = Math.log1p(maxCntD == null ? 1.0 : maxCntD);
        int[] n = {0};
        mapper.scanItemRatings(behavior, context -> {
            var row = context.getResultObject();
            long itemId = row.itemId();
            long cnt = row.count();
            double avg = row.average();
            double popNorm = lnMax > 0 ? Math.log1p(cnt) / lnMax : 0.0;
            Map<String, Double> f = new HashMap<>();
            f.put("item_pop_norm", round(popNorm));
            f.put("item_avg_rating", round(avg));
            f.put("item_rating_cnt", (double) cnt);
            // 扩充特征(S2):评分离散度=共识/争议(与 as-of 的 sqrt(sumSq/cnt-mean^2) 同源)
            f.put("item_rating_std", round(row.deviation()));
            featureService.writeItemFeatures(itemId, f);
            n[0]++;
        });
        log.info("物化 item 特征 {} 条", n[0]);
    }

    private void buildUserFeatures(BehaviorSource behavior, CatalogTable items) {
        Double maxCntD = mapper.maxUserRatingCount(behavior);
        double lnMax = Math.log1p(maxCntD == null ? 1.0 : maxCntD);

        // 1. 先在内存攒每个用户的特征(基础 + 各类目均分),最后一次性写,减少 Redis 往返
        Map<Long, Map<String, Double>> userFeats = new HashMap<>();
        Map<Long, Long> userCnt = new HashMap<>();   // 供交叉特征算 catratio(与 as-of 同源)
        mapper.scanUserRatings(behavior, context -> {
            var row = context.getResultObject();
            long userId = row.userId();
            long cnt = row.count();
            double avg = row.average();
            double actNorm = lnMax > 0 ? Math.log1p(cnt) / lnMax : 0.0;
            userCnt.put(userId, cnt);
            Map<String, Double> f = userFeats.computeIfAbsent(userId, k -> new HashMap<>());
            f.put("user_act_norm", round(actNorm));
            f.put("user_avg_rating", round(avg));
        });

        // 2. 交叉特征:用户 × 物品主类目 的历史均分 + 参与深度(S2:catcnt_norm/catratio)
        int[] catRows = {0};
        mapper.scanCategoryRatings(behavior, items, context -> {
            var row = context.getResultObject();
            long userId = row.userId();
            String cat = row.category();
            long cnt = row.count();
            double avg = row.average();
            Map<String, Double> f = userFeats.computeIfAbsent(userId, k -> new HashMap<>());
            f.put("catavg:" + cat, round(avg));
            f.put("catcnt_norm:" + cat, round(lnMax > 0 ? Math.log1p(cnt) / lnMax : 0.0));
            Long uc = userCnt.get(userId);
            if (uc != null && uc > 0) {
                f.put("catratio:" + cat, round((double) cnt / uc));
            }
            catRows[0]++;
        });

        userFeats.forEach(featureService::writeUserFeatures);
        log.info("物化 user 特征 {} 个用户(含交叉类目均分 {} 条)", userFeats.size(), catRows[0]);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
