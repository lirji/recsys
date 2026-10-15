package com.recsys.adserving.persistence.mapper.addb;

import com.recsys.adserving.report.AdReportStatsRepository.Stat;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 报表只聚合广告自有事件库，不跨源关联目录。 */
public interface AdReportStatsMapper {
    /** 无事件广告不产生结果行，零值填充由报表调用者负责。 */
    List<Stat> stats(@Param("ids") Long[] ids);
}
