package com.recsys.adserving.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

/** 可靠目录的唯一 SQL 边界，全部操作使用广告自有库。 */
public interface CatalogProjectionMapper {
    record Control(String sourceId, String clusterId, String topicId, String topicName, String groupId,
                   String state, String workerId, long fence, Instant leaseUntil) { }
    record Position(int partitionId, long startOffset, long nextOffset) { }
    record Receipt(int partitionId, long recordOffset, byte[] recordKey, byte[] payload, String digest,
                   String state, int attempts, String errorCode) { }
    record Projection(long adId, String sourceId, long aggregateVersion, String digest, byte[] payload, byte[] embeddingPayload,
                      Long itemId, boolean deleted) { }
    record Task(long taskId, long adId, long aggregateVersion, byte[] oldPayload, byte[] payload,
                String state, int attempts, long fence, Instant leaseUntil) { }
    record Audit(String commandId, String kind, String target, String actor, String reason, Long resultId) { }
    /** 只读检测不要求默认关闭环境已安装迁移。 */
    String installed();
    /** 显式安装器在本事务绑定资源摘要，DDL 原文不随环境改变。 */
    String prepareInstall(@Param("digest") String digest);
    /** 启用前核对实际安装版本对应的资源摘要。 */
    String schemaDigest();
    /** 每次短事务设置锁与语句上限。 */
    void budget();
    /** 初始化/领取串行化，锁键属于本服务固定技术空间。 */
    void controlLock();
    /** 初始化先排空已有副本写事务，再登记可靠模式，封闭检查空表到启用之间的窗口。 */
    void freezeLegacy();
    /** 仅当前可靠事务能够写入已登记的数据集。 */
    String projectionWriter();
    /** 同一广告的版本、刷新命令按单库事务串行。 */
    void adLock(@Param("adId") long adId);
    /** 读取冻结源身份与执行状态。 */
    Control control();
    /** 登记唯一数据集，不替换旧来源。 */
    int initialize(@Param("control") Control control);
    /** 保存首次保留起点与可靠位置。 */
    int addPosition(@Param("position") Position position);
    /** 读取分区可靠位置，禁止根据最大消息ID推断。 */
    List<Position> positions();
    /** 仅领取可恢复且无有效租约的数据集。 */
    int claimConsumer(@Param("worker") String worker);
    /** 原有效fence续租。 */
    int renew(@Param("worker") String worker, @Param("fence") long fence);
    /** 校验当前执行器仍持有有效租约。 */
    int checkFence(@Param("worker") String worker, @Param("fence") long fence);
    /** 固定失败分类持久暂停。 */
    int pauseConsumer(@Param("worker") String worker, @Param("fence") long fence, @Param("code") String code);
    /** 只允许暂停态进入新恢复轮。 */
    int resumeConsumer();
    /** 正常退出只释放当前执行器，不清空失败预算或别人的租约。 */
    int releaseConsumer(@Param("worker") String worker, @Param("fence") long fence);
    /** 初始化拒绝接管已有副本。 */
    int originalRows();
    /** 幂等保存原key与payload以保留失败证据。 */
    int capture(@Param("receipt") Receipt receipt);
    /** 按原Kafka坐标读取投递。 */
    Receipt receipt(@Param("partition") int partition, @Param("offset") long offset);
    /** 执行前持久扣除重试预算。 */
    int attempt(@Param("partition") int partition, @Param("offset") long offset);
    /** 只从待处理状态提交终态。 */
    int receiptDone(@Param("partition") int partition, @Param("offset") long offset,
                    @Param("state") String state, @Param("code") String code);
    /** 审计恢复轮只重置未决原记录。 */
    int resetAttempts();
    /** 隔离原记录进入本次审计重检；事务失败仍保持原隔离结论。 */
    int reopen(@Param("partition") int partition, @Param("offset") long offset);
    /** 已验证的768维向量连同源模型标识一起更新。 */
    int upsertEmbedding(@Param("adId") long adId, @Param("vector") String vector, @Param("model") String model);
    /** 只推进连续的原可靠位置。 */
    int advance(@Param("partition") int partition, @Param("offset") long offset);
    /** 读取广告最高版本和墓碑。 */
    Projection projection(@Param("adId") long adId);
    /** 身份摘要冲突必须隔离。 */
    String inboxDigest(@Param("eventId") String eventId);
    /** 写入不可覆盖的事件身份。 */
    int inbox(@Param("eventId") String eventId, @Param("adId") long adId, @Param("version") long version,
              @Param("digest") String digest, @Param("state") String state);
    /** 同源较高版本才能替换投影。 */
    int project(@Param("projection") Projection projection);
    /** 通过 RETURNING 返回原任务身份，不能用 MAX(id) 猜测本次写入。 */
    long addTask(@Param("adId") long adId, @Param("version") long version,
                 @Param("oldPayload") byte[] oldPayload, @Param("payload") byte[] payload);
    /** 耗尽预算的过期任务收敛为暂停。 */
    int expireTasks();
    /** 按广告顺序领取一项任务，其他广告可继续。 */
    Task claimTask();
    /** 有效fence才可确认成功。 */
    int finishTask(@Param("id") long id, @Param("fence") long fence);
    /** 失败持久退避并保留次数。 */
    int failTask(@Param("id") long id, @Param("fence") long fence, @Param("code") String code);
    /** 读取任务原身份和执行状态。 */
    Task task(@Param("id") long id);
    /** 仅暂停任务允许新审计轮。 */
    int retryTask(@Param("id") long id);
    /** 命令重放查询原结果。 */
    Audit audit(@Param("id") String id);
    /** 恢复动作与业务效果原子保存。 */
    int addAudit(@Param("audit") Audit audit);
    /** 固定低基数状态指标。 */
    long stateCount(@Param("state") String state);
    /** 隔离消息独立计数，不能与成功任务混为零积压。 */
    long quarantinedCount();
    /** 未决最老年龄用于告警。 */
    double oldestPendingSeconds();
}
