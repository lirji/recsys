package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.common.user.UserProfileReader;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final ItemCatalogReader itemCatalog;   // #3:热路径 item 读经此 seam(db 直读 item / replica 读 item_local)
    private final StringRedisTemplate redis;
    private final RecallProperties props;
    private final UserProfileReader userProfileReader;   // #3:读 app_user 类目经此 seam(db|grpc)

    public TagRecaller(ItemCatalogReader itemCatalog, StringRedisTemplate redis, RecallProperties props,
                       UserProfileReader userProfileReader) {
        this.itemCatalog = itemCatalog;
        this.redis = redis;
        this.props = props;
        this.userProfileReader = userProfileReader;
    }

    @Override
    public RecallChannel channel() {
        return RecallChannel.TAG;
    }

    @Override
    public List<RecallItem> recall(RecallContext ctx) {
        // 类目 → 权重(实时类目 >1,静态画像类目 =1,query 意图类目按意图分加权)
        Map<String, Double> weights = categoryWeights(ctx);
        if (weights.isEmpty()) {
            return List.of();
        }
        int limit = props.getQuota().getTag();
        List<String> categories = new ArrayList<>(weights.keySet());
        // 候选池放大(类目可能多、实时加权可能把低热度类目顶上来),拉回内存按加权分截断
        int poolSize = Math.max(limit * 4, limit);

        List<RecallItem> pool = new ArrayList<>();
        for (ItemCatalogReader.CatItem ci : itemCatalog.byCategories(categories, poolSize)) {
            double w = weights.getOrDefault(ci.category(), 1.0);
            pool.add(new RecallItem(ci.itemId(), ci.popularity() * w, RecallChannel.TAG));
        }
        // 加权后重排截断(SQL 按原始 popularity 排,加权改变了顺序)
        pool.sort((a, b) -> Double.compare(b.recallScore(), a.recallScore()));
        return pool.size() > limit ? new ArrayList<>(pool.subList(0, limit)) : pool;
    }

    /**
     * 合并三路类目偏好,产出 类目→权重(同类目命中多路取最大权重):
     * <ol>
     *   <li>静态画像类目:基准权重 1.0;</li>
     *   <li>实时类目(rt:user):权重 1+realtimeBoost·(count/maxCount);</li>
     *   <li><b>query 意图类目</b>(ctx.params["intentCategories"],Query 理解层产出):
     *       权重 1+intentBoost·score —— 让搜索意图主导 TAG 召回。</li>
     * </ol>
     */
    private Map<String, Double> categoryWeights(RecallContext ctx) {
        long userId = ctx.userId();
        Map<String, Double> weights = new LinkedHashMap<>();
        RecallProperties.Tag tagCfg = props.getTag();
        // 1. 静态画像类目(基准权重 1.0)
        for (String c : preferredCategories(userId)) {
            if (c != null && !c.isEmpty()) {
                weights.putIfAbsent(c, 1.0);
            }
        }
        // 2. 实时类目偏好叠加
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
        // 3. query 意图类目叠加(搜索场景)
        for (var e : parseIntentCategories(ctx.params().get("intentCategories")).entrySet()) {
            double w = 1.0 + tagCfg.getIntentBoost() * e.getValue();
            weights.merge(e.getKey(), w, Math::max);
        }
        return weights;
    }

    /** 解析编排层传入的 "类目:分,类目:分" 为 类目→意图分;格式异常的项跳过。 */
    private static Map<String, Double> parseIntentCategories(String csv) {
        if (csv == null || csv.isBlank()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String pair : csv.split(",")) {
            int i = pair.lastIndexOf(':');
            if (i <= 0 || i == pair.length() - 1) {
                continue;
            }
            String cat = pair.substring(0, i).trim();
            if (cat.isEmpty()) {
                continue;
            }
            try {
                out.put(cat, Double.parseDouble(pair.substring(i + 1).trim()));
            } catch (NumberFormatException ignore) {
                // 分数解析失败的项跳过
            }
        }
        return out;
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
        // #3:经 UserProfileReader 读(默认 db 直读 app_user;grpc 走 user-service),不再本类直读用户库。
        try {
            return userProfileReader.categories(userId);
        } catch (Exception e) {
            log.debug("读取用户偏好类目失败: {}", e.getMessage());
            return List.of();
        }
    }
}
