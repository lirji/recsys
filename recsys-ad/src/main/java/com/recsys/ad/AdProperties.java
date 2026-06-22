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
    private final Ocpc ocpc = new Ocpc();
    private final AdLoad adLoad = new AdLoad();
    private final Freq freq = new Freq();
    private final Exploration exploration = new Exploration();
    private final AntiFraud antiFraud = new AntiFraud();
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

    public Ocpc getOcpc() {
        return ocpc;
    }

    public AdLoad getAdLoad() {
        return adLoad;
    }

    public Freq getFreq() {
        return freq;
    }

    public Exploration getExploration() {
        return exploration;
    }

    public AntiFraud getAntiFraud() {
        return antiFraud;
    }

    /**
     * 反作弊(docs/05 §6):无效点击过滤,守计费公平。判定后:有效点击落 CLICK 并扣费,
     * 无效点击落 INVALID_CLICK(不进 CTR/计费)。两道在线规则:**去重**(同一曝光的点击只计一次,
     * 防重复提交/双击)+ **频次**(同用户每分钟点击数超阈值判机器流量)。
     */
    public static class AntiFraud {
        /** 总开关。关闭(或 Redis 不可用)→ 一律放行(fail-open,不误伤正常点击)。 */
        private boolean enabled = true;
        /** 同用户每分钟最多有效点击数,超出判无效。 */
        private int maxClicksPerMinute = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxClicksPerMinute() {
            return maxClicksPerMinute;
        }

        public void setMaxClicksPerMinute(int maxClicksPerMinute) {
            this.maxClicksPerMinute = maxClicksPerMinute;
        }
    }

    /**
     * 新广告 EE 探索(docs/05 §6):新广告无 CTR 历史,纯 eCPM 排序永远赢不了 → 给曝光不足的广告
     * 一个 UCB 探索加成抬升<b>排序</b> eCPM;计费仍按校准 pCTR(boost 只进排序阈值,不进自身计费,守红线)。
     * boost = 1 + coef·sqrt(ln(total+e)/(adImp+1)),封顶 maxBoost。曝光越少加成越大,随曝光衰减。
     */
    public static class Exploration {
        /** 总开关。关闭(或 Redis/统计缺失)→ boost 恒 1.0(无探索)。 */
        private boolean enabled = true;
        /** UCB 系数:越大探索越激进。 */
        private double coef = 0.5;
        /** 探索加成上限(防新广告把相关广告全挤掉)。 */
        private double maxBoost = 3.0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getCoef() {
            return coef;
        }

        public void setCoef(double coef) {
            this.coef = coef;
        }

        public double getMaxBoost() {
            return maxBoost;
        }

        public void setMaxBoost(double maxBoost) {
            this.maxBoost = maxBoost;
        }
    }

    /**
     * 混排 Ad Load(docs/05 §4.8):广告以何位置/密度插入自然推荐结果。
     */
    public static class AdLoad {
        /** 总开关。关闭则 /api/feed 只出自然结果。 */
        private boolean enabled = true;
        /** 广告插入的位次(1 基,信息流位置);如 [2,6,10] 表示第 2/6/10 位放广告。 */
        private java.util.List<Integer> slots = java.util.List.of(2, 6, 10);
        /** 单次信息流最多插入广告数(Ad Load 上限)。 */
        private int maxAds = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.util.List<Integer> getSlots() {
            return slots;
        }

        public void setSlots(java.util.List<Integer> slots) {
            this.slots = slots;
        }

        public int getMaxAds() {
            return maxAds;
        }

        public void setMaxAds(int maxAds) {
            this.maxAds = maxAds;
        }
    }

    /**
     * 频控(docs/05 §4.8):同一用户当日对同广告 / 同广告主的曝光上限,防骚扰。
     */
    public static class Freq {
        /** 总开关。关闭(或 Redis 不可用)则不限频。 */
        private boolean enabled = true;
        /** 同用户当日同一广告最多曝光次数。 */
        private int perAd = 3;
        /** 同用户当日同一广告主最多曝光次数。 */
        private int perAdvertiser = 8;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPerAd() {
            return perAd;
        }

        public void setPerAd(int perAd) {
            this.perAd = perAd;
        }

        public int getPerAdvertiser() {
            return perAdvertiser;
        }

        public void setPerAdvertiser(int perAdvertiser) {
            this.perAdvertiser = perAdvertiser;
        }
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

    /**
     * oCPC 智能出价(docs/05 §6,M6)。广告主只设 target_cpa,平台用 pCVR 自动出价:
     * {@code bid = targetCpa × pCVR × k},k 为离线反馈控制系数(OcpcCalibrateJob 拟合 → ad:ocpc:{adv})。
     */
    public static class Ocpc {
        /** 总开关。关闭则 oCPC 广告也按其 manual bid 竞价(退化为 CPC)。 */
        private boolean enabled = true;
        /** 排序模型(mmoe/din)未给出 pCVR 时的兜底先验转化率,保证 oCPC 仍能出价。 */
        private double defaultPcvr = 0.1;
        /** 自动出价上限(元,安全护栏;<=0 表示不封顶)。防 pCVR 异常高时出价失控。 */
        private double maxBid = 0.0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getDefaultPcvr() {
            return defaultPcvr;
        }

        public void setDefaultPcvr(double defaultPcvr) {
            this.defaultPcvr = defaultPcvr;
        }

        public double getMaxBid() {
            return maxBid;
        }

        public void setMaxBid(double maxBid) {
            this.maxBid = maxBid;
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
