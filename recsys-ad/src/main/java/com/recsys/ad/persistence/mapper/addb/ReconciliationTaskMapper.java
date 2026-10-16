package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.billing.ReconciliationTask.*;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 任务领取与报告检查点只在资金owner本库提交，XML负责所有范围和版本条件。 */
public interface ReconciliationTaskMapper {
    /** 校验V017摘要，开启能力时拒绝结构漂移。 */
    List<String> migrationHashes();
    /** 缺表和缺列直接失败，不能伪装成空任务。 */
    void verifySchema();
    /** 双唯一约束区分重复命令与同账户已有活跃任务。 */
    int insert(@Param("command") Create command);
    /** 行锁在读取数据库时钟前取得，避免锁等待耗掉租约。 */
    List<Task> find(@Param("advertiserId") long advertiserId, @Param("taskId") UUID taskId, @Param("lock") boolean lock);
    /** 每次实际读取数据库时钟，不使用客户端时间计算租约。 */
    OffsetDateTime now();
    /** 领取同时递增版本及本日尝试次数，进程崩溃也消耗尝试预算。 */
    int claim(@Param("task") Task task, @Param("leaseSeconds") int leaseSeconds, @Param("actor") String actor);
    /** 失败状态和下次可领取时间一起持久化，禁止在内存无限重试。 */
    int fail(@Param("task") Task task, @Param("state") State state, @Param("failure") Failure failure,
             @Param("delaySeconds") int delaySeconds, @Param("actor") String actor);
    /** 过期且已耗尽尝试的任务不再领取。 */
    int exhaust(@Param("task") Task task, @Param("actor") String actor);
    /** 保存报告之前推进有版本及租约保护的检查点，二者由同一事务保证原子性。 */
    int complete(@Param("task") Task task, @Param("actor") String actor);
    /** 保存不可变原报告；重复日期是并发/事务错误，不能覆盖历史。 */
    int insertDay(@Param("task") Task task, @Param("report") String report, @Param("reportVersion") int reportVersion,
                  @Param("observedAt") OffsetDateTime observedAt);
    /** 取消与完成共用任务行锁，并递增fence使旧执行器失效。 */
    int cancel(@Param("task") Task task, @Param("command") Cancel command);
    /** 状态与审计同事务，审计不可修改删除。 */
    int audit(@Param("taskId") UUID taskId, @Param("event") Event event,
              @Param("budgetDate") LocalDate budgetDate, @Param("attempts") int attempts);
    /** 仅从指定广告主所属任务读取，固定上限31日。 */
    List<DayRow> days(@Param("advertiserId") long advertiserId, @Param("taskId") UUID taskId);

    /** JSON仅是内部持久化格式；解码后才返回领域报告。 */
    record DayRow(LocalDate budgetDate, long fence, int reportVersion, String reportJson, OffsetDateTime observedAt) { }
}
