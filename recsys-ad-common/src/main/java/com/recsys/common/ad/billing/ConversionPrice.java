package com.recsys.common.ad.billing;

import com.recsys.common.ad.BidType;
import java.math.BigDecimal;
import java.util.Objects;

/** CPA报价是完整单次转化义务，概率只影响竞价因子，不能缩小硬预算预占。 */
public record ConversionPrice(GspPrice basis) {
    public static final String VERSION="CPA_GSP_CNY6_V1";
    public static final String UNIT="PER_CONVERSION";
    public ConversionPrice { Objects.requireNonNull(basis); }
    /** 显式固定CPA，不把OCPM的转化优化误认为转化计费。 */
    public BidType bidType() { return BidType.CPA; }
    /** 复用精确数学实现，最终一次HALF_UP到六位。 */
    public BigDecimal amount() { return basis.amount(); }
}
