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
    private final Quality quality = new Quality();
    private final Dco dco = new Dco();
    private final Gd gd = new Gd();
    private final Cvr cvr = new Cvr();

    public Cvr getCvr() {
        return cvr;
    }

    /**
     * 延迟反馈 DFM ad-CVR(A6,{@code recsys.ad.cvr.*})。默认关;开启且 {@link DfmCvrService} 就绪时,
     * 用带删失联合训练的去偏 pCVR 覆盖复用的 MMoE 头。缺模型/未就绪 → 退回 MMoE 头(零风险)。
     */
    public static class Cvr {
        private boolean enabled = false;
        private String modelPath = "classpath:model/model_dfm_cvr.onnx";
        private String schemaPath = "classpath:model/dfm_cvr_schema.json";
        private String vocabPath = "classpath:model/dfm_cvr_category_vocab.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public String getSchemaPath() {
            return schemaPath;
        }

        public void setSchemaPath(String schemaPath) {
            this.schemaPath = schemaPath;
        }

        public String getVocabPath() {
            return vocabPath;
        }

        public void setVocabPath(String vocabPath) {
            this.vocabPath = vocabPath;
        }
    }

    public Gd getGd() {
        return gd;
    }

    /**
     * 品牌广告 / GD 保量(A4,{@code recsys.ad.gd.*})。默认关;开启后落后于投放进度的合约优先出、竞价让位。
     */
    public static class Gd {
        /** 是否启用 GD 保量分配。 */
        private boolean enabled = false;
        /** 落后容忍带:紧迫度(落后占期望比例)> tolerance 才保量,避免临界抖动。 */
        private double tolerance = 0.02;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getTolerance() {
            return tolerance;
        }

        public void setTolerance(double tolerance) {
            this.tolerance = tolerance;
        }
    }
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

    public Quality getQuality() {
        return quality;
    }

    public Dco getDco() {
        return dco;
    }

    /**
     * 动态创意优化(DCO,docs/05 §7 M7)。一个广告多套创意,在线 {@link CreativeSelector} 用多臂老虎机
     * (UCB)按创意级 CTR 历史({@code ad:cstats})择优展示;新创意得探索曝光。关闭 → 一律广告默认创意。
     */
    public static class Dco {
        /** 总开关(默认关:开启会改变展示创意,且需先 seed-ads 造创意 + ad-explore-stats 物化统计)。 */
        private boolean enabled = false;
        /** UCB 探索系数(越大越激进探索新/低曝创意)。 */
        private double ucbCoef = 0.5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getUcbCoef() {
            return ucbCoef;
        }

        public void setUcbCoef(double ucbCoef) {
            this.ucbCoef = ucbCoef;
        }
    }

    /**
     * 精细化质量度(docs/05 §7 M7)。在线 {@link QualityScoreService} 用离线 {@code ad-quality} 作业算好的
     * 数据驱动质量度(Redis {@code ad:quality:{adId}})替换随机基线 {@code ad.quality_score};缺失退基线。
     */
    public static class Quality {
        /** 总开关。关闭 → 一律用广告自带 quality_score(等同 M7 之前)。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
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
        /** U2A 定向召回配额(用户长期向量 → ad_embedding ANN)。 */
        private int u2a = 80;
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

        public int getU2a() {
            return u2a;
        }

        public void setU2a(int u2a) {
            this.u2a = u2a;
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
        /** List-wise 外部性(docs/05 §7 M7):整页选择考虑广告间 CTR 蚕食。 */
        private Listwise listwise = new Listwise();
        /** VCG 位置拍卖(docs/05 §4.6/§7 M7):激励相容,付"对其他人的外部性";开启时优先于 List-wise/GSP。 */
        private Vcg vcg = new Vcg();

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

        public Listwise getListwise() {
            return listwise;
        }

        public void setListwise(Listwise listwise) {
            this.listwise = listwise;
        }

        public Vcg getVcg() {
            return vcg;
        }

        public void setVcg(Vcg vcg) {
            this.vcg = vcg;
        }
    }

    /**
     * VCG 位置拍卖(docs/05 §4.6/§7 M7)。GSP 只让广告付"保位最小出价"、非激励相容;VCG 让广告付它给
     * 其他人造成的福利损失(externality),从而说真话出价是占优策略。完整 VCG 需<b>位置点击折扣模型</b>
     * (本类的 {@code positionDiscounts})把不同位次的点击价值显式建模。开启后优先于 List-wise / GSP。
     * 关闭(默认)行为完全不变。退化:单广告位 ⇒ 与 GSP 单位次等价。详见 {@link VcgAuction}。
     */
    public static class Vcg {
        /** 总开关(默认关:改变计费口径,需显式启用)。 */
        private boolean enabled = false;
        /** 位置点击折扣 θ_1,θ_2,…(单调非增,(0,1]):第 j 位的点击率相对基线的折扣。 */
        private java.util.List<Double> positionDiscounts = java.util.List.of(1.0, 0.7, 0.5);
        /** 超出 positionDiscounts 的位次按此比例几何衰减(也用于列表不足时外推)。 */
        private double tailDecay = 0.7;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.util.List<Double> getPositionDiscounts() {
            return positionDiscounts;
        }

        public void setPositionDiscounts(java.util.List<Double> positionDiscounts) {
            this.positionDiscounts = positionDiscounts;
        }

        public double getTailDecay() {
            return tailDecay;
        }

        public void setTailDecay(double tailDecay) {
            this.tailDecay = tailDecay;
        }
    }

    /**
     * List-wise 外部性拍卖(docs/05 §7 M7):逐条 eCPM 降序忽略广告间相互影响——相邻同类广告蚕食彼此
     * CTR。开启后改用贪心整页选择:候选的有效质量按"与已选广告的最大相似度"做衰减(MMR 思路,复用
     * item_embedding 余弦),再选下一位;GSP 计费在外部性折扣后的分上进行(可审计的 GSP-with-externality
     * 近似,非完整 VCG)。关闭(默认)则退回逐条 eCPM + GSP,行为完全不变。
     */
    public static class Listwise {
        /** 总开关(默认关:开启会改变竞得集合与计费,需显式启用)。 */
        private boolean enabled = false;
        /** 外部性强度 λ∈[0,1]:衰减 = 1 − λ·maxSim。越大越惩罚冗余、越偏多样。 */
        private double externalityWeight = 0.5;
        /** 衰减下限:再冗余的广告也至少保留这一比例的有效质量,防完全清零(权重上限 1/此值)。 */
        private double minRetention = 0.3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getExternalityWeight() {
            return externalityWeight;
        }

        public void setExternalityWeight(double externalityWeight) {
            this.externalityWeight = externalityWeight;
        }

        public double getMinRetention() {
            return minRetention;
        }

        public void setMinRetention(double minRetention) {
            this.minRetention = minRetention;
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
