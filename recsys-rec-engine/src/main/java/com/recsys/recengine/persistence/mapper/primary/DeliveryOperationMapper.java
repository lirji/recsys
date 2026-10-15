package com.recsys.recengine.persistence.mapper.primary;

import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.UUID;

/** 三模式交付只写engine主源，表名由XML固定分支选择而非传入SQL。 */
public interface DeliveryOperationMapper {
    /** 返回原始文本，Repository先检查owner，再解码可能不兼容的JSON。 */
    record OperationRow(String subjectKey, String requestJson, String bucket, double reserve,
                        String state, long fence, String planJson) { }

    /** 摘要必须恰好一行，缺失或重复保持启动失败。 */
    List<String> migrationHashes(@Param("version") int version);
    /** 零行探针验证当前模式依赖的列。 */
    List<UUID> verifyOperation(@Param("mode") ChargeMode mode);
    /** 重复ID保留首次参数，随后由Repository比较原请求。 */
    int create(@Param("mode") ChargeMode mode, @Param("id") UUID id,
               @Param("subject") String subject, @Param("requestJson") String requestJson,
               @Param("bucket") String bucket, @Param("reserve") double reserve);
    /** 只读原检查点，不领取租约。 */
    List<OperationRow> find(@Param("mode") ChargeMode mode, @Param("id") UUID id);
    /** UPDATE RETURNING与调用方Spring事务共用连接，回滚不能消耗fence。 */
    List<OperationRow> acquire(@Param("mode") ChargeMode mode, @Param("id") UUID id,
                               @Param("subject") String subject);
    /** 只有有效租约可以首次冻结计划。 */
    int freeze(@Param("mode") ChargeMode mode, @Param("id") UUID id,
               @Param("fence") long fence, @Param("planJson") String planJson);
    /** 同时检查旧快照与fence，避免同租约的错误覆盖。 */
    int progress(@Param("mode") ChargeMode mode, @Param("id") UUID id, @Param("fence") long fence,
                 @Param("planJson") String planJson, @Param("oldPlanJson") String oldPlanJson,
                 @Param("state") String state, @Param("complete") boolean complete);
    /** 旧执行器不能撤销后继执行器的租约。 */
    int relinquish(@Param("mode") ChargeMode mode, @Param("id") UUID id, @Param("fence") long fence);
}
