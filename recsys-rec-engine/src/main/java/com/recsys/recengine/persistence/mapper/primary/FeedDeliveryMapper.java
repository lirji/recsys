package com.recsys.recengine.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 混排整次操作只写engine主源；冻结位置与补齐广告结果共用原短事务边界。 */
public interface FeedDeliveryMapper {
    /** 原始快照由Repository在归属检查后解码，保持不兼容版本的隔离。 */
    record OperationRow(String subjectKey, String requestJson, String settingsJson,
                        String state, long fence, String planJson) { }
    /** 校验原迁移摘要，缺行不当作空配置。 */
    List<String> migrationHashes();
    /** 启动只验证依赖列，迁移仍显式执行。 */
    List<UUID> verifyOperation();
    /** 相同ID只能保留首次请求与混排设置。 */
    int create(@Param("id") UUID id, @Param("subject") String subject,
               @Param("requestJson") String requestJson, @Param("settingsJson") String settingsJson);
    /** 不领取租约，不触发广告owner副作用。 */
    List<OperationRow> find(@Param("id") UUID id);
    /** 原子递增fence且只领取无有效租约的操作。 */
    List<OperationRow> acquire(@Param("id") UUID id, @Param("subject") String subject);
    /** 资格时间来自数据库，精度由原Repository约束。 */
    OffsetDateTime databaseNow();
    /** 广告准备前必须先冻结完整位置，纯自然可直接完成。 */
    int freeze(@Param("id") UUID id, @Param("subject") String subject, @Param("fence") long fence,
               @Param("planJson") String planJson, @Param("state") String state, @Param("complete") boolean complete);
    /** 对比原快照和执行权，防止并发覆盖已确认的广告结果。 */
    int progress(@Param("id") UUID id, @Param("subject") String subject, @Param("fence") long fence,
                 @Param("planJson") String planJson, @Param("oldPlanJson") String oldPlanJson,
                 @Param("state") String state, @Param("complete") boolean complete);
    /** 只能释放同一fence的执行权。 */
    int relinquish(@Param("id") UUID id, @Param("fence") long fence);
}
