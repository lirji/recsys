package com.recsys.common.ad.billing;

import java.math.BigDecimal;

/** CPM 契约保持原五字段和版本；只复用数学计算，不把其他事件变为曝光收费。 */
public record CpmPrice(BigDecimal threshold, BigDecimal billFactor, BigDecimal increment,
                       BigDecimal floor, BigDecimal cap) {
    public static final String VERSION = "CPM_GSP_CNY6_V1";
    public static final String UNIT = "PER_IMPRESSION";

    public CpmPrice {
        var basis = new GspPrice(threshold, billFactor, increment, floor, cap);
        threshold = basis.threshold(); billFactor = basis.billFactor(); increment = basis.increment();
        floor = basis.floor(); cap = basis.cap();
    }
    /** 兼容原调用方的有限十进制输入校验。 */
    public static BigDecimal decimal(double value) { return GspPrice.decimal(value); }
    /** 保留每次金额输入 HALF_UP 六位的原语义。 */
    public static BigDecimal money(BigDecimal value) { return GspPrice.money(value); }
    /** 收费模式之外共享同一有理数边界与最终舍入，避免算法漂移。 */
    public BigDecimal amount() { return new GspPrice(threshold, billFactor, increment, floor, cap).amount(); }
}
