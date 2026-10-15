package com.recsys.offline.persistence.mapper.derived;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/** 人群扩散只读取派生库种子和用户向量，Redis发布仍由原作业负责。 */
public interface LookalikeMapper {
    /** 保留原无排序、去重且排除NULL人群的扫描。 */
    List<Long> audienceIds();
    /** 保留重复种子与NULL用户，不能擅自改变最终种子并集。 */
    List<Long> seedUsers(@Param("audienceId") long audienceId);
    /** SQL IN只让重复种子向量参与一次质心，NULL向量由原算法跳过。 */
    void scanSeedVectors(@Param("ids") List<Long> ids, ResultHandler<String> handler);
    /** 余弦距离排序和LIMIT保持，NULL身份按原ResultSet.getLong转换为0。 */
    List<Long> nearestUsers(@Param("vector") PGvector vector, @Param("limit") int limit);
}
