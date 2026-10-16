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
 * 及独立LIVE/HISTORY入口构建),不再跨上下文直读 {@code user_behavior}(DB-per-service)。
 * 仅 {@code recsys.behavior.seen-source=replica} 时装配。fail-open:异常返回空集(不过滤)。
 *
 * <p>包含登记起点后实际成功消费的旧/在线/历史合法正反馈；未捕获、非法源、源漂移和保留缺口仍未知。
 * Redis丢集合不会由普通重启或lag=0补全；已知记录可受控RESTORE，不能据此声称全历史完整。
 */
@Component
@ConditionalOnProperty(name = "recsys.behavior.seen-source", havingValue = "replica")
public class ReplicaSeenItemsSource implements SeenItemsSource {

    private static final Logger log = LoggerFactory.getLogger(ReplicaSeenItemsSource.class);

    private final StringRedisTemplate redis;

    /** 仅读取engine自有投影，不跨owner补查历史。 */
    public ReplicaSeenItemsSource(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 失败沿原fail-open；结果不携带历史完整性承诺。 */
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
            log.warn("已看查询失败，本次不过滤 type={}", e.getClass().getSimpleName());
            return Set.of();
        }
    }
}
