package com.recsys.ad.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;

/** 独立转化事实以eventId主键判重，不覆盖首次有效负载。 */
public interface AdConversionFactMapper {
    /** 返回插入行数，冲突0行由服务转换为幂等重试结果。 */
    int insert(@Param("eventId") String eventId, @Param("advertiserId") long advertiserId,
               @Param("userId") long userId, @Param("objective") String objective,
               @Param("value") double value, @Param("occurredAt") Timestamp occurredAt);
}
