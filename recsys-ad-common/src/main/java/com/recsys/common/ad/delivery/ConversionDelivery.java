package com.recsys.common.ad.delivery;

import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.ad.billing.ConversionPrice;
import java.time.Instant;
import java.util.UUID;

/** CPA候选的签名载荷；复用交付容器但绝不复用CPM价格和证明用途。 */
public final class ConversionDelivery {
    public static final String OFFER_PURPOSE = "conversion-offer";
    private ConversionDelivery() {}
    public record Offer(UUID operationId, String subject, long userId, UUID childId, SponsoredAd ad,
                        ConversionPrice price, String bucket, Instant expiresAt) {
        public Offer {
            SandboxDelivery.requireIdentity(operationId, subject, userId);
            if (childId == null || ad == null || price == null || expiresAt == null || bucket == null
                    || bucket.length() > 200 || ad.advertiserId() <= 0 || ad.adId() <= 0 || ad.itemId() <= 0
                    || ad.creativeId() < 0 || ad.title() == null || ad.title().isBlank() || ad.title().length() > 2000
                    || !price.bidType().name().equals(ad.bidType())) throw new IllegalArgumentException("CPA候选证明不完整");
        }
        @Override public String toString() { return "ConversionOffer[childId=" + childId + "]"; }
    }
}
