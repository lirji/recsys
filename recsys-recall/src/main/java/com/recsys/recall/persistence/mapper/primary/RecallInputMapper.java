package com.recsys.recall.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 召回在主库读取行为和画像；派生向量查询由独立数据源承载。 */
public interface RecallInputMapper {

    /** 时间序列按每个物品最近一次正反馈去重，调用者再转为时间升序。 */
    List<Long> latestPositiveSequence(@Param("userId") long userId, @Param("limit") int limit);

    /** 旧通道种子保留全评分动作及物品ID排序，与模型序列分开建模。 */
    List<Long> recentPositiveItems(@Param("userId") long userId, @Param("limit") int limit);

    /** JSONB 数组保留重复和NULL元素，错误仍由画像读者按原约定降级。 */
    List<String> categories(@Param("userId") long userId);
}
