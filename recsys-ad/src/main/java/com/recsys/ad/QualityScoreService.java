package com.recsys.ad;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 精细化质量度在线查表(docs/05 §7 M7):把进 eCPM 的 {@code quality} 从广告自带的随机基线
 * ({@code ad.quality_score})替换为离线 {@code ad-quality} 作业算好的<b>可解释、数据驱动</b>分数
 * (相关性 × 经验 CTR × CVR 融合,围绕 1.0 的乘子),写在 Redis {@link RedisKeys#adQuality}。
 *
 * <p><b>优雅降级</b>(守不引入噪声原则):开关关 / Redis 不可用 / 该广告无精细化分(新广告无历史)→
 * 退回入参 {@code fallback}(即 {@code ad.quality_score});异常同样退 fallback。于是"有数据的广告用
 * 精细分、没数据的广告用基线",平滑过渡、零硬切换。
 *
 * <p>质量度同时进<b>排序与计费</b>(它是校准后历史聚合的乘子,非未校准的单次概率,不违反 §9.3 红线);
 * 与 {@link ExplorationService} 的临时探索加成(只进排序)正交,二者在 Ad Rank 上各自相乘。
 */
@Service
public class QualityScoreService {

    private static final Logger log = LoggerFactory.getLogger(QualityScoreService.class);

    private final StringRedisTemplate redis;
    private final AdProperties props;

    public QualityScoreService(StringRedisTemplate redis, AdProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 广告 {@code adId} 的有效质量度。开启且 Redis 命中精细化分 → 用它;否则退 {@code fallback}。
     *
     * @param adId     广告 ID
     * @param fallback 兜底质量度(通常是 {@code ad.quality_score})
     */
    public double refined(long adId, double fallback) {
        if (!props.getQuality().isEnabled()) {
            return fallback;
        }
        try {
            String s = redis.opsForValue().get(RedisKeys.adQuality(adId));
            if (s != null && !s.isBlank()) {
                double q = Double.parseDouble(s.trim());
                if (Double.isFinite(q) && q > 0) {
                    return q;
                }
            }
        } catch (Exception e) {
            log.debug("读取精细化质量度失败,退基线 {}: {}", fallback, e.getMessage());
        }
        return fallback;
    }
}
