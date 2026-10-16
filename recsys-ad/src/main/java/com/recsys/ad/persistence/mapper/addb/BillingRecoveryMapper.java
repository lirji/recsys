package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.billing.BillingRecovery.Command;
import com.recsys.common.ad.billing.BillingRecovery.Status;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 恢复命令和原事件状态只在同owner事务内修改，固定表族全部由XML选择。 */
public interface BillingRecoveryMapper {
    /** 启动时核对迁移指纹，避免连接到缺少恢复约束的数据库。 */
    List<String> migrationHashes();
    /** 只探测必要字段，不加载业务数据。 */
    void verifySchema();
    /** 唯一命令键决定同一意图仅执行一次，冲突由应用层比较原始输入。 */
    int insert(@Param("command") Command command);
    /** 查询始终带广告主范围，禁止通过命令标识跨账户取结果。 */
    List<CommandRow> find(@Param("advertiserId") long advertiserId, @Param("commandId") UUID commandId);
    /** 最终结果与本次副作用在同事务提交，完成后由触发器禁止改写。 */
    int complete(@Param("command") Command command, @Param("status") Status status);
    /** 只锁一个明确事件，不扫全局、不抢活跃发送器。 */
    List<UUID> lockOutbox(@Param("family") ChargeMode family, @Param("command") Command command);
    /** 账户范围与账本/义务/投影关键身份一起核查，缺Outbox必须显式人工处理。 */
    List<TargetRow> target(@Param("family") ChargeMode family, @Param("command") Command command);
    /** 仅允许死信或等待窗口外的已发布事件恢复；清除旧发送时间而保留原事件内容。 */
    int requeue(@Param("family") ChargeMode family, @Param("targetId") UUID targetId, @Param("waitSeconds") int waitSeconds);

    /** PENDING只存在于同事务内部，不属于外部恢复结果。 */
    record CommandRow(UUID commandId, long advertiserId, LocalDate budgetDate, ChargeMode family,
                      String action, UUID targetId, String reason, String actor, String status,
                      OffsetDateTime createdAt, OffsetDateTime completedAt) { }
    /** 账本不可变，原payload与目标身份核验后才可重新进入已有relay。 */
    record TargetRow(UUID eventId, UUID qualificationId, BigDecimal amount, String deliveryState,
                     String destination, String partitionKey, String payloadJson, boolean obligationMatches,
                     boolean factPresent, boolean factMatches, String factHash) { }
}
