package com.recsys.advertiser.persistence.mapper.primary;

import com.recsys.advertiser.catalog.CatalogProtocol.CommandKey;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 本库冻结目标与有界公平调度；所有写操作由父命令fence所在短事务约束。 */
public interface CatalogTaskMapper {
    /** 持久冻结的成员、原child身份及当前审计轮；不是从业务表现查重采样。 */
    record Target(long ordinal,String targetKind,long targetId,Long targetOwnerId,Long expectedVersion,
                  UUID childCommandId,String state,byte[] resultBytes,String errorType,String pageSha256,
                  String phase,int round,int attempts,Instant availableAt) { }
    /** 计数不表示跨owner共同快照；仅表示home已核实的持久进度。 */
    record Counts(long total,long completed,long conflicts,long cancelled,long paused,long pending) {
        /** 全部真实完成才成功；取消、冲突或暂停均不能冒充完成。 */
        public boolean complete(){return completed==total;}
    }
    /** 每个物理库按版本读取摘要，追加版本不能替换原V001身份。 */
    String schemaChecksum(@Param("shard") int shard,@Param("version") int version);
    /** guard封闭集合后按主键稳定分页，不能用于开放集合的提交水位。 */
    List<Long> adPage(@Param("owner") long owner,@Param("after") long after);
    /** 父事务中插入原目标，必须和分页检查点一起提交。 */
    int insert(@Param("key") CommandKey key,@Param("target") Target target);
    /** 读取原作用域的单目标，恢复不改其预期版本。 */
    Target target(@Param("key") CommandKey key,@Param("kind") String kind,@Param("id") long id);
    /** 同owner串行取得最早未完成目标，不跳过未决副作用。 */
    Target nextFanout(@Param("key") CommandKey key);
    /** 仅领取到期候选，失败退避允许其他owner公平推进。 */
    Target nextBatch(@Param("key") CommandKey key);
    /** 只统计home已保存的事实，不声称跨owner同时快照。 */
    Counts counts(@Param("key") CommandKey key);
    /** 有界读取冻结清单，游标是ordinal而非业务提交水位。 */
    List<Target> page(@Param("key") CommandKey key,@Param("after") long after);
    /** 先将目标持久派发，才执行其本地步骤。 */
    int dispatchFanout(@Param("key") CommandKey key,@Param("id") long id);
    /** 与本ad最终事件同事务保存完成事实，不完成根命令。 */
    int completeFanout(@Param("key") CommandKey key,@Param("id") long id,@Param("result") byte[] result);
    /** 跨库查询或派发前先计次，退出也消耗预算。 */
    int beginObservation(@Param("key") CommandKey key,@Param("id") long id);
    /** 派发事实先于远端效果，未知时不能被取消。 */
    int dispatchBatch(@Param("key") CommandKey key,@Param("id") long id);
    /** 已知原child状态才更新观察结果和公平轮转时间。 */
    int observed(@Param("key") CommandKey key,@Param("id") long id,@Param("state") String state,
                 @Param("phase") String phase,@Param("result") byte[] result,@Param("error") String error);
    /** 未知保留原输入并计入有限预算，耗尽暂停此目标。 */
    int unknown(@Param("key") CommandKey key,@Param("id") long id,@Param("error") String error);
    /** 仅清算已耗尽目标，不通过重启恢复尝试预算。 */
    int pauseExhausted(@Param("key") CommandKey key,@Param("id") long id);
    /** 保存部分计数与父暂停状态，不能报全成功。 */
    int pauseParent(@Param("key") CommandKey key,@Param("fence") long fence,@Param("result") byte[] result);
    /** 只允许READY取消，已派发或已提交效果保留。 */
    int cancelUndispatched(@Param("key") CommandKey key,@Param("result") byte[] result);
    /** 新审计轮重开原PAUSED目标，条件检查旧轮次。 */
    int retryTarget(@Param("key") CommandKey key,@Param("id") long id,@Param("round") int round);
    /** 原父已暂停时随目标审计恢复，不改输入/child身份。 */
    int retryParent(@Param("key") CommandKey key,@Param("round") int round,@Param("fence") long fence,@Param("frozen") byte[] frozen);
    /** 用数据库时间确认最后一次观察领取已过期。 */
    boolean exhaustedObservation(@Param("key") CommandKey key);
    /** 清算原目标的最终领取，不触发新的远端尝试。 */
    int pauseObservationTarget(@Param("key") CommandKey key,@Param("id") long id);
    /** 原fence匹配时转下个目标，耗尽事实须已在同事务保存。 */
    int settleObservation(@Param("key") CommandKey key,@Param("fence") long fence,@Param("frozen") byte[] frozen);
}
