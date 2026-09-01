package com.recsys.recall;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * MIND 在线行为序列来源。Redis ZSet 的 score 与 DB 的 ts 都是事件时间，统一返回 oldest→newest。
 * DB 按 item 的最后一次正反馈去重，避免重复行为挤满短序列；Redis 故障透明回退 DB。
 */
@Component
public class BehaviorSequenceSource {

    private static final Logger log = LoggerFactory.getLogger(BehaviorSequenceSource.class);
    private static final String SQL = """
            SELECT item_id FROM (
              SELECT item_id, max(ts) AS last_ts
              FROM user_behavior
              WHERE user_id=?
                AND (action IN ('CLICK','LIKE','PLAY') OR (action='RATING' AND value>=4))
              GROUP BY item_id
            ) s ORDER BY last_ts DESC LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public BehaviorSequenceSource(JdbcTemplate jdbc,
                                  ObjectProvider<StringRedisTemplate> redisProvider) {
        this.jdbc = jdbc;
        this.redisProvider = redisProvider;
    }

    public List<Long> sequence(long userId, int limit) {
        int bounded = Math.max(1, limit);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Set<String> newestFirst = redis.opsForZSet()
                        .reverseRange(RedisKeys.userSeq(userId), 0, bounded - 1L);
                if (newestFirst != null && !newestFirst.isEmpty()) {
                    List<Long> out = parse(newestFirst);
                    Collections.reverse(out);
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            } catch (Exception e) {
                log.debug("MIND 序列读 Redis 失败 user={},回退 DB: {}", userId, e.getMessage());
            }
        }
        try {
            List<Long> newestFirst = jdbc.query(SQL, (rs, n) -> rs.getLong(1), userId, bounded);
            Collections.reverse(newestFirst);
            return List.copyOf(newestFirst);
        } catch (Exception e) {
            log.debug("MIND 序列读 DB 失败 user={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private static List<Long> parse(Set<String> members) {
        List<Long> out = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                out.add(Long.parseLong(member));
            } catch (NumberFormatException ignore) {
                // 脏 Redis member 跳过。
            }
        }
        return out;
    }
}
