package com.recsys.content;

import java.util.List;
import java.util.Map;

/**
 * 物品元数据服务。供召回/排序/前端取物品信息。
 */
public interface ContentService {

    /** 按 id 查单个物品,不存在返回 null。 */
    Item findById(long itemId);

    /** 按 id 批量查,返回 id->Item(不存在的 id 不在 map 中)。 */
    Map<Long, Item> findByIds(List<Long> itemIds);

    /** 列出所有物品 id(供离线灌向量遍历)。 */
    List<Long> allItemIds();

    /** upsert 物品。 */
    void save(Item item);

    /** 物品总数。 */
    long count();
}
