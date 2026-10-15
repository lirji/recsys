package com.recsys.common.error;

/** 由contracts/errors.json生成；保留既有码族，状态分类由所属业务边界决定。 */
public enum ErrorFamily {
    DELIVERY("DELIVERY", ErrorCode.DELIVERY_400),
    RECEIPT("RECEIPT", ErrorCode.RECEIPT_400),
    CLICK_RECEIPT("CLICK_RECEIPT", ErrorCode.CLICK_RECEIPT_400),
    CONVERSION_RECEIPT("CONVERSION_RECEIPT", ErrorCode.CONVERSION_RECEIPT_400),
    NATURAL_DELIVERY("NATURAL_DELIVERY", ErrorCode.NATURAL_DELIVERY_400),
    NATURAL_RECEIPT("NATURAL_RECEIPT", ErrorCode.NATURAL_RECEIPT_400),
    FEED_DELIVERY("FEED_DELIVERY", ErrorCode.FEED_DELIVERY_400),
    CONVERSION("CONVERSION", ErrorCode.CONVERSION_400);

    private static final int MIN_HTTP_ERROR_STATUS = 400;
    private static final int MAX_HTTP_ERROR_STATUS = 599;
    private final String prefix;
    private final ErrorCode fallback;

    ErrorFamily(String prefix, ErrorCode fallback) {
        this.prefix = prefix;
        this.fallback = fallback;
    }

    /** 未单独登记的错误状态也保留原码族，不能静默改成通用500。 */
    public String code(int status) {
        if (status < MIN_HTTP_ERROR_STATUS || status > MAX_HTTP_ERROR_STATUS) {
            throw new IllegalArgumentException("错误码族仅支持HTTP错误状态");
        }
        return prefix + "_" + status;
    }

    /** 只取目录固定文案；503原ID恢复提示与其他状态的安全回退均不依赖异常详情。 */
    public String message(int status) {
        ErrorCode registered = ErrorCode.fromCode(code(status));
        return (registered == null ? fallback : registered).message();
    }
}
