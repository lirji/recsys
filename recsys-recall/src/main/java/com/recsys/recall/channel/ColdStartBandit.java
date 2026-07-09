package com.recsys.recall.channel;

import com.recsys.common.constant.RedisKeys;
import com.recsys.recall.RecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 冷启动类目 bandit(UCB1):给每个类目一个「探索-利用」分,驱动 {@link ColdStartRecaller} 的类目探索。
 *
 * <pre>ucb(cat) = 经验正反馈率 + coef · sqrt( ln(total + e) / (catImpr + 1) )</pre>
 * <ul>
 *   <li><b>exploit</b>:经验正反馈率(pos/impr)高的类目(历史上冷用户更买账)优先;</li>
 *   <li><b>explore</b>:曝光越少的类目探索加成越大,保证欠试探的类目也有机会浮现。</li>
 * </ul>
 *
 * <p>统计 {@code cold:cat:{category}}("impr,pos")/{@code cold:cat:total} 由离线
 * {@code cold-bandit-stats} 物化。<b>降级</b>:关闭 / Redis 无统计 → 所有类目 impr=0 得同一探索加成
 * → 退化为按类目名次均匀铺开(等同旧的 {@link ColdStartRecaller} 行为);异常 → 0(不改排序)。
 *
 * <p>与广告 {@code ExplorationService} 同 UCB 家族:一个抬新广告曝光、一个探冷用户类目偏好。
 */
@Component
public class ColdStartBandit {

    private static final Logger log = LoggerFactory.getLogger(ColdStartBandit.class);

    private final StringRedisTemplate redis;
    private final RecallProperties props;
    private volatile long cachedTotal = -1;

    public ColdStartBandit(StringRedisTemplate redis, RecallProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public boolean isEnabled() {
        return props.getColdBandit().isEnabled();
    }

    /** 类目 UCB 分;关闭 / 异常 → 0(调用方据此退回按热度铺开)。 */
    public double score(String category) {
        RecallProperties.ColdBandit cfg = props.getColdBandit();
        if (!cfg.isEnabled() || category == null) {
            return 0.0;
        }
        try {
            long[] s = readStats(RedisKeys.coldCatStats(category));   // [impr, pos]
            long impr = s[0], pos = s[1];
            double posRate = impr > 0 ? (double) pos / impr : 0.0;
            double bonus = cfg.getCoef() * Math.sqrt(Math.log(total() + Math.E) / (impr + 1.0));
            return posRate + bonus;
        } catch (Exception e) {
            log.debug("冷启动 bandit 分计算失败 cat={},退 0: {}", category, e.getMessage());
            return 0.0;
        }
    }

    private long total() {
        long t = cachedTotal;
        if (t >= 0) {
            return t;
        }
        long v = readStats(RedisKeys.COLD_CAT_TOTAL)[0];
        cachedTotal = Math.max(1, v);   // 每实例缓存一次,避免每候选打 Redis;缺失退 1
        return cachedTotal;
    }

    /** 解析 "impr,pos"(或纯数字=impr);缺失/异常 → [0,0]。 */
    private long[] readStats(String key) {
        String s = redis.opsForValue().get(key);
        if (s == null || s.isBlank()) {
            return new long[]{0, 0};
        }
        try {
            int comma = s.indexOf(',');
            long impr = Long.parseLong((comma >= 0 ? s.substring(0, comma) : s).trim());
            long pos = comma >= 0 ? Long.parseLong(s.substring(comma + 1).trim()) : 0;
            return new long[]{impr, pos};
        } catch (NumberFormatException e) {
            return new long[]{0, 0};
        }
    }
}
