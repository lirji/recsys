package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 广告种子只从主源目录读取候选，不通过分片逻辑源读取item。 */
public interface SeedItemMapper {
    /** 保留标题和类目的可空值，随机排序仍由原种子算法处理。 */
    record Item(long itemId, String title, String category) { }
    /** 来源复用已有CatalogTable封闭枚举，XML固定选择两张表。 */
    List<Item> items(@Param("source") CatalogTable source);
}
