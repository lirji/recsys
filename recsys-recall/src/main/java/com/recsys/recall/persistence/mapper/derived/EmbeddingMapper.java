package com.recsys.recall.persistence.mapper.derived;

import com.pgvector.PGvector;
import com.recsys.recall.persistence.model.VectorMatch;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Collection;

/** 派生库的向量读取，与主库行为及画像查询显式隔离。 */
public interface EmbeddingMapper {

    /** 文本读取避免依赖连接级pgvector类型注册，保留原缺失值判定。 */
    List<String> userEmbedding(@Param("userId") long userId);

    /** PGvector经驱动对象绑定，保持原余弦距离排序和限制。 */
    List<VectorMatch> nearestItems(@Param("vector") PGvector vector, @Param("limit") int limit);

    /** 图用户向量按发布版本读取，防止不同模型的坐标混用。 */
    List<String> graphUserEmbedding(@Param("version") String version, @Param("userId") long userId);

    /** 图物品候选必须使用与用户相同的模型版本。 */
    List<VectorMatch> nearestGraphItems(@Param("vector") PGvector vector, @Param("version") String version, @Param("limit") int limit);

    /** 每个兴趣执行一次有界检索，跨兴趣去重仍由召回器负责。 */
    List<VectorMatch> nearestMultiInterestItems(@Param("vector") PGvector vector, @Param("version") String version, @Param("limit") int limit);

    /** 双塔全库召回保持既有余弦距离和配额。 */
    List<VectorMatch> nearestTowerItems(@Param("vector") PGvector vector, @Param("limit") int limit);

    /** 候选集评分只查询调用方已经去重的ID，不将缺失物品补为零。 */
    List<VectorMatch> scoreTowerItems(@Param("vector") PGvector vector, @Param("itemIds") Collection<Long> itemIds);
}
