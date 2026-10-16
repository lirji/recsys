package com.recsys.recengine.persistence.mapper.primary;

import com.recsys.recengine.filter.seen.SeenRecord;
import org.apache.ibatis.annotations.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** engine主库SQL边界；所有外部网络调用必须在这些短事务之外。 */
public interface SeenRecoveryMapper {
    record Control(String stream, String clusterId, String topicId, String topic, String groupId,
                   UUID initializingCommandId, String stopReason) { }
    record Partition(String stream, int partitionId, String clusterId, String topicId, String topic,
                     String groupId, long bootstrapOffset, UUID commandId, long observedOffset) { }
    record Command(UUID commandId, String kind, String stream, UUID recordId, String requestJson,
                   String requestDigest, String actor, String reason, String status, String result,
                   int attempts, long fence, OffsetDateTime leaseUntil, String beforeStatus,
                   Long beforeEpoch, Integer beforeAttempts, OffsetDateTime createdAt,
                   OffsetDateTime completedAt, boolean leaseActive, boolean due) {
        /** 审计默认输出只含技术标识和状态，避免原请求泄漏。 */
        @Override public String toString() { return "SeenCommand[" + commandId + "," + status + "]"; }
        /** 已完成命令不可重开，精确重试只返回原结果。 */
        public boolean terminal() { return status.equals(SeenRecord.CommandState.SUCCEEDED.name()) || status.equals(SeenRecord.CommandState.FAILED.name()); }
    }
    /** PG锁/语句预算随每个新事务设置。 */
    String tune();
    /** 只接受已安装脚本摘要，不在应用启动时隐式迁移。 */
    List<String> migrationHashes();
    /** 唯一绑定先落库，外部位点副作用不能抢在所有权之前。 */
    int insertControl(@Param("c") Control control);
    /** 读取已持久绑定，不从Kafka名称推断身份。 */
    Control control(@Param("stream") String stream);
    /** 统一以流锁开头，避免恢复和普通消费锁序相反。 */
    Control lockControl(@Param("stream") String stream);
    /** 只有空占有可登记，新命令不能接管未知结果。 */
    int ownInitialization(@Param("stream") String stream, @Param("id") UUID id);
    /** 仅原命令成功后释放，失败保留排他。 */
    int releaseInitialization(@Param("stream") String stream, @Param("id") UUID id);
    /** 记录固定停止原因，重启不能静默清除。 */
    int stop(@Param("stream") String stream, @Param("reason") String reason);
    /** 只返回有界成功清单，不能按broker新分区自动扩张。 */
    List<Partition> partitions(@Param("stream") String stream);
    /** 起点与原初始化命令绑定，之后不得改写。 */
    int insertPartition(@Param("p") Partition partition);
    /** 全流屏障，不只观察当前进程分配的分区。 */
    int barriers(@Param("stream") String stream);
    /** 末次领取退出仍消耗预算，限量清算为暂停。 */
    int settleExpired(@Param("stream") String stream);
    /** 外部提交位点不得跨过持久未完成义务。 */
    int gaps(@Param("stream") String stream, @Param("partition") int partition, @Param("committed") long committed);
    /** 按技术坐标唯一插入，不能以内容摘要合并业务事件。 */
    int insertRecord(@Param("r") SeenRecord record);
    /** 坐标冲突后重读原文摘要，禁止覆盖。 */
    SeenRecord coordinate(@Param("stream") String stream, @Param("partition") int partition, @Param("offset") long offset);
    /** 查询原记录不改变预算或处理状态。 */
    SeenRecord record(@Param("id") UUID id);
    /** 在control与command之后锁定，保护单记录恢复占有。 */
    SeenRecord lockRecord(@Param("id") UUID id);
    /** 第三次租约到期即暂停，不因进程退出重置。 */
    int expireRecord(@Param("id") UUID id);
    /** 副作用前原子增加尝试和fence，恢复占有期间不得领取。 */
    int claimRecord(@Param("id") UUID id);
    /** 仅有效租约与fence允许回写成功，再由listener确认。 */
    int finishRecord(@Param("id") UUID id, @Param("fence") long fence, @Param("status") String status, @Param("error") String error);
    /** 失败有限退避，未知结果不伪造完成。 */
    int failRecord(@Param("id") UUID id, @Param("fence") long fence, @Param("error") String error, @Param("delay") long delay);
    /** 稳定ID保存首次请求与审计，重复不能覆盖。 */
    int insertCommand(@Param("c") Command command);
    /** 查询原命令，用于超时后先查状态。 */
    Command command(@Param("id") UUID id);
    /** 流锁之后锁命令，串行同命令续办。 */
    Command lockCommand(@Param("id") UUID id);
    /** 单次领取持久消耗预算，终态不重开。 */
    int claimCommand(@Param("id") UUID id);
    /** 恢复开启新epoch并独占目标，原轮次留在审计。 */
    int attachCommand(@Param("id") UUID id, @Param("command") UUID command);
    /** 同步领取记录fence，阻止旧普通消费者回写。 */
    int claimRecoveryRecord(@Param("id") UUID id);
    /** 命令终态只接受有效领取，保留完成时间。 */
    int finishCommand(@Param("id") UUID id, @Param("fence") long fence, @Param("status") String status, @Param("result") String result);
    /** 耗尽预算固定FAILED，不无限自动续办。 */
    int failCommand(@Param("id") UUID id, @Param("fence") long fence, @Param("error") String error, @Param("delay") long delay);
    /** 最后一次进程退出也须形成可查询终态。 */
    int expireCommand(@Param("id") UUID id);
    /** 只有原命令可释放占有，不影响别的恢复。 */
    int releaseRecord(@Param("id") UUID id, @Param("command") UUID command, @Param("status") String status);
    /** 仅固定状态的当前数量/积压年龄，指标读取同样受短事务超时约束。 */
    long statusCount(@Param("status") String status);
    /** 积压年龄来自PG事实，不能用消费lag推断集合完整。 */
    double oldestPendingSeconds();
    /** 稳定游标分页隔离原记录，避免一次取出无界原文。 */
    List<SeenRecord> quarantine(@Param("at") OffsetDateTime at, @Param("id") UUID id, @Param("limit") int limit);
}
