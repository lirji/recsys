package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.billing.BudgetAccount;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 点击结算所有语句绑定adDb，事务与quote→rate→account锁序由原用例负责。 */
public interface ClickSettlementMapper {
    /** 固定V009摘要，缺失或重复由Repository拒绝。 */
    List<String> migrationHashes();
    /** 启动只验证必要列，不能隐式建表。 */
    void verifySettlement();
    /** 回执原决定必须可持久化。 */
    void verifyReceipt();
    /** 风控计数表缺失不能放行。 */
    void verifyRate();
    /** 账本唯一事实表必须就绪。 */
    void verifyLedger();
    /** Outbox和资金在同库事务内写入。 */
    void verifyOutbox();
    /** 锁定已有quote父行，串行化尚不存在的结算。 */
    List<UUID> lockQuote(@Param("id") UUID id);
    /** 每次稳定排序领取最多一个到期quote，跳过其他持有者。 */
    List<UUID> lockOneExpired();
    /** 仅已锁quote的内部用例读取原主体。 */
    List<String> owner(@Param("id") UUID id);
    /** 状态、报价与捕获金额必须来自同一查询快照。 */
    List<SnapshotRow> find(@Param("id") UUID id);
    /** 与账户held增加同事务提交第一次准备结果。 */
    int insert(Preparation row);
    /** 唯一键等待首个回执事务，冲突不覆盖。 */
    int claim(Claim row);
    /** 复用同一JOIN结果集映射回执和状态，禁止额外查询拼接快照。 */
    List<StoredRow> stored(@Param("id") UUID id);
    /** 原种类canonical回执独立保存接受时刻。 */
    List<ReceiptRow> canonical(@Param("id") UUID id);
    /** 只允许PENDING记录首次决定。 */
    int complete(Completed row);
    /** 用户计数行首次建立，不创建逐分钟无限增长记录。 */
    int ensureRate(@Param("userId") long userId);
    /** rate在账户之前锁定，保持跨广告主风控串行化。 */
    List<Long> lockRate(@Param("userId") long userId);
    /** 超限不自增；使用持有全部锁后的同一个决定时刻。 */
    int countClick(@Param("userId") long userId, @Param("limit") int limit, @Param("now") Timestamp now);
    /** 同版本HELD才能推进，全部条件与状态一起写入。 */
    int advance(Advance row);
    /** 释放或捕获均减少原日held，版本/余额条件守住最终完整性。 */
    int debitHeld(@Param("account") BudgetAccount account, @Param("amount") BigDecimal amount,
                  @Param("charge") BigDecimal charge, @Param("now") Timestamp now);
    /** 不可变账本插入失败须回滚资金与回执。 */
    int appendLedger(Ledger row);
    /** 原TEXT事件契约只落本地待办，不在持锁期间调用broker。 */
    int appendOutbox(Outbox row);

    /** 可空捕获/关闭字段按原始行映射，业务校验仍在Repository完成。 */
    record SnapshotRow(UUID qualificationId, String decision, LocalDate budgetDate, BigDecimal amount,
                       OffsetDateTime issuedAt, OffsetDateTime expiresAt, OffsetDateTime preparedAt, String qualificationToken,
                       String ownerSubject, String state, long version, UUID visibleReceiptId, UUID clickReceiptId,
                       UUID ledgerId, BigDecimal capturedAmount, OffsetDateTime closedAt, String closeReason) { }
    /** PENDING尚无决定时刻，先拒绝半成品再创建领域Receipt。 */
    record ReceiptRow(UUID receiptId, UUID qualificationId, String kind, String status, UUID canonicalReceiptId,
                      OffsetDateTime acceptedAt, OffsetDateTime decidedAt) { }
    /** 两个不可变子记录均来自同一SQL行，不触发nested select。 */
    record StoredRow(String hash, ReceiptRow receipt, SnapshotRow snapshot) { }
    /** 准备决定与签名结果一起持久化。 */
    record Preparation(UUID qualificationId, LocalDate budgetDate, String decision, String qualificationToken,
                       Timestamp preparedAt, String state) { }
    /** 证明原文不落回执表，仅保存服务端摘要与观测。 */
    record Claim(UUID receiptId, UUID qualificationId, String subject, String payloadHash, String kind,
                 long visibleDurationMs, int minVisibleRatioBps, boolean pageVisible) { }
    /** 明确NULL绑定，不使用自动非空更新策略。 */
    record Completed(UUID receiptId, String status, UUID canonicalReceiptId, Timestamp acceptedAt, Timestamp decidedAt) { }
    /** 状态和版本以原快照为条件，关闭时间可空。 */
    record Advance(UUID qualificationId, long version, UUID visible, UUID click, UUID ledger, String state,
                   String reason, Timestamp closedAt, Timestamp now) { }
    /** 冻结报价和原预占预算日组合成唯一捕获事实。 */
    record Ledger(UUID ledgerId, UUID qualificationId, UUID visible, UUID click, UUID operationId, long advertiserId,
                  long adId, long creativeId, long itemId, long userId, int slot, String bidType, LocalDate budgetDate,
                  BigDecimal amount, long accountVersion, Timestamp acceptedAt) { }
    /** 事件身份等于账本身份，分区键沿用广告主和预算日。 */
    record Outbox(UUID eventId, String eventType, int schemaVersion, String destination, String partitionKey, String payloadJson) { }
}
