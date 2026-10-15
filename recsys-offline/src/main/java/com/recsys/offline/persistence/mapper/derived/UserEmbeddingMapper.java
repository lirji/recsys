package com.recsys.offline.persistence.mapper.derived;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Param;

/** 用户向量完全派生，保留原全量重建和单条增量写入边界。 */
public interface UserEmbeddingMapper {
    /** 只在已有聚合结果时调用；仍由原连接autoCommit/外层事务决定提交。 */
    int truncateAll();
    /** 显式PGvector绑定，重复用户仅覆盖向量。 */
    int upsert(@Param("userId") long userId, @Param("vector") PGvector vector);
    /** 保留原结束时总行数统计。 */
    Long countAll();
}
