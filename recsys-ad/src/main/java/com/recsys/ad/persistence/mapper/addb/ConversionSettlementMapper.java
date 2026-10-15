package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.billing.BudgetAccount;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** CPA所有事实绑定同一adDb，保留quote父锁、账户条件更新及原5秒超时。 */
public interface ConversionSettlementMapper {
    /** 启用前核对固定V013，不自动修改数据库。 */
    List<String> migrationHashes();
    /** 准备/结算表必要列必须存在。 */
    void verifySettlement();
    /** 可见回执与业务事实独立存储，分别校验。 */
    void verifyVisible();
    /** 广告主业务事实表缺失不能接单。 */
    void verifyOutcome();
    /** 唯一资金账本必须就绪。 */
    void verifyLedger();
    /** 资金事务写入的可靠待办必须就绪。 */
    void verifyOutbox();
    /** 串行化尚未存在的结算，避免反向锁序。 */
    List<UUID> lockQuote(@Param("id") UUID id);
    /** 每次稳定排序领取最多一个到期父行，跳过其他持锁者。 */
    List<UUID> lockOneExpired();
    /** 已持quote锁的内部用例读取展示主体。 */
    List<String> owner(@Param("id") UUID id);
    /** 报价、结算与捕获金额来自同一JOIN快照。 */
    List<SnapshotRow> find(@Param("id") UUID id);
    /** 与账户预占同事务保存第一次准备决定。 */
    int insert(Preparation row);
    /** 已授权广告主范围必须在数据库查询中执行。 */
    List<SnapshotRow> findForAdvertiser(@Param("id") UUID id, @Param("advertiserId") long advertiserId);
    /** 可见命令唯一键等待首个事务，冲突不覆盖。 */
    int claimVisible(VisibleClaim row);
    /** 原可见决定与当前状态从同一结果集构造，不做nested select。 */
    List<StoredVisibleRow> storedVisible(@Param("id") UUID id);
    /** canonical接受时间不因重复观测改变。 */
    List<VisibleRow> canonicalVisible(@Param("id") UUID id);
    /** 仅PENDING可保存第一次可见决定。 */
    int completeVisible(CompletedVisible row);
    /** 只忽略广告主+业务键冲突，内部outcomeId冲突仍须报错。 */
    int claimOutcome(OutcomeClaim row);
    /** 业务查询必须带广告主，原actor仅保留审计语义。 */
    List<StoredOutcomeRow> storedOutcome(@Param("advertiserId") long advertiserId, @Param("eventId") UUID eventId);
    /** 内部canonical事实读取，保留其自身ID与发生/接收时刻。 */
    List<OutcomeRow> outcome(@Param("id") UUID id);
    /** 第一业务决定只保存一次，和资金/Outbox共用事务。 */
    int completeOutcome(@Param("id") UUID id, @Param("status") String status, @Param("canonical") UUID canonical, @Param("now") Timestamp now);
    /** 版本与HELD条件保证双条件状态迁移只生效一次。 */
    int advance(Advance row);
    /** 释放或捕获都减原日held，只有捕获增加charged。 */
    int debitHeld(@Param("account") BudgetAccount account, @Param("amount") BigDecimal amount,
                  @Param("charge") BigDecimal charge, @Param("now") Timestamp now);
    /** 唯一账本冲突必须回滚此前资金更新。 */
    int appendLedger(Ledger row);
    /** 原TEXT事件协议只写本地待办，不调用broker。 */
    int appendOutbox(Outbox row);

    /** outcomeId来自settled_outcome_id，不能与本次查询的业务事实ID混同。 */
    record SnapshotRow(UUID qualificationId, String decision, LocalDate budgetDate, BigDecimal amount,
                       OffsetDateTime issuedAt, OffsetDateTime expiresAt, OffsetDateTime preparedAt, String qualificationToken,
                       String ownerSubject, String state, long version, UUID visibleReceiptId, UUID outcomeId,
                       UUID ledgerId, BigDecimal capturedAmount, OffsetDateTime closedAt, String closeReason) { }
    /** PENDING允许决定字段为空，Repository先拒绝再创建领域结果。 */
    record VisibleRow(UUID receiptId, UUID qualificationId, String status, UUID canonicalReceiptId,
                      OffsetDateTime acceptedAt, OffsetDateTime decidedAt) { }
    /** outcomeId来自event_outcome_id，保留广告主业务键和原事件时间。 */
    record OutcomeRow(UUID outcomeId, long advertiserId, UUID eventId, UUID qualificationId, OffsetDateTime occurredAt,
                      OffsetDateTime receivedAt, String status, UUID canonicalOutcomeId, OffsetDateTime decidedAt) { }
    /** 两个子记录均从同一JOIN结果行构造。 */
    record StoredVisibleRow(String hash, VisibleRow visible, SnapshotRow snapshot) { }
    /** 原业务决定和当前结算状态来自同一数据库快照。 */
    record StoredOutcomeRow(String hash, OutcomeRow outcome, SnapshotRow snapshot) { }
    /** 广告主归属与原预算日随第一次准备保存。 */
    record Preparation(UUID qualificationId, long advertiserId, LocalDate budgetDate, String decision,
                       String qualificationToken, Timestamp preparedAt, String state) { }
    /** 敏感证明不写可见回执，只保存服务端摘要。 */
    record VisibleClaim(UUID receiptId, UUID qualificationId, String subject, String payloadHash,
                        long visibleDurationMs, int minVisibleRatioBps, boolean pageVisible) { }
    /** 拒绝结果中的canonical/acceptedAt允许NULL，显式绑定避免被非空策略跳过。 */
    record CompletedVisible(UUID receiptId, String status, UUID canonicalReceiptId, Timestamp acceptedAt, Timestamp decidedAt) { }
    /** actor用于原审计，业务幂等仍按advertiserId和eventId。 */
    record OutcomeClaim(UUID outcomeId, long advertiserId, UUID eventId, UUID qualificationId, String actor,
                        String payloadHash, Timestamp occurredAt) { }
    /** 原状态版本作为条件，HELD继续等待时不写关闭时刻。 */
    record Advance(UUID qualificationId, long version, UUID visible, UUID outcome, UUID ledger, String state,
                   String reason, Timestamp closedAt, Timestamp now) { }
    /** 捕获使用冻结报价金额，不使用业务事件携带的其他费用。 */
    record Ledger(UUID ledgerId, UUID qualificationId, UUID visible, UUID outcome, UUID operationId, long advertiserId,
                  long adId, long creativeId, long itemId, long userId, int slot, String bidType, LocalDate budgetDate,
                  BigDecimal amount, long accountVersion, Timestamp acceptedAt) { }
    /** 分区键和事件身份沿用原契约。 */
    record Outbox(UUID eventId, String eventType, int schemaVersion, String destination, String partitionKey, String payloadJson) { }
}
