package com.recsys.offline.persistence.mapper.derived;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Param;

/** 派生物品向量的存在检查与回填写入，不读取或改写主源内容。 */
public interface ItemEmbeddingMapper {
    /** 保留COUNT行数判定，存在但向量为NULL的行也被视为已存在。 */
    Integer countItem(@Param("itemId") long itemId);
    /** 作业结束统计沿用原总行数口径。 */
    Long countAll();
    /** 仅更新向量和模型，PGvector通过原setObject语义绑定。 */
    int upsert(@Param("itemId") long itemId, @Param("vector") PGvector vector, @Param("model") String model);
}
