package com.recsys.ad.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 可信频控只读取已接受的CPM账本，目标列表在Repository按既有位数上限校验。 */
public interface FeedCandidateMapper {
    /** 固定V007摘要与启动校验使用相同资源。 */
    List<String> migrationHashes();
    /** 名称来自服务端固定索引清单；缺失时保留一行NULL结果。 */
    List<String> indexNames(@Param("name") String name);
    /** 两级计数都在阈值处停止，按上海接受日而非原预占预算日查询。 */
    List<Long> eligible(@Param("userId") long userId, @Param("targets") List<Target> targets,
                        @Param("perAd") int perAd, @Param("perAdvertiser") int perAdvertiser);

    /** 只传查询所需身份，不把候选报价或资格令牌带入持久化查询。 */
    record Target(long adId, long advertiserId) { }
}
