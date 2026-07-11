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
            return parseQuality(redis.opsForValue().get(RedisKeys.adQuality(adId)), fallback);
        } catch (Exception e) {
            log.debug("读取精细化质量度失败,退基线 {}: {}", fallback, e.getMessage());
            return fallback;
        }
    }

    /**
     * 批量取精细化质量度(一次 MGET),供竞价循环预取避免每候选一次 Redis 往返。
     * 返回的 Map 只含"命中且有效(&gt;0)"的广告;缺失/关闭/异常 → 不入 Map,调用方 getOrDefault(adId, fallback)。
     */
    public Map<Long, Double> refinedBatch(Collection<Long> adIds) {
        if (!props.getQuality().isEnabled() || adIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = adIds.stream().distinct().collect(Collectors.toList());
        try {
            List<String> keys = ids.stream().map(RedisKeys::adQuality).collect(Collectors.toList());
            List<String> vals = redis.opsForValue().multiGet(keys);
            Map<Long, Double> out = new HashMap<>();
            if (vals != null) {
                for (int i = 0; i < ids.size() && i < vals.size(); i++) {
                    double q = parseQuality(vals.get(i), Double.NaN);
                    if (!Double.isNaN(q)) {
                        out.put(ids.get(i), q);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("批量读精细化质量度失败,退基线: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 解析质量度字符串:命中且有限且&gt;0 → 用它;否则退 {@code fallback}。 */
    private static double parseQuality(String s, double fallback) {
        if (s != null && !s.isBlank()) {
            try {
                double q = Double.parseDouble(s.trim());
                if (Double.isFinite(q) && q > 0) {
                    return q;
                }
            } catch (NumberFormatException ignored) {
                // 非数字 → 退 fallback
            }
        }
        return fallback;
    }
}
