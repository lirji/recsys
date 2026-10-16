package com.recsys.behavior.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 行为事件发送状态唯一 SQL 边界，绑定 behavior primary 连接。 */
public interface BehaviorEventMapper {
    record Pending(String eventId, String eventType, int schemaVersion, String destination,
                   String key, String payload, String payloadSha256, long fence, int attempts) { }
    record Locked(String status, long fence, int attempts, String error) { }
    record Replay(UUID replayId, String eventId, String actor, String reason, int oldAttempts,
                  long previousFence, long newFence, OffsetDateTime replayedAt) { }
    record Backlog(long pending, long dead, double oldestSeconds) { }

    record Source(long behaviorId, UUID sourceId, String snapshot, String capturedAt) { }
    record Click(long behaviorId, Long userId, Long itemId) { }
    record Existing(String eventId, String status) { }
    /** 每次启动核对真实源schema和持久身份，兼容模式不调用。 */
    boolean verifySourceSchema();
    UUID sourceId();
    /** 下一条READ_COMMITTED语句才保证可见并发INSERT的赢家。 */
    Click lockClick(@Param("exposureId") String exposureId);
    /** 大快照不返回Java，只在后续INSERT SELECT内保存。 */
    Source source(@Param("id") long id);
    Existing existing(@Param("id") long id);
    /** 业务行持锁时写不可变源快照和可发送负载或明确隔离义务。 */
    int insertEvent(@Param("id") long id, @Param("origin") String origin, @Param("destination") String destination,
                    @Param("reason") String reason, @Param("capturedAt") String capturedAt,
                    @Param("payload") String payload, @Param("key") String key);

    /** 只校验显式迁移记录，不自动建表。 */
    List<String> migrationHashes(@Param("version") int version);
    /** 列探针阻止配置漂移的实例开始领取。 */
    List<Pending> verifyOutbox();
    List<Replay> verifyReplay();
    /** 事务内配置数据库等待上限。 */
    void boundLocks();
    void boundStatements();
    /** 原子领取一条已到期任务，返回提交后发送需要的原事件。 */
    List<Pending> claim(@Param("maxAttempts") int maxAttempts, @Param("leaseSeconds") int leaseSeconds, @Param("destination") String destination);
    /** 有效 fence 持有者才能记录 broker 确认。 */
    int published(@Param("eventId") String eventId, @Param("fence") long fence);
    /** 只写受限错误分类，永久坏事件或耗尽事件进入 DEAD。 */
    int failed(@Param("eventId") String eventId, @Param("fence") long fence,
               @Param("delayMs") long delayMs, @Param("error") String error, @Param("maxAttempts") int maxAttempts);
    /** 每轮只清算有界数量的耗尽租约。 */
    int exhaust(@Param("maxAttempts") int maxAttempts, @Param("limit") int limit, @Param("destination") String destination);
    /** 原命令先查询，事件锁用于串行化不同重放命令。 */
    Replay replay(@Param("replayId") UUID replayId);
    Locked lockEvent(@Param("eventId") String eventId);
    /** 唯一键处理不同事件共用命令 ID 的并发冲突。 */
    int insertReplay(@Param("replayId") UUID replayId, @Param("eventId") String eventId,
                     @Param("actor") String actor, @Param("reason") String reason,
                     @Param("attempts") int attempts, @Param("fence") long fence);
    /** 与审计同事务重开 DEAD，不重建业务事实。 */
    int reopen(@Param("eventId") String eventId, @Param("fence") long fence);
    /** 有时限的低频积压观测。 */
    Backlog backlog(@Param("destination") String destination);
}
