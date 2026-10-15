package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 归因路径输入沿原用户和事件时间排序，路径折叠与分权仍由作业负责。 */
public interface AdAttributionMapper {
    /** SQL与归因路径全程保留long身份精度，时间值独立使用double。 */
    record Event(long userId, long adId, String eventType, double epoch) { }
    /** NULL排序按原始列而非默认值，非正days仍读取全量及NULL时间事件。 */
    void scan(@Param("source") AdEventSource source, @Param("days") int days, ResultHandler<Event> handler);
}
