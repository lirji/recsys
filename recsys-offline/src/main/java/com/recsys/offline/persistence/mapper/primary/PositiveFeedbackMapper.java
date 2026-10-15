package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.PositiveFeedbackFilter;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 六个离线作业的共用正反馈扫描，回调在会话关闭前消费，不物化全量查询List。 */
public interface PositiveFeedbackMapper {
    /** 保留重复行为行，去重或截断仍由各算法决定。 */
    record Pair(long userId,long itemId) { }
    /** 原始可空值保留，由调用方延续ResultSet.getDouble的空值处理。 */
    record Weighted(long userId,long itemId,String action,Double value,Double epoch) { }
    /** 最小列扫描用于CF、训练正例和bandit reward。 */
    void scanPairs(@Param("filter") PositiveFeedbackFilter filter, ResultHandler<Pair> handler);
    /** 用户向量聚合保留动作、权重与发生时间。 */
    void scanWeighted(@Param("filter") PositiveFeedbackFilter filter, ResultHandler<Weighted> handler);
    /** 空正反馈集返回NULL，原时间衰减逻辑据此关闭。 */
    Double maxEpoch(@Param("filter") PositiveFeedbackFilter filter);
}
