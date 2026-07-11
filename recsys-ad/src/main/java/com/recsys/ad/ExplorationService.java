package com.recsys.ad;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 新广告 EE 探索(docs/05 §6):解决"新广告无 CTR 历史 → 纯 eCPM 排序永远不给曝光 → 永远是新广告"
 * 的冷启动陷阱。给曝光不足的广告一个 <b>UCB 探索加成</b>,抬升其<b>排序</b> eCPM,让它有机会拿到
 * 曝光、积累数据;加成随曝光增长衰减,最终回归纯 eCPM。
 *
 * <pre>boost = 1 + coef · sqrt( ln(total + e) / (adImp + 1) ),  封顶 maxBoost</pre>
 *
 * <p><b>只进排序、不进计费</b>:boost 仅用于 Ad Rank 排序(谁出现),GSP 计费仍按校准 pCTR 算
 * (见 {@link BiddingService}),守住"未校准概率不进计费"的红线(docs/05 §9.3)。
 *
 * <p>统计 {@code ad:stats:{adId}} / {@code ad:stats:total} 由离线 {@code ad-explore-stats} 物化。
 * <b>优雅降级</b>:开关关 / Redis 不可用 / 无统计 → 新广告(adImp≈0)得最大加成,老广告 boost→1;
 * 整体异常 → boost=1.0(退化为无探索的纯 eCPM)。
 */
@Service
public class ExplorationService {

    private static final Logger log = LoggerFactory.getLogger(ExplorationService.class);

    private final StringRedisTemplate redis;
    private final AdProperties props;
    private volatile long cachedTotal = -1;

    public ExplorationService(StringRedisTemplate redis, AdProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 广告 {@code adId} 的探索加成(≥1)。曝光越少越大,随曝光衰减;关闭/异常 → 1.0。 */
    public double boost(long adId) {
        AdProperties.Exploration cfg = props.getExploration();
        if (!cfg.isEnabled()) {
            return 1.0;
        }
        try {
            long adImp = readLong(RedisKeys.adStats(adId));     // 缺失=0 → 新广告,最大加成
            return ucbBoost(adImp, total(), cfg);
        } catch (Exception e) {
            log.debug("EE 探索加成计算失败,退 1.0: {}", e.getMessage());
            return 1.0;
        }
    }

    /**
     * 批量取探索加成(一次 MGET),供竞价循环预取避免每候选一次 Redis 往返。
     * <b>对每个请求的广告都算加成</b>(缺 stats → adImp=0 → 新广告最大加成);关闭/异常 → 返回空 Map,
     * 调用方 getOrDefault(adId, 1.0) 退无探索。
     */
    public Map<Long, Double> boosts(Collection<Long> adIds) {
        AdProperties.Exploration cfg = props.getExploration();
        if (!cfg.isEnabled() || adIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = adIds.stream().distinct().collect(Collectors.toList());
        try {
            List<String> keys = ids.stream().map(RedisKeys::adStats).collect(Collectors.toList());
            List<String> vals = redis.opsForValue().multiGet(keys);
            long total = total();
            Map<Long, Double> out = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                long adImp = parseImp(vals != null && i < vals.size() ? vals.get(i) : null);
                out.put(ids.get(i), ucbBoost(adImp, total, cfg));
            }
            return out;
        } catch (Exception e) {
            log.debug("批量 EE 探索加成计算失败,退 1.0: {}", e.getMessage());
            return Map.of();
        }
    }

    private static double ucbBoost(long adImp, long total, AdProperties.Exploration cfg) {
        double ucb = cfg.getCoef() * Math.sqrt(Math.log(total + Math.E) / (adImp + 1.0));
        return Math.min(cfg.getMaxBoost(), 1.0 + ucb);
    }

    private long total() {
        long t = cachedTotal;
        if (t >= 0) {
            return t;
        }
        long v = readLong(RedisKeys.AD_STATS_TOTAL);
        cachedTotal = Math.max(1, v);   // 每实例缓存一次,避免每候选一次 Redis;缺失退 1
        return cachedTotal;
    }

    /** ad:stats 形如 "imp,clk" 或纯数字;取首段为曝光数。缺失/异常 → 0。 */
    private long readLong(String key) {
        return parseImp(redis.opsForValue().get(key));
    }

    /** 解析 ad:stats 字符串("imp,clk" 或纯数字)首段为曝光数;缺失/异常 → 0。 */
    private static long parseImp(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        int comma = s.indexOf(',');
        String imp = comma >= 0 ? s.substring(0, comma) : s;
        try {
            return Long.parseLong(imp.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
