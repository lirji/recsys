package com.recsys.ad.persistence.mapper.addb;

import com.recsys.ad.billing.BillingOutboxRepository.EventFamily;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.UUID;

/** 三族可靠投递共用原子算法，表名在XML固定分支中选择，业务值全部绑定。 */
public interface BillingOutboxMapper {
    /** 启动只检查既有迁移摘要，不能隐式修改共享数据库。 */
    List<String> migrationHashes(@Param("version") int version);
    /** 租约和fence所需列必须存在。 */
    void verifyOutbox(@Param("family") EventFamily family);
    /** 事件族自己的Inbox必须可读。 */
    void verifyFact(@Param("family") EventFamily family);
    /** 可重建汇总必须具备金额列。 */
    void verifyDaily(@Param("family") EventFamily family);
    /** 有界清理尝试已耗尽的到期租约，跳过其他执行器持锁行。 */
    int exhaust(@Param("family") EventFamily family, @Param("maxAttempts") int maxAttempts);
    /** UPDATE RETURNING作为有副作用的select，自动提交和显式事务回滚都必须生效。 */
    List<PendingRow> claim(@Param("family") EventFamily family, @Param("maxAttempts") int maxAttempts,
                           @Param("leaseSeconds") int leaseSeconds);
    /** ACK之后仍须持有效租约/fence，过期执行器不能标记新执行器的待办。 */
    int published(@Param("family") EventFamily family, @Param("eventId") UUID eventId, @Param("fence") long fence);
    /** 重试次数与退避持久化，失败摘要只保存受限错误类型。 */
    int failed(@Param("family") EventFamily family, @Param("maxAttempts") int maxAttempts,
               @Param("delayMs") long delayMs, @Param("errorType") String errorType,
               @Param("eventId") UUID eventId, @Param("fence") long fence);
    /** DEAD重开和审计同一语句；审计冲突必须回滚重开效果。 */
    int replay(@Param("family") EventFamily family, @Param("replayId") UUID replayId,
               @Param("eventId") UUID eventId, @Param("actor") String actor, @Param("reason") String reason);
    /** 一次聚合读取待办、死信和最老等待时间，不混入已发布事件。 */
    List<BacklogRow> backlog(@Param("family") EventFamily family);

    /** 原事件载荷保持TEXT，领取只增加租约与尝试计数。 */
    record PendingRow(UUID eventId, String destination, String key, String payload, long fence, int attempts) { }
    /** 空积压仍返回一行零值。 */
    record BacklogRow(long pending, long dead, double oldestSeconds) { }
}
