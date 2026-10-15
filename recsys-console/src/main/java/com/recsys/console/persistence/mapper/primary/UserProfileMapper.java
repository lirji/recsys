package com.recsys.console.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;
import java.sql.Timestamp;
import java.util.List;

/** 主库用户360只读查询；各查询独立失败时仍由服务保留其他已读取部分。 */
public interface UserProfileMapper {
    /** 同时读取非空主键，防止全NULL画像被MyBatis当作不存在的行。 */
    Profile profile(@Param("userId") long userId);
    /** 向量只检查存在性，不加载大字段。 */
    boolean hasEmbedding(@Param("userId") long userId);
    /** 只统计能关联到物料的非空类目。 */
    int distinctCategories(@Param("userId") long userId);
    /** 排除NULL和空字符串，维持数据库原排序。 */
    List<String> buckets(@Param("userId") long userId);
    /** 逐行处理以保留原聚合读取中断时已经取得的结果。 */
    void actionCounts(@Param("userId") long userId, ResultHandler<ActionCount> handler);
    /** 有界近期行为查询，NULL位次/值不改成0。 */
    List<Behavior> recent(@Param("userId") long userId, @Param("limit") int limit);

    /** userId用于区分全NULL画像与不存在的行。 */
    record Profile(long userId, String profile, Timestamp updatedAt) { }
    /** action可空，服务沿用unknown展示。 */
    record ActionCount(String action, long count) { }
    /** JDBC时间在服务转换为毫秒，避免SQL数值转换的舍入差异。 */
    record Behavior(long itemId, String action, Timestamp ts, String bucket, Integer position, String scene, Double value) { }
}
