package com.recsys.common.ad.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** 人民币账务边界：额度输入必须精确，不能通过浮点或静默舍入改变用户意图。 */
public final class CnyAmount {
    public static final int SCALE = 6;
    public static final String CURRENCY = "CNY";
    public static final BigDecimal MAX = new BigDecimal("99999999999999.999999");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,14}(\\.[0-9]{1,6})?");

    private CnyAmount() {}

    /** 只接受普通十进制文本；规范化后 1、1.0 视为同一命令负载。 */
    public static BigDecimal parse(String value) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException("金额须为非负十进制字符串，最多 14 位整数和 6 位小数");
        }
        return exact(new BigDecimal(value));
    }

    /** 校验内部金额，防止绕过 HTTP/RPC 后写入越界或不精确的值。 */
    public static BigDecimal exact(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("金额超出人民币账务范围");
        }
        try {
            return value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("金额最多支持 6 位小数");
        }
    }
}
