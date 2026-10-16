package com.recsys.behavior.persistence.mapper.primary;

import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** behavior自有历史清单SQL边界；读取报告也不装载源快照或wire负载。 */
public interface BehaviorHistoryMapper {
    record Command(UUID commandId, UUID sourceId, String manifest, String manifestSha256, int itemCount,
                   String actor, String reason, String status, long visitSequence,
                   OffsetDateTime createdAt, OffsetDateTime completedAt) { }
    record Item(long behaviorId, String status, String eventId, String error, long lastVisit,
                OffsetDateTime checkedAt, String destination, String deliveryStatus) { }
    record Existing(String eventId, String error, boolean sourceChanged) { }
    record Cancellation(UUID cancelId, UUID commandId, String actor, String reason,
                        String previousStatus, String result, OffsetDateTime cancelledAt) { }
    /** 显式迁移后只做列探针，不自动创建历史表。 */
    List<Command> verifyCommands();
    List<Item> verifyItems();
    List<Cancellation> verifyCancellations();
    /** 命令与清单同事务登记；唯一冲突后下一SQL读取原结果。 */
    int insertCommand(@Param("id") UUID id, @Param("source") UUID source, @Param("manifest") String manifest,
                      @Param("count") int count, @Param("actor") String actor, @Param("reason") String reason);
    int insertItems(@Param("id") UUID id);
    Command lockCommand(@Param("id") UUID id);
    /** 仅在command锁下选下一项；本次已访问ID有界排除，公平次序跨进程持久保存。 */
    Long nextItem(@Param("id") UUID id, @Param("visited") List<Long> visited);
    int tick(@Param("id") UUID id);
    Long lockSource(@Param("id") long id);
    /** 源行锁后再取新快照；大源数据仅在数据库内计算摘要。 */
    Existing existing(@Param("id") long id);
    int saveItem(@Param("command") UUID command, @Param("id") long id, @Param("status") String status,
                 @Param("event") String event, @Param("error") String error);
    int complete(@Param("id") UUID id);
    /** 固定捕获结果与查询时真实Outbox状态分别返回。 */
    List<Item> items(@Param("id") UUID id);
    Cancellation cancellation(@Param("id") UUID id);
    int insertCancellation(@Param("id") UUID id, @Param("command") UUID command, @Param("actor") String actor,
                           @Param("reason") String reason, @Param("previous") String previous, @Param("result") String result);
    int cancel(@Param("id") UUID id);
}
