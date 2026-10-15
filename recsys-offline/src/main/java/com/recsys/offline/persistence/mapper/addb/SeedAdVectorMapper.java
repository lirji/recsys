package com.recsys.offline.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;

/** 广告自有源种子向量及事件清理；不使用offline主库替代广告源。 */
public interface SeedAdVectorMapper {
    /** 仅原clear参数触发时清理广告事件与广告向量两表。 */
    void clearData();
    /** 向量文本转为PG vector，保留主键冲突忽略及实际写入计数。 */
    int insertVector(@Param("adId") long adId, @Param("vector") String vector, @Param("model") String model);
}
