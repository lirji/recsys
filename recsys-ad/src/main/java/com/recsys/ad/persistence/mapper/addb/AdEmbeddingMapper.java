package com.recsys.ad.persistence.mapper.addb;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 广告向量ANN直接使用广告自有库，不能送入分片SQL解析路径。 */
public interface AdEmbeddingMapper {
    /** 先按距离取候选，再由目录读者校验可服务状态和出价。 */
    List<AdMatch> nearestAds(@Param("vector") PGvector vector, @Param("limit") int limit);

    /** ANN候选有序行，合并重复标识的规则仍由Repository保持。 */
    record AdMatch(long adId, double similarity) { }
}
