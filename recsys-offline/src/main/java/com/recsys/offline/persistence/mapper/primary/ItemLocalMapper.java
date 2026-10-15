package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.ItemCatalogCopyRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 主源目录读模型复制，保留覆盖全列与生成列自动更新。 */
public interface ItemLocalMapper {
    /** full参数才清空目标，普通upsert保留源已删除的旧项。 */
    void truncate();
    /** 同库不经Java映射，保留NULL标签数组。 */
    int copyLocal();
    /** 跨库按调用方原1000条批次提交，覆盖所有非主键基列。 */
    int upsertRows(@Param("rows") List<ItemCatalogCopyRow> rows);
    /** 供原作业日志读取实际目标总数。 */
    long count();
}
