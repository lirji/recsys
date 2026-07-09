package com.recsys.common.feature;

/**
 * 近线增量学习(FTRL-LR)的特征哈希契约 —— <b>在线打分与离线训练必须逐位一致</b>(同 SparseFeatureEncoder 的定位)。
 *
 * <p>用「哈希技巧」把 (user, item) 交互编码成稀疏二值特征:user 桶、item 桶、user×item 交叉,
 * 各哈希到 {@code [0, DIM)}。FTRL-LR 学 {@code P(正反馈 | user,item)},是一个协同过滤味的轻量线性模型,
 * 由离线 {@code train-ftrl} 近线增量训练、在线 {@code FtrlScorer} 查权重打分(喂融合/粗排)。
 *
 * <p>不含类目等需回表的特征 —— 在线融合阶段只有 (userId, itemId) 可直接拿到,保证在线零额外取数。
 */
public final class FtrlHashing {

    private FtrlHashing() {
    }

    /** 哈希空间维度(2^20;越大冲突越少、内存越大)。改动需在线/离线同步并重训。 */
    public static final int DIM = 1 << 20;
    /** 分桶基数(与稀疏模型口径一致,便于理解;FTRL 独立空间,数值可不同)。 */
    public static final int USER_BUCKETS = 5000;
    public static final int ITEM_BUCKETS = 20000;

    /** (user,item) → 特征哈希下标数组(值均为 1.0);偏置项单独处理,不在此列。 */
    public static int[] features(long userId, long itemId) {
        long ub = Math.floorMod(userId, USER_BUCKETS);
        long ib = Math.floorMod(itemId, ITEM_BUCKETS);
        return new int[]{
                idx("u:" + ub),
                idx("i:" + ib),
                idx("x:" + ub + ":" + ib),   // user×item 交叉(协同信号)
        };
    }

    /** 稳定哈希到 [0, DIM);用 32 位混合避免 String.hashCode 的低位聚集。 */
    static int idx(String s) {
        int h = s.hashCode();
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        return Math.floorMod(h, DIM);
    }
}
