package com.recsys.offline.persistence.mapper.source;

import com.recsys.offline.persistence.model.BehaviorLogRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 仅由跨库源会话加载，不进入主源Mapper扫描。 */
public interface BehaviorCopySourceMapper {
    /** 延续原按ID排序的增量读取；本轮不改变已有全量物化方式。 */
    List<BehaviorLogRow> since(@Param("since") long since);
}
