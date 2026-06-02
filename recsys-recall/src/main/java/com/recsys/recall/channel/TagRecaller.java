package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签召回:按用户偏好类目取该类目下的热门物品。类目来源两路合并——
 * <ol>
 *   <li><b>静态画像</b>:{@code app_user.profile->categories}(onboarding / 长期偏好)。</li>
 *   <li><b>实时偏好</b>:{@code rt:user:{id}} Hash(Flink 流作业按滑动窗口正反馈计数维护,
 *       field=类目、value=近期计数),反映"用户此刻在看哪类"。</li>
 * </ol>
 * 实时类目按近期计数加权(权重=1+boost·count/maxCount),让当下在看的类目物品在 TAG 内排前;
 * 实时独有(画像里还没有)的类目也纳入,补画像滞后。两路口径一致:Hash field 与
 * profile categories 都是 {@code item.category} 整串(Flink 富化用 catMap.get(itemId),与本类
 * {@code WHERE category IN (...)} 同口径)。Redis 不可用时优雅降级为纯静态画像(原行为)。
 */
@Component
public class TagRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(TagRecaller.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final RecallProperties props;

    public TagRecaller(JdbcTemplate jdbc, StringRedisTemplate redis, RecallProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.TAG;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        // 类目 → 权重(实时类目 >1,静态画像类目 =1)
        Map<String, Double> weights = categoryWeights(ctx.userId());
        if (weights.isEmpty()) {
            return List.of();
        }
        int limit = props.getQuota().getTag();
        List<String> categories = new ArrayList<>(weights.keySet());
        String placeholders = String.join(",", categories.stream().map(c -> "?").toList());
        // 候选池放大(类目可能多、实时加权可能把低热度类目顶上来),拉回内存按加权分截断
        int poolSize = Math.max(limit * 4, limit);
        Object[] params = new Object[categories.size() + 1];
        for (int i = 0; i < categories.size(); i++) {
            params[i] = categories.get(i);
        }
        params[categories.size()] = poolSize;

        List<RecallItem> pool = jdbc.query(
                "SELECT item_id, category, popularity FROM item WHERE category IN (" + placeholders + ") " +
                "ORDER BY popularity DESC LIMIT ?",
                (rs, n) -> {
                    double w = weights.getOrDefault(rs.getString("category"), 1.0);
                    return new RecallItem(rs.getLong("item_id"), rs.getDouble("popularity") * w, RecallChannel.TAG);
                },
                params);
        // 加权后重排截断(SQL 按原始 popularity 排,加权改变了顺序)
        pool.sort((a, b) -> Double.compare(b.recallScore(), a.recallScore()));
        return pool.size() > limit ? new ArrayList<>(pool.subList(0, limit)) : pool;
    }

    /**
     * 合并静态画像类目与实时类目偏好,产出 类目→权重。
     * 静态画像类目权重 1.0;实时类目权重 1+boost·(count/maxCount)(若同时也在画像里,取较大的实时权重)。
     */
    private Map<String, Double> categoryWeights(long userId) {
        Map<String, Double> weights = new LinkedHashMap<>();
        // 1. 静态画像类目(基准权重 1.0)
        for (String c : preferredCategories(userId)) {
            if (c != null && !c.isEmpty()) {
                weights.putIfAbsent(c, 1.0);
            }
        }
        // 2. 实时类目偏好叠加
        RecallProperties.Tag tagCfg = props.getTag();
        if (tagCfg.isRealtimeEnabled()) {
            Map<Object, Object> rt = readRealtimeCategories(userId);
            long max = 0;
            for (Object v : rt.values()) {
                max = Math.max(max, parseCount(v));
            }
            if (max > 0) {
                for (var e : rt.entrySet()) {
                    String cat = String.valueOf(e.getKey());
                    if (cat.isEmpty()) {
                        continue;
                    }
                    double w = 1.0 + tagCfg.getRealtimeBoost() * (parseCount(e.getValue()) / (double) max);
                    weights.merge(cat, w, Math::max);
                }
            }
        }
        return weights;
    }

    private Map<Object, Object> readRealtimeCategories(long userId) {
        try {
            Map<Object, Object> rt = redis.opsForHash().entries(RedisKeys.rtUser(userId));
            return rt == null ? Map.of() : rt;
        } catch (Exception e) {
            log.debug("读实时类目偏好失败,降级纯画像: {}", e.getMessage());
            return Map.of();
        }
    }

    private static long parseCount(Object v) {
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> preferredCategories(long userId) {
        try {
            // 从 JSONB profile 的 categories 数组取值
            return jdbc.queryForList(
                    "SELECT jsonb_array_elements_text(profile->'categories') FROM app_user WHERE user_id=?",
                    String.class, userId);
        } catch (Exception e) {
            log.debug("读取用户偏好类目失败: {}", e.getMessage());
            return List.of();
        }
    }
}
