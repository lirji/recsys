package com.recsys.recengine.experiment;

import com.recsys.common.constant.RedisKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验管理面(P3 实验平台化)——把"改实验要改 yml + 重启"变成<b>在线接口热更</b>:开关实验 / 放量 / 停止。
 * 写 Redis 覆盖层 {@code recsys:exp}(见 {@link ExperimentOverrideService}),写后立即 invalidate 让秒级生效。
 *
 * <p>接口(挂 {@code /api/experiment/**},网关已放行):
 * <ul>
 *   <li>{@code GET  /api/experiment} —— 当前静态配置 + 动态覆盖快照;</li>
 *   <li>{@code POST /api/experiment/enabled?value=true|false} —— 全局开关;</li>
 *   <li>{@code POST /api/experiment/{layer}/enabled?value=...} —— 单层开关(停某层实验);</li>
 *   <li>{@code POST /api/experiment/{layer}/{variant}/weight?value=N} —— 变体流量权重(放量/停止=置 0);</li>
 *   <li>{@code DELETE /api/experiment/override} —— 清空覆盖,回落 yml。</li>
 * </ul>
 * Redis 不可用时返回 {@code ok:false}(在线分桶仍按静态配置运行,不受影响)。
 */
@RestController
@RequestMapping("/api/experiment")
public class ExperimentAdminController {

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ExperimentOverrideService override;
    private final ExperimentProperties props;

    public ExperimentAdminController(ObjectProvider<StringRedisTemplate> redisProvider,
                                     ExperimentOverrideService override, ExperimentProperties props) {
        this.redisProvider = redisProvider;
        this.override = override;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> current() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staticEnabled", props.isEnabled());
        Map<String, Object> layers = new LinkedHashMap<>();
        props.getLayers().forEach((name, cfg) -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("salt", cfg.getSalt());
            Map<String, Integer> vs = new LinkedHashMap<>();
            if (cfg.getVariants() != null) {
                cfg.getVariants().forEach(v -> vs.put(v.getName(), v.getWeight()));
            }
            l.put("variants", vs);
            layers.put(name, l);
        });
        out.put("staticLayers", layers);
        out.put("overrides", override.snapshot());
        return out;
    }

    @PostMapping("/enabled")
    public Map<String, Object> setGlobalEnabled(@RequestParam boolean value) {
        return set("enabled", String.valueOf(value));
    }

    @PostMapping("/{layer}/enabled")
    public Map<String, Object> setLayerEnabled(@PathVariable String layer, @RequestParam boolean value) {
        return set(layer + ".enabled", String.valueOf(value));
    }

    @PostMapping("/{layer}/{variant}/weight")
    public Map<String, Object> setVariantWeight(@PathVariable String layer, @PathVariable String variant,
                                                @RequestParam int value) {
        return set(layer + "." + variant + ".weight", String.valueOf(Math.max(0, value)));
    }

    @DeleteMapping("/override")
    public Map<String, Object> clear() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Map.of("ok", false, "reason", "redis unavailable");
        }
        try {
            redis.delete(RedisKeys.EXP_OVERRIDE);
            override.invalidate();
            return Map.of("ok", true, "cleared", true);
        } catch (Exception e) {
            return Map.of("ok", false, "reason", e.getMessage());
        }
    }

    private Map<String, Object> set(String field, String value) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Map.of("ok", false, "reason", "redis unavailable");
        }
        try {
            redis.opsForHash().put(RedisKeys.EXP_OVERRIDE, field, value);
            override.invalidate();   // 秒级生效,不等 30s 刷新
            return Map.of("ok", true, "field", field, "value", value);
        } catch (Exception e) {
            return Map.of("ok", false, "reason", e.getMessage());
        }
    }
}
