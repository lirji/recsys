package com.recsys.ad;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 反作弊(docs/05 §6):点击有效性判定,守住计费公平——只有有效点击才扣费。两道在线规则:
 * <ul>
 *   <li><b>去重</b>:同一曝光({@code requestId+adId})的点击只计一次(Redis setIfAbsent 带 TTL),
 *       防重复提交 / 双击 / 客户端重试导致的多次扣费;</li>
 *   <li><b>频次</b>:同一用户每分钟点击数超 {@code maxClicksPerMinute} 判机器流量。</li>
 * </ul>
 * 还有一道在编排层:点击必须能归因到真实曝光({@code readAttribution} 非空),否则也判无效。
 *
 * <p><b>fail-open</b>:开关关 / Redis 不可用 → 判有效(不误伤正常点击,可用性优先;与 PacingService
 * 同一降级哲学)。无效原因经 {@link Verdict} 返回,编排层据此落 INVALID_CLICK + 打点、跳过扣费。
 */
@Service
public class AntiFraudService {

    private static final Logger log = LoggerFactory.getLogger(AntiFraudService.class);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** 去重键 TTL:覆盖一次曝光的合理点击窗口即可。 */
    private static final Duration DEDUP_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final AdProperties props;

    public AntiFraudService(StringRedisTemplate redis, AdProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 点击有效性判定。{@code valid=true} 才应计费。 */
    public Verdict check(String requestId, long adId, long userId) {
        if (!props.getAntiFraud().isEnabled()) {
            return Verdict.VALID;
        }
        try {
            // 1. 去重:首次点击 setIfAbsent 成功 = 有效;已存在 = 重复点击
            Boolean first = redis.opsForValue().setIfAbsent(
                    RedisKeys.adClicked(requestId, adId), "1", DEDUP_TTL);
            if (Boolean.FALSE.equals(first)) {
                return Verdict.invalid("duplicate");
            }
            // 2. 频次:同用户每分钟点击计数
            String key = RedisKeys.adClickRate(userId, LocalDateTime.now().format(MINUTE));
            Long cnt = redis.opsForValue().increment(key);
            if (cnt != null && cnt == 1L) {
                redis.expire(key, Duration.ofMinutes(2));
            }
            if (cnt != null && cnt > props.getAntiFraud().getMaxClicksPerMinute()) {
                return Verdict.invalid("velocity");
            }
            return Verdict.VALID;
        } catch (Exception e) {
            log.debug("反作弊判定失败,fail-open 放行: {}", e.getMessage());
            return Verdict.VALID;
        }
    }

    /** 判定结果:valid + 无效原因(valid 时 reason 为空)。 */
    public record Verdict(boolean valid, String reason) {
        public static final Verdict VALID = new Verdict(true, "");

        public static Verdict invalid(String reason) {
            return new Verdict(false, reason);
        }
    }
}
