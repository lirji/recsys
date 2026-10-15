package com.recsys.common.error;

/** 由contracts/errors.json生成；纯契约，不依赖Web框架。请修改目录后运行generate_error_codes.py。 */
public enum ErrorCode {
    BAD_REQUEST("BAD_REQUEST", "请求参数不合法，请检查后重试", 400),
    VALIDATION_FAILED("VALIDATION_FAILED", "请求参数校验失败", 400),
    UNAUTHORIZED("UNAUTHORIZED", "请先登录后再操作", 401),
    UNAUTHENTICATED("UNAUTHENTICATED", "需要有效身份", 401),
    FORBIDDEN("FORBIDDEN", "无权访问该资源", 403),
    NOT_FOUND("NOT_FOUND", "请求的资源不存在", 404),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "请求方法不支持", 405),
    CONFLICT("CONFLICT", "请求与当前状态冲突，请刷新后重试", 409),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", "请求内容格式不支持", 415),
    UNPROCESSABLE_ENTITY("UNPROCESSABLE_ENTITY", "请求内容无法处理，请检查业务条件", 422),
    TOO_MANY_REQUESTS("TOO_MANY_REQUESTS", "请求过于频繁，请稍后重试", 429),
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "服务器内部错误", 500),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", 500),
    BAD_GATEWAY("BAD_GATEWAY", "上游服务暂时不可用，请稍后重试", 502),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", "服务暂时不可用，请稍后重试", 503),
    GATEWAY_TIMEOUT("GATEWAY_TIMEOUT", "请求处理超时，请查询原操作结果", 504),
    NETWORK_ERROR("NETWORK_ERROR", "网络连接失败，请检查网络并确认操作结果", 0),
    REQUEST_TIMEOUT("REQUEST_TIMEOUT", "请求超时，请查询操作结果后再决定是否重试", 408),
    REQUEST_CANCELLED("REQUEST_CANCELLED", "请求已取消", 0),
    UNKNOWN_ERROR("UNKNOWN_ERROR", "操作未完成，请稍后重试或联系管理员", 0),
    NOT_ACCEPTABLE("NOT_ACCEPTABLE", "请求的响应格式不支持", 406),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", "请求内容过大，请缩小后重试", 413),
    CONVERSION_400("CONVERSION_400", "转化请求未通过校验或当前不可访问", 400),
    CONVERSION_401("CONVERSION_401", "转化请求未通过校验或当前不可访问", 401),
    CONVERSION_403("CONVERSION_403", "转化请求未通过校验或当前不可访问", 403),
    CONVERSION_404("CONVERSION_404", "转化请求未通过校验或当前不可访问", 404),
    CONVERSION_409("CONVERSION_409", "转化请求未通过校验或当前不可访问", 409),
    CONVERSION_503("CONVERSION_503", "转化结果未知，请保留原业务ID查询或重试", 503),
    NATURAL_RECEIPT_400("NATURAL_RECEIPT_400", "自然回执未通过校验或当前不可访问", 400),
    NATURAL_RECEIPT_401("NATURAL_RECEIPT_401", "自然回执未通过校验或当前不可访问", 401),
    NATURAL_RECEIPT_403("NATURAL_RECEIPT_403", "自然回执未通过校验或当前不可访问", 403),
    NATURAL_RECEIPT_404("NATURAL_RECEIPT_404", "自然回执未通过校验或当前不可访问", 404),
    NATURAL_RECEIPT_409("NATURAL_RECEIPT_409", "自然回执未通过校验或当前不可访问", 409),
    NATURAL_RECEIPT_503("NATURAL_RECEIPT_503", "自然回执结果未知，请保留原 receiptId 查询或重试", 503),
    CLICK_RECEIPT_400("CLICK_RECEIPT_400", "点击请求未通过校验或当前不可访问", 400),
    CLICK_RECEIPT_401("CLICK_RECEIPT_401", "点击请求未通过校验或当前不可访问", 401),
    CLICK_RECEIPT_403("CLICK_RECEIPT_403", "点击请求未通过校验或当前不可访问", 403),
    CLICK_RECEIPT_404("CLICK_RECEIPT_404", "点击请求未通过校验或当前不可访问", 404),
    CLICK_RECEIPT_409("CLICK_RECEIPT_409", "点击请求未通过校验或当前不可访问", 409),
    CLICK_RECEIPT_503("CLICK_RECEIPT_503", "点击结果未知，请按原ID查询或重试", 503),
    CONVERSION_RECEIPT_400("CONVERSION_RECEIPT_400", "CPA请求未通过校验或当前不可访问", 400),
    CONVERSION_RECEIPT_401("CONVERSION_RECEIPT_401", "CPA请求未通过校验或当前不可访问", 401),
    CONVERSION_RECEIPT_403("CONVERSION_RECEIPT_403", "CPA请求未通过校验或当前不可访问", 403),
    CONVERSION_RECEIPT_404("CONVERSION_RECEIPT_404", "CPA请求未通过校验或当前不可访问", 404),
    CONVERSION_RECEIPT_409("CONVERSION_RECEIPT_409", "CPA请求未通过校验或当前不可访问", 409),
    CONVERSION_RECEIPT_503("CONVERSION_RECEIPT_503", "CPA结果未知，请按原ID查询或重试", 503),
    RECEIPT_400("RECEIPT_400", "回执请求未通过校验或当前不可访问", 400),
    RECEIPT_401("RECEIPT_401", "回执请求未通过校验或当前不可访问", 401),
    RECEIPT_403("RECEIPT_403", "回执请求未通过校验或当前不可访问", 403),
    RECEIPT_404("RECEIPT_404", "回执请求未通过校验或当前不可访问", 404),
    RECEIPT_409("RECEIPT_409", "回执请求未通过校验或当前不可访问", 409),
    RECEIPT_503("RECEIPT_503", "账务结果未知，请按原 receiptId 查询或重试", 503),
    DELIVERY_400("DELIVERY_400", "交付请求未通过校验或当前不可访问", 400),
    DELIVERY_401("DELIVERY_401", "交付请求未通过校验或当前不可访问", 401),
    DELIVERY_403("DELIVERY_403", "交付请求未通过校验或当前不可访问", 403),
    DELIVERY_404("DELIVERY_404", "交付请求未通过校验或当前不可访问", 404),
    DELIVERY_409("DELIVERY_409", "交付请求未通过校验或当前不可访问", 409),
    DELIVERY_503("DELIVERY_503", "交付结果未知，请保留原 operationId 查询或重试", 503),
    NATURAL_DELIVERY_400("NATURAL_DELIVERY_400", "自然交付请求未通过校验或当前不可访问", 400),
    NATURAL_DELIVERY_401("NATURAL_DELIVERY_401", "自然交付请求未通过校验或当前不可访问", 401),
    NATURAL_DELIVERY_403("NATURAL_DELIVERY_403", "自然交付请求未通过校验或当前不可访问", 403),
    NATURAL_DELIVERY_404("NATURAL_DELIVERY_404", "自然交付请求未通过校验或当前不可访问", 404),
    NATURAL_DELIVERY_409("NATURAL_DELIVERY_409", "自然交付请求未通过校验或当前不可访问", 409),
    NATURAL_DELIVERY_503("NATURAL_DELIVERY_503", "自然交付结果未知，请保留原 operationId 查询或重试", 503),
    FEED_DELIVERY_400("FEED_DELIVERY_400", "混排交付请求未通过校验或当前不可访问", 400),
    FEED_DELIVERY_401("FEED_DELIVERY_401", "混排交付请求未通过校验或当前不可访问", 401),
    FEED_DELIVERY_403("FEED_DELIVERY_403", "混排交付请求未通过校验或当前不可访问", 403),
    FEED_DELIVERY_404("FEED_DELIVERY_404", "混排交付请求未通过校验或当前不可访问", 404),
    FEED_DELIVERY_409("FEED_DELIVERY_409", "混排交付请求未通过校验或当前不可访问", 409),
    FEED_DELIVERY_503("FEED_DELIVERY_503", "混排交付结果未知，请保留原 operationId 查询或重试", 503),
    AUTH_INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", "用户名或密码错误", 401),
    AUTH_TOKEN_ISSUANCE_FAILED("AUTH_TOKEN_ISSUANCE_FAILED", "登录服务暂时不可用，请稍后重试", 500),
    IDENTITY_PROPAGATION_FAILED("IDENTITY_PROPAGATION_FAILED", "身份传递失败，请稍后重试", 500),
    NATURAL_DELIVERY_RESPONSE_MISMATCH("NATURAL_DELIVERY_RESPONSE_MISMATCH", "自然交付响应与原请求或可见政策不匹配", 0),
    NATURAL_DELIVERY_DUPLICATE_IDENTITY("NATURAL_DELIVERY_DUPLICATE_IDENTITY", "自然交付身份重复，未开始采集", 0),
    NATURAL_DELIVERY_INCOMPLETE_CONTENT("NATURAL_DELIVERY_INCOMPLETE_CONTENT", "自然交付内容或资格不完整，未开始采集", 0),
    FEED_DELIVERY_RESPONSE_MISMATCH("FEED_DELIVERY_RESPONSE_MISMATCH", "混排响应与原请求或可见政策不匹配", 0),
    FEED_DELIVERY_INVALID_SLOT("FEED_DELIVERY_INVALID_SLOT", "混排最终位置非法", 0),
    FEED_DELIVERY_INVALID_AD("FEED_DELIVERY_INVALID_AD", "混排广告身份或正文非法", 0),
    FEED_DELIVERY_AD_RESULT_UNKNOWN("FEED_DELIVERY_AD_RESULT_UNKNOWN", "广告结果未知，整次交付不能显示完成", 0),
    FEED_DELIVERY_INVALID_PREPARATION("FEED_DELIVERY_INVALID_PREPARATION", "广告准备结果身份非法", 0),
    FEED_DELIVERY_INCOMPLETE_QUALIFICATION("FEED_DELIVERY_INCOMPLETE_QUALIFICATION", "广告计费资格不完整，未开始采集", 0),
    FEED_DELIVERY_INVALID_REJECTION("FEED_DELIVERY_INVALID_REJECTION", "广告拒绝结果非法", 0),
    FEED_DELIVERY_UNSUPPORTED_KIND("FEED_DELIVERY_UNSUPPORTED_KIND", "混排类型不受支持", 0),
    FEED_DELIVERY_DUPLICATE_IDENTITY("FEED_DELIVERY_DUPLICATE_IDENTITY", "混排身份重复", 0),
    FEED_DELIVERY_INCONSISTENT_STATE("FEED_DELIVERY_INCONSISTENT_STATE", "混排准备状态与条目结论不一致", 0),
    FEED_DELIVERY_CHANGED_PLAN("FEED_DELIVERY_CHANGED_PLAN", "恢复响应改变了已冻结混排", 0),
    FEED_DELIVERY_CHANGED_PREPARATION("FEED_DELIVERY_CHANGED_PREPARATION", "恢复响应改变了原广告准备结论", 0),
    RPC_BUDGET_ACCOUNT_NOT_FOUND("RPC_BUDGET_ACCOUNT_NOT_FOUND", "当前未查到账户", 0),
    RPC_BUDGET_COMMAND_NOT_FOUND("RPC_BUDGET_COMMAND_NOT_FOUND", "当前未查到命令结果", 0),
    RPC_BUDGET_CALLER_FORBIDDEN("RPC_BUDGET_CALLER_FORBIDDEN", "调用方无账务权限", 0),
    RPC_BUDGET_PAYLOAD_CONFLICT("RPC_BUDGET_PAYLOAD_CONFLICT", "commandId 已用于不同预算命令", 0),
    RPC_BUDGET_INVALID_ARGUMENT("RPC_BUDGET_INVALID_ARGUMENT", "预算命令参数非法", 0),
    RPC_BUDGET_RESULT_UNKNOWN("RPC_BUDGET_RESULT_UNKNOWN", "账务结果未知，请保留原 commandId 查询或重试", 0),
    RPC_DELIVERY_FEED_DISABLED("RPC_DELIVERY_FEED_DISABLED", "sandbox feed 候选未启用", 0),
    RPC_DELIVERY_NOT_FOUND("RPC_DELIVERY_NOT_FOUND", "当前未观察到准备结果", 0),
    RPC_DELIVERY_FORBIDDEN("RPC_DELIVERY_FORBIDDEN", "交付证明或主体不匹配", 0),
    RPC_DELIVERY_CONFLICT("RPC_DELIVERY_CONFLICT", "交付标识负载冲突", 0),
    RPC_DELIVERY_INVALID_ARGUMENT("RPC_DELIVERY_INVALID_ARGUMENT", "交付请求非法", 0),
    RPC_DELIVERY_RESULT_UNKNOWN("RPC_DELIVERY_RESULT_UNKNOWN", "结果未知，请按原 ID 查询或重试", 0),
    RPC_RECEIPT_NOT_FOUND("RPC_RECEIPT_NOT_FOUND", "当前未观察到回执结果", 0),
    RPC_RECEIPT_CONFLICT("RPC_RECEIPT_CONFLICT", "回执负载冲突", 0),
    RPC_RECEIPT_FORBIDDEN("RPC_RECEIPT_FORBIDDEN", "资格或主体无效", 0),
    RPC_RECEIPT_INVALID_ARGUMENT("RPC_RECEIPT_INVALID_ARGUMENT", "回执格式非法", 0),
    RPC_RECEIPT_RESULT_UNKNOWN("RPC_RECEIPT_RESULT_UNKNOWN", "账务结果未知，请保留原 receiptId 查询或重试", 0),
    RPC_CLICK_NOT_FOUND("RPC_CLICK_NOT_FOUND", "当前未观察到原结果", 0),
    RPC_CLICK_FORBIDDEN("RPC_CLICK_FORBIDDEN", "点击证明或主体无效", 0),
    RPC_CLICK_CONFLICT("RPC_CLICK_CONFLICT", "点击标识负载冲突", 0),
    RPC_CLICK_INVALID_ARGUMENT("RPC_CLICK_INVALID_ARGUMENT", "点击请求格式非法", 0),
    RPC_CLICK_RESULT_UNKNOWN("RPC_CLICK_RESULT_UNKNOWN", "结果未知，请保留原ID查询或重试", 0),
    RPC_CONVERSION_NOT_FOUND("RPC_CONVERSION_NOT_FOUND", "当前未观察到原结果", 0),
    RPC_CONVERSION_FORBIDDEN("RPC_CONVERSION_FORBIDDEN", "CPA证明或主体无效", 0),
    RPC_CONVERSION_CONFLICT("RPC_CONVERSION_CONFLICT", "CPA标识负载冲突", 0),
    RPC_CONVERSION_INVALID_ARGUMENT("RPC_CONVERSION_INVALID_ARGUMENT", "CPA请求格式非法", 0),
    RPC_CONVERSION_RESULT_UNKNOWN("RPC_CONVERSION_RESULT_UNKNOWN", "结果未知，请保留原ID查询或重试", 0),
    BUDGET_INVALID_ARGUMENT("BUDGET_INVALID_ARGUMENT", "预算命令参数非法", 400, "BAD_REQUEST"),
    BUDGET_COMMAND_CONFLICT("BUDGET_COMMAND_CONFLICT", "commandId 已用于不同预算命令", 409, "CONFLICT"),
    BUDGET_RESULT_NOT_OBSERVED("BUDGET_RESULT_NOT_OBSERVED", "当前未查到账户或命令；在途命令请保留原 commandId 查询或重试", 404, "NOT_FOUND"),
    BUDGET_RESULT_UNKNOWN("BUDGET_RESULT_UNKNOWN", "账务结果未知，请保留原 commandId 查询或重试", 503, "SERVICE_UNAVAILABLE"),
    BUDGET_RESPONSE_INVALID("BUDGET_RESPONSE_INVALID", "账务响应无法验证，请保留原 commandId 查询", 502, "BAD_GATEWAY"),
    BUDGET_DEPENDENCY_UNAVAILABLE("BUDGET_DEPENDENCY_UNAVAILABLE", "账务服务暂不可用，请保留原 commandId 查询或重试", 503, "SERVICE_UNAVAILABLE"),
    RPC_INTERNAL_CALLER_FORBIDDEN("RPC_INTERNAL_CALLER_FORBIDDEN", "内部调用方无服务访问权限", 0),
    RPC_INTERNAL_ACCESS_FAILURE("RPC_INTERNAL_ACCESS_FAILURE", "内部服务身份不可用，请保留原ID查询或重试", 0),
    RPC_DELIVERY_REPLY_INVALID("RPC_DELIVERY_REPLY_INVALID", "交付响应无法验证，请保留原 operationId 查询", 0),
    RPC_RECEIPT_REPLY_INVALID("RPC_RECEIPT_REPLY_INVALID", "账务响应无法验证，请保留原 receiptId 查询", 0),
    RPC_CLICK_REPLY_INVALID("RPC_CLICK_REPLY_INVALID", "点击响应无法验证，请按原ID查询", 0),
    RPC_CONVERSION_REPLY_INVALID("RPC_CONVERSION_REPLY_INVALID", "CPA响应无法验证，请按原ID查询", 0),
    RPC_AD_QUERY_FAILED("RPC_AD_QUERY_FAILED", "广告查询暂不可用", 0),
    RPC_AD_REPORT_QUERY_FAILED("RPC_AD_REPORT_QUERY_FAILED", "广告报表查询暂不可用", 0),
    RPC_AD_WRITE_RESULT_UNKNOWN("RPC_AD_WRITE_RESULT_UNKNOWN", "广告写入结果未知，请核查原业务记录", 0),
    RPC_CONTENT_QUERY_FAILED("RPC_CONTENT_QUERY_FAILED", "内容查询暂不可用", 0),
    RPC_USER_QUERY_FAILED("RPC_USER_QUERY_FAILED", "用户偏好查询暂不可用", 0),
    RPC_USER_WRITE_RESULT_UNKNOWN("RPC_USER_WRITE_RESULT_UNKNOWN", "用户偏好保存结果未知", 0);

    /** RPC专用码、CLI或客户端故障没有HTTP状态，不能将此值写入HTTP响应。 */
    public static final int NO_HTTP_STATUS = 0;
    private final String code;
    private final String message;
    private final int httpStatus;
    private final String wireCode;

    ErrorCode(String code, String message, int httpStatus) {
        this(code, message, httpStatus, code);
    }

    ErrorCode(String code, String message, int httpStatus, String wireCode) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.wireCode = wireCode;
    }

    /** 使用显式稳定字符串，不用枚举ordinal或依赖重命名后的name。 */
    public String code() { return code; }

    /** HTTP兼容码；有上下文安全文案时仍保留已发布的响应code。 */
    public String wireCode() { return wireCode; }

    /** 可直接向用户展示的固定文案，禁止拼接内部异常详情。 */
    public String message() { return message; }

    /** RPC/CLI等直接展示场景统一包含目录码；HTTP的code与message字段仍分别序列化。 */
    public String displayMessage() { return "[" + code + "] " + message; }

    /** 默认协议状态；业务适配层仍须保留其既有状态分类。 */
    public int httpStatus() { return httpStatus; }

    /** 未登记代码返回null，让调用边界显式选择兼容回退。 */
    public static ErrorCode fromCode(String code) {
        for (ErrorCode value : values()) {
            if (value.code.equals(code)) return value;
        }
        return null;
    }

    /** 未登记HTTP状态只使用安全兜底文案，调用方决定原HTTP状态是否保留。 */
    public static ErrorCode forHttpStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> UNPROCESSABLE_ENTITY;
            case 429 -> TOO_MANY_REQUESTS;
            case 500 -> INTERNAL_SERVER_ERROR;
            case 502 -> BAD_GATEWAY;
            case 503 -> SERVICE_UNAVAILABLE;
            case 504 -> GATEWAY_TIMEOUT;
            case 408 -> REQUEST_TIMEOUT;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            default -> INTERNAL_ERROR;
        };
    }
}
