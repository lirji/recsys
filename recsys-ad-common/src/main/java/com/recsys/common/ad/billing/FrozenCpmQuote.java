package com.recsys.common.ad.billing;

import java.time.Instant;
import java.util.UUID;

/** 广告 owner 冻结的不可变报价；预占和结算只认此快照，不重跑竞价或延长期限。 */
public record FrozenCpmQuote(UUID quoteId, UUID deliveryId, long advertiserId, long adId, int slot,
                             CpmPrice price, Instant issuedAt, Instant expiresAt) {
    public static final long TTL_SECONDS = 300;
    public static final int MAX_SLOTS = 100;

    public FrozenCpmQuote {
        if (quoteId == null || deliveryId == null || advertiserId <= 0 || adId <= 0 || slot < 1 || slot > MAX_SLOTS
                || price == null || issuedAt == null || expiresAt == null
                || !expiresAt.equals(issuedAt.plusSeconds(TTL_SECONDS))) {
            throw new IllegalArgumentException("报价身份、位置或期限非法");
        }
    }

    /** 不同报价不能重用同一 quoteId 或交付位置。 */
    public static final class Conflict extends RuntimeException {
        public Conflict() {
            super("报价标识或交付位置已绑定其他报价");
        }
    }
}
