package com.recsys.offline.persistence.mapper.primary;

import com.recsys.offline.persistence.model.AdEventLogRow;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

/** 本库日志同步checkpoint唯一SQL边界；来源Mapper不进入此事务。 */
public interface AdEventSyncMapper {
    record Checkpoint(String sourceId, String sourceFingerprint, String state, long epoch, Long upperId, long cursorId,
                      String worker, long fence, Instant leaseUntil, int attempts, long scanned, long copied, String errorCode, Long errorId) { }
    record Audit(String commandId, String action, String actor, String reason, long epoch) { }
    /** 安装、领取、恢复串行，锁只在短事务内持有。 */
    void lock();
    /** 数据库锁/语句有界，避免坏依赖占住worker。 */
    void budget();
    /** 老环境不必预先安装新表。 */
    String installed();
    /** 安装器绑定不可变迁移摘要。 */
    String prepareInstall(@Param("digest") String digest);
    /** 验证已安装脚本身份。 */
    String schemaDigest();
    /** 首次绑定来源，不从目标MAX(id)推断进度。 */
    int initialize(@Param("source") String source, @Param("fingerprint") String fingerprint);
    /** 可查询的当前恢复事实。 */
    Checkpoint checkpoint();
    /** 末次崩溃租约过期也必须收敛为暂停。 */
    int pauseExhausted();
    /** 只有无有效租约且预算可用时领取，新fence隔离旧执行器。 */
    int claim(@Param("worker") String worker);
    /** 读取源之前持久扣除一次预算并续租。 */
    int beginAttempt(@Param("worker") String worker, @Param("fence") long fence);
    /** 有效原执行器才可提交目标效果。 */
    int checkFence(@Param("worker") String worker, @Param("fence") long fence);
    /** 冻结一轮有限上界，仅可设置一次。 */
    int freezeUpper(@Param("upper") long upper);
    /** 读写同页比对所有16列，冲突不覆盖。 */
    Long conflictingId(@Param("rows") List<AdEventLogRow> rows);
    /** 插入与cursor/计数/完成状态在同一事务。 */
    int advance(@Param("cursor") long cursor, @Param("scanned") int scanned, @Param("copied") int copied,
                @Param("complete") boolean complete);
    /** 依赖失败退避，内容/源冲突立即暂停，不改游标。 */
    int fail(@Param("worker") String worker, @Param("fence") long fence, @Param("reason") String reason,
             @Param("eventId") Long eventId, @Param("permanent") boolean permanent);
    /** 预算用尽正常让出，不清除已经扣除的失败预算。 */
    int release(@Param("worker") String worker, @Param("fence") long fence);
    /** 无活租约时显式重建开启新轮。 */
    int rebuild();
    /** 修复后新审计恢复轮不丢失分页位置。 */
    int resume();
    /** 同命令ID复用原结果。 */
    Audit audit(@Param("id") String id);
    /** 与受控恢复动作同事务审计。 */
    int addAudit(@Param("audit") Audit audit);
}
