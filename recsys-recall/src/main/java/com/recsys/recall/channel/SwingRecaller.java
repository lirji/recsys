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
 * Swing i2i 召回:与 {@link I2iRecaller} 同构,但读 Redis {@code swing:{itemId}} 倒排。
 *
 * <p>Swing 相似度对"被很多重叠用户共同点击"的物品对加更强惩罚,比 ItemCF 更抗热门污染,
 * 召回更聚焦小众强相关。倒排由离线 {@code swing} 作业生成,为空时该路返回空。
 */
@Component
public class SwingRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(SwingRecaller.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RecallProperties props;

    public SwingRecaller(JdbcTemplate jdbc, StringRedisTemplate redis, RecallProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.SWING;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        List<Long> seeds = recentItems(ctx.userId(), props.getQuota().getI2iSeed());
        if (seeds.isEmpty()) {
            return List.of();
        }
        int perSeed = Math.max(5, props.getQuota().getSwing() / seeds.size());
        Map<Long, Double> best = new LinkedHashMap<>();
        for (long seed : seeds) {
            Set<ZSetOperations.TypedTuple<String>> sims =
                    redis.opsForZSet().reverseRangeWithScores(RedisKeys.swing(seed), 0, perSeed - 1);
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
            out.add(new RecallItem(e.getKey(), e.getValue(), RecallChannel.SWING));
        }
        return out;
    }

    private List<Long> recentItems(long userId, int n) {
        return jdbc.queryForList(
                "SELECT DISTINCT item_id FROM user_behavior WHERE user_id=? " +
                "AND action IN ('CLICK','LIKE','PLAY','RATING') ORDER BY item_id DESC LIMIT ?",
                Long.class, userId, n);
    }
}
