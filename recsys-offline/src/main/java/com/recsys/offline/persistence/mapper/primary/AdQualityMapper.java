package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/** 质量分三个原聚合输入，归一/先验/阈值与Redis和CSV发布留在作业。 */
public interface AdQualityMapper {
    /** 计数继续沿旧JDBC双精度消费，广告身份始终为long。 */
    record Impressions(long adId, double count, double relevanceSum) { }
    /** 点击和转化分别按请求去重，不与曝光COUNT(*)混同。 */
    record Count(long adId, double count) { }
    /** 按原身份分组后才映射NULL为0，保留重复键的原覆盖规则。 */
    void scanImpressions(@Param("source") AdEventSource source, @Param("days") int days, ResultHandler<Impressions> handler);
    /** 仅约束点击本身的窗口，不连接曝光或提前去掉NULL广告分组。 */
    void scanClicks(@Param("source") AdEventSource source, @Param("days") int days, ResultHandler<Count> handler);
    /** 转化按原窗口独立计数，不额外要求匹配点击或已到达。 */
    void scanConversions(@Param("source") AdEventSource source, @Param("days") int days, ResultHandler<Count> handler);
}
