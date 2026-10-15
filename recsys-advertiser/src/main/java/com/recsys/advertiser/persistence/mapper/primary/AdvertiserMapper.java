package com.recsys.advertiser.persistence.mapper.primary;

import com.recsys.advertiser.AdvertiserRepository.AdRow;
import com.recsys.advertiser.dto.AdvertiserView;
import com.recsys.advertiser.dto.BidwordView;
import com.recsys.advertiser.dto.CreativeView;
import com.recsys.advertiser.persistence.model.GeneratedId;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 广告主权威库访问；主源是ShardingSphere，生成ID和物理路由继续由驱动负责。 */
public interface AdvertiserMapper {
    /** 驱动生成主键并回填key；不使用ASSIGN_ID。 */
    int insertAdvertiser(@Param("key") GeneratedId key, @Param("name") String name, @Param("dailyBudget") double dailyBudget, @Param("status") String status);

    /** 执行对应XML语句，更新返回实际影响行数。 */
    int updateAdvertiser(@Param("id") long id, @Param("name") String name, @Param("dailyBudget") Double dailyBudget, @Param("status") String status);

    /** 广告主存在性计数，按advertiser_id路由。 */
    int countAdvertiser(@Param("id") long id);

    /** 读取基础广告主信息，预算消耗留给服务补充。 */
    List<AdvertiserView> findAdvertiser(@Param("id") long id);

    /** 跨片按广告主ID保序读取基础信息。 */
    List<AdvertiserView> listAdvertisers();

    /** 驱动生成主键并回填key；不使用ASSIGN_ID。 */
    int insertAd(@Param("key") GeneratedId key, @Param("advertiserId") long advertiserId, @Param("itemId") long itemId, @Param("title") String title, @Param("landingUrl") String landingUrl, @Param("qualityScore") double qualityScore, @Param("status") String status, @Param("reviewStatus") String reviewStatus, @Param("optimizationType") String optimizationType, @Param("targetCpa") Double targetCpa, @Param("audienceId") Long audienceId);

    /** 新广告生成ID后回填默认落地地址。 */
    int setAdLandingUrl(@Param("adId") long adId, @Param("url") String url);

    /** 执行对应XML语句，更新返回实际影响行数。 */
    int updateAd(@Param("adId") long adId, @Param("itemId") Long itemId, @Param("title") String title, @Param("landingUrl") String landingUrl, @Param("qualityScore") Double qualityScore, @Param("status") String status, @Param("optimizationType") String optimizationType, @Param("targetCpa") Double targetCpa, @Param("audienceId") Long audienceId);

    /** 保存服务层已经校验的启停状态，返回实际更新数。 */
    int setAdStatus(@Param("adId") long adId, @Param("status") String status);

    /** 广告ID可能跨片，保留驱动广播合并。 */
    int countAd(@Param("adId") long adId);

    /** 查询归属供服务鉴权；缺失返回null。 */
    List<Long> advertiserOfAd(@Param("adId") long adId);

    /** 单广告行的缺省数值保持原JDBC取值语义。 */
    List<AdRow> findAd(@Param("adId") long adId);

    /** 广告主分片内按ad_id读取，不增加逐广告查询。 */
    List<AdRow> listAds(@Param("advertiserId") long advertiserId);

    /** 一次批量查询向量单表，避免N+1。 */
    List<Long> embeddingAdIds(@Param("ids") Long[] ids);

    /** 执行对应XML语句，更新返回实际影响行数。 */
    int setAdReview(@Param("adId") long adId, @Param("reviewStatus") String reviewStatus, @Param("reason") String reason);

    /** 向量单表存在性计数，不与分片广告JOIN。 */
    int countEmbedding(@Param("adId") long adId);

    /** 删除广告下全部竞价词，保持原子资源清理顺序。 */
    int deleteAdBidwords(@Param("adId") long adId);

    /** 删除广告下全部创意，保持原子资源清理顺序。 */
    int deleteAdCreatives(@Param("adId") long adId);

    /** 删除向量投影，广告及其其他子资源由对应用例管理。 */
    int deleteEmbedding(@Param("adId") long adId);

    /** 删除广告记录；子资源由Repository先行清理。 */
    int deleteAd(@Param("adId") long adId);

    /** 关联内容位于原SINGLE表。 */
    int countItem(@Param("itemId") long itemId);

    /** 新增竞价词并回填分片驱动生成的ID。 */
    int insertBidword(@Param("key") GeneratedId key, @Param("adId") long adId, @Param("keyword") String keyword, @Param("matchType") String matchType, @Param("bid") double bid, @Param("bidMode") String bidMode);

    /** 只更新非null字段，保留原出价模式和匹配规则。 */
    int updateBidword(@Param("id") long id, @Param("keyword") String keyword, @Param("matchType") String matchType, @Param("bid") Double bid, @Param("bidMode") String bidMode);

    /** 删除单个词，调用者依据影响行数判断是否存在。 */
    int deleteBidword(@Param("id") long id);

    /** 读取词及广告归属供更新前校验；缺失返回null。 */
    List<BidwordView> findBidword(@Param("id") long id);

    /** 按ID保序读取子资源，供目录事件和视图组装。 */
    List<BidwordView> listBidwords(@Param("adId") long adId);

    /** 新增创意并回填分片驱动生成的ID。 */
    int insertCreative(@Param("key") GeneratedId key, @Param("adId") long adId, @Param("title") String title, @Param("landingUrl") String landingUrl, @Param("status") String status);

    /** 仅修改非null创意字段，返回实际更新数。 */
    int updateCreative(@Param("creativeId") long creativeId, @Param("title") String title, @Param("landingUrl") String landingUrl, @Param("status") String status);

    /** 删除单创意，调用者依据影响行数处理缺失。 */
    int deleteCreative(@Param("creativeId") long creativeId);

    /** 查询创意归属广告，供服务层进行权限校验。 */
    List<Long> adOfCreative(@Param("creativeId") long creativeId);

    /** 按创意ID稳定排序，供目录事件和视图使用。 */
    List<CreativeView> listCreatives(@Param("adId") long adId);

    /** 覆盖向量和模型版本；不存在时插入。 */
    int upsertEmbedding(@Param("adId") long adId, @Param("embedding") String embedding, @Param("model") String model);

    /** 按广告ID保序提供报表基础行。 */
    List<AdTitle> adTitles(@Param("advertiserId") long advertiserId);

    /** 按广告聚合事件，仅CLICK的charged_price计入花费。 */
    List<ReportStats> reportStats(@Param("ids") Long[] ids);

    /** 报表组装所需广告标题，不暴露额外广告字段。 */
    record AdTitle(long adId, String title) { }
    /** 原事件聚合计数；费用只计CLICK。 */
    record ReportStats(long adId, long impressions, long clicks, long conversions, double spend) { }
}
