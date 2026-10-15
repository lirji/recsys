package com.recsys.content.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.recsys.content.Item;
import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.content.persistence.model.ItemRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 权威表的按主键读/计数复用 BaseMapper；副本来源和 PG upsert 用明确 XML。 */
public interface ItemMapper extends BaseMapper<ItemRow> {

    /** 同一物品标识可在权威表和副本表共存，来源必须显式传入。 */
    ItemRow findById(@Param("source") CatalogTable source, @Param("itemId") long itemId);

    /** 调用方已处理空集合，缺失标识不生成占位物品。 */
    List<ItemRow> findByIds(@Param("source") CatalogTable source, @Param("ids") List<Long> ids);

    /** 沿用物品标识升序，便于离线使用相同顺序扫描。 */
    List<Long> allItemIds(@Param("source") CatalogTable source);

    /** 原子 upsert，避免先查后写造成并发覆盖窗口。 */
    int upsert(@Param("source") CatalogTable source, @Param("item") Item item, @Param("tags") List<String> tags);

    /** 副本表不能使用固定绑定 item 的 BaseMapper 计数。 */
    long countFrom(@Param("source") CatalogTable source);
}
