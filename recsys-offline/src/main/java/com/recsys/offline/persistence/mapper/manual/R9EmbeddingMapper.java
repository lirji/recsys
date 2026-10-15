package com.recsys.offline.persistence.mapper.manual;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Param;

/** R9三组向量只在原手动发布事务中使用，不进入普通Spring Mapper扫描。 */
public interface R9EmbeddingMapper {
    /** 已通过文件维度、归一化和版本校验的向量，保留PGvector绑定语义。 */
    record Row(String version, long id, PGvector embedding) { }

    /** 仅替换本次模型版本，保留仍在线的其他版本。 */
    void deleteMindVersion(@Param("version") String version);
    /** 加入MIND物品向量的原500行批次。 */
    void insertMind(@Param("row") Row row);
    /** 可选用户向量为空时调用方不删除旧组。 */
    void deleteGraphUserVersion(@Param("version") String version);
    /** 加入图用户向量的原500行批次。 */
    void insertGraphUser(@Param("row") Row row);
    /** 删除当前图物品版本，受三组共同事务保护。 */
    void deleteGraphItemVersion(@Param("version") String version);
    /** 加入图物品向量的原500行批次。 */
    void insertGraphItem(@Param("row") Row row);
}
