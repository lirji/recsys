package com.recsys.offline.persistence.mapper.sharded;

import org.apache.ibatis.annotations.Param;

/** 只绑定ShardingSphere逻辑源，原分片键和创意Snowflake生成职责保持。 */
public interface SeedAdMapper {
    /** 清理顺序与原作业一致；稳定code仅用于日志，表名由XML固定分支选择。 */
    enum ClearTable {
        BIDWORD("bidword"), CREATIVE("ad_creative"), AD("ad"), ADVERTISER("advertiser");
        private final String code;
        ClearTable(String code) { this.code = code; }
        /** 原日志中的表名，不能直接拼接到SQL。 */
        public String code() { return code; }
    }
    /** 种子确定的广告身份、归属和可空目标CPA。 */
    record Ad(long adId, long advertiserId, long itemId, String title, String landingUrl,
              double quality, String optimizationType, Double targetCpa) { }
    /** 显式广告主ID不触发Snowflake，保留重复键失败。 */
    int insertAdvertiser(@Param("id") long id, @Param("name") String name, @Param("budget") double budget);
    /** 广告按advertiserId路由而非adId路由。 */
    int insertAd(@Param("ad") Ad ad);
    /** 不传creative_id，继续由ShardingSphere生成；按adId路由。 */
    int insertCreative(@Param("adId") long adId, @Param("title") String title, @Param("landingUrl") String landingUrl);
    /** 保留确定性竞价词ID、CPC模式和原出价。 */
    int insertBidword(@Param("id") long id, @Param("adId") long adId, @Param("keyword") String keyword,
                      @Param("matchType") String matchType, @Param("bid") double bid);
    /** 单表广播清理，调用方继续按表独立处理原失败策略。 */
    void truncate(@Param("table") ClearTable table);
}
