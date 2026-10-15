package com.recsys.common.ad.delivery;

import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.ad.billing.ClickPrice;
import java.time.Instant;
import java.util.UUID;

/** 点击候选的签名载荷；复用交付容器但绝不复用CPM价格和证明用途。 */
public final class ClickDelivery {
    public static final String OFFER_PURPOSE = "click-offer";
    private ClickDelivery() {}
    public record Offer(UUID operationId, String subject, long userId, UUID childId, SponsoredAd ad,
                        ClickPrice price, String bucket, Instant expiresAt) {
        public Offer {
            SandboxDelivery.requireIdentity(operationId, subject, userId);
            if (childId == null || ad == null || price == null || expiresAt == null || bucket == null
                    || bucket.length() > 200 || ad.advertiserId() <= 0 || ad.adId() <= 0 || ad.itemId() <= 0
                    || ad.creativeId() < 0 || ad.title() == null || ad.title().isBlank() || ad.title().length() > 2000
                    || !price.bidType().name().equals(ad.bidType())) throw new IllegalArgumentException("点击候选证明不完整");
        }
        @Override public String toString() { return "ClickOffer[childId=" + childId + "]"; }
    }
}
