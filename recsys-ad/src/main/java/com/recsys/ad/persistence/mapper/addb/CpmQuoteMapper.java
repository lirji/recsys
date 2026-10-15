package com.recsys.ad.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** CPM不可变报价持久化；期限与冲突判定沿用同一adDb数据库时钟。 */
public interface CpmQuoteMapper {
    /** 固定迁移版本，缺失/重复由Repository拒绝。 */
    List<String> migrationHashes();
    /** 只验证必要列，不隐式应用迁移。 */
    void verifyQuote();
    /** 只有数据库当前时间早于期限才首次插入，冲突不覆盖或续期。 */
    int freeze(Draft draft);
    /** 查原始摘要，允许调用者区分未创建与同键冲突。 */
    List<String> hashes(@Param("quoteId") UUID quoteId);
    /** 语句时钟重新判定期限，不复用事务启动时间。 */
    boolean beforeDeadline(@Param("deadline") Timestamp deadline);
    /** 不可变原报价，金额与冻结依据仍由Repository复核。 */
    List<StoredQuote> quotes(@Param("quoteId") UUID quoteId);

    /** threshold/billFactor继续按十进制原文写入，不能先舍入为金额精度。 */
    record Draft(UUID quoteId, UUID deliveryId, long advertiserId, long adId, int slot, BigDecimal amount,
                 String threshold, String billFactor, BigDecimal increment, BigDecimal floor, BigDecimal cap,
                 String payloadHash, Timestamp deadline) { }
    /** 原始冻结依据和数据库时刻；不在Mapper内重新定价。 */
    record StoredQuote(UUID quoteId, UUID deliveryId, long advertiserId, long adId, int slot, BigDecimal amount,
                       String threshold, String billFactor, BigDecimal increment, BigDecimal floor, BigDecimal cap,
                       OffsetDateTime issuedAt, OffsetDateTime expiresAt) { }
}
