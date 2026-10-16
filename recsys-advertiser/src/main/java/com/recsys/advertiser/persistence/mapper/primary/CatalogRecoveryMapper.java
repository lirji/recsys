package com.recsys.advertiser.persistence.mapper.primary;

import com.recsys.advertiser.catalog.CatalogProtocol.CommandKey;
import org.apache.ibatis.annotations.Param;

/** 恢复审计结果与被重置目标同物理事务；原输入/结果/历史计数由不可变receipt保存。 */
public interface CatalogRecoveryMapper {
    Receipt receipt(@Param("shard") int shard,@Param("id") String id);
    /** 避免Mapper将byte[]返回类型误认为多行集合；不存在仍须严格返回null。 */
    record Receipt(byte[] payloadBytes) { }
    int retryStep(@Param("shard") int shard,@Param("step") String step,@Param("round") int round,@Param("fence") long fence);
    int retryCommand(@Param("key") CommandKey key,@Param("round") int round,@Param("fence") long fence);
    /** 恢复专用装配无worker时，仅清算已核验原轮次/fence的末次到期，不提前接管有效租约。 */
    int pauseExpiredCommand(@Param("key") CommandKey key,@Param("round") int round,@Param("fence") long fence);
    int pauseExpiredStep(@Param("shard") int shard,@Param("step") String step,@Param("round") int round,@Param("fence") long fence);
    /** 审计重发ACK只确认原暂停fence，不清空历史预算或错误，也不覆盖已发布的原坐标。 */
    int confirmReplay(@Param("shard") int shard,@Param("id") String id,@Param("fence") long fence,
                      @Param("partition") int partition,@Param("offset") long offset);
    /** 已存在不可变ACK时只清算原结果，不重新领取外部尝试；包含最后一次退出后的PAUSED。 */
    int finishKnownReplay(@Param("key") CommandKey key,@Param("fence") long fence,@Param("result") byte[] result);
}
