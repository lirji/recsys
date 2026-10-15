package com.recsys.offline.persistence.mapper.primary;

import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 冷启动统计在原主源关联行为与目录，只物化有可用类目的分组。 */
public interface ColdBanditMapper {
    /** 统计字段保持BIGINT精度，避免字符串数组下标与往返解析隐藏语义。 */
    record Stats(String category, long impressions, long positives) { }

    /** 两种来源分别由封闭枚举选择；正反馈阈值沿原参数绑定。 */
    List<Stats> categoryStats(@Param("behavior") BehaviorSource behavior, @Param("items") CatalogTable items,
                              @Param("minRating") double minRating);
}
