package com.recsys.advertiser.persistence.mapper.primary;

import com.recsys.advertiser.dto.BidwordView;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

/** 可靠业务SQL携带真实owner/ad路由键；归属发现仅只读，实际写前必须在持久占有下重验。 */
public interface CatalogBusinessMapper {
    record Root(long advertiserId,String name,double dailyBudget,String status) { }
    record Ad(long adId,long advertiserId,long itemId,String title,String landingUrl,double qualityScore,
              String status,String reviewStatus,String reviewReason,String optimizationType,Double targetCpa,Long audienceId) { }
    record Creative(long creativeId,long adId,String title,String landingUrl,String status,String reviewStatus) { }
    record Aggregate(long adId,long advertiserId,long reservedVersion,long lastMaterializedVersion,boolean deleted) { }
    /** 默认limit2，重复归属必须拒绝而不能任取第一行。 */
    List<Long> ownersOfAd(@Param("ad") long ad);
    List<Long> adsOfBidword(@Param("id") long id);
    List<Long> adsOfCreative(@Param("id") long id);
    Root root(@Param("owner") long owner);
    Ad ad(@Param("owner") long owner,@Param("ad") long ad);
    /** 本库DB时间用于审计；不得以时间决定业务事件新旧。 */
    Instant now(@Param("shard") int shard);
    /** item属于原只读SINGLE源，仅允许在目录物理事务外调用。 */
    boolean itemExists(@Param("item") long item);
    List<BidwordView> bidwords(@Param("ad") long ad,@Param("after") long after);
    List<Creative> creatives(@Param("ad") long ad,@Param("after") long after);
    BidwordView bidword(@Param("ad") long ad,@Param("id") long id);
    Creative creative(@Param("ad") long ad,@Param("id") long id);
    Aggregate lockAggregate(@Param("shard") int shard,@Param("ad") long ad);
    int createAggregate(@Param("shard") int shard,@Param("ad") long ad,@Param("owner") long owner);
    int reserveVersion(@Param("shard") int shard,@Param("ad") long ad,@Param("expected") long expected);
    int materialize(@Param("shard") int shard,@Param("ad") long ad,@Param("version") long version,@Param("event") String event,@Param("deleted") boolean deleted);
    int insertRoot(@Param("root") Root root);
    int updateRoot(@Param("root") Root root);
    int updateAd(@Param("ad") Ad ad);
    int deleteAd(@Param("owner") long owner,@Param("ad") long ad);
    int updateBidword(@Param("word") BidwordView word);
    int deleteBidword(@Param("ad") long ad,@Param("id") long id);
    int updateCreative(@Param("creative") Creative creative);
    int deleteCreative(@Param("ad") long ad,@Param("id") long id);
    int deleteBidwords(@Param("ad") long ad);
    int deleteCreatives(@Param("ad") long ad);
}
