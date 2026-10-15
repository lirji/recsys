package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.SponsoredAd;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 广告事件库访问；单次曝光页的批次作为一个SQL执行，保持PG整批失败回滚。 */
public interface AdEventMapper {
    /** 保存本页最终展示广告，默认创意ID转为SQL NULL。 */
    int insertImpressions(@Param("requestId") String requestId, @Param("query") String query,
                          @Param("userId") long userId, @Param("ads") List<SponsoredAd> ads, @Param("bucket") String bucket);
    /** 读取原曝光计费额，缺失或NULL由调用者保持无归因结果。 */
    Double chargedPrice(@Param("requestId") String requestId, @Param("adId") long adId);
    /** 反馈只记录事实，不在这里引入计费或去重语义。 */
    int insertFeedback(@Param("requestId") String requestId, @Param("adId") long adId,
                       @Param("userId") long userId, @Param("event") String event);
}
