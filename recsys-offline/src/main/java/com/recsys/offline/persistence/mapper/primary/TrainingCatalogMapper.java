package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 训练负采样目录读取，与模拟数据写入职责分离。 */
public interface TrainingCatalogMapper {
    /** 保留原无排序、无去重、可空物品池，避免改变固定随机种子的样本序列。 */
    List<Long> itemIds(@Param("table") CatalogTable table);
}
