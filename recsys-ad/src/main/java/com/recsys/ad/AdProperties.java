package com.recsys.ad;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 搜索广告配置。对应 recsys.ad.* 树,env-var 可覆盖,设计为可迁 Nacos 热更新。
 */
@ConfigurationProperties(prefix = "recsys.ad")
public class AdProperties {

    private final Recall recall = new Recall();
    private final Auction auction = new Auction();
    private final Pacing pacing = new Pacing();
    /** pCTR 校准模型标识(对应离线 AdCalibrateJob 拟合的 ad:calib:{model})。默认随排序策略走。 */
    private String calibModel = "deepfm";
    /** 默认广告位数(slots)。 */
    private int slots = 3;

    public Recall getRecall() {
        return recall;
    }

    public Auction getAuction() {
        return auction;
    }

    public Pacing getPacing() {
        return pacing;
    }

    public String getCalibModel() {
        return calibModel;
    }

    public void setCalibModel(String calibModel) {
        this.calibModel = calibModel;
    }

    public int getSlots() {
        return slots;
    }

    public void setSlots(int slots) {
        this.slots = slots;
    }

    /** 召回配额 + 相关性门槛。 */
    public static class Recall {
        private int kw = 200;
        private int semantic = 100;
        private int hot = 50;
        /** 相关性门槛:低于此值的广告直接丢弃(广告独有硬过滤,空位也比不相关强)。 */
        private double relevanceThreshold = 0.05;
        /** 相关性 = kwWeight·关键词匹配度 + semWeight·query↔ad 余弦(无向量时仅关键词)。 */
        private double kwWeight = 0.6;
        private double semWeight = 0.4;

        public int getKw() {
            return kw;
        }

        public void setKw(int kw) {
            this.kw = kw;
        }

        public int getSemantic() {
            return semantic;
        }

        public void setSemantic(int semantic) {
            this.semantic = semantic;
        }

        public int getHot() {
            return hot;
        }

        public void setHot(int hot) {
            this.hot = hot;
        }

        public double getRelevanceThreshold() {
            return relevanceThreshold;
        }

        public void setRelevanceThreshold(double relevanceThreshold) {
            this.relevanceThreshold = relevanceThreshold;
        }

        public double getKwWeight() {
            return kwWeight;
        }

        public void setKwWeight(double kwWeight) {
            this.kwWeight = kwWeight;
        }

        public double getSemWeight() {
            return semWeight;
        }

        public void setSemWeight(double semWeight) {
            this.semWeight = semWeight;
        }
    }

    /** 竞价 / 拍卖。 */
    public static class Auction {
        /** 保留价(reserve price,元):eCPM 低于此值不展示;GSP 扣费下限。 */
        private double reservePrice = 0.1;
        /** GSP 次高价加价(元),避免与次位完全相等。 */
        private double priceIncrement = 0.01;

        public double getReservePrice() {
            return reservePrice;
        }

        public void setReservePrice(double reservePrice) {
            this.reservePrice = reservePrice;
        }

        public double getPriceIncrement() {
            return priceIncrement;
        }

        public void setPriceIncrement(double priceIncrement) {
            this.priceIncrement = priceIncrement;
        }
    }

    /** 预算 pacing。 */
    public static class Pacing {
        /** 是否启用实时预算熔断 + 平滑。关闭时不限预算(便于本地无 Redis 调试)。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
