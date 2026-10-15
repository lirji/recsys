package com.recsys.ad.persistence.mapper.addb;

import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 点击报价使用adDb不可变事实；每条语句保持原5秒查询超时。 */
public interface ClickQuoteMapper {
    /** 启用前核对固定V008迁移摘要。 */
    List<String> migrationHashes();
    /** 只验证必要列，不在启动时执行DDL。 */
    void verifyQuote();
    /** 首次签发受截止时间限制，冲突不更新原报价和期限。 */
    int freeze(Draft row);
    /** 同一操作最终位置已占用时，冲突判断优先于候选过期。 */
    boolean atPosition(@Param("operationId") UUID operationId, @Param("slot") int slot);
    /** 每次读取数据库当前时钟，不复用事务起始时间。 */
    boolean beforeDeadline(@Param("deadline") Timestamp deadline);
    /** 原始报价先由Repository鉴权，再复核金额、摘要和版本。 */
    List<Stored> find(@Param("id") UUID id);

    /** 数学依据使用十进制原文，金额独立保持六位精度。 */
    record Draft(UUID quoteId, UUID operationId, String subject, long userId, long advertiserId, long adId,
                 long creativeId, long itemId, int slot, String title, String bidType, BigDecimal amount,
                 String threshold, String billFactor, BigDecimal increment, BigDecimal floor, BigDecimal cap,
                 String payloadHash, Timestamp deadline) { }
    /** 明确列映射，避免数据库新增列影响不可变构造参数顺序。 */
    record Stored(UUID operationId, String subject, long userId, long advertiserId, long adId, long creativeId,
                  long itemId, int slot, String title, String bidType, BigDecimal amount, String threshold, String billFactor,
                  BigDecimal increment, BigDecimal floor, BigDecimal cap, String payloadHash, String priceUnit,
                  String pricingVersion, OffsetDateTime issuedAt, OffsetDateTime expiresAt) { }
}
