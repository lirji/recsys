package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.billing.BillingReconciliation.Scope;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 对账只读查询与固定三族表集中于XML，所有读取加入调用方同一个RR快照。 */
public interface ReconciliationMapper {
    /** 校验已执行迁移，不隐式修改数据库。 */
    List<String> migrationHashes(@Param("version") int version);
    /** 账本关键身份与金额列必须可读。 */
    void verifyLedger(@Param("family") ChargeMode family);
    /** 待发状态缺失不能解释成没有积压。 */
    void verifyOutbox(@Param("family") ChargeMode family);
    /** 投影事实缺失不能返回健康零值。 */
    void verifyFact(@Param("family") ChargeMode family);
    /** 各事件族保留独立计数列。 */
    void verifyDaily(@Param("family") ChargeMode family);
    /** 各族未完结资金义务的关联列必须存在。 */
    void verifyObligations(@Param("family") ChargeMode family);
    /** 保留transaction_timestamp，后续查询共用同事务的MVCC快照。 */
    List<OffsetDateTime> observedAt();
    /** 不锁账户，账户不存在与查询异常严格区分。 */
    List<AccountRow> account(@Param("scope") Scope scope);
    /** 单条聚合同时比对义务、账本、待发、Inbox与汇总；零金额仍按身份和笔数核对。 */
    List<FamilyRow> family(@Param("scope") Scope scope, @Param("family") ChargeMode family,
                           @Param("observedAt") OffsetDateTime observedAt, @Param("ledgerIdentityRequired") boolean ledgerIdentityRequired);

    /** 原预算日由查询scope带入领域账户，金额精度保持原样。 */
    record AccountRow(long advertiserId, BigDecimal limitAmount, BigDecimal heldAmount, BigDecimal chargedAmount, long version) { }
    /** 金额先保留NUMERIC，两个可能缺失的事实时间允许NULL，再由Repository按原格式构造报告。 */
    record FamilyRow(long heldCount, BigDecimal heldAmount, long expiredHeldCount,
                     long ledgerCount, BigDecimal ledgerAmount, long missingLedgerCount,
                     long invalidLedgerCount, long pendingOutboxCount, long deadOutboxCount,
                     long missingOutboxCount, OffsetDateTime oldestPendingAt, long factCount,
                     BigDecimal factAmount, long missingFactCount, long invalidFactCount,
                     long unexpectedFactCount, OffsetDateTime lastAppliedAt, boolean summaryPresent,
                     long summaryCount, BigDecimal summaryAmount) { }
}
