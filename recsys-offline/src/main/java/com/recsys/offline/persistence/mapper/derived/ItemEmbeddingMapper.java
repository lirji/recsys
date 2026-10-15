package com.recsys.offline.persistence.mapper.derived;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 派生物品向量的存在检查与回填写入，不读取或改写主源内容。 */
public interface ItemEmbeddingMapper {
    /** 保留COUNT行数判定，存在但向量为NULL的行也被视为已存在。 */
    Integer countItem(@Param("itemId") long itemId);
    /** 作业结束统计沿用原总行数口径。 */
    Long countAll();
    /** 仅更新向量和模型，PGvector通过原setObject语义绑定。 */
    int upsert(@Param("itemId") long itemId, @Param("vector") PGvector vector, @Param("model") String model);
    /** 保留原无排序目录输入，全量ID仍在作业开始时物化。 */
    List<Long> itemIds();
    /** List区分缺行与NULL投影，原融合算法对两种情况均跳过。 */
    List<String> vectorText(@Param("itemId") long itemId);
    /** 只更新仍存在的向量行，不能因并发删除而重新插入。 */
    int updateExisting(@Param("itemId") long itemId, @Param("vector") PGvector vector, @Param("model") String model);
}
