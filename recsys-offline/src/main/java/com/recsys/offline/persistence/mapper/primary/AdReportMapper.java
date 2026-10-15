package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 广告报表主源统计；曝光窗口、去重关联和原始NULL分组由XML明确表达。 */
public interface AdReportMapper {
    /** 位次NULL按原getInt映射0，但不能因此合并真实0位次或改变原排序。 */
    record Position(int position, long impressions, long clicks, long conversions, double revenue, double relevance) { }
    /** 桶名按原COALESCE合并，收入NULL沿原getDouble映射0。 */
    record Bucket(String bucket, long impressions, long clicks, long conversions, double revenue) { }

    /** 只过滤曝光时间，关联点击与转化保留全量去重输入。 */
    void scanPositions(@Param("source") AdEventSource source, @Param("since") String since, ResultHandler<Position> handler);
    /** 单独保留晚查询入口，使调用方保持原部分CSV发布边界。 */
    void scanBuckets(@Param("source") AdEventSource source, @Param("since") String since, ResultHandler<Bucket> handler);
    /** 仅无效点击本身受时间下界约束；作业必须区分真实0与不可确认的统计。 */
    Long countInvalidClicks(@Param("source") AdEventSource source, @Param("since") String since);
}
