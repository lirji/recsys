package com.recsys.recengine.experiment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层 A/B 实验配置(对应 {@code recsys.experiment.*})。
 *
 * <p>每个"层"(recall / rank / rerank)是一个独立实验:有自己的 salt 和若干 variant,
 * 用户按 hash 落桶、按权重命中某个 variant。各层相互正交,可任意组合。
 *
 * <pre>
 * recsys.experiment:
 *   enabled: true
 *   layers:
 *     recall:
 *       salt: recall-2026
 *       variants:
 *         - { name: base,  weight: 50, params: { channels: "VECTOR,I2I,HOT,TAG" } }
 *         - { name: plus,  weight: 50, params: { channels: "VECTOR,I2I,HOT,TAG,U2U,SWING,SEMANTIC" } }
 * </pre>
 *
 * {@code enabled=false} 时每层固定取第一个 variant(等价"无实验"基线)。
 */
@ConfigurationProperties(prefix = "recsys.experiment")
public class ExperimentProperties {

    private boolean enabled = false;
    private Map<String, Layer> layers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Layer> getLayers() {
        return layers;
    }

    public void setLayers(Map<String, Layer> layers) {
        this.layers = layers;
    }

    /** 一个实验层。 */
    public static class Layer {
        /** 分桶盐值:不同层用不同 salt,保证各层落桶相互独立。 */
        private String salt = "";
        private List<Variant> variants = new java.util.ArrayList<>();

        public String getSalt() {
            return salt;
        }

        public void setSalt(String salt) {
            this.salt = salt;
        }

        public List<Variant> getVariants() {
            return variants;
        }

        public void setVariants(List<Variant> variants) {
            this.variants = variants;
        }
    }

    /** 一个实验分组。 */
    public static class Variant {
        private String name = "default";
        /** 流量权重(整数,各 variant 权重之和即分桶总数,无需凑满 100)。 */
        private int weight = 1;
        private Map<String, String> params = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public Map<String, String> getParams() {
            return params;
        }

        public void setParams(Map<String, String> params) {
            this.params = params;
        }
    }
}
