package com.recsys.offline.persistence.mapper.derived;

import org.apache.ibatis.annotations.Param;

/** 语义编码属于派生库，逐条upsert保留重复物品ID后写覆盖。 */
public interface SemanticIdMapper {
    /** codeword沿原INT范围绑定，不额外收窄训练产物的编码域。 */
    record Row(long itemId, int c0, int c1, int c2, String model) { }
    /** 文件存在时才进入原清表重建流程。 */
    int truncateAll();
    /** 只向原500行BATCH队列添加语句，不将返回值当成提交数量。 */
    void upsert(@Param("row") Row row);
    /** 输出真实唯一物品行数，与处理行数分别统计。 */
    Long countAll();
}
