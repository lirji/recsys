package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 热门召回:兜底/冷启动路在明确资源预算内逐级取数，依赖均不可用时返回空:
 * <ol>
 *   <li>实时热门 {@code recall:rt_hot}(Flink 流作业近实时更新,反映"此刻在热什么");</li>
 *   <li>离线热门 {@code recall:hot}(HotJob T+1 物化);</li>
 *   <li>单飞查 item 表 popularity Top-N。</li>
 * </ol>
 * 实时优先体现"实时特征生效";流作业没跑时自动回落离线；故障与过载受C20预算保护。
 */
@Component
public class HotRecaller implements ChannelRecaller {

    private final StringRedisTemplate redis;
    private final ItemCatalogReader itemCatalog;   // #3:热路径 item 读经此 seam(db 直读 item / replica 读 item_local)
    private final RecallProperties props;
    private final String realtimeKey;
    private final HotReadBudget budget;

    public HotRecaller(StringRedisTemplate redis, ItemCatalogReader itemCatalog, RecallProperties props) {
        this(redis, itemCatalog, props, "");
    }

    /** 新投影只在运维显式选择namespace后参与在线召回，默认保留原读路径。 */
    public HotRecaller(StringRedisTemplate redis, ItemCatalogReader itemCatalog, RecallProperties props,
                       @org.springframework.beans.factory.annotation.Value("${recsys.streaming.namespace:}") String namespace) {
        this(redis, itemCatalog, props, namespace, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** 运行时复用应用指标；资源由本Spring组件持有并在退出时关闭。 */
    @org.springframework.beans.factory.annotation.Autowired
    public HotRecaller(StringRedisTemplate redis, ItemCatalogReader itemCatalog, RecallProperties props,
                       @org.springframework.beans.factory.annotation.Value("${recsys.streaming.namespace:}") String namespace,
                       org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        this(redis, itemCatalog, props, namespace, meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
    }

    public HotRecaller(StringRedisTemplate redis, ItemCatalogReader itemCatalog, RecallProperties props, String namespace,
                       io.micrometer.core.instrument.MeterRegistry meters) {
        this.redis = redis;
        this.itemCatalog = itemCatalog;
        this.props = props;
        this.realtimeKey = RedisKeys.realtimeHot(namespace);
        this.budget = new HotReadBudget(props.getCacheBudget(), meters);
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.HOT;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        int limit = props.getQuota().getHot();
        return budget.read(limit, () -> {
            List<RecallItem> realtime = fromRedis(realtimeKey, limit);
            return realtime.isEmpty() ? fromRedis(RedisKeys.HOT_RECALL, limit) : realtime;
        }, () -> fromDb(limit));
    }

    @jakarta.annotation.PreDestroy
    public void close() { budget.close(); }

    private List<RecallItem> fromRedis(String key, int limit) {
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
    }

    private List<RecallItem> fromDb(int limit) {
        List<RecallItem> out = new ArrayList<>();
        for (ItemCatalogReader.ScoredId s : itemCatalog.hotByPopularity(limit)) {
            out.add(new RecallItem(s.itemId(), s.score(), RecallChannel.HOT));
        }
        return out;
    }
}
