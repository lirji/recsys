package com.recsys.offline.persistence.mapper.source;

import com.recsys.offline.persistence.model.ItemCatalogCopyRow;
import java.util.List;

/** 仅在物品源库会话加载，数组读取后由共享TypeHandler释放JDBC Array。 */
public interface ItemCopySourceMapper {
    /** 沿用原全量读取和无显式排序，不引入新的水位或过滤。 */
    List<ItemCatalogCopyRow> items();
}
