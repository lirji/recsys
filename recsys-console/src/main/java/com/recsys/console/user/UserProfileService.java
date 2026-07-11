package com.recsys.console.user;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户 360:从主库(app_user / user_behavior / user_embedding,console 已连的 recsys 库)聚合静态画像 + 行为,
 * 并从 Redis 补在线实时态(rt:user / rt:user:seq / seen / cache:rec)。
 *
 * <p>优雅降级:{@code jdbc==null} 或查询异常 → DB 部分返回空、exists=false;Redis 不可达 → realtime.available=false。
 * 全部只读,不写任何存储。
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final int RECENT_LIMIT = 50;
    private static final int SEQ_LIMIT = 20;

    @Nullable
    private final JdbcTemplate jdbc;
    @Nullable
    private final StringRedisTemplate redis;

    public UserProfileService(@Nullable JdbcTemplate jdbc, @Nullable StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public UserProfileView get(long userId) {
        String profileJson = null;
        Long profileUpdatedAt = null;
        boolean profileFound = false;
        boolean hasEmbedding = false;
        Map<String, Long> actionCounts = new LinkedHashMap<>();
        int distinctCategories = 0;
        List<String> buckets = List.of();
        List<UserProfileView.BehaviorRow> recent = List.of();

        if (jdbc != null) {
            try {
                Object[] pr = jdbc.query(
                        "SELECT profile::text AS profile, updated_at FROM app_user WHERE user_id = ?",
                        rs -> rs.next()
                                ? new Object[]{rs.getString("profile"), rs.getTimestamp("updated_at")}
                                : null,
                        userId);
                if (pr != null) {
                    profileFound = true;
                    profileJson = (String) pr[0];
                    Timestamp ts = (Timestamp) pr[1];
                    profileUpdatedAt = ts != null ? ts.getTime() : null;
                }
            } catch (Exception e) {
                log.debug("查 app_user {} 失败: {}", userId, e.getMessage());
            }
            hasEmbedding = queryBool("SELECT EXISTS(SELECT 1 FROM user_embedding WHERE user_id = ?)", userId);
            actionCounts = queryActionCounts(userId);
            distinctCategories = queryInt(
                    "SELECT count(DISTINCT i.category) FROM user_behavior b "
                            + "JOIN item i ON i.item_id = b.item_id WHERE b.user_id = ?", userId);
            buckets = queryStrings(
                    "SELECT DISTINCT bucket FROM user_behavior "
                            + "WHERE user_id = ? AND bucket IS NOT NULL AND bucket <> '' ORDER BY bucket", userId);
            recent = queryRecent(userId);
        }

        long total = actionCounts.values().stream().mapToLong(Long::longValue).sum();
        boolean exists = profileFound || total > 0;
        UserProfileView.Stats stats =
                new UserProfileView.Stats(total, actionCounts, distinctCategories, buckets);
        UserProfileView.Realtime realtime = queryRealtime(userId);

        return new UserProfileView(userId, exists, profileJson, profileUpdatedAt, hasEmbedding, stats, recent, realtime);
    }

    // ---------- DB ----------

    private boolean queryBool(String sql, long userId) {
        try {
            Boolean b = jdbc.queryForObject(sql, Boolean.class, userId);
            return Boolean.TRUE.equals(b);
        } catch (Exception e) {
            log.debug("bool 查询失败: {}", e.getMessage());
            return false;
        }
    }

    private int queryInt(String sql, long userId) {
        try {
            Integer n = jdbc.queryForObject(sql, Integer.class, userId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.debug("int 查询失败: {}", e.getMessage());
            return 0;
        }
    }

    private List<String> queryStrings(String sql, long userId) {
        try {
            return jdbc.query(sql, (rs, i) -> rs.getString(1), userId);
        } catch (Exception e) {
            log.debug("字符串列查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Long> queryActionCounts(long userId) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT action, count(*) AS c FROM user_behavior WHERE user_id = ? GROUP BY action ORDER BY c DESC",
                    rs -> {
                        String action = rs.getString("action");
                        out.put(action == null ? "unknown" : action, rs.getLong("c"));
                    }, userId);
        } catch (Exception e) {
            log.debug("action 聚合失败: {}", e.getMessage());
        }
        return out;
    }

    private List<UserProfileView.BehaviorRow> queryRecent(long userId) {
        try {
            return jdbc.query(
                    "SELECT item_id, action, ts, bucket, position, scene, value FROM user_behavior "
                            + "WHERE user_id = ? ORDER BY ts DESC LIMIT " + RECENT_LIMIT,
                    (rs, i) -> {
                        Timestamp ts = rs.getTimestamp("ts");
                        int pos = rs.getInt("position");
                        Integer position = rs.wasNull() ? null : pos;
                        double val = rs.getDouble("value");
                        Double value = rs.wasNull() ? null : val;
                        return new UserProfileView.BehaviorRow(
                                rs.getLong("item_id"),
                                rs.getString("action"),
                                ts != null ? ts.getTime() : 0L,
                                rs.getString("bucket"),
                                position,
                                rs.getString("scene"),
                                value);
                    }, userId);
        } catch (Exception e) {
            log.debug("近期行为查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ---------- Redis(在线实时态) ----------

    private UserProfileView.Realtime queryRealtime(long userId) {
        if (redis == null) {
            return new UserProfileView.Realtime(false, Map.of(), List.of(), false, 0L);
        }
        try {
            Map<String, Long> rtPrefs = new LinkedHashMap<>();
            Map<Object, Object> hash = redis.opsForHash().entries(RedisKeys.rtUser(userId));
            for (Map.Entry<Object, Object> e : hash.entrySet()) {
                rtPrefs.put(String.valueOf(e.getKey()), parseLong(e.getValue()));
            }
            List<Long> seq = new ArrayList<>();
            Set<String> seqMembers = redis.opsForZSet().reverseRange(RedisKeys.userSeq(userId), 0, SEQ_LIMIT - 1);
            if (seqMembers != null) {
                for (String m : seqMembers) {
                    try {
                        seq.add(Long.parseLong(m));
                    } catch (NumberFormatException ignored) {
                        // 跳过非数字 member
                    }
                }
            }
            boolean recCached = Boolean.TRUE.equals(redis.hasKey(RedisKeys.recCache(userId)));
            Long seenSize = redis.opsForSet().size(RedisKeys.seenItems(userId));
            return new UserProfileView.Realtime(true, rtPrefs, seq, recCached, seenSize == null ? 0L : seenSize);
        } catch (Exception e) {
            log.debug("Redis 实时态读取失败(降级): {}", e.getMessage());
            return new UserProfileView.Realtime(false, Map.of(), List.of(), false, 0L);
        }
    }

    private static long parseLong(Object v) {
        if (v == null) {
            return 0L;
        }
        try {
            // rt:user 的 value 可能是浮点计数字符串,先按 double 再取整,稳妥
            return (long) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
