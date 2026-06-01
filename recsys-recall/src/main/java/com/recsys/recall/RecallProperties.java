package com.recsys.recall;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 召回配置。对应 recsys.recall.quota.*(各路配额)。
 */
@ConfigurationProperties(prefix = "recsys.recall")
public class RecallProperties {

    private final Quota quota = new Quota();
    private final TwoTower twoTower = new TwoTower();

    public Quota getQuota() {
        return quota;
    }

    public TwoTower getTwoTower() {
        return twoTower;
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
        /** i2i/swing:取用户最近多少个行为物品做种子。 */
        private int i2iSeed = 20;
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

        public int getI2iSeed() {
            return i2iSeed;
        }

        public void setI2iSeed(int i2iSeed) {
            this.i2iSeed = i2iSeed;
        }

        public int getColdPerCategory() {
            return coldPerCategory;
        }

        public void setColdPerCategory(int coldPerCategory) {
            this.coldPerCategory = coldPerCategory;
        }
    }
}
