package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventLogRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 广告事件读仓写入仅使用offline主源，跨库源读取单独装配。 */
public interface AdEventLogMapper {
    /** 只在原full参数触发时清空目标读模型。 */
    void truncate();
    /** 保留原MAX(id)增量水位，不把该脚手架升级为CDC承诺。 */
    long watermark();
    /** 同库沿用INSERT SELECT快路径和主键冲突忽略。 */
    int copyLocal(@Param("since") long since);
    /** 跨库单批最多1000行；失败整批回滚，既有批次保留。 */
    int insertRows(@Param("rows") List<AdEventLogRow> rows);
    /** 日志使用真实目标总数。 */
    long count();
}
