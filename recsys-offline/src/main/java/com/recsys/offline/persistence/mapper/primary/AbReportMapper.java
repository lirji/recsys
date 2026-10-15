package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.BehaviorSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Timestamp;

/** A/B报表原始统计与CUPED窗口读取；ITT归因和统计模型仍属于作业。 */
public interface AbReportMapper {
    /** users继续统计所有行为的独立非NULL用户，不仅统计曝光用户。 */
    record Bucket(String bucket, long impressions, long clicks, long users) { }
    /** Timestamp保留原getTimestamp读取及时区语义，由作业按原毫秒精度做ITT。 */
    record PostPeriod(long userId, String bucket, long impressions, long clicks, Timestamp firstImpression) { }
    /** 原始用户分组后才映射NULL为0，保留调用方原覆盖规则。 */
    record PrePeriod(long userId, long impressions, long clicks) { }

    /** 桶为空的行为排除，时间下界包含、上界不包含，scene值参数绑定。 */
    void scanBuckets(@Param("source") BehaviorSource source, @Param("since") String since,
                     @Param("until") String until, @Param("scene") String scene, ResultHandler<Bucket> handler);
    /** 实验期仅保留有曝光的用户桶，不提前合并NULL/0或跨桶人群。 */
    void scanPostPeriod(@Param("source") BehaviorSource source, @Param("since") String since,
                        @Param("until") String until, @Param("scene") String scene, ResultHandler<PostPeriod> handler);
    /** 前置期严格为[since-preDays,since)，允许NULL桶并独立按用户聚合。 */
    void scanPrePeriod(@Param("source") BehaviorSource source, @Param("since") String since,
                       @Param("scene") String scene, @Param("preDays") int preDays, ResultHandler<PrePeriod> handler);
}
