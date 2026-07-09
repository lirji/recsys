package com.recsys.recengine;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 轻量配置热更新(S5,docs/06 §2):把「高频调参项」叠加层放到 Redis Hash {@link RedisKeys#TUNING},
 * 周期刷新覆盖静态 {@code application.yml} 默认值 —— 改一个融合权重/开关无需重启进程,也不引入 Nacos。
 *
 * <p>用法:{@code redis-cli hset recsys:tuning fusion.pop-debias.beta 0.8} → 至多 {@link #REFRESH_MS} 后生效。
 * 缺 field / Redis 不可用 → 回退传入的静态默认值(与接入前行为一致,零风险)。缓存刷新避免每请求打 Redis。
 *
 * <p>覆盖的是<strong>值</strong>而非 bean,故不需要 {@code @RefreshScope}/Nacos;编排层在读参数处调本类即可。
 * 真要上生产可平滑替换为 Nacos 配置监听(契约不变)。
 */
@Component
public class DynamicTuningService {

    private static final Logger log = LoggerFactory.getLogger(DynamicTuningService.class);
    private static final long REFRESH_MS = 30_000;

    private final StringRedisTemplate redis;
    private volatile Map<String, String> cache = Map.of();
    private volatile long loadedAt = -1;

    public DynamicTuningService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 动态 double(缺失/非法/Redis 不可用 → def)。 */
    public double getDouble(String field, double def) {
        String v = snapshot().get(field);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 动态 boolean(缺失/Redis 不可用 → def)。 */
    public boolean getBoolean(String field, boolean def) {
        String v = snapshot().get(field);
        return (v == null || v.isBlank()) ? def : Boolean.parseBoolean(v.trim());
    }

    private Map<String, String> snapshot() {
        long now = System.currentTimeMillis();
        if (loadedAt >= 0 && now - loadedAt < REFRESH_MS) {
            return cache;
        }
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.TUNING);
            Map<String, String> m = new HashMap<>(raw.size());
            raw.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
            cache = m;
        } catch (Exception e) {
            log.debug("动态调参读取失败,沿用旧值/默认: {}", e.getMessage());
        }
        loadedAt = now;   // 无论成败都更新时间,避免每请求打 Redis
        return cache;
    }
}
