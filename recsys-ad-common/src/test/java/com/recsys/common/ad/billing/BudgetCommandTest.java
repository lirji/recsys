package com.recsys.common.ad.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 金额与幂等负载边界测试，不用浮点近似证明精度。 */
@Tag("money-chain")
class BudgetCommandTest {
    @Test
    void plainNonnegativeSixDecimalOnly() {
        for (String invalid : new String[]{"-1", "+1", "1e2", "NaN", "Infinity", ".1", "1.", " 1",
                "1.0000001", "100000000000000", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> CnyAmount.parse(invalid), String.valueOf(invalid));
        }
        assertEquals(new BigDecimal("0.000001"), CnyAmount.parse("0.000001"));
        assertEquals(CnyAmount.MAX, CnyAmount.parse("99999999999999.999999"));
        assertEquals(new BigDecimal("0.000000"), CnyAmount.parse("0"));
    }

    @Test
    void requiredBusinessFieldsFailBeforeTransaction() {
        String id = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> BudgetCommand.parse(0, id, "2026-10-15", "CNY", "1", 0, "admin"));
        assertThrows(IllegalArgumentException.class, () -> BudgetCommand.parse(1, id, "2026-10-15", "USD", "1", 0, "admin"));
        assertThrows(IllegalArgumentException.class, () -> BudgetCommand.parse(1, id, "2026-10-15", "CNY", "1", -1, "admin"));
        assertThrows(IllegalArgumentException.class, () -> BudgetCommand.parse(1, id, "2026-10-15", "CNY", "1", 0, " "));
        assertThrows(IllegalArgumentException.class, () -> BudgetCommand.parseDate("2026-02-30"));
        assertThrows(IllegalArgumentException.class, () -> BudgetCommand.parseDate("2026-1-1"));
        assertThrows(IllegalArgumentException.class, () -> BudgetCommand.parseId("1-1-1-1-1"));
    }

    @Test
    void equivalentAmountHashesMatchAndActorChangesConflict() {
        String id = UUID.randomUUID().toString();
        var first = BudgetCommand.parse(1, id, "2026-10-15", "CNY", "1", 0, "admin");
        assertEquals(first.payloadHash(), BudgetCommand.parse(1, id, "2026-10-15", "CNY", "1.000000", 0, "admin").payloadHash());
        assertNotEquals(first.payloadHash(), BudgetCommand.parse(1, id, "2026-10-15", "CNY", "1", 0, "other").payloadHash());
    }
}
