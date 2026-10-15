package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.billing.BudgetAccount;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 预占与账户共用adDb事务，Mapper不自行提交或决定状态迁移。 */
public interface ReservationMapper {
    /** 校验固定V003的唯一迁移记录。 */
    List<String> migrationHashes();
    /** 启动探针只验证，不执行DDL。 */
    void verifyReservation();
    /** 每次到库取得真实语句时钟，用于锁后的到期判定。 */
    OffsetDateTime databaseNow();
    /** 数据库唯一键抢占，首次请求保存原预算日和报价期限。 */
    int claim(Claim row);
    /** 只锁已有账户，不在预占用例自动创建预算。 */
    List<BudgetAccount> lockAccount(@Param("advertiserId") long advertiserId, @Param("day") LocalDate day);
    /** 版本和可用额度双条件守住硬预算。 */
    int hold(@Param("account") BudgetAccount account, @Param("amount") BigDecimal amount);
    /** 只允许PENDING保存第一次决策。 */
    int complete(@Param("reservationId") UUID reservationId, @Param("decision") String decision, @Param("state") String state);
    /** lock为服务端用例参数，只控制固定FOR UPDATE片段。 */
    List<Stored> find(@Param("reservationId") UUID reservationId, @Param("lock") boolean lock);
    /** 有序领取最多一条到期记录，跳过其他执行器已持有的行锁。 */
    List<Stored> lockOneExpired();
    /** 与预占终态在同一事务内移动held到charged。 */
    int captureAccount(@Param("account") BudgetAccount account, @Param("amount") BigDecimal amount);
    /** 捕获只允许从同版本HELD转移一次。 */
    int captureReservation(@Param("reservationId") UUID reservationId, @Param("version") long version, @Param("acceptedAt") Timestamp acceptedAt);
    /** 释放额度不能小于已预占金额，失配由Repository抛错回滚。 */
    int releaseAccount(@Param("account") BudgetAccount account, @Param("amount") BigDecimal amount);
    /** 关闭状态和审计时刻与额度释放一起提交。 */
    int close(@Param("reservationId") UUID reservationId, @Param("version") long version,
              @Param("state") String state, @Param("reason") String reason, @Param("now") Timestamp now);

    /** 原报价与接受时刻的写入快照，时间沿用Timestamp绑定。 */
    record Claim(UUID reservationId, UUID quoteId, long advertiserId, long adId, String subject, LocalDate day,
                 BigDecimal amount, Timestamp expiresAt, Timestamp acceptedAt) { }
    /** 决策可在事务内PENDING时为空，先由Repository拒绝半成品再构造领域对象。 */
    record Stored(UUID reservationId, UUID quoteId, long advertiserId, long adId, String subjectKey, LocalDate budgetDate,
                  BigDecimal amount, OffsetDateTime expiresAt, String decision, OffsetDateTime createdAt,
                  String state, long version, OffsetDateTime closedAt, String closeReason) { }
}
