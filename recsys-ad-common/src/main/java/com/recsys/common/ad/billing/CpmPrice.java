package com.recsys.common.ad.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 首版 CPM/GSP 精确计价依据；比值保持精确到最终六位量化，不从旧 chargedPrice 反推。 */
public record CpmPrice(BigDecimal threshold, BigDecimal billFactor, BigDecimal increment,
                       BigDecimal floor, BigDecimal cap) {
    public static final String VERSION = "CPM_GSP_CNY6_V1";
    public static final String UNIT = "PER_IMPRESSION";

    public CpmPrice {
        if (threshold == null || threshold.signum() < 0 || billFactor == null || billFactor.signum() <= 0
                || threshold.precision() > 400 || billFactor.precision() > 400
                || threshold.scale() < -400 || threshold.scale() > 400
                || billFactor.scale() < -400 || billFactor.scale() > 400) {
            throw new IllegalArgumentException("CPM 阈值或计价因子非法");
        }
        threshold = threshold.stripTrailingZeros();
        billFactor = billFactor.stripTrailingZeros();
        increment = CnyAmount.exact(increment);
        floor = CnyAmount.exact(floor);
        cap = CnyAmount.exact(cap);
        if (cap.compareTo(floor) < 0) throw new IllegalArgumentException("计价上限不得小于底价");
    }

    /** 有限旧输入桥接到十进制，无法恢复历史 double 已丢失的源精度。 */
    public static BigDecimal decimal(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("计价输入须为有限非负数");
        return BigDecimal.valueOf(value);
    }

    /** 只有金额输入被六位量化，概率/排序阈值不能按金额精度提前截断。 */
    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(CnyAmount.MAX) > 0) {
            throw new IllegalArgumentException("计价金额超出范围");
        }
        return CnyAmount.exact(value.setScale(CnyAmount.SCALE, RoundingMode.HALF_UP));
    }

    /** 先比较有理数边界，再做一次最终除法量化，消除中间舍入跨越半微元的风险。 */
    public BigDecimal amount() {
        BigDecimal numerator = threshold.add(increment.multiply(billFactor));
        if (numerator.compareTo(floor.multiply(billFactor)) <= 0) return floor;
        if (numerator.compareTo(cap.multiply(billFactor)) >= 0) return cap;
        return CnyAmount.exact(numerator.divide(billFactor, CnyAmount.SCALE, RoundingMode.HALF_UP));
    }
}
