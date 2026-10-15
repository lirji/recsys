package com.recsys.offline.persistence.mapper.derived;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Param;

/** 双塔物品向量只属于派生库，逐条upsert保留同批重复ID的后写覆盖。 */
public interface TowerEmbeddingMapper {
    /** 保留原PGvector对象，不改变浮点编码及数据库维度校验。 */
    record Row(long itemId, PGvector embedding) { }
    /** 文件存在时按原流程清空重建，事务仍由调用方决定。 */
    int truncateAll();
    /** 供BATCH会话逐条添加，不合并为多VALUES而改变重复ID语义。 */
    void upsert(@Param("row") Row row);
    /** 完成后的实际唯一物品行数，与输入处理行数分别统计。 */
    Long countAll();
}
