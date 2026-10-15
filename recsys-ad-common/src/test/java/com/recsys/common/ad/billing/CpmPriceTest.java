package com.recsys.common.ad.billing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 验证半微元与有理数最终舍入，防止中间 double/除法舍入改变收费。 */
@Tag("money-chain")
class CpmPriceTest {
    @Test
    void halfUpExactlyOnceIncludingNonTerminatingDivision() {
        assertEquals("0.000001", price("0.0000005", "1", "0", "0", "1").amount().toPlainString());
        assertEquals("0.000000", price("0.00000049999999999999", "1", "0", "0", "1").amount().toPlainString());
        assertEquals("0.333333", price("1", "3", "0", "0", "1").amount().toPlainString());
        assertEquals("0.666667", price("2", "3", "0", "0", "1").amount().toPlainString());
    }

    @Test
    void exactClampAndMoneyBoundaries() {
        assertEquals("0.200000", price("0.1", "1", "0", "0.2", "0.5").amount().toPlainString());
        assertEquals("0.500000", price("2", "1", "0.1", "0.2", "0.5").amount().toPlainString());
        assertEquals(CnyAmount.MAX, price("1E300", "1E-300", "0", "0", CnyAmount.MAX.toPlainString()).amount());
        assertEquals("1.000001", CpmPrice.money(new BigDecimal("1.0000005")).toPlainString());
        assertThrows(IllegalArgumentException.class, () -> price("1", "0", "0", "0", "1"));
        assertThrows(IllegalArgumentException.class, () -> price("1", "1", "0", "2", "1"));
        assertThrows(IllegalArgumentException.class, () -> CpmPrice.money(new BigDecimal("100000000000000")));
    }

    @Test
    void legacyBridgeRejectsNonfiniteAndNormalizesSemanticScale() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> CpmPrice.decimal(invalid));
        }
        assertEquals(new BigDecimal("0.1"), CpmPrice.decimal(0.1));
        assertEquals(price("1", "2", "0", "0", "1"), price("1.00", "2.0", "0", "0", "1"));
    }

    private static CpmPrice price(String numerator, String denominator, String increment, String floor, String cap) {
        return new CpmPrice(new BigDecimal(numerator), new BigDecimal(denominator), CnyAmount.parse(increment),
                CnyAmount.parse(floor), CnyAmount.parse(cap));
    }
}
