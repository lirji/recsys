package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventLogRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 广告事件读仓写入仅使用offline主源，跨库源读取单独装配。 */
public interface AdEventLogMapper {
    /** 只在已审计的显式full新命令内清空目标读模型。 */
    void truncate();
    /** 单批最多1000行；与检查点同事务提交，既有成功页保留。 */
    int insertRows(@Param("rows") List<AdEventLogRow> rows);
    /** 日志使用真实目标总数。 */
    long count();
}
