package com.recsys.advertiser.persistence.mapper.derived;

import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 只读推荐派生向量库，通过文本传输避免跨数据库JOIN。 */
public interface AdvertiserItemEmbeddingMapper {
    /** 连同模型版本读取，供广告向量复制。 */
    List<ItemVector> find(@Param("itemId") long itemId);
    /** 目录事件仅需要向量文本。 */
    List<String> text(@Param("itemId") long itemId);
    /** 向量和模型可能为空，不能将缺失向量写成默认零向量。 */
    record ItemVector(String embedding, String model) { }
}
