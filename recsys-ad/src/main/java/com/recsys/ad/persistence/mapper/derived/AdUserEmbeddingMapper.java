package com.recsys.ad.persistence.mapper.derived;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 广告U2A只读用户派生向量，不新增对用户权威数据的写入口。 */
public interface AdUserEmbeddingMapper {
    /** 沿原文本读取避免连接级PGvector注册要求。 */
    List<String> userEmbedding(@Param("userId") long userId);
}
