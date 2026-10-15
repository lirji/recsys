package com.recsys.offline.persistence.mapper.derived;

import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 派生源向量读取与广告向量写入分开，保留原跨库文本传输。 */
public interface SeedItemVectorMapper {
    /** NULL向量与模型保持原值，由作业决定是否跳过。 */
    record Vector(String vector, String model) { }
    /** 供原稳定排序把已有向量的物品放在前面。 */
    List<Long> itemIds();
    /** 沿用原列表读取及首项规则，不新增跨源JOIN。 */
    List<Vector> vectors(@Param("itemId") long itemId);
}
