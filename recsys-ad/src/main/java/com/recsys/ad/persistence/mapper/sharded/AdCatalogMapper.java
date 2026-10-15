package com.recsys.ad.persistence.mapper.sharded;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/** 广告目录查询仅经过ShardingSphere；逐行结果保留旧读者的部分结果及内存边界。 */
public interface AdCatalogMapper {

    /** 标题读取保留NULL，是否可服务由调用该查询的业务场景决定。 */
    void titles(@Param("ids") Long[] ids, ResultHandler<TitleRow> handler);

    /** 缺失优化类型或CPA沿旧规则退为CPC/零。 */
    void optimizations(@Param("ids") Long[] ids, ResultHandler<OptimizationRow> handler);

    /** null标识全体可服务目录，非null数组为指定候选集合。 */
    void activeDetails(@Param("ids") Long[] ids, ResultHandler<DetailRow> handler);

    /** 最高出价由分片数据库聚合并由ShardingSphere归并。 */
    void bids(@Param("ids") Long[] ids, ResultHandler<BidRow> handler);

    /** 只返回实际设置了定向人群的广告。 */
    void audiences(@Param("ids") Long[] ids, ResultHandler<AudienceRow> handler);

    /** 出价保持可空直到排序/limit完成，防止默认零值改变跨片归并顺序。 */
    List<KeywordHit> keywordHits(@Param("keywords") List<String> keywords, @Param("limit") int limit);

    /** 目录标题投影。 */
    record TitleRow(long adId, String title) { }

    /** 优化参数投影。 */
    record OptimizationRow(long adId, String type, double targetCpa) { }

    /** 可服务详情投影，保留广告标识以填充原Map接口。 */
    record DetailRow(long adId, long itemId, long advertiserId, double quality) { }

    /** 聚合出价投影。 */
    record BidRow(long adId, double bid) { }

    /** 定向关系投影。 */
    record AudienceRow(long adId, long audienceId) { }

    /** 关键词命中统一为一行，替代调用方的三份平行数组。 */
    record KeywordHit(long adId, long bidwordId, String keyword, Double bid) {
        /** 评分阶段再应用原getDouble的NULL默认值。 */
        public double bidOrZero() {
            return bid == null ? 0 : bid;
        }
    }
}
