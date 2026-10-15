package com.recsys.common.ad.billing;

import com.recsys.common.ad.BidType;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 固定转化单位与完整义务，避免优化目标或小概率把预算占用缩小。 */
@Tag("money-chain")
class ConversionPriceTest {
    @Test void cpaUsesFullObligationWithoutProbabilityDiscount() {
        var price=new ConversionPrice(new GspPrice(new BigDecimal("0.0000001"),new BigDecimal("0.00000001"),
                BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("20")));
        assertEquals(BidType.CPA,price.bidType());assertEquals("PER_CONVERSION",ConversionPrice.UNIT);
        assertEquals("10.000000",price.amount().toPlainString());
        assertThrows(NullPointerException.class,()->new ConversionPrice(null));
    }
    @Test void halfMicroExtremeAndZeroHaveExplicitExactAmounts() {
        var price=new ConversionPrice(new GspPrice(new BigDecimal("0.0000005"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE));
        assertEquals("0.000001",price.amount().toPlainString());
        assertEquals(CnyAmount.MAX,new ConversionPrice(new GspPrice(new BigDecimal("1e300"),new BigDecimal("1e-300"),
                BigDecimal.ZERO,BigDecimal.ZERO,CnyAmount.MAX)).amount());
        assertEquals("0.000000",new ConversionPrice(new GspPrice(BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE)).amount().toPlainString());
    }
}
