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
import java.util.List;
import java.util.Set;

/**
 * 热门召回:读 Redis recall:hot ZSet 取 topN。这是兜底/冷启动路,必须始终可用。
 * 若 Redis 热门为空(离线作业未跑),降级直接查 item 表 popularity Top-N,保证永不空手。
 */
@Component
public class HotRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(HotRecaller.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final RecallProperties props;

    public HotRecaller(StringRedisTemplate redis, JdbcTemplate jdbc, RecallProperties props) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.HOT;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        int limit = props.getQuota().getHot();
        List<RecallItem> fromRedis = fromRedis(limit);
        if (!fromRedis.isEmpty()) {
            return fromRedis;
        }
        // 降级:直接查库 popularity(保证热门兜底永远有结果)
        return fromDb(limit);
    }

    private List<RecallItem> fromRedis(int limit) {
        try {
            Set<ZSetOperations.TypedTuple<String>> hot =
                    redis.opsForZSet().reverseRangeWithScores(RedisKeys.HOT_RECALL, 0, limit - 1);
            if (hot == null || hot.isEmpty()) {
                return List.of();
            }
            List<RecallItem> out = new ArrayList<>(hot.size());
            for (var t : hot) {
                if (t.getValue() == null) {
                    continue;
                }
                out.add(new RecallItem(Long.parseLong(t.getValue()),
                        t.getScore() == null ? 0 : t.getScore(), RecallChannel.HOT));
            }
            return out;
        } catch (Exception e) {
            log.warn("读热门 Redis 失败,降级查库: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RecallItem> fromDb(int limit) {
        return jdbc.query(
                "SELECT item_id, popularity FROM item ORDER BY popularity DESC LIMIT ?",
                (rs, n) -> new RecallItem(rs.getLong("item_id"), rs.getDouble("popularity"), RecallChannel.HOT),
                limit);
    }
}
