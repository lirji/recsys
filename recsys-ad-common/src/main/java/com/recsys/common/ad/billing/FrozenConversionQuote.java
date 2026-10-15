package com.recsys.common.ad.billing;

import java.time.Instant;
import java.util.UUID;

/** 转化报价原主体和最终展示身份同被冻结；后续重试不会重定价或延长五分钟窗口。 */
public record FrozenConversionQuote(UUID quoteId, UUID operationId, String subject, long userId,
        long advertiserId, long adId, long creativeId, long itemId, int slot, String title,
        ConversionPrice price, Instant issuedAt, Instant expiresAt) {
    public static final long TTL_SECONDS = 300;
    public static final int MAX_SLOTS = 10;
    public FrozenConversionQuote {
        Reservation.validateSubject(subject);
        if (quoteId == null || operationId == null || userId <= 0 || advertiserId <= 0 || adId <= 0
                || creativeId < 0 || itemId <= 0 || slot < 1 || slot > MAX_SLOTS || title == null || title.isBlank()
                || title.length() > 2000 || price == null || issuedAt == null || expiresAt == null
                || !expiresAt.equals(issuedAt.plusSeconds(TTL_SECONDS))) throw new IllegalArgumentException("转化报价上下文非法");
    }
    /** 防止诊断时泄漏认证主体或完整创意。 */
    @Override public String toString() { return "FrozenConversionQuote[quoteId=" + quoteId + "]"; }
    /** 改主体、价格或最终位置都不能复用原业务身份。 */
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("转化报价标识或交付位置已绑定其他上下文"); }
    }
}
