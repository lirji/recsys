package com.recsys.offline.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;

/** 广告自有库教学分配与事实，完整字段绑定并保留任意唯一冲突的忽略语义。 */
public interface UpliftSimulationMapper {
    /** 17个原分配字段具名保存，时间与概率精度沿用现有表。 */
    record Assignment(String assignmentId, String requestId, long userId, long adId, long advertiserId,
                      long itemId, boolean treatment, double propensity, double pctr, double pcvr,
                      double quality, double relevance, double bid, String adBucket, String modelVersion,
                      Timestamp assignedAt, Timestamp outcomeDueAt) { }
    /** created_at继续由数据库默认值生成，不将应用时间覆盖写入。 */
    record Fact(String eventId, long advertiserId, long userId, String objective,
                double conversionValue, Timestamp occurredAt) { }

    /** 单行语句加入第一批，BATCH返回值不能作为已提交行数。 */
    void insertAssignment(@Param("row") Assignment row);
    /** 单行语句加入第二批，不能与第一批合并为一个新事务。 */
    void insertFact(@Param("row") Fact row);
}
