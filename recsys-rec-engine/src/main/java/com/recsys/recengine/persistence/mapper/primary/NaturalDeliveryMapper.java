package com.recsys.recengine.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 自然交付使用engine主源，事务边界保留在Repository，SQL只在XML。 */
public interface NaturalDeliveryMapper {
    /** 先校验归属再反序列化，避免不兼容的快照抢先暴露给其他主体。 */
    record OperationRow(String subjectKey, String requestJson, String policyJson,
                        String state, long fence, String resultJson) { }
    /** 保留缺行/重复行失败语义。 */
    List<String> migrationHashes();
    /** 启动验证自然交付必需列，不执行迁移。 */
    List<UUID> verifyOperation();
    /** 相同ID不改变首次请求和政策。 */
    int create(@Param("id") UUID id, @Param("subject") String subject,
               @Param("requestJson") String requestJson, @Param("policyJson") String policyJson);
    /** 返回原快照，不更新租约。 */
    List<OperationRow> find(@Param("id") UUID id);
    /** 原子领取独立提交后才允许访问远程候选与内容。 */
    List<OperationRow> acquire(@Param("id") UUID id, @Param("subject") String subject);
    /** 使用数据库实时时钟，Repository保持原毫秒截断。 */
    OffsetDateTime databaseNow();
    /** 归属、有效租约与fence同时满足才冻结整次响应。 */
    int freeze(@Param("id") UUID id, @Param("subject") String subject,
               @Param("fence") long fence, @Param("resultJson") String resultJson);
    /** 旧执行器不能释放后继租约。 */
    int relinquish(@Param("id") UUID id, @Param("fence") long fence);
}
