package com.recsys.behavior.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 自然事实owner的持久化操作；锁、数据库时间和写入必须共享原Spring事务连接。 */
public interface NaturalReceiptMapper {
    /** 保留单条迁移记录的必需存在性检查。 */
    List<String> migrationHashes();
    /** LIMIT 0只校验必要列，不读取或修改业务数据。 */
    void verifyGuard();
    /** LIMIT 0只校验回执必要列。 */
    void verifyReceipt();
    /** LIMIT 0只校验事实必要列。 */
    void verifyFact();
    /** LIMIT 0只校验Outbox必要列。 */
    void verifyOutbox();
    /** 使用语句时钟，不能被会话查询缓存替代。 */
    OffsetDateTime databaseNow();
    /** 设置当前事务锁等待边界，不改变连接池全局参数。 */
    void boundLocks();
    /** 设置当前事务语句等待边界。 */
    void boundStatements();
    /** 唯一键抢占返回实际插入数；参数不包含原资格票据。 */
    int claim(Claim row);
    /** 先幂等建立资格锁记录，不覆盖首次上下文。 */
    int insertGuard(GuardWrite row);
    /** 获取资格行锁后再由Repository核验主体与上下文。 */
    Guard lockGuard(@Param("qualificationId") UUID qualificationId);
    /** 按原回执ID恢复持久化决定，不重新执行现行策略。 */
    List<StoredReceipt> receipts(@Param("receiptId") UUID receiptId);
    /** 资格已接受时引用同一事实，禁止生成第二条。 */
    List<AcceptedFact> accepted(@Param("qualificationId") UUID qualificationId);
    /** 事实与Outbox必须在同一事务提交，由数据库延迟约束兜底。 */
    int appendFact(@Param("qualificationId") UUID qualificationId, @Param("receiptId") UUID receiptId,
                   @Param("acceptedAt") Timestamp acceptedAt);
    /** 不发送Kafka，只写不可变Outbox。 */
    int appendOutbox(OutboxWrite row);
    /** 只允许PENDING首次变更，调用者必须检查影响行数。 */
    int complete(Decision row);

    /** 幂等命令的安全持久化字段，不包含资格签名票据。 */
    record Claim(UUID receiptId, UUID qualificationId, String subject, String payloadHash,
                 long visibleDurationMs, int minVisibleRatioBps, boolean pageVisible) { }
    /** 已验证资格上下文；不包含签名密钥。 */
    record GuardWrite(UUID qualificationId, String subject, String hash, String contextJson) { }
    /** 锁定后需比较的主体及上下文摘要。 */
    record Guard(String subjectKey, String contextHash) { }
    /** 原数据库状态，枚举及权限校验仍在Repository中完成。 */
    record StoredReceipt(UUID qualificationId, String subjectKey, String payloadHash, String status,
                         UUID eventId, UUID canonicalReceiptId, OffsetDateTime acceptedAt, OffsetDateTime decidedAt) { }
    /** 已接受事实的规范回执及时间。 */
    record AcceptedFact(UUID receiptId, OffsetDateTime acceptedAt) { }
    /** 事件写入行，元数据沿用已有版本与目的地常量。 */
    record OutboxWrite(UUID eventId, String eventType, int schemaVersion, String destination, String partitionKey, String payloadJson) { }
    /** 首次决定字段，Timestamp继续保持原JDBC绑定语义。 */
    record Decision(UUID receiptId, String status, UUID eventId, UUID canonicalReceiptId, Timestamp acceptedAt, Timestamp decidedAt) { }
}
