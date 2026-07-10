package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 作业 hot:从 user_behavior 聚合物品热度,写 Redis {@code recall:hot} ZSet(score=热度),
 * 让 HotRecaller 走 Redis 而非降级查库的静态 popularity。
 *
 * <p>热度 = 各行为加权求和:CLICK=1,LIKE=2,PLAY=1,RATING=value(评分越高权重越大)。
 * 参数:--recent-days(只统计近 N 天行为,默认 0=全量)、--topn(写入榜单条数,默认 1000)。
 */
@Component
public class HotJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(HotJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public HotJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "hot";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int recentDays = intArg(args, "recent-days", 0);
        int topN = intArg(args, "topn", 1000);
        Long maxTs = BehaviorQuery.maxTs(args);   // 严格 eval:只统计切分点前的热度
        String bt = BehaviorQuery.table(args);    // #2:行为读来源表(默认 user_behavior)

        // 在 SQL 内完成加权聚合,避免把全量行为拉进内存
        StringBuilder sql = new StringBuilder(
                "SELECT item_id, SUM(" +
                "  CASE action " +
                "    WHEN 'CLICK' THEN 1.0 " +
                "    WHEN 'LIKE'  THEN 2.0 " +
                "    WHEN 'PLAY'  THEN 1.0 " +
                "    WHEN 'RATING' THEN COALESCE(value, 1.0) " +
                "    ELSE 0.0 END) AS hot " +
                "FROM " + bt + " ");
        java.util.List<String> conds = new java.util.ArrayList<>();
        if (recentDays > 0) {
            conds.add("ts >= now() - INTERVAL '" + recentDays + " days'");
        }
        if (maxTs != null) {
            conds.add("extract(epoch from ts) <= " + maxTs);   // maxTs 已解析为 long,内联安全
        }
        if (!conds.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", conds)).append(' ');
        }
        sql.append("GROUP BY item_id ORDER BY hot DESC LIMIT ?");

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        jdbc.query(sql.toString(), rs -> {
            tuples.add(ZSetOperations.TypedTuple.of(
                    String.valueOf(rs.getLong("item_id")), rs.getDouble("hot")));
        }, topN);

        if (tuples.isEmpty()) {
            log.warn("无行为数据,recall:hot 未更新;先跑 --job=import-behavior");
            return;
        }
        // 原子替换:删旧 key 再整榜写入
        redis.delete(RedisKeys.HOT_RECALL);
        redis.opsForZSet().add(RedisKeys.HOT_RECALL, tuples);
        log.info("hot 完成:recall:hot 写入 {} 条(recent-days={}, topn={})",
                tuples.size(), recentDays, topN);
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }
}
