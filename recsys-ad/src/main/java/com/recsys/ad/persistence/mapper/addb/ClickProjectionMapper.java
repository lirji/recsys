package com.recsys.ad.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Click事件投影只写自身Inbox和汇总，沿用数据库唯一约束与调用方事务。 */
public interface ClickProjectionMapper {
    /** 首次独立事实插入，全部唯一约束冲突由Repository继续核对原身份与负载。 */
    int insert(Fact row);
    /** 两种回执共用唯一命名空间，按UUID顺序领取避免交叉死锁。 */
    int insertReceipts(@Param("visibleReceiptId") UUID visibleReceiptId, @Param("clickReceiptId") UUID clickReceiptId,
                       @Param("eventId") UUID eventId);
    /** 冲突后读取同eventId原摘要，不能把其他唯一键冲突当成成功。 */
    List<String> hashes(@Param("eventId") UUID eventId);
    /** 每个独立事实都累计，不按账户版本水位丢弃反序到达的金额。 */
    int accumulate(@Param("advertiserId") long advertiserId, @Param("budgetDate") LocalDate budgetDate,
                   @Param("amount") BigDecimal amount);

    /** 身份字段按本事件族建模，原载荷保持TEXT，金额保留精确十进制。 */
    record Fact(UUID eventId, UUID qualificationId, UUID visibleReceiptId, UUID clickReceiptId, long advertiserId,
                LocalDate budgetDate, BigDecimal amount, String payloadHash, String payloadJson) { }
}
