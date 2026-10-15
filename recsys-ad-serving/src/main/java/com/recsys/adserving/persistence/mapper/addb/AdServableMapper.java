package com.recsys.adserving.persistence.mapper.addb;

import com.recsys.common.ad.AdCatalogEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;
import java.util.List;

/** 广告目录副本SQL；逐行回调保留原查询失败时已有结果的语义。 */
public interface AdServableMapper {
    /** 同一广告覆盖向量，不把空输入转换成零向量。 */
    int upsertEmbedding(@Param("adId") long adId, @Param("vector") String vector);
    /** 幂等移除向量投影。 */
    int deleteEmbedding(@Param("adId") long adId);
    /** 保存消费顺序中的最后快照，ts不参与版本比较。 */
    int upsert(@Param("event") AdCatalogEvent event, @Param("maxBid") double maxBid,
               @Param("bidwordsJson") String bidwordsJson, @Param("creativesJson") String creativesJson);
    /** 只读竞价词JSON供倒排关键词差异计算。 */
    List<String> bidwords(@Param("adId") long adId);
    /** 墓碑通过物理删除移出可服务集合。 */
    int delete(@Param("adId") long adId);
    /** 验证副本中的广告总量。 */
    long count();
    /** 标题允许NULL，不丢失已有广告键。 */
    void titles(@Param("ids") Long[] ids, ResultHandler<Title> handler);
    /** NULL优化方式与目标成本延续CPC/0默认值。 */
    void optimizations(@Param("ids") Long[] ids, ResultHandler<Optimization> handler);
    /** ids=null读取全部可服务副本，其他情况按数组绑定。 */
    void details(@Param("ids") Long[] ids, ResultHandler<Detail> handler);
    /** 消费时预聚合的最高出价，NULL沿用0。 */
    void bids(@Param("ids") Long[] ids, ResultHandler<Bid> handler);
    /** 只返回显式指定人群的广告。 */
    void audiences(@Param("ids") Long[] ids, ResultHandler<Audience> handler);

    /** 标题持久化行。 */
    record Title(long adId, String title) { }
    /** 优化参数持久化行。 */
    record Optimization(long adId, String type, double cpa) { }
    /** 可服务详情持久化行。 */
    record Detail(long adId, long itemId, long advertiserId, double quality) { }
    /** 预聚合出价行。 */
    record Bid(long adId, double bid) { }
    /** 非空人群定向行。 */
    record Audience(long adId, long audienceId) { }
}
