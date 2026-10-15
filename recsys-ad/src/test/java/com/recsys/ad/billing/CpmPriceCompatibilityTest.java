package com.recsys.ad.billing;

import com.recsys.common.ad.billing.CpmPrice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 使用已有ad JSON依赖验证兼容，不给纯领域公共模块增加技术依赖。 */
@Tag("money-chain")
class CpmPriceCompatibilityTest {
    @Test void cpmJsonStillHasExactlyTheOriginalFiveFieldsAndRoundTrips() throws Exception {
        var json = new ObjectMapper();
        var original = new CpmPrice(new BigDecimal("1.00"),new BigDecimal("3.0"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE);
        var encoded = json.readTree(json.writeValueAsString(original));
        assertEquals(5,encoded.size());
        assertTrue(encoded.has("threshold") && encoded.has("billFactor") && encoded.has("increment") && encoded.has("floor") && encoded.has("cap"));
        assertEquals(original,json.treeToValue(encoded,CpmPrice.class));
        assertEquals("0.333333",original.amount().toPlainString());
        assertEquals("PER_IMPRESSION",CpmPrice.UNIT);
        assertEquals("CPM_GSP_CNY6_V1",CpmPrice.VERSION);
    }
}
