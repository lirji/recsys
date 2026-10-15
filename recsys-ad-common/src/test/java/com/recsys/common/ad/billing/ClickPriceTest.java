package com.recsys.common.ad.billing;

import com.recsys.common.ad.BidType;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 点击单位独立，数学共用后仍验证旧CPM序列化与半微元边界。 */
@Tag("money-chain")
class ClickPriceTest {
    @Test void preservesPerClickUnitAndFullObligationForBothModes() {
        var basis = new GspPrice(new BigDecimal("0.0000001"),new BigDecimal("0.00000001"),
                BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("20"));
        for (BidType type : new BidType[]{BidType.CPC,BidType.OCPC}) {
            assertEquals("10.000000",new ClickPrice(type,basis).amount().toPlainString());
        }
        assertEquals("PER_CLICK",ClickPrice.UNIT);
        assertThrows(IllegalArgumentException.class,()->new ClickPrice(BidType.CPM,basis));
        assertThrows(IllegalArgumentException.class,()->new ClickPrice(BidType.CPA,basis));
    }
    @Test void roundingAndExtremeRatioDoNotUseBinaryFloatingPointOrIntermediateDivision() {
        var p = new ClickPrice(BidType.CPC,new GspPrice(new BigDecimal("0.0000005"),BigDecimal.ONE,
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE));
        assertEquals("0.000001",p.amount().toPlainString());
        assertEquals(CnyAmount.MAX,new GspPrice(new BigDecimal("1e300"),new BigDecimal("1e-300"),
                BigDecimal.ZERO,BigDecimal.ZERO,CnyAmount.MAX).amount());
        assertThrows(IllegalArgumentException.class,()->GspPrice.decimal(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->GspPrice.money(new BigDecimal("100000000000000")));
    }
}
