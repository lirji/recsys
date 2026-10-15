package com.recsys.recengine.persistence.mapper.derived;

import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

/** 派生向量源的推荐输入查询，不通过分片SQL解析器。 */
public interface EngineEmbeddingMapper {
    /** 保留按行存在性判定，embedding本身为NULL也算有用户向量行。 */
    int userEmbeddingCount(@Param("userId") long userId);
    /** 只查询调用者已限定的候选ID，用户无向量时affinity为NULL。 */
    List<Affinity> affinities(@Param("userId") long userId, @Param("itemIds") Collection<Long> itemIds);

    /** SQL NULL亲和度由业务层忽略，不能以0伪装已计算。 */
    record Affinity(long itemId, Double affinity) { }
}
