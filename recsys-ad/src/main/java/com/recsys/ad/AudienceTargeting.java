package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 广告智能定向(A3)——按 Look-alike 人群包过滤候选广告:绑定了 {@code audience_id} 的广告,
 * 只对<b>命中该人群包</b>(离线扩散、物化在 Redis set {@code ad:audience:{id}})的用户投放;
 * 未定向(audience_id 为空)的广告对全体可见。
 *
 * <p>召回后、竞价前的硬过滤(与相关性门槛/预算过滤并列)。批量取候选广告的 audience_id,
 * 再对定向广告做一次 Redis 批量 SISMEMBER 判定,零向量计算、O(候选数)。
 *
 * <p>优雅降级:Redis 不可用 → <b>fail-open</b>(放行,不因定向存储故障拉低填充率,与本仓"Redis 挂则不限"一致);
 * 广告无 audience_id → 直接放行。
 */
@Service
public class AudienceTargeting {

    private static final Logger log = LoggerFactory.getLogger(AudienceTargeting.class);

    private final AdCatalogReader catalog;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public AudienceTargeting(AdCatalogReader catalog, ObjectProvider<StringRedisTemplate> redisProvider) {
        this.catalog = catalog;
        this.redisProvider = redisProvider;
    }

    /** 过滤:保留 未定向 或 用户命中其人群包 的候选。 */
    public List<AdCandidate> filter(long userId, List<AdCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        Set<Long> adIds = new LinkedHashSet<>();
        for (AdCandidate c : candidates) {
            adIds.add(c.adId());
        }
        Map<Long, Long> audienceByAd = catalog.audiencesByAd(adIds);
        if (audienceByAd.isEmpty()) {
            return candidates;   // 全部未定向,无需过滤
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        List<AdCandidate> out = new ArrayList<>(candidates.size());
        for (AdCandidate c : candidates) {
            Long aud = audienceByAd.get(c.adId());
            if (aud == null || inAudience(redis, userId, aud)) {
                out.add(c);
            }
        }
        return out;
    }

    private boolean inAudience(StringRedisTemplate redis, long userId, long audienceId) {
        if (redis == null) {
            return true;   // fail-open:定向存储不可用不拉低填充
        }
        try {
            Boolean member = redis.opsForSet().isMember(RedisKeys.adAudience(audienceId), String.valueOf(userId));
            return Boolean.TRUE.equals(member);
        } catch (Exception e) {
            log.debug("人群包判定失败,放行 user={} audience={}: {}", userId, audienceId, e.getMessage());
            return true;
        }
    }
}
