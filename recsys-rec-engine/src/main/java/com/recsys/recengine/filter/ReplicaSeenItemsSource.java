package com.recsys.recengine.filter;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 副本已看来源(P4):读 rec-engine 自有的 {@code seen:{user}} Redis Set 读模型(由 {@link SeenItemsConsumer}
 * 从 {@code behavior-events} 构建),不再跨上下文直读 {@code user_behavior}(DB-per-service)。
 * 仅 {@code recsys.behavior.seen-source=replica} 时装配。fail-open:异常返回空集(不过滤)。
 *
 * <p>注:读模型只含消费者启动后到达的事件;历史已看需一次性从 user_behavior 回填 {@code seen:{user}}(P4 收尾项)。
 */
@Component
@ConditionalOnProperty(name = "recsys.behavior.seen-source", havingValue = "replica")
public class ReplicaSeenItemsSource implements SeenItemsSource {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSeenItemsSource.class);

    private final StringRedisTemplate redis;

    public ReplicaSeenItemsSource(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Set<Long> seenItems(long userId) {
        try {
            Set<String> members = redis.opsForSet().members(RedisKeys.seenItems(userId));
            if (members == null || members.isEmpty()) {
                return Set.of();
            }
            Set<Long> out = new HashSet<>(members.size());
            for (String m : members) {
                try {
                    out.add(Long.parseLong(m));
                } catch (NumberFormatException ignore) {
                    // 跳过脏成员
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("已看查询失败(seen 读模型),本次不过滤 user={}: {}", userId, e.getMessage());
            return Set.of();
        }
    }
}
