package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 归因路径输入沿原用户和事件时间排序，路径折叠与分权仍由作业负责。 */
public interface AdAttributionMapper {
    /** SQL边界保留身份精度；原作业内double路径表示由本次迁移保持不变。 */
    record Event(long userId, long adId, String eventType, double epoch) { }
    /** NULL排序按原始列而非默认值，非正days仍读取全量及NULL时间事件。 */
    void scan(@Param("source") AdEventSource source, @Param("days") int days, ResultHandler<Event> handler);
}
