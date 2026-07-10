package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.content.ItemCatalogReader;
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
 * 热门召回:这是兜底/冷启动路,必须始终可用。三级取数,逐级降级:
 * <ol>
 *   <li>实时热门 {@code recall:rt_hot}(Flink 流作业近实时更新,反映"此刻在热什么");</li>
 *   <li>离线热门 {@code recall:hot}(HotJob T+1 物化);</li>
 *   <li>直接查 item 表 popularity Top-N(永不空手)。</li>
 * </ol>
 * 实时优先体现"实时特征生效";流作业没跑时自动回落离线,零依赖。
 */
@Component
public class HotRecaller implements ChannelRecaller {

    private static final Logger log = LoggerFactory.getLogger(HotRecaller.class);

    private final StringRedisTemplate redis;
    private final ItemCatalogReader itemCatalog;   // #3:热路径 item 读经此 seam(db 直读 item / replica 读 item_local)
    private final RecallProperties props;

    public HotRecaller(StringRedisTemplate redis, ItemCatalogReader itemCatalog, RecallProperties props) {
        this.redis = redis;
        this.itemCatalog = itemCatalog;
        this.props = props;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.HOT;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        int limit = props.getQuota().getHot();
        // 1. 实时热门优先(Flink 流作业近实时维护)
        List<RecallItem> rt = fromRedis(RedisKeys.RT_HOT_RECALL, limit);
        if (!rt.isEmpty()) {
            return rt;
        }
        // 2. 离线热门
        List<RecallItem> offline = fromRedis(RedisKeys.HOT_RECALL, limit);
        if (!offline.isEmpty()) {
            return offline;
        }
        // 3. 降级:直接查库 popularity(保证热门兜底永远有结果)
        return fromDb(limit);
    }

    private List<RecallItem> fromRedis(String key, int limit) {
        try {
            Set<ZSetOperations.TypedTuple<String>> hot =
                    redis.opsForZSet().reverseRangeWithScores(key, 0, limit - 1);
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
        List<RecallItem> out = new ArrayList<>();
        for (ItemCatalogReader.ScoredId s : itemCatalog.hotByPopularity(limit)) {
            out.add(new RecallItem(s.itemId(), s.score(), RecallChannel.HOT));
        }
        return out;
    }
}
