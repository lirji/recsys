package com.recsys.offline.persistence.mapper.sharded;

import org.apache.ibatis.session.ResultHandler;

/** 广告模拟仅从逻辑分片源读取聚合和有序创意，不误读某一物理分片。 */
public interface AdSimulationCatalogMapper {
    /** 创意身份和归属保持原Long精度。 */
    record Creative(long adId, long creativeId) { }
    /** 裸MAX供ShardingSphere正确跨分片归并；空表保留NULL。 */
    Long maxAdId();
    /** 流式消费原按广告/创意排序的active行，缺表仍由作业按原策略降级。 */
    void scanCreatives(ResultHandler<Creative> handler);
}
