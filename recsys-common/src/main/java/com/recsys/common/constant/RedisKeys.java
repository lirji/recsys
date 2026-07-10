package com.recsys.common.constant;

/**
 * Redis Key 规范(架构文档 §4.2)。所有模块统一通过此处生成 key,禁止散落硬编码。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 用户在线特征 Hash:feat:user:{userId} */
    public static String userFeature(long userId) {
        return "feat:user:" + userId;
    }

    /** 物品在线特征 Hash:feat:item:{itemId} */
    public static String itemFeature(long itemId) {
        return "feat:item:" + itemId;
    }

    /** 全局热门 ZSet(score=热度):recall:hot(离线 HotJob 物化,T+1) */
    public static final String HOT_RECALL = "recall:hot";

    /**
     * 实时热门 ZSet(score=滑动窗口内正反馈加权热度):recall:rt_hot。
     * 由 Flink 流作业 {@code RealtimeFeatureJob} 近实时更新(带 TTL),HotRecaller 优先读它、
     * 缺失则回落离线 {@link #HOT_RECALL}。实时与离线互补:实时反映"此刻在热什么"。
     */
    public static final String RT_HOT_RECALL = "recall:rt_hot";

    /**
     * 用户实时类目偏好 Hash(field=category,value=滑动窗口内正反馈计数):rt:user:{userId}(带 TTL)。
     * 由 Flink 流作业近实时更新,供画像/标签召回叠加"用户近期在看哪类"。
     */
    public static String rtUser(long userId) {
        return "rt:user:" + userId;
    }

    /** i2i 相似物品 ZSet(score=相似度):i2i:{itemId} */
    public static String i2i(long itemId) {
        return "i2i:" + itemId;
    }

    /** 推荐结果缓存 String(短 TTL):cache:rec:{userId} */
    public static String recCache(long userId) {
        return "cache:rec:" + userId;
    }

    /** P4:rec-engine 自有"已正反馈物品"读模型 Set(从 behavior-events 构建,供已看过滤):seen:{userId} */
    public static String seenItems(long userId) {
        return "seen:" + userId;
    }

    /** 文本→向量缓存 String:emb:cache:{hash} */
    public static String embCache(String textHash) {
        return "emb:cache:" + textHash;
    }

    /** LLM 生成结果缓存 String:llm:cache:{hash}(query 理解的 JSON 输出,省外呼) */
    public static String llmCache(String textHash) {
        return "llm:cache:" + textHash;
    }

    /** UserCF 个性化召回列表 ZSet(离线物化,score=相似用户加权分):u2u:{userId} */
    public static String u2u(long userId) {
        return "u2u:" + userId;
    }

    /** Swing 相似物品 ZSet(score=Swing 相似度):swing:{itemId} */
    public static String swing(long itemId) {
        return "swing:" + itemId;
    }

    /**
     * 曝光分桶归因 String(短 TTL):expo:{userId}:{itemId} = bucketTag。
     * 由编排层曝光埋点写入,行为服务收到点击时回查,用于在线分桶 CTR 指标。
     */
    public static String exposureBucket(long userId, long itemId) {
        return "expo:" + userId + ":" + itemId;
    }

    // ---------- 搜索广告(docs/05 §3) ----------

    /** 竞价词倒排 ZSet(score=出价):bidword:inv:{keyword}(SeedAdsJob 物化,KW 召回读)。 */
    public static String bidwordInv(String keyword) {
        return "bidword:inv:" + keyword;
    }

    /** 广告主当日已消耗预算 String(计数,元):ad:budget:{advertiserId}:{yyyymmdd}。pacing 实时扣减/熔断。 */
    public static String adBudget(long advertiserId, String yyyymmdd) {
        return "ad:budget:" + advertiserId + ":" + yyyymmdd;
    }

    /** 广告主 pacing 平滑系数 String(PID 输出,出价折扣 (0,1]):ad:pacing:{advertiserId}。 */
    public static String adPacing(long advertiserId) {
        return "ad:pacing:" + advertiserId;
    }

    /**
     * pCTR 校准表(保序回归分段点 JSON):ad:calib:{model}。
     * 离线 AdCalibrateJob 拟合写入,在线 Calibrator 查表线性插值;缺失则退化 identity。
     */
    public static String adCalib(String model) {
        return "ad:calib:" + model;
    }

    /**
     * 推荐精排分数校准表(保序回归分段点 JSON):rec:calib:{model}。
     * 离线 RecCalibrateJob 拟合写入(rankScore→真实正反馈率),在线 RecScoreCalibrator 查表插值;
     * 缺失则退化 identity。校准让 rank 分成为可比的概率,融合(recall+rank)才有量纲意义。
     */
    public static String recCalib(String model) {
        return "rec:calib:" + model;
    }

    /** 广告归因 String(短 TTL):ad:expo:{requestId}:{adId} = "bidwordId;pctrCalib;ecpm;charged;position"。 */
    public static String adExposure(String requestId, long adId) {
        return "ad:expo:" + requestId + ":" + adId;
    }

    /** 反作弊去重:某次曝光的点击是否已计费 String(带 TTL,setIfAbsent):ad:clicked:{requestId}:{adId}。 */
    public static String adClicked(String requestId, long adId) {
        return "ad:clicked:" + requestId + ":" + adId;
    }

    /** 反作弊频次:用户每分钟点击数 String(带 TTL):ad:clk:rate:{userId}:{yyyyMMddHHmm}。 */
    public static String adClickRate(long userId, String yyyyMMddHHmm) {
        return "ad:clk:rate:" + userId + ":" + yyyyMMddHHmm;
    }

    /** 广告累计统计 String("imp,clk"):ad:stats:{adId}(离线 AdExploreStatsJob 物化,EE 探索读)。 */
    public static String adStats(long adId) {
        return "ad:stats:" + adId;
    }

    /** 全局广告总曝光数 String:ad:stats:total(UCB 探索的总试验次数)。 */
    public static final String AD_STATS_TOTAL = "ad:stats:total";

    /**
     * 冷启动类目 bandit 统计 String("impr,pos"):cold:cat:{category}(离线 cold-bandit-stats 物化)。
     * 在线 ColdStartBandit 算类目 UCB(经验正反馈率 + 欠曝光探索加成)驱动冷启动类目探索。
     */
    public static String coldCatStats(String category) {
        return "cold:cat:" + category;
    }

    /** 冷启动 bandit 全局总曝光数 String:cold:cat:total(UCB 的总试验次数)。 */
    public static final String COLD_CAT_TOTAL = "cold:cat:total";

    /** 近线增量学习 FTRL-LR 服务权重 String(JSON {"bias":w,"w":{idx:weight,…}}):ftrl:weights。 */
    public static final String FTRL_WEIGHTS = "ftrl:weights";

    /** FTRL 训练状态 String(JSON {"bias":[z,n],"c":{idx:[z,n],…}}):ftrl:state(增量续训 warm-start)。 */
    public static final String FTRL_STATE = "ftrl:state";

    /**
     * R7 全量 contextual bandit 模型 String(JSON {"order":[...],"lambda":λ,"n":k,"a":[[..]],"b":[..]}):bandit:model。
     * 离线 {@code bandit-stats} 攒 A/b 充分统计写入(既作在线服务参数、又作增量续训 warm-start);
     * 在线 {@code BanditScorer} 读并预计算 A⁻¹/θ̂ 出 LinUCB/Thompson 探索加成;缺失/解析失败 → 打分退 0(不影响融合)。
     */
    public static final String BANDIT_MODEL = "bandit:model";

    /**
     * 动态调参 Hash(S5 轻量配置热更新):recsys:tuning,field=配置项(如 fusion.pop-debias.beta)、value=覆盖值。
     * 在线 DynamicTuningService 周期刷新叠加在静态 yml 上;`redis-cli hset recsys:tuning <field> <v>` 即热更、免重启。
     */
    public static final String TUNING = "recsys:tuning";

    /**
     * 实验动态覆盖 Hash(P3 实验平台化):recsys:exp,field/value —— 在静态 yml 之上热更实验,免重启。
     * field 约定:{@code enabled}=全局开关;{@code <layer>.enabled}=层开关;
     * {@code <layer>.<variant>.weight}=变体流量权重(放量/停止=调权重/置 0)。在线周期刷新叠加。
     */
    public static final String EXP_OVERRIDE = "recsys:exp";

    /**
     * 词项 IDF Hash:idf:terms,field=归一化词项、value=IDF。
     * 离线 IdfJob 从 item 标题/类目按<b>与在线 query 理解相同的分词</b>({@code QueryTokens})统计
     * document frequency,拟合 IDF=ln((N+1)/(df+1))+1(稀有词更高、≥1)。在线 query 理解据此给
     * {@code TermWeight} 赋权(替代恒 1.0);缺失/OOV 词退中性 1.0(保守,不放大生僻/拼写噪声)。
     */
    public static final String IDF_TERMS = "idf:terms";

    /** IDF 语料文档总数 String:idf:doc-count(= 参与统计的 item 数,口径核对用)。 */
    public static final String IDF_DOC_COUNT = "idf:doc-count";

    /**
     * Look-alike 人群包 Set(A3):ad:audience:{audienceId},member=扩散后的 user_id。
     * 离线 lookalike 作业从种子用户的 user_embedding 向量扩散(ANN)物化;在线广告定向按 SISMEMBER 判用户是否在包内。
     */
    public static String adAudience(long audienceId) {
        return "ad:audience:" + audienceId;
    }

    /** GD 保量已交付曝光计数 String(A4):ad:gd:delivered:{contractId},曝光时 INCR,投放进度分配据此判是否落后。 */
    public static String adGdDelivered(long contractId) {
        return "ad:gd:delivered:" + contractId;
    }

    /**
     * 用户行为序列 ZSet(R2):rt:user:seq:{userId},member=itemId、score=行为时刻(epoch 秒)。
     * DIN 在线排序优先读它(取最近 N 个作 target-attention 序列),未命中回退 DB 并 cache-aside 回填;
     * 与近线/流作业(可选)按 ts 增量 ZADD 兼容。带 TTL,冷用户自然淘汰。
     */
    public static String userSeq(long userId) {
        return "rt:user:seq:" + userId;
    }

    /** 频控:用户当日某广告曝光次数 String(带 TTL):ad:freq:{userId}:{adId}:{yyyymmdd}。 */
    public static String adFreq(long userId, long adId, String yyyymmdd) {
        return "ad:freq:" + userId + ":" + adId + ":" + yyyymmdd;
    }

    /** 频控:用户当日某广告主曝光次数 String(带 TTL):ad:freq:adv:{userId}:{advertiserId}:{yyyymmdd}。 */
    public static String adFreqAdvertiser(long userId, long advertiserId, String yyyymmdd) {
        return "ad:freq:adv:" + userId + ":" + advertiserId + ":" + yyyymmdd;
    }

    /**
     * oCPC 出价调节系数 String(>0,默认 1.0):ad:ocpc:{advertiserId}。
     * 离线 OcpcCalibrateJob 按"实际 CPA vs 目标 CPA"反馈控制拟合写入,在线 OcpcBidder 查表;
     * 缺失则退 1.0(等价于 bid = targetCpa × pCVR,不做偏差校正)。
     */
    public static String adOcpc(long advertiserId) {
        return "ad:ocpc:" + advertiserId;
    }

    /**
     * 延迟转化模型——指数延迟分布的速率 λ(单位 1/天)String:ad:delay:lambda。
     * 离线 DelayModelJob 从已观测的 (转化时刻 − 点击时刻) 拟合;转化完成曲线 c(e)=1−e^(−λ·e)。
     * 下游 ad-ocpc 用它做 Horvitz–Thompson 纠偏(每个已观测转化按 1/c(elapsed) 加权,补回尚未到达的转化)。
     * 缺失则退化为不纠偏(原始转化计数)。
     */
    public static final String AD_DELAY_LAMBDA = "ad:delay:lambda";

    /** 延迟转化模型——平均转化延迟(天)String:ad:delay:mean-days(= 1/λ,报表/可读性)。 */
    public static final String AD_DELAY_MEAN_DAYS = "ad:delay:mean-days";

    /**
     * 精细化质量度 String(eCPM 乘子,围绕 1.0):ad:quality:{adId}(docs/05 §7 M7)。
     * 离线 QualityScoreJob 从 {@code ad_event} 聚合相关性/经验 CTR/CVR 三因子融合写入(替代随机基线 quality_score),
     * 在线 {@code QualityScoreService} 查表;缺失则退广告自带 {@code ad.quality_score}(不引入噪声)。
     */
    public static String adQuality(long adId) {
        return "ad:quality:" + adId;
    }

    /**
     * DCO 创意级累计统计 String("imp,clk"):ad:cstats:{adId}:{creativeId}(docs/05 §7 M7)。
     * 离线 ad-explore-stats 按创意聚合物化,在线 {@code CreativeSelector} 读它跑多臂老虎机(UCB)选创意。
     */
    public static String adCreativeStats(long adId, long creativeId) {
        return "ad:cstats:" + adId + ":" + creativeId;
    }
}
