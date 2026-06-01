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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * UserCF(U2U)召回:读 Redis {@code u2u:{userId}} ZSet 取该用户的个性化召回列表。
 *
 * <p>该列表由离线 {@code user-cf} 作业物化:基于行为算 user-user 相似度,
 * 汇总相似用户的正反馈物品(sim 加权、排除已看)写入 ZSet。在线只做一次 O(topN) 读取。
 * 用户无 u2u 列表(新用户/离线未覆盖)时返回空,交由其他路兜底。
 */
@Component
public class UserCfRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(UserCfRecaller.class);

    private final StringRedisTemplate redis;
    private final RecallProperties props;

    public UserCfRecaller(StringRedisTemplate redis, RecallProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.U2U;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        int limit = props.getQuota().getU2u();
        try {
            Set<ZSetOperations.TypedTuple<String>> rows =
                    redis.opsForZSet().reverseRangeWithScores(RedisKeys.u2u(ctx.userId()), 0, limit - 1);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<RecallItem> out = new ArrayList<>(rows.size());
            for (var t : rows) {
                if (t.getValue() == null) {
                    continue;
                }
                out.add(new RecallItem(Long.parseLong(t.getValue()),
                        t.getScore() == null ? 0 : t.getScore(), RecallChannel.U2U));
            }
            return out;
        } catch (Exception e) {
            log.warn("读 u2u 召回失败 user={}: {}", ctx.userId(), e.getMessage());
            return List.of();
        }
    }
}
