package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 广告及创意探索统计沿原三次查询执行，保留分步发布和关联重复计数。 */
public interface AdExplorationMapper {
    /** 广告级计数保留long精度，无曝光/点击的其他事件也形成0值统计。 */
    record AdStats(long adId, long impressions, long clicks) { }
    /** 创意曝光与关联点击共用具名统计行，NULL广告身份按旧JDBC读为0。 */
    record CreativeCount(long adId, long creativeId, long count) { }
    /** 逐行发布广告统计，消费失败时原部分写入边界保持。 */
    void scanAds(@Param("source") AdEventSource source, ResultHandler<AdStats> handler);
    /** 先读曝光，按原始身份分组，不能将NULL和实际0提前合并。 */
    void scanCreativeImpressions(@Param("source") AdEventSource source, ResultHandler<CreativeCount> handler);
    /** 普通关联保留重复曝光/点击的乘积，不改为DISTINCT。 */
    void scanCreativeClicks(@Param("source") AdEventSource source, ResultHandler<CreativeCount> handler);
}
