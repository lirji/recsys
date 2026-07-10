package com.recsys.advertiser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CreativeReview} 机审契约(A2)—— 决定新广告能否进"可服务集"(approved)。
 * 违禁/空标题必须拒审(不投放),正常标题进 pending 待人审。
 */
class CreativeReviewTest {

    @Test
    void bannedWord_rejected() {
        assertEquals(CreativeReview.REJECTED,
                CreativeReview.machineReview("赌博网站充值", "http://x").status());
        assertEquals(CreativeReview.REJECTED,
                CreativeReview.machineReview("Best CASINO bonus", null).status());
    }

    @Test
    void emptyOrTooShortTitle_rejected() {
        assertEquals(CreativeReview.REJECTED, CreativeReview.machineReview("", null).status());
        assertEquals(CreativeReview.REJECTED, CreativeReview.machineReview("a", null).status());
        assertEquals(CreativeReview.REJECTED, CreativeReview.machineReview(null, null).status());
    }

    @Test
    void cleanTitle_goesToPendingReview() {
        // 干净标题机审通过但仍需人审(默认不投放,须 /review 人工 approve)
        CreativeReview.Decision d = CreativeReview.machineReview("经典科幻电影合集", "https://example.com");
        assertEquals(CreativeReview.PENDING, d.status());
    }

    @Test
    void normalizeDecision_maps() {
        assertEquals(CreativeReview.APPROVED, CreativeReview.normalizeDecision("approve"));
        assertEquals(CreativeReview.APPROVED, CreativeReview.normalizeDecision("APPROVED"));
        assertEquals(CreativeReview.REJECTED, CreativeReview.normalizeDecision("reject"));
        assertEquals(CreativeReview.PENDING, CreativeReview.normalizeDecision(null));
        assertEquals(CreativeReview.PENDING, CreativeReview.normalizeDecision("garbage"));
    }
}
