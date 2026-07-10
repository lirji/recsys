package com.recsys.recengine.experiment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 分层实验分桶服务:对每层用 {@code hash(userId|salt) % 100} 落桶,按权重命中 variant。
 *
 * <p>**确定性**:同一 userId 在同一层永远命中同一 variant(分桶只依赖 userId 和 salt),
 * 因此结果缓存按 userId 即可,无需按 bucket 拆分;实验配置变更才会改变分桶。
 */
@Service
@EnableConfigurationProperties(ExperimentProperties.class)
public class ExperimentService {

    private static final int BUCKETS = 100;

    private final ExperimentProperties props;
    private final ExperimentOverrideService override;   // P3:Redis 动态覆盖(放量/开关,不重启)

    public ExperimentService(ExperimentProperties props, ExperimentOverrideService override) {
        this.props = props;
        this.override = override;
    }

    /** 为该用户在所有已配置的层上做分桶,返回各层命中的 variant。 */
    public ExperimentDecision assign(long userId, String scene) {
        ExperimentDecision decision = new ExperimentDecision();
        for (var entry : props.getLayers().entrySet()) {
            String layer = entry.getKey();
            if (ExperimentDecision.LAYER_AD.equals(layer)) {
                continue;   // 广告层由广告链路单独分桶(见 adVariant),不混入推荐 bucketTag
            }
            ExperimentProperties.Layer cfg = entry.getValue();
            List<ExperimentProperties.Variant> variants = cfg.getVariants();
            if (variants == null || variants.isEmpty()) {
                continue;
            }
            ExperimentProperties.Variant chosen = pick(userId, layer, cfg, variants);
            decision.put(layer, chosen.getName(), chosen.getParams());
        }
        return decision;
    }

    /**
     * 广告分层 A/B(docs/05):为用户在 {@code ad} 层确定性分桶,返回命中变体(name + params,
     * 如 {@code reserve-price})。未配置 {@code ad} 层或无变体 → 返回 null(广告链路用全局默认)。
     */
    public ExperimentProperties.Variant adVariant(long userId) {
        ExperimentProperties.Layer cfg = props.getLayers().get(ExperimentDecision.LAYER_AD);
        if (cfg == null || cfg.getVariants() == null || cfg.getVariants().isEmpty()) {
            return null;
        }
        return pick(userId, ExperimentDecision.LAYER_AD, cfg, cfg.getVariants());
    }

    private ExperimentProperties.Variant pick(long userId, String layer,
                                              ExperimentProperties.Layer cfg,
                                              List<ExperimentProperties.Variant> variants) {
        // 全局开关:Redis 覆盖优先,否则静态。关闭 → 固定第一个 variant 作基线
        Boolean gEnabled = override.globalEnabled();
        boolean enabled = gEnabled != null ? gEnabled : props.isEnabled();
        // 层开关:Redis 覆盖该层 off → 该层也退基线(实现"停某层实验"不重启)
        Boolean lEnabled = override.layerEnabled(layer);
        if (!enabled || Boolean.FALSE.equals(lEnabled)) {
            return variants.get(0);
        }
        // 变体权重:Redis 覆盖优先(放量/停止),否则静态 weight
        int totalWeight = 0;
        int[] w = new int[variants.size()];
        for (int i = 0; i < variants.size(); i++) {
            Integer ov = override.variantWeight(layer, variants.get(i).getName());
            w[i] = Math.max(0, ov != null ? ov : variants.get(i).getWeight());
            totalWeight += w[i];
        }
        if (totalWeight <= 0) {
            return variants.get(0);
        }
        int bucket = Math.floorMod((userId + "|" + cfg.getSalt()).hashCode(), BUCKETS);
        int threshold = bucket * totalWeight; // bucket*total < cum*100 等价 bucket/100 < cum/total
        int cum = 0;
        for (int i = 0; i < variants.size(); i++) {
            cum += w[i];
            if (threshold < cum * BUCKETS) {
                return variants.get(i);
            }
        }
        return variants.get(variants.size() - 1);
    }

    /** 空判定(无任何分层),用于实验配置缺失时。 */
    public ExperimentDecision empty() {
        return new ExperimentDecision();
    }

    Map<String, ExperimentProperties.Layer> layers() {
        return props.getLayers();
    }
}
