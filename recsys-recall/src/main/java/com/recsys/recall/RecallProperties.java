package com.recsys.recall;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 召回配置。对应 recsys.recall.quota.*(各路配额)。
 */
@ConfigurationProperties(prefix = "recsys.recall")
public class RecallProperties {

    private final Quota quota = new Quota();
    private final TwoTower twoTower = new TwoTower();
    private final Tag tag = new Tag();
    private final Tiger tiger = new Tiger();
    private final Parallel parallel = new Parallel();
    private final ColdBandit coldBandit = new ColdBandit();
    /** RRF(Reciprocal Rank Fusion)平滑常数 k:贡献 = 1/(k+rank)。业界常用 60。 */
    private int rrfK = 60;

    public Quota getQuota() {
        return quota;
    }

    public Parallel getParallel() {
        return parallel;
    }

    public ColdBandit getColdBandit() {
        return coldBandit;
    }

    /**
     * 冷启动类目 bandit(UCB):把 COLD 召回从"跨类目均匀铺开"升级为
     * "按类目 UCB 分驱动"——经验正反馈率高(exploit)+ 欠曝光(explore)的类目优先。
     * 关闭 / Redis 无统计 → 退回按热度铺开(旧行为)。
     */
    public static class ColdBandit {
        private boolean enabled = true;
        /** UCB 探索系数:越大越偏探索欠曝光类目。 */
        private double coef = 0.5;

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
    }

    /**
     * 多路召回并行执行:各路在有界线程池上并发调用,单路超时/异常当空(不阻断其余路与合并)。
     * 总延迟从 Σ 各路 降到 ≈ 最慢一路,且单路慢查询不再拖垮整请求。
     */
    public static class Parallel {
        /** 是否并行调用各路召回;false=串行(可回退开关)。 */
        private boolean enabled = true;
        /** 单路召回超时(毫秒);超时当空,交由其余路/热门兜底。 */
        private long timeoutMs = 150;
        /** 召回线程池大小(建议 ≈ 通道数)。舱壁开启时作为<b>慢池</b>大小;关闭时为单池大小。 */
        private int poolSize = 12;
        /**
         * 舱壁隔离:把慢通道(pgvector ANN / ONNX / 重 DB)与快通道(HOT/TAG/I2I 等兜底)拆到<b>各自线程池</b>——
         * 慢通道超时后 {@code cancel(true)} 不中断阻塞调用、线程被占,但只占<b>慢池</b>,快池(兜底路)始终有线程可用,
         * 不被拖垮。false=退回单池(原行为)。
         */
        private boolean bulkheadEnabled = true;
        /** 快通道线程池大小(舱壁开启时生效)。 */
        private int fastPoolSize = 6;
        /** 归入慢池的通道(逗号分隔,大小写不敏感;未列出的通道走快池)。 */
        private String slowChannels = "VECTOR,SEMANTIC,TWO_TOWER,GENERATIVE,TIGER,U2U,LEXICAL";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public boolean isBulkheadEnabled() {
            return bulkheadEnabled;
        }

        public void setBulkheadEnabled(boolean bulkheadEnabled) {
            this.bulkheadEnabled = bulkheadEnabled;
        }

        public int getFastPoolSize() {
            return fastPoolSize;
        }

        public void setFastPoolSize(int fastPoolSize) {
            this.fastPoolSize = fastPoolSize;
        }

        public String getSlowChannels() {
            return slowChannels;
        }

        public void setSlowChannels(String slowChannels) {
            this.slowChannels = slowChannels;
        }
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public TwoTower getTwoTower() {
        return twoTower;
    }

    public Tag getTag() {
        return tag;
    }

    public Tiger getTiger() {
        return tiger;
    }

    /** 完整 TIGER 生成式召回:decoder-only Transformer ONNX + schema(token 契约)+ beam 宽度。 */
    public static class Tiger {
        private String modelPath = "classpath:model/tiger.onnx";
        private String schemaPath = "classpath:model/tiger_schema.json";
        /** beam search 宽度(每级保留的候选数,也≈最终生成的语义 ID 数)。 */
        private int beam = 8;

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

        public int getBeam() {
            return beam;
        }

        public void setBeam(int beam) {
            this.beam = beam;
        }
    }

    /**
     * 标签召回:实时类目偏好(Flink 写的 {@code rt:user:{id}})叠加到静态画像类目上。
     * 实时类目反映"用户此刻在看哪类",按近期计数加权让其物品在 TAG 内排前;
     * 关闭后退化为只读 app_user.profile 的静态行为。
     */
    public static class Tag {
        /** 是否把实时类目偏好叠加进 TAG 召回。 */
        private boolean realtimeEnabled = true;
        /** 实时加权强度:权重 = 1 + boost·(count/maxCount),热度最高的实时类目得 (1+boost) 倍。 */
        private double realtimeBoost = 1.0;
        /** query 意图类目加权强度:权重 = 1 + intentBoost·score(score 为 Query 理解层归一化意图分)。 */
        private double intentBoost = 2.0;

        public boolean isRealtimeEnabled() {
            return realtimeEnabled;
        }

        public void setRealtimeEnabled(boolean realtimeEnabled) {
            this.realtimeEnabled = realtimeEnabled;
        }

        public double getRealtimeBoost() {
            return realtimeBoost;
        }

        public void setRealtimeBoost(double realtimeBoost) {
            this.realtimeBoost = realtimeBoost;
        }

        public double getIntentBoost() {
            return intentBoost;
        }

        public void setIntentBoost(double intentBoost) {
            this.intentBoost = intentBoost;
        }
    }

    /** 双塔召回:user 塔 ONNX 模型 + schema(user 桶数)路径。item 向量已离线灌库,在线无需 vocab。 */
    public static class TwoTower {
        /** classpath: 或文件路径;user 塔 ONNX(输入 user_bucket[N,1] int64 → user_vec[N,dim])。 */
        private String modelPath = "classpath:model/user_tower.onnx";
        /** classpath: 或文件路径;tower_schema.json(user_buckets / dim / 输入输出名)。 */
        private String schemaPath = "classpath:model/tower_schema.json";

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
    }

    public static class Quota {
        private int vector = 200;
        private int i2i = 100;
        private int hot = 50;
        private int tag = 50;
        private int u2u = 100;
        private int swing = 100;
        private int semantic = 100;
        private int cold = 100;
        private int twoTower = 200;
        private int generative = 200;
        private int lexical = 200;
        private int tiger = 200;
        /** i2i/swing:取用户最近多少个行为物品做种子。 */
        private int i2iSeed = 20;
        /** generative:取用户最近多少个正反馈物品做语义 ID 种子。 */
        private int generativeSeed = 20;
        /** cold:每个类目取多少热门做探索。 */
        private int coldPerCategory = 5;

        public int getVector() {
            return vector;
        }

        public void setVector(int vector) {
            this.vector = vector;
        }

        public int getI2i() {
            return i2i;
        }

        public void setI2i(int i2i) {
            this.i2i = i2i;
        }

        public int getHot() {
            return hot;
        }

        public void setHot(int hot) {
            this.hot = hot;
        }

        public int getTag() {
            return tag;
        }

        public void setTag(int tag) {
            this.tag = tag;
        }

        public int getU2u() {
            return u2u;
        }

        public void setU2u(int u2u) {
            this.u2u = u2u;
        }

        public int getSwing() {
            return swing;
        }

        public void setSwing(int swing) {
            this.swing = swing;
        }

        public int getSemantic() {
            return semantic;
        }

        public void setSemantic(int semantic) {
            this.semantic = semantic;
        }

        public int getCold() {
            return cold;
        }

        public void setCold(int cold) {
            this.cold = cold;
        }

        public int getTwoTower() {
            return twoTower;
        }

        public void setTwoTower(int twoTower) {
            this.twoTower = twoTower;
        }

        public int getGenerative() {
            return generative;
        }

        public void setGenerative(int generative) {
            this.generative = generative;
        }

        public int getLexical() {
            return lexical;
        }

        public void setLexical(int lexical) {
            this.lexical = lexical;
        }

        public int getTiger() {
            return tiger;
        }

        public void setTiger(int tiger) {
            this.tiger = tiger;
        }

        public int getI2iSeed() {
            return i2iSeed;
        }

        public void setI2iSeed(int i2iSeed) {
            this.i2iSeed = i2iSeed;
        }

        public int getGenerativeSeed() {
            return generativeSeed;
        }

        public void setGenerativeSeed(int generativeSeed) {
            this.generativeSeed = generativeSeed;
        }

        public int getColdPerCategory() {
            return coldPerCategory;
        }

        public void setColdPerCategory(int coldPerCategory) {
            this.coldPerCategory = coldPerCategory;
        }
    }
}
