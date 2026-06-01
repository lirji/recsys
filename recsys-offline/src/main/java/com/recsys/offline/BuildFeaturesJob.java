package com.recsys.offline;

import com.recsys.feature.RedisFeatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final RedisFeatureService featureService;

    public BuildFeaturesJob(JdbcTemplate jdbc, RedisFeatureService featureService) {
        this.jdbc = jdbc;
        this.featureService = featureService;
    }

    @Override
    public String name() {
        return "build-features";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        buildItemFeatures();
        buildUserFeatures();
        log.info("build-features 完成");
    }

    private void buildItemFeatures() {
        Double maxCntD = jdbc.queryForObject(
                "SELECT max(cnt) FROM (SELECT count(*) cnt FROM user_behavior " +
                "WHERE action='RATING' GROUP BY item_id) t", Double.class);
        double lnMax = Math.log1p(maxCntD == null ? 1.0 : maxCntD);
        int[] n = {0};
        jdbc.query(
                "SELECT item_id, count(*) cnt, avg(value) avg_v FROM user_behavior " +
                "WHERE action='RATING' GROUP BY item_id",
                rs -> {
                    long itemId = rs.getLong("item_id");
                    long cnt = rs.getLong("cnt");
                    double avg = rs.getDouble("avg_v");
                    double popNorm = lnMax > 0 ? Math.log1p(cnt) / lnMax : 0.0;
                    Map<String, Double> f = new HashMap<>();
                    f.put("item_pop_norm", round(popNorm));
                    f.put("item_avg_rating", round(avg));
                    f.put("item_rating_cnt", (double) cnt);
                    featureService.writeItemFeatures(itemId, f);
                    n[0]++;
                });
        log.info("物化 item 特征 {} 条", n[0]);
    }

    private void buildUserFeatures() {
        Double maxCntD = jdbc.queryForObject(
                "SELECT max(cnt) FROM (SELECT count(*) cnt FROM user_behavior " +
                "WHERE action='RATING' GROUP BY user_id) t", Double.class);
        double lnMax = Math.log1p(maxCntD == null ? 1.0 : maxCntD);

        // 1. 先在内存攒每个用户的特征(基础 + 各类目均分),最后一次性写,减少 Redis 往返
        Map<Long, Map<String, Double>> userFeats = new HashMap<>();
        jdbc.query(
                "SELECT user_id, count(*) cnt, avg(value) avg_v FROM user_behavior " +
                "WHERE action='RATING' GROUP BY user_id",
                rs -> {
                    long userId = rs.getLong("user_id");
                    long cnt = rs.getLong("cnt");
                    double avg = rs.getDouble("avg_v");
                    double actNorm = lnMax > 0 ? Math.log1p(cnt) / lnMax : 0.0;
                    Map<String, Double> f = userFeats.computeIfAbsent(userId, k -> new HashMap<>());
                    f.put("user_act_norm", round(actNorm));
                    f.put("user_avg_rating", round(avg));
                });

        // 2. 交叉特征:用户 × 物品主类目 的历史平均分
        int[] catRows = {0};
        jdbc.query(
                "SELECT b.user_id, i.category, avg(b.value) avg_v " +
                "FROM user_behavior b JOIN item i ON b.item_id = i.item_id " +
                "WHERE b.action='RATING' AND i.category IS NOT NULL " +
                "GROUP BY b.user_id, i.category",
                rs -> {
                    long userId = rs.getLong("user_id");
                    String cat = rs.getString("category");
                    double avg = rs.getDouble("avg_v");
                    Map<String, Double> f = userFeats.computeIfAbsent(userId, k -> new HashMap<>());
                    f.put("catavg:" + cat, round(avg));
                    catRows[0]++;
                });

        userFeats.forEach(featureService::writeUserFeatures);
        log.info("物化 user 特征 {} 个用户(含交叉类目均分 {} 条)", userFeats.size(), catRows[0]);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
