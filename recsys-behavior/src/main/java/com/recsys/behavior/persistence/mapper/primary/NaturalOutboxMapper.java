package com.recsys.behavior.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 自然事件发送状态唯一 SQL 边界，绑定 behavior primary 连接。 */
public interface NaturalOutboxMapper {
    record Pending(UUID eventId, String eventType, int schemaVersion, String destination,
                   String key, String payload, long fence, int attempts) { }
    record Locked(String status, long fence, int attempts) { }
    record Replay(UUID replayId, UUID eventId, String actor, String reason, int oldAttempts,
                  long previousFence, long newFence, OffsetDateTime replayedAt) { }
    record Backlog(long pending, long dead, double oldestSeconds) { }

    /** 只校验显式迁移记录，不自动建表。 */
    List<String> migrationHashes(@Param("version") int version);
    /** 列探针阻止配置漂移的实例开始领取。 */
    List<Pending> verifyOutbox();
    List<Replay> verifyReplay();
    /** 事务内配置数据库等待上限。 */
    void boundLocks();
    void boundStatements();
    /** 原子领取一条已到期任务，返回提交后发送需要的原事件。 */
    List<Pending> claim(@Param("maxAttempts") int maxAttempts, @Param("leaseSeconds") int leaseSeconds);
    /** 有效 fence 持有者才能记录 broker 确认。 */
    int published(@Param("eventId") UUID eventId, @Param("fence") long fence);
    /** 只写受限错误分类，永久坏事件或耗尽事件进入 DEAD。 */
    int failed(@Param("eventId") UUID eventId, @Param("fence") long fence,
               @Param("delayMs") long delayMs, @Param("error") String error, @Param("maxAttempts") int maxAttempts);
    /** 每轮只清算有界数量的耗尽租约。 */
    int exhaust(@Param("maxAttempts") int maxAttempts, @Param("limit") int limit);
    /** 原命令先查询，事件锁用于串行化不同重放命令。 */
    Replay replay(@Param("replayId") UUID replayId);
    Locked lockEvent(@Param("eventId") UUID eventId);
    /** 唯一键处理不同事件共用命令 ID 的并发冲突。 */
    int insertReplay(@Param("replayId") UUID replayId, @Param("eventId") UUID eventId,
                     @Param("actor") String actor, @Param("reason") String reason,
                     @Param("attempts") int attempts, @Param("fence") long fence);
    /** 与审计同事务重开 DEAD，不重建业务事实。 */
    int reopen(@Param("eventId") UUID eventId, @Param("fence") long fence);
    /** 有时限的低频积压观测。 */
    Backlog backlog();
}
