package com.recsys.ad.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 回执、账本与Outbox共用owner事务；写入行数由Repository检查。 */
public interface ImpressionReceiptMapper {
    /** 固定V005摘要，缺失或重复由Repository拒绝。 */
    List<String> migrationHashes();
    /** 仅验证必要列，不创建业务表。 */
    void verifyReceipt();
    /** 账本是捕获最终事实，不以缓存代替。 */
    void verifyLedger();
    /** Outbox必须和捕获事实一同可用。 */
    void verifyOutbox();
    /** 唯一键等待首个事务；证明原文不写回执表。 */
    int claim(Claim row);
    /** 先映射可空原始行，Repository鉴权并拒绝PENDING后才构造结果。 */
    List<Stored> find(@Param("receiptId") UUID receiptId);
    /** 按资格读取唯一收费事实，返回原金额、预算日和接受时刻。 */
    List<Captured> alreadyCaptured(@Param("qualificationId") UUID qualificationId);
    /** 不可变账本，冲突不得忽略或覆盖。 */
    int appendLedger(Ledger row);
    /** 同事务写待发事件，Mapper不调用broker。 */
    int appendOutbox(Outbox row);
    /** 只有PENDING可保存首次终态，异常必须回滚此前所有效果。 */
    int complete(Completed row);

    /** 仅持久化服务端生成的负载摘要与观测值。 */
    record Claim(UUID receiptId, UUID qualificationId, String subject, String payloadHash,
                 long visibleDurationMs, int minVisibleRatioBps, boolean pageVisible) { }
    /** PENDING允许结果字段为空，保留主体优先校验。 */
    record Stored(String subject, String payloadHash, UUID qualificationId, String status, UUID ledgerId,
                  UUID canonicalReceiptId, BigDecimal chargedAmount, LocalDate budgetDate,
                  OffsetDateTime acceptedAt, OffsetDateTime decidedAt) { }
    /** 已捕获快照不按账户当前版本过滤，避免漏记历史事实。 */
    record Captured(UUID ledgerId, UUID receiptId, BigDecimal amount, LocalDate budgetDate, OffsetDateTime acceptedAt) { }
    /** 资格和预占快照组合出的账本列，金额保持精确十进制。 */
    record Ledger(UUID ledgerId, UUID qualificationId, UUID receiptId, UUID operationId, long advertiserId, long adId,
                  long creativeId, long userId, int slot, LocalDate budgetDate, BigDecimal amount, long accountVersion, Timestamp acceptedAt) { }
    /** TEXT事件载荷沿用既有契约，不更换JSON存储类型。 */
    record Outbox(UUID eventId, String eventType, int schemaVersion, String destination, String partitionKey, String payloadJson) { }
    /** 拒绝结果的账本ID/接受时间可空，绑定显式列避免自动更新策略漏写NULL。 */
    record Completed(UUID receiptId, String status, UUID ledgerId, UUID canonicalReceiptId, BigDecimal chargedAmount,
                     LocalDate budgetDate, Timestamp acceptedAt, Timestamp decidedAt) { }
}
