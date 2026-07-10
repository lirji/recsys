package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 作业 cold-bandit-stats:物化冷启动类目 bandit 的统计到 Redis,供在线 {@code ColdStartBandit} 算 UCB。
 *
 * <p>按 {@code item.category} 聚合 {@code user_behavior}:
 * <ul>
 *   <li>impr(cat) = 该类目下所有行为数(曝光/试验次数的代理);</li>
 *   <li>pos(cat)  = 正反馈行为数(CLICK/LIKE/PLAY 或 RATING≥min-rating)。</li>
 * </ul>
 * 写 {@code cold:cat:{category}} = "impr,pos" 与 {@code cold:cat:total} = Σ impr。
 * 在线类目 UCB = pos/impr(exploit)+ coef·sqrt(ln(total+e)/(impr+1))(explore)。
 *
 * <p>参数:--min-rating(默认 4.0)。类比广告的 {@code ad-explore-stats}(那是按广告聚合)。
 */
@Component
public class ColdBanditStatsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(ColdBanditStatsJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public ColdBanditStatsJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "cold-bandit-stats";
    }

    @Override
    public void run(ApplicationArguments args) {
        String bt = BehaviorQuery.table(args);   // #2:行为读来源表(默认 user_behavior)
        String it = ItemQuery.table(args);       // #3:item 读来源表(默认 item)
        double minRating = doubleArg(args, "min-rating", 4.0);

        List<String[]> rows = new ArrayList<>();   // [category, impr, pos]
        jdbc.query(
                "SELECT i.category AS cat, count(*) AS impr, " +
                "  sum(CASE WHEN b.action IN ('CLICK','LIKE','PLAY') " +
                "           OR (b.action='RATING' AND b.value >= ?) THEN 1 ELSE 0 END) AS pos " +
                "FROM " + bt + " b JOIN " + it + " i ON i.item_id = b.item_id " +
                "WHERE i.category IS NOT NULL " +
                "GROUP BY i.category",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> rows.add(new String[]{
                        rs.getString("cat"),
                        String.valueOf(rs.getLong("impr")),
                        String.valueOf(rs.getLong("pos"))}),
                minRating);

        if (rows.isEmpty()) {
            log.warn("无类目统计(user_behavior 为空?先跑 import-behavior)");
            return;
        }
        long total = 0;
        for (String[] r : rows) {
            redis.opsForValue().set(RedisKeys.coldCatStats(r[0]), r[1] + "," + r[2]);
            total += Long.parseLong(r[1]);
        }
        redis.opsForValue().set(RedisKeys.COLD_CAT_TOTAL, String.valueOf(total));
        log.info("cold-bandit-stats 完成:{} 个类目,总曝光 {};示例 {} → impr={},pos={}",
                rows.size(), total, rows.get(0)[0], rows.get(0)[1], rows.get(0)[2]);
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
