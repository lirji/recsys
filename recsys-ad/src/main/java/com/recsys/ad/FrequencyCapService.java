package com.recsys.ad;

import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 频控(docs/05 §4.8):限制同一用户当日对「同广告 / 同广告主」的曝光次数,防骚扰。
 * Redis 日维度计数(带 TTL),{@link #filter} 在混排前丢弃已超频的广告,{@link #record} 在
 * 广告真正展示后累加计数。
 *
 * <p><b>优雅降级</b>:开关关闭 / Redis 不可用 → 不限频(返回原列表 / 静默),不拖垮主链路。
 */
@Service
public class FrequencyCapService {

    private static final Logger log = LoggerFactory.getLogger(FrequencyCapService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 计数 TTL:覆盖当日即可,给 2 天冗余防跨天竞态。 */
    private static final Duration TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final AdProperties props;

    public FrequencyCapService(StringRedisTemplate redis, AdProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 丢弃当日已达「同广告 perAd 次」或「同广告主 perAdvertiser 次」上限的广告。 */
    public List<SponsoredAd> filter(long userId, List<SponsoredAd> ads) {
        if (!props.getFreq().isEnabled() || ads.isEmpty()) {
            return ads;
        }
        String day = LocalDate.now().format(DAY);
        try {
            List<SponsoredAd> out = new ArrayList<>(ads.size());
            for (SponsoredAd a : ads) {
                long adCnt = count(RedisKeys.adFreq(userId, a.adId(), day));
                long advCnt = count(RedisKeys.adFreqAdvertiser(userId, a.advertiserId(), day));
                if (adCnt < props.getFreq().getPerAd() && advCnt < props.getFreq().getPerAdvertiser()) {
                    out.add(a);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("频控读取失败,降级不限频: {}", e.getMessage());
            return ads;
        }
    }

    /** 广告真正展示后累加当日计数(同广告 + 同广告主)。 */
    public void record(long userId, List<SponsoredAd> shown) {
        if (!props.getFreq().isEnabled() || shown == null || shown.isEmpty()) {
            return;
        }
        String day = LocalDate.now().format(DAY);
        try {
            for (SponsoredAd a : shown) {
                incr(RedisKeys.adFreq(userId, a.adId(), day));
                incr(RedisKeys.adFreqAdvertiser(userId, a.advertiserId(), day));
            }
        } catch (Exception e) {
            log.debug("频控计数失败(忽略): {}", e.getMessage());
        }
    }

    private long count(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    private void incr(String key) {
        Long v = redis.opsForValue().increment(key);
        if (v != null && v == 1L) {
            redis.expire(key, TTL);   // 首次创建时设 TTL
        }
    }
}
