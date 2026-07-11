package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * i2i 召回(ItemCF):取用户近期行为物品作种子,读 Redis i2i:{itemId} 取相似物品。
 * i2i 倒排由离线 ItemCF 作业(Track E)生成;倒排为空时该路返回空。
 */
@Component
public class I2iRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(I2iRecaller.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RecallProperties props;

    public I2iRecaller(JdbcTemplate jdbc, StringRedisTemplate redis, RecallProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.I2I;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        List<Long> seeds = seeds(ctx, props.getQuota().getI2iSeed());
        if (seeds.isEmpty()) {
            return List.of();
        }
        int perSeed = Math.max(5, props.getQuota().getI2i() / seeds.size());
        // 同一相似物品可能被多个种子命中,取最高分
        Map<Long, Double> best = new LinkedHashMap<>();
        for (long seed : seeds) {
            Set<ZSetOperations.TypedTuple<String>> sims =
                    redis.opsForZSet().reverseRangeWithScores(RedisKeys.i2i(seed), 0, perSeed - 1);
            if (sims == null) {
                continue;
            }
            for (var t : sims) {
                if (t.getValue() == null) {
                    continue;
                }
                long simItem = Long.parseLong(t.getValue());
                double score = t.getScore() == null ? 0 : t.getScore();
                best.merge(simItem, score, Math::max);
            }
        }
        List<RecallItem> out = new ArrayList<>(best.size());
        for (var e : best.entrySet()) {
            out.add(new RecallItem(e.getKey(), e.getValue(), RecallChannel.I2I));
        }
        return out;
    }

    /** 种子物品:优先用编排层预取的近期正反馈(取前 n 前缀,与 SQL LIMIT n 逐项等价);未预取则回退查库。 */
    private List<Long> seeds(RecallContext ctx, int n) {
        List<Long> prefetched = ctx.recentPositiveItems();
        if (prefetched != null) {
            return prefetched.size() > n ? prefetched.subList(0, n) : prefetched;
        }
        return recentItems(ctx.userId(), n);
    }

    private List<Long> recentItems(long userId, int n) {
        return jdbc.queryForList(
                "SELECT DISTINCT item_id FROM user_behavior WHERE user_id=? " +
                "AND action IN ('CLICK','LIKE','PLAY','RATING') ORDER BY item_id DESC LIMIT ?",
                Long.class, userId, n);
    }
}
