package com.recsys.common.ad;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BidType} 契约:计费单位 → 结算事件的映射(A1)。错配会让 CPM/CPA 在错误事件扣费(变现错误)。
 */
@Tag("money-chain")   // P1 钱链路验证闸门
class BidTypeTest {

    @Test
    void chargeEvent_matchesBillingUnit() {
        assertTrue(BidType.CPC.chargeOnClick());
        assertTrue(BidType.OCPC.chargeOnClick());
        assertTrue(BidType.CPM.chargeOnImpression());
        assertTrue(BidType.OCPM.chargeOnImpression());
        assertTrue(BidType.CPA.chargeOnConversion());
        // 互斥:CPC 不在曝光/转化扣;CPA 不在点击扣
        assertFalse(BidType.CPC.chargeOnImpression());
        assertFalse(BidType.CPC.chargeOnConversion());
        assertFalse(BidType.CPA.chargeOnClick());
    }

    @Test
    void from_isCaseInsensitive_andDefaultsToCpc() {
        assertEquals(BidType.CPM, BidType.from("cpm"));
        assertEquals(BidType.OCPC, BidType.from(" OCPC "));
        assertEquals(BidType.CPC, BidType.from(null), "null → 安全默认 CPC");
        assertEquals(BidType.CPC, BidType.from("UNKNOWN"), "未知 → 安全默认 CPC");
    }
}
