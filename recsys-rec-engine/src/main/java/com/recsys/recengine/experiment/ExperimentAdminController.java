package com.recsys.recengine.experiment;

import com.recsys.common.error.ErrorCode;

import com.recsys.common.constant.RedisKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasRole('ADMIN')")   // P0 纵深防御:除网关粗粒度 RBAC 外,服务侧再校验管理员身份
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

    /** 读取实际覆盖配置，供写入结果不确定时核对。 */
    @GetMapping
    public Map<String, Object> current() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean staticEnabled = props.isEnabled();
        Boolean globalOv = override.globalEnabled();
        out.put("staticEnabled", staticEnabled);
        out.put("enabled", globalOv != null ? globalOv : staticEnabled);
        Map<String, Object> layers = new LinkedHashMap<>();
        props.getLayers().forEach((name, cfg) -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("salt", cfg.getSalt());
            Boolean layerOv = override.layerEnabled(name);
            l.put("enabled", layerOv != null ? layerOv : Boolean.TRUE);
            Map<String, Integer> vs = new LinkedHashMap<>();
            if (cfg.getVariants() != null) {
                cfg.getVariants().forEach(v -> {
                    Integer wOv = override.variantWeight(name, v.getName());
                    vs.put(v.getName(), wOv != null ? wOv : v.getWeight());
                });
            }
            l.put("variants", vs);
            layers.put(name, l);
        });
        out.put("staticLayers", layers);
        out.put("overrides", override.snapshot());
        return out;
    }

    /** 全局开关沿用Redis覆盖层，不改变静态默认配置。 */
    @PostMapping("/enabled")
    public Map<String, Object> setGlobalEnabled(@RequestParam boolean value) {
        return set("enabled", String.valueOf(value));
    }

    /** 层开关保持原字段与权限边界。 */
    @PostMapping("/{layer}/enabled")
    public Map<String, Object> setLayerEnabled(@PathVariable String layer, @RequestParam boolean value) {
        return set(layer + ".enabled", String.valueOf(value));
    }

    /** 保留负权重归零规则，写入失败须允许调用者核对真实结果。 */
    @PostMapping("/{layer}/{variant}/weight")
    public Map<String, Object> setVariantWeight(@PathVariable String layer, @PathVariable String variant,
                                                @RequestParam int value) {
        return set(layer + "." + variant + ".weight", String.valueOf(Math.max(0, value)));
    }

    /** 清除覆盖仅在Redis确认后失效本地快照，异常不声称已清除。 */
    @DeleteMapping("/override")
    public Map<String, Object> clear() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return rejected(ErrorCode.EXPERIMENT_STORE_UNAVAILABLE);
        }
        try {
            redis.delete(RedisKeys.EXP_OVERRIDE);
            override.invalidate();
            return Map.of("ok", true, "cleared", true);
        } catch (Exception e) {
            return rejected(ErrorCode.EXPERIMENT_WRITE_UNCONFIRMED);
        }
    }

    /** 保留旧200/ok:false协议与reason字段，公开内容只来自目录，不透传Redis异常。 */
    private Map<String, Object> rejected(ErrorCode code) {
        return Map.of("ok", false, "code", code.code(), "reason", code.message());
    }

    private Map<String, Object> set(String field, String value) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return rejected(ErrorCode.EXPERIMENT_STORE_UNAVAILABLE);
        }
        try {
            redis.opsForHash().put(RedisKeys.EXP_OVERRIDE, field, value);
            override.invalidate();   // 秒级生效,不等 30s 刷新
            return Map.of("ok", true, "field", field, "value", value);
        } catch (Exception e) {
            return rejected(ErrorCode.EXPERIMENT_WRITE_UNCONFIRMED);
        }
    }
}
