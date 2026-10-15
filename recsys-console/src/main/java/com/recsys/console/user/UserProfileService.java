package com.recsys.console.user;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.console.persistence.mapper.primary.UserProfileMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户 360:从主库(app_user / user_behavior / user_embedding,console 已连的 recsys 库)聚合静态画像 + 行为,
 * 并从 Redis 补在线实时态(rt:user / rt:user:seq / seen / cache:rec)。
 *
 * <p>优雅降级:Mapper缺失或单项查询异常时该部分为空，其他已取得结果保留；Redis不可达时realtime.available=false。
 * 全部只读,不写任何存储。
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final int RECENT_LIMIT = 50;
    private static final int SEQ_LIMIT = 20;

    @Nullable
    private final UserProfileMapper mapper;
    @Nullable
    private final StringRedisTemplate redis;

    /** 保持数据库和Redis依赖可选，独立使用服务时也能返回完整降级视图。 */
    public UserProfileService(@Nullable UserProfileMapper mapper, @Nullable StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 各数据源独立降级，画像存在性不受其他可选查询失败影响。 */
    public UserProfileView get(long userId) {
        String profileJson = null;
        Long profileUpdatedAt = null;
        boolean profileFound = false;
        boolean hasEmbedding = false;
        Map<String, Long> actionCounts = new LinkedHashMap<>();
        int distinctCategories = 0;
        List<String> buckets = List.of();
        List<UserProfileView.BehaviorRow> recent = List.of();

        if (mapper != null) {
            try {
                var profile = mapper.profile(userId);
                if (profile != null) {
                    profileFound = true;
                    profileJson = profile.profile();
                    profileUpdatedAt = profile.updatedAt() == null ? null : profile.updatedAt().getTime();
                }
            } catch (Exception e) {
                log.debug("查 app_user {} 失败: {}", userId, e.getMessage());
            }
            hasEmbedding = queryHasEmbedding(userId);
            actionCounts = queryActionCounts(userId);
            distinctCategories = queryDistinctCategories(userId);
            buckets = queryBuckets(userId);
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

    private boolean queryHasEmbedding(long userId) {
        try {
            return mapper.hasEmbedding(userId);
        } catch (Exception e) {
            log.debug("bool 查询失败: {}", e.getMessage());
            return false;
        }
    }

    private int queryDistinctCategories(long userId) {
        try {
            return mapper.distinctCategories(userId);
        } catch (Exception e) {
            log.debug("int 查询失败: {}", e.getMessage());
            return 0;
        }
    }

    private List<String> queryBuckets(long userId) {
        try {
            return mapper.buckets(userId);
        } catch (Exception e) {
            log.debug("字符串列查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Long> queryActionCounts(long userId) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            mapper.actionCounts(userId, context -> {
                var row = context.getResultObject();
                out.put(row.action() == null ? "unknown" : row.action(), row.count());
            });
        } catch (Exception e) {
            log.debug("action 聚合失败: {}", e.getMessage());
        }
        return out;
    }

    private List<UserProfileView.BehaviorRow> queryRecent(long userId) {
        try {
            return mapper.recent(userId, RECENT_LIMIT).stream().map(row ->
                    new UserProfileView.BehaviorRow(row.itemId(), row.action(),
                            row.ts() == null ? 0L : row.ts().getTime(), row.bucket(), row.position(), row.scene(), row.value()))
                    .toList();
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
