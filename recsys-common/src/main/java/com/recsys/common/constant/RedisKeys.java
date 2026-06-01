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

    /** 全局热门 ZSet(score=热度):recall:hot */
    public static final String HOT_RECALL = "recall:hot";

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
}
