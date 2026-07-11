package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预算控制 / Pacing(docs/05 §4.7)。
 *
 * <ul>
 *   <li><b>实时熔断</b>:每次计费 {@link #charge} 累加 {@code ad:budget:{adv}:{today}};
 *       {@link #overBudget} 把当日花费 ≥ 日预算的广告主挑出,召回后过滤掉。</li>
 *   <li><b>平滑(pacing)</b>:{@link #pacingFactor} 读 {@code ad:pacing:{adv}}(离线/近线 PID 控制器
 *       按"理想花费曲线 vs 实际"产出的折扣系数 ∈ (0,1]),乘到出价上,避免上午花光。缺失=1.0。</li>
 * </ul>
 *
 * <p>{@code recsys.ad.pacing.enabled=false} 或 Redis 不可用时整体降级为"不限预算",不拖垮链路
 * (本地无 Redis 调试友好)。
 */
@Service
public class PacingService {

    private static final Logger log = LoggerFactory.getLogger(PacingService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 预算计数键 TTL:跨天自然失效(留 2 天冗余便于 T+1 对账)。 */
    private static final Duration BUDGET_TTL = Duration.ofDays(2);

    /** 分片库:advertiser 表(daily_budget)按 advertiser_id 分布在 ds_0/ds_1。 */
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AdProperties props;

    public PacingService(@Qualifier("adShardingJdbc") JdbcTemplate jdbc,
                         StringRedisTemplate redis, AdProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
    }

    /** 过滤掉超预算广告主的候选。pacing 关闭/降级时原样返回。 */
    public List<AdCandidate> filterBudget(List<AdCandidate> candidates) {
        if (!props.getPacing().isEnabled() || candidates.isEmpty()) {
            return candidates;
        }
        Set<Long> advs = candidates.stream().map(AdCandidate::advertiserId).collect(Collectors.toSet());
        Set<Long> over = overBudget(advs);
        if (over.isEmpty()) {
            return candidates;
        }
        List<AdCandidate> kept = new ArrayList<>(candidates.size());
        for (AdCandidate c : candidates) {
            if (!over.contains(c.advertiserId())) {
                kept.add(c);
            }
        }
        log.debug("pacing 过滤超预算广告主 {} 个,候选 {} → {}", over.size(), candidates.size(), kept.size());
        return kept;
    }

    /** 当日花费 ≥ 日预算的广告主集合。Redis/DB 失败 → 视为都未超(放行,fail-open)。 */
    public Set<Long> overBudget(Collection<Long> advertiserIds) {
        if (advertiserIds.isEmpty()) {
            return Set.of();
        }
        try {
            Map<Long, Double> budgets = dailyBudgets(advertiserIds);
            String today = LocalDate.now().format(DAY);
            Set<Long> over = new HashSet<>();
            for (Long adv : advertiserIds) {
                double budget = budgets.getOrDefault(adv, Double.MAX_VALUE);
                double spent = spent(adv, today);
                if (spent >= budget) {
                    over.add(adv);
                }
            }
            return over;
        } catch (Exception e) {
            log.debug("预算检查失败,放行: {}", e.getMessage());
            return Set.of();
        }
    }

    /** pacing 平滑系数 ∈ (0,1];缺失/异常=1.0(不折扣)。 */
    public double pacingFactor(long advertiserId) {
        if (!props.getPacing().isEnabled()) {
            return 1.0;
        }
        try {
            String v = redis.opsForValue().get(RedisKeys.adPacing(advertiserId));
            return parseFactor(v);
        } catch (Exception e) {
            return 1.0;
        }
    }

    /**
     * 批量取 pacing 系数(一次 MGET),供竞价循环预取避免每候选一次 Redis 往返。
     * 返回的 Map 只含"命中且有效(&gt;0)"的广告主;缺失/关闭/异常 → 不入 Map,调用方 getOrDefault(adv, 1.0)。
     */
    public Map<Long, Double> pacingFactors(Collection<Long> advertiserIds) {
        if (!props.getPacing().isEnabled() || advertiserIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = advertiserIds.stream().distinct().collect(Collectors.toList());
        try {
            List<String> keys = ids.stream().map(RedisKeys::adPacing).collect(Collectors.toList());
            List<String> vals = redis.opsForValue().multiGet(keys);
            Map<Long, Double> out = new HashMap<>();
            if (vals != null) {
                for (int i = 0; i < ids.size() && i < vals.size(); i++) {
                    double f = parseFactor(vals.get(i));
                    if (f < 1.0) {   // 只有真折扣才入 Map;=1.0 与缺失等价,交给 getOrDefault
                        out.put(ids.get(i), f);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("批量读 pacing 系数失败,退不折扣: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 解析 pacing 系数字符串:缺失/非法/&le;0 → 1.0;否则 min(1.0, f)。 */
    private static double parseFactor(String v) {
        if (v == null) {
            return 1.0;
        }
        try {
            double f = Double.parseDouble(v);
            return f <= 0 ? 1.0 : Math.min(1.0, f);
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /** 计费扣减:当日花费累加 price(元)。pacing 关闭/Redis 失败时静默(不阻塞返回)。 */
    public void charge(long advertiserId, double price) {
        if (!props.getPacing().isEnabled() || price <= 0) {
            return;
        }
        try {
            String key = RedisKeys.adBudget(advertiserId, LocalDate.now().format(DAY));
            // 用整数分计数避免浮点累加误差
            Long after = redis.opsForValue().increment(key, Math.round(price * 100));
            if (after != null && after == Math.round(price * 100)) {
                redis.expire(key, BUDGET_TTL); // 首次创建时设 TTL
            }
        } catch (Exception e) {
            log.debug("预算扣减失败 adv={} price={}: {}", advertiserId, price, e.getMessage());
        }
    }

    private double spent(long advertiserId, String day) {
        String v = redis.opsForValue().get(RedisKeys.adBudget(advertiserId, day));
        return v == null ? 0.0 : Long.parseLong(v) / 100.0; // 分 → 元
    }

    private Map<Long, Double> dailyBudgets(Collection<Long> advertiserIds) {
        Long[] ids = advertiserIds.toArray(new Long[0]);
        Map<Long, Double> out = new HashMap<>();
        jdbc.query(
                "SELECT advertiser_id, daily_budget FROM advertiser WHERE advertiser_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                rs -> {
                    out.put(rs.getLong("advertiser_id"), rs.getDouble("daily_budget"));
                });
        return out;
    }
}
