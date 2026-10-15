package com.recsys.console.user;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.console.persistence.mapper.primary.UserProfileMapper;
import com.recsys.console.user.UserProfileView.Section;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** 用户360独立读取各分区；故障只使该部分未知，不抹掉成功数据或伪造0/不存在。 */
@Service
public class UserProfileService {
    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final int RECENT_LIMIT = 50;
    private static final int SEQ_LIMIT = 20;

    @Nullable private final UserProfileMapper mapper;
    @Nullable private final StringRedisTemplate redis;

    /** 依赖可选，但未装配与确定无数据必须通过errors区分。 */
    public UserProfileService(@Nullable UserProfileMapper mapper, @Nullable StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 只有完整读到的画像/行为能判定存在性；各查询不承诺同一事务快照。 */
    public UserProfileView get(long userId) {
        Map<Section, String> errors = new EnumMap<>(Section.class);
        var profile = readDb(Section.PROFILE, () -> mapper.profile(userId), null, errors);
        Boolean embedding = readDb(Section.EMBEDDING, () -> mapper.hasEmbedding(userId), null, errors);
        ActionSummary actions = readDb(Section.ACTIONS, () -> queryActions(userId), null, errors);
        Integer categories = readDb(Section.CATEGORIES, () -> mapper.distinctCategories(userId), null, errors);
        List<String> buckets = readDb(Section.BUCKETS, () -> Objects.requireNonNull(mapper.buckets(userId)), List.of(), errors);
        List<UserProfileView.BehaviorRow> recent = readDb(Section.RECENT, () -> queryRecent(userId), List.of(), errors);
        Boolean exists = null;
        if (profile != null || (actions != null && actions.total() > 0)) exists = true;
        else if (!errors.containsKey(Section.PROFILE) && actions != null) exists = false;

        var stats = new UserProfileView.Stats(actions == null ? null : actions.total(),
                actions == null ? Map.of() : actions.counts(), categories, buckets);
        var realtime = queryRealtime(userId, errors);
        return new UserProfileView(userId, exists, profile == null ? null : profile.profile(),
                profile == null || profile.updatedAt() == null ? null : profile.updatedAt().getTime(),
                embedding, stats, recent, realtime, Map.copyOf(errors));
    }

    /** 各部分共用安全错误策略，返回值是否为空由errors判定，不能吞错后声称成功。 */
    private <T> T readDb(Section section, Supplier<T> query, T unavailable, Map<Section, String> errors) {
        if (mapper == null) {
            errors.put(section, ErrorCode.CONSOLE_USER_DATA_UNAVAILABLE.code());
            return unavailable;
        }
        try {
            return query.get();
        } catch (RuntimeException failure) {
            errors.put(section, ErrorCode.CONSOLE_USER_DATA_UNAVAILABLE.code());
            log.debug("{} section={} failureType={}", ErrorCode.CONSOLE_USER_DATA_UNAVAILABLE.displayMessage(),
                    section.code(), failure.getClass().getSimpleName());
            return unavailable;
        }
    }

    private record ActionSummary(Map<String, Long> counts, long total) { }

    private ActionSummary queryActions(long userId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        mapper.actionCounts(userId, context -> {
            var row = context.getResultObject();
            counts.put(row.action() == null ? "unknown" : row.action(), row.count());
        });
        // 整个游标成功完成后才发布；读取中断不会把已取得子集当完整计数。
        long total = 0;
        for (long count : counts.values()) {
            if (count < 0) throw new IllegalStateException(ErrorCode.CONSOLE_USER_DATA_UNAVAILABLE.code());
            total = Math.addExact(total, count);
        }
        return new ActionSummary(Collections.unmodifiableMap(counts), total);
    }

    private List<UserProfileView.BehaviorRow> queryRecent(long userId) {
        return mapper.recent(userId, RECENT_LIMIT).stream().map(row ->
                new UserProfileView.BehaviorRow(row.itemId(), row.action(),
                        row.ts() == null ? 0L : row.ts().getTime(), row.bucket(), row.position(), row.scene(), row.value()))
                .toList();
    }

    /** 实时态单独降级；坏值或中途失败不能把部分内容当完整实时画像。 */
    private UserProfileView.Realtime queryRealtime(long userId, Map<Section, String> errors) {
        if (redis != null) {
            try {
                Map<String, Long> preferences = new LinkedHashMap<>();
                Map<Object, Object> hash = Objects.requireNonNull(redis.opsForHash().entries(RedisKeys.rtUser(userId)));
                for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                    preferences.put(String.valueOf(entry.getKey()), count(entry.getValue()));
                }
                List<Long> sequence = new ArrayList<>();
                var members = Objects.requireNonNull(redis.opsForZSet().reverseRange(RedisKeys.userSeq(userId), 0, SEQ_LIMIT - 1));
                for (String member : members) sequence.add(Long.parseLong(member));
                Boolean cached = Objects.requireNonNull(redis.hasKey(RedisKeys.recCache(userId)));
                Long seen = Objects.requireNonNull(redis.opsForSet().size(RedisKeys.seenItems(userId)));
                return new UserProfileView.Realtime(true, preferences, sequence, cached, seen);
            } catch (RuntimeException failure) {
                log.debug("{} failureType={}", ErrorCode.CONSOLE_USER_REALTIME_UNAVAILABLE.displayMessage(), failure.getClass().getSimpleName());
            }
        }
        errors.put(Section.REALTIME, ErrorCode.CONSOLE_USER_REALTIME_UNAVAILABLE.code());
        return new UserProfileView.Realtime(false, Map.of(), List.of(), null, null);
    }

    /** 保留浮点计数向零取整的展示口径，但拒绝损坏、负数和超出long范围的值。 */
    private static long count(Object raw) {
        BigDecimal value = new BigDecimal(Objects.requireNonNull(raw).toString());
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(ErrorCode.CONSOLE_USER_REALTIME_UNAVAILABLE.code());
        }
        // 极小科学计数也直接为0，避免对损坏的大指数做无意义的大数缩放。
        if (value.compareTo(BigDecimal.ONE) < 0) return 0;
        return value.setScale(0, RoundingMode.DOWN).longValueExact();
    }
}
