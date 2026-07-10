package com.recsys.recengine.experiment;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 实验动态覆盖(P3 实验平台化)——在静态 {@code recsys.experiment.*}(yml)之上叠加 Redis 覆盖层
 * {@code recsys:exp},实现<b>不重启</b>地开关实验 / 调整流量(放量/停止)。仿 {@code DynamicTuningService}:
 * 内存缓存 + 周期(refreshMs)整表 HGETALL 刷新,{@link ExperimentService} 每次分桶读内存(不打 Redis)。
 *
 * <p>覆盖 field(见 {@link RedisKeys#EXP_OVERRIDE}):
 * <ul>
 *   <li>{@code enabled} = true/false —— 全局实验开关(覆盖 {@code recsys.experiment.enabled});</li>
 *   <li>{@code <layer>.enabled} = true/false —— 单层开关(off → 该层固定基线变体);</li>
 *   <li>{@code <layer>.<variant>.weight} = 整数 —— 变体流量权重(放量=调大、停止=置 0)。</li>
 * </ul>
 * Redis 不可用 / 无覆盖 → 全部返回 null,{@link ExperimentService} 用静态配置(优雅降级)。
 */
@Service
public class ExperimentOverrideService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentOverrideService.class);
    private static final long REFRESH_MS = 30_000;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private volatile Map<String, String> cache = Map.of();
    private volatile long loadedAtMs = 0;

    public ExperimentOverrideService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** 全局实验开关覆盖;无覆盖返回 null(用静态)。 */
    public Boolean globalEnabled() {
        return boolField("enabled");
    }

    /** 层开关覆盖;无覆盖返回 null。 */
    public Boolean layerEnabled(String layer) {
        return boolField(layer + ".enabled");
    }

    /** 变体流量权重覆盖;无覆盖 / 非法返回 null(用静态 weight)。 */
    public Integer variantWeight(String layer, String variant) {
        String v = current().get(layer + "." + variant + ".weight");
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 当前覆盖全表(管理接口展示用)。 */
    public Map<String, String> snapshot() {
        return new HashMap<>(current());
    }

    private Boolean boolField(String field) {
        String v = current().get(field);
        if (v == null) {
            return null;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private Map<String, String> current() {
        long now = System.currentTimeMillis();
        if (now - loadedAtMs < REFRESH_MS && loadedAtMs != 0) {
            return cache;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAtMs < REFRESH_MS && loadedAtMs != 0) {
                return cache;
            }
            cache = load();
            loadedAtMs = System.currentTimeMillis();
            return cache;
        }
    }

    private Map<String, String> load() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return cache;
        }
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.EXP_OVERRIDE);
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<String, String> m = new HashMap<>(raw.size() * 2);
            raw.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
            return m;
        } catch (Exception e) {
            log.debug("加载实验覆盖失败,用静态配置: {}", e.getMessage());
            return cache;
        }
    }

    /** 强制立即刷新(管理接口写入后调用,让改动秒级生效而不必等 30s)。 */
    public void invalidate() {
        loadedAtMs = 0;
    }
}
