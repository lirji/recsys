package com.recsys.common.ad.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** sandbox 单广告主单业务日预算快照；持有额与消费额共同受硬上限约束。 */
public record BudgetAccount(long advertiserId, LocalDate budgetDate, BigDecimal limitAmount,
                            BigDecimal heldAmount, BigDecimal chargedAmount, long version) {
    public static final String TRAFFIC_CLASS = "SANDBOX";

    public BudgetAccount {
        if (advertiserId <= 0 || budgetDate == null || version < 0) {
            throw new IllegalArgumentException("预算账户标识或版本非法");
        }
        limitAmount = CnyAmount.exact(limitAmount);
        heldAmount = CnyAmount.exact(heldAmount);
        chargedAmount = CnyAmount.exact(chargedAmount);
        if (heldAmount.add(chargedAmount).compareTo(limitAmount) > 0) {
            throw new IllegalArgumentException("预算不能低于已消费加预占");
        }
    }

    /** 可用额度只从权威账户计算，不读取 Redis 估计计数。 */
    public BigDecimal availableAmount() {
        return limitAmount.subtract(heldAmount).subtract(chargedAmount);
    }
}
