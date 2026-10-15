package com.recsys.recall.persistence.mapper.derived;

import com.pgvector.PGvector;
import com.recsys.recall.persistence.model.VectorMatch;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 派生库的向量读取，与主库行为及画像查询显式隔离。 */
public interface EmbeddingMapper {

    /** 文本读取避免依赖连接级pgvector类型注册，保留原缺失值判定。 */
    List<String> userEmbedding(@Param("userId") long userId);

    /** PGvector经驱动对象绑定，保持原余弦距离排序和限制。 */
    List<VectorMatch> nearestItems(@Param("vector") PGvector vector, @Param("limit") int limit);
}
