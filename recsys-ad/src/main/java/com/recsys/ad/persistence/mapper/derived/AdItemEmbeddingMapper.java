package com.recsys.ad.persistence.mapper.derived;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;
import java.util.Collection;

/** 一次读取候选物品向量，后续相似度计算完全在内存完成。 */
public interface AdItemEmbeddingMapper {
    /** 回调保留旧读取在异常发生前已经收集到的向量。 */
    void vectors(@Param("ids") Collection<Long> ids, ResultHandler<ItemVector> handler);
    /** NULL向量留给调用者按无相似度处理。 */
    record ItemVector(long itemId, String embedding) { }
}
