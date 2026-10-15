package com.recsys.offline.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;

/** 模拟数据单行语句由BATCH会话聚合，避免默认20000条曝光超过PG单句参数上限。 */
public interface AdSimulationMapper {
    /** 仅曝光携带预测/费用/创意；creativeId为NULL时保留原不归因语义。 */
    record Impression(String requestId, long userId, long adId, int position, double pctr,
                      double charged, Timestamp timestamp, Long creativeId) { }
    /** 点击和转化共用原关联字段，类型由固定XML方法决定。 */
    record Action(String requestId, long userId, long adId, Timestamp timestamp) { }
    /** 加入曝光批次，不把BATCH返回值当已提交行数。 */
    void insertImpression(@Param("row") Impression row);
    /** 加入点击批次，其他列保持SQL NULL。 */
    void insertClick(@Param("row") Action row);
    /** 加入转化批次，延迟后的原发生时间不截断。 */
    void insertConversion(@Param("row") Action row);
}
