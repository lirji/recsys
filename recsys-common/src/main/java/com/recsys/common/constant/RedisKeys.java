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

    /** 文本→向量缓存 String:emb:cache:{hash} */
    public static String embCache(String textHash) {
        return "emb:cache:" + textHash;
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
}
