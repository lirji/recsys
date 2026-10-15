package com.recsys.offline.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 从广告库读取成熟随机分配和独立转化标签，整用户切分仍由作业负责。 */
public interface UpliftSampleMapper {
    /** 显式字段映射保留原JDBC空数值读0和空布尔读false的样本语义。 */
    record Row(long userId, long itemId, long adId, long advertiserId, boolean treatment,
               double propensity, double pctr, double pcvr, double quality, double relevance,
               double bid, boolean outcome, Instant ts) { }

    /** 先完整读取再打开输出文件，查询失败不能覆盖旧样本；since为空代表不限制起始时间。 */
    List<Row> load(@Param("objective") String objective, @Param("asOf") Timestamp asOf,
                   @Param("since") Timestamp since);
}
