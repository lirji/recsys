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
    RPC_USER_WRITE_RESULT_UNKNOWN("RPC_USER_WRITE_RESULT_UNKNOWN", "用户偏好保存结果未知", 0),
    AD_WRITE_RESULT_UNKNOWN("AD_WRITE_RESULT_UNKNOWN", "广告回传结果未知，请核查原业务记录，避免重复提交", 503),
    USER_INTEREST_WRITE_RESULT_UNKNOWN("USER_INTEREST_WRITE_RESULT_UNKNOWN", "兴趣保存结果未知，请稍后核查", 503),
    USER_INTEREST_QUERY_FAILED("USER_INTEREST_QUERY_FAILED", "兴趣信息暂时无法读取，请稍后重试", 503),
    CONSOLE_METRICS_UNAVAILABLE("CONSOLE_METRICS_UNAVAILABLE", "系统指标暂时不可用，请检查观测服务与查询配置", 0),
    CONSOLE_HEALTH_TARGET_MISSING("CONSOLE_HEALTH_TARGET_MISSING", "未配置健康探测地址", 0),
    CONSOLE_HEALTH_UNAVAILABLE("CONSOLE_HEALTH_UNAVAILABLE", "健康探测暂时不可达", 0),
    CONSOLE_HEALTH_HTTP_FAILED("CONSOLE_HEALTH_HTTP_FAILED", "健康探测返回非成功状态", 0),
    CONSOLE_HEALTH_STATUS_UNKNOWN("CONSOLE_HEALTH_STATUS_UNKNOWN", "健康状态未知，请检查探测结果", 0),
    CONSOLE_HEALTH_UNHEALTHY("CONSOLE_HEALTH_UNHEALTHY", "服务健康检查未通过", 0),
    CONSOLE_DIAGNOSIS_CHECK_FAILED("CONSOLE_DIAGNOSIS_CHECK_FAILED", "诊断检查未完成，请检查对应服务或报表", 0),
    CONSOLE_DIAGNOSIS_INCOMPLETE("CONSOLE_DIAGNOSIS_INCOMPLETE", "诊断数据缺失或无效，暂时无法判定正常", 0),
    CONSOLE_REPORT_UNAVAILABLE("CONSOLE_REPORT_UNAVAILABLE", "报表暂时不可用，请检查数据源或稍后重试", 503),
    CONSOLE_REPORT_READ_FAILED("CONSOLE_REPORT_READ_FAILED", "报表内容无法读取，请检查文件或稍后重试", 500),
    CONSOLE_USER_DATA_UNAVAILABLE("CONSOLE_USER_DATA_UNAVAILABLE", "该部分用户数据暂时无法读取，请稍后重试", 0),
    CONSOLE_USER_REALTIME_UNAVAILABLE("CONSOLE_USER_REALTIME_UNAVAILABLE", "用户实时态暂时无法读取，请稍后重试", 0),
    CONSOLE_ITEM_QUERY_FAILED("CONSOLE_ITEM_QUERY_FAILED", "物品信息暂时无法读取，请稍后重试", 503),
    JOB_UNKNOWN("JOB_UNKNOWN", "作业名称不存在，请检查 --job 或 --targets", 0),
    JOB_INVALID_ARGUMENT("JOB_INVALID_ARGUMENT", "作业参数或依赖计划不合法，请检查参数和作业依赖", 0),
    JOB_EXECUTION_FAILED("JOB_EXECUTION_FAILED", "作业执行失败，可能已有部分结果，请核对状态后再重试", 0),
    JOB_DEPENDENCY_FAILED("JOB_DEPENDENCY_FAILED", "上游作业未完成，本作业尚未执行", 0),
    JOB_ALREADY_RUNNING("JOB_ALREADY_RUNNING", "已有作业管线在执行，请等待完成后重试", 0),
    JOB_LOCK_UNAVAILABLE("JOB_LOCK_UNAVAILABLE", "无法确认作业执行锁，本次未开始执行，请稍后重试", 0),
    JOB_STATUS_WRITE_FAILED("JOB_STATUS_WRITE_FAILED", "作业状态未能确认，业务效果可能已生效，请核对后再重试", 0),
    JOB_STATUS_INVALID("JOB_STATUS_INVALID", "作业状态记录无法识别，当前结果未知，请核对执行记录", 0),
    CONSOLE_OPS_UNAVAILABLE("CONSOLE_OPS_UNAVAILABLE", "运行配置与作业状态暂时无法读取，请稍后重试", 0),
    JOB_LOCK_RELEASE_FAILED("JOB_LOCK_RELEASE_FAILED", "作业执行已结束，但锁释放未能确认，请核对锁状态后再运行", 0),
    STREAM_CATEGORY_LOAD_FAILED("STREAM_CATEGORY_LOAD_FAILED", "物品类目加载失败，实时特征作业未就绪，请检查数据源后重试", 0),
    JOB_EMBEDDING_QUOTA_EXHAUSTED("JOB_EMBEDDING_QUOTA_EXHAUSTED", "向量化配额耗尽，本次回填未完成；已有结果保留，可在配额恢复后使用 --skip-existing 续跑", 0),
    JOB_EMBEDDING_PARTIAL_FAILED("JOB_EMBEDDING_PARTIAL_FAILED", "部分向量回填失败，已有结果保留，请核对失败项后使用 --skip-existing 续跑", 0),
    JOB_EMBEDDING_INVALID_RESULT("JOB_EMBEDDING_INVALID_RESULT", "向量结果的维度或数值不合法，请检查模型配置与响应", 0),
    JOB_REPORT_INPUT_UNAVAILABLE("JOB_REPORT_INPUT_UNAVAILABLE", "报表输入目录不存在或不可读取，请检查 --eval-dir 与上游产物", 0),
    JOB_REPORT_PARTIAL_FAILED("JOB_REPORT_PARTIAL_FAILED", "部分报表未能发布，已发布结果保留，请修复输入或数据源后重试", 0),
    JOB_INTERRUPTED("JOB_INTERRUPTED", "作业被中断，可能已有部分结果，请核对后再运行", 0),
    JOB_INPUT_UNAVAILABLE("JOB_INPUT_UNAVAILABLE", "导入文件不存在或不可读取，请检查输入路径并先生成上游产物", 0),
    JOB_INPUT_INVALID("JOB_INPUT_INVALID", "输入数据格式或数值不合法，请检查输入文件；可能已有部分结果", 0),
    JOB_IMPORT_PARTIAL_FAILED("JOB_IMPORT_PARTIAL_FAILED", "部分输入行未能导入，已有结果保留，请修复文件后重跑", 0),
    JOB_R9_REQUIRED_GROUP_EMPTY("JOB_R9_REQUIRED_GROUP_EMPTY", "R9 要求三组向量均非空，请先生成全部输入产物", 0),
    JOB_R9_INVALID_VECTOR("JOB_R9_INVALID_VECTOR", "R9 向量须为64维有限数值并近似L2归一化，请检查训练产物", 0),
    JOB_R9_INVALID_IDENTITY("JOB_R9_INVALID_IDENTITY", "R9 向量版本为空或同组身份重复，请检查输入文件", 0),
    JOB_R9_VERSION_MISMATCH("JOB_R9_VERSION_MISMATCH", "R9 三组向量必须使用同一个模型版本，请检查输入产物", 0),
    JOB_R9_NO_INPUT("JOB_R9_NO_INPUT", "没有可发布的R9向量，本次未发布，请先生成输入产物", 0),
    JOB_POPULARITY_FALLBACK("JOB_POPULARITY_FALLBACK", "缺少可选评分文件，本次使用0热度导入物品，后续补齐评分后可重跑", 0),
    JOB_NO_BEHAVIOR("JOB_NO_BEHAVIOR", "没有符合条件的行为数据，本次未更新热榜；请检查输入来源与时间范围", 0),
    JOB_NO_ITEMS("JOB_NO_ITEMS", "物品语料为空，本次未更新；请先导入物品或检查目录来源", 0),
    JOB_NO_POSITIVE_FEEDBACK("JOB_NO_POSITIVE_FEEDBACK", "没有符合条件的正反馈，本次未生成结果；请检查行为输入与评分阈值", 0),
    JOB_NO_CATEGORY_STATS("JOB_NO_CATEGORY_STATS", "没有可关联的类目行为，本次未更新统计；请检查物品类目与行为来源", 0),
    JOB_NO_ITEM_VECTORS("JOB_NO_ITEM_VECTORS", "没有可用物品向量，本次未更新用户向量；请先完成物品向量回填", 0),
    JOB_NO_USER_VECTORS("JOB_NO_USER_VECTORS", "正反馈未命中可用物品向量，本次未更新用户向量；请检查输入覆盖范围", 0),
    JOB_NO_RECALL_OUTPUT("JOB_NO_RECALL_OUTPUT", "输入未形成可发布的相似召回列表；请检查共现数据与过滤参数", 0),
    JOB_NO_RATINGS("JOB_NO_RATINGS", "没有评分事件，本次未生成样本；请先导入行为或检查输入来源", 0),
    JOB_NO_IMPRESSIONS("JOB_NO_IMPRESSIONS", "没有曝光事件，本次未生成样本；请先积累曝光或运行 sim-rec-events", 0),
    JOB_NO_AD_CLICKS("JOB_NO_AD_CLICKS", "没有符合条件的广告点击，本次未生成样本；请检查输入或运行 sim-ad-events", 0),
    JOB_NO_MATURE_ASSIGNMENTS("JOB_NO_MATURE_ASSIGNMENTS", "没有已成熟的实验分配，本次未生成样本；请检查 as-of、时间范围与归因窗口", 0),
    JOB_SAMPLES_ALL_NEGATIVE("JOB_SAMPLES_ALL_NEGATIVE", "样本已生成，但全部为负标签；请检查曝光后的正反馈与归因窗口再训练", 0),
    JOB_SAMPLES_MISSING_ITEM("JOB_SAMPLES_MISSING_ITEM", "样本已生成，但已跳过无物品关联的广告点击；请检查广告物品映射及样本覆盖范围", 0),
    JOB_NO_SAMPLE_OUTPUT("JOB_NO_SAMPLE_OUTPUT", "没有可用样本产出，输出可能仅含表头；请补齐广告物品关联后重新生成", 0),
    JOB_GENERATION_INPUT_EMPTY("JOB_GENERATION_INPUT_EMPTY", "输入物品为空，未生成新数据；如启用了 clear，旧数据可能已清除，请补齐物品后重试", 0),
    JOB_NO_ADS("JOB_NO_ADS", "没有可用广告，未生成模拟事件；请先运行 seed-ads 或检查广告目录", 0),
    JOB_SEED_CLEAR_FAILED("JOB_SEED_CLEAR_FAILED", "清理未完整完成，尚未开始生成广告；部分旧数据可能已清除，请检查存储并核对后重试", 0),
    JOB_CREATIVE_INPUT_UNAVAILABLE("JOB_CREATIVE_INPUT_UNAVAILABLE", "创意目录读取失败，未开始生成事件；请检查分片表与连接后重试", 0),
    JOB_CREATIVE_ATTRIBUTION_UNAVAILABLE("JOB_CREATIVE_ATTRIBUTION_UNAVAILABLE", "创意目录为空，模拟事件仍已生成，但不包含创意归因；请补齐创意后再评估创意效果", 0),
    JOB_NO_EVALUATION_USERS("JOB_NO_EVALUATION_USERS", "没有可评估的测试正例，请检查评分数据、时间切分和历史物品过滤条件", 0),
    JOB_NO_EVALUATION_OUTPUT("JOB_NO_EVALUATION_OUTPUT", "没有可用的评估结果，请检查召回候选与历史过滤条件；已生成的零用户报告不能用于效果判断", 0),
    JOB_CALIBRATION_SAMPLES_INSUFFICIENT("JOB_CALIBRATION_SAMPLES_INSUFFICIENT", "校准样本不足，原曲线保持不变；请增加有效用户或候选，或调整分箱数", 0),
    JOB_TUNING_STRATEGY_UNSUPPORTED("JOB_TUNING_STRATEGY_UNSUPPORTED", "调参仅支持 mmoe 或 din 多目标策略，请检查 strategy 参数", 0),
    JOB_MODEL_HEADS_UNAVAILABLE("JOB_MODEL_HEADS_UNAVAILABLE", "没有可用的 pCTR/pCVR 模型输出，请确认多目标模型已启用且加载成功", 0),
    JOB_MODEL_HEADS_PARTIAL("JOB_MODEL_HEADS_PARTIAL", "部分候选缺少 pCTR/pCVR，已过滤这些候选；结果仅覆盖可用的模型输出", 0),
    JOB_EVALUATION_RESULT_INVALID("JOB_EVALUATION_RESULT_INVALID", "模型输出或评估计算包含无效数值，请检查模型及调参范围后重试", 0),
    JOB_EVALUATION_PARTIAL_FAILED("JOB_EVALUATION_PARTIAL_FAILED", "部分用户评估失败，作业未完整完成；已生成的部分结果可能保留，请修复依赖或模型输出后重试", 0),
    JOB_NO_BUCKET_IMPRESSIONS("JOB_NO_BUCKET_IMPRESSIONS", "没有满足条件的分桶曝光数据，未生成报表；请检查曝光采集、时间窗口和筛选条件", 0),
    JOB_CUPED_START_REQUIRED("JOB_CUPED_START_REQUIRED", "启用 CUPED 时必须提供 since 实验开始时间，请补齐参数后重试", 0),
    JOB_DELAY_SAMPLES_INSUFFICIENT("JOB_DELAY_SAMPLES_INSUFFICIENT", "可用转化延迟样本不足，原延迟模型保持不变；请补齐数据或调整采样条件后重试", 0),
    JOB_DELAY_MODEL_INVALID("JOB_DELAY_MODEL_INVALID", "转化延迟均值或模型参数无效，原延迟模型保持不变；请检查延迟样本后重试", 0),
    JOB_NO_AUDIENCE_SEEDS("JOB_NO_AUDIENCE_SEEDS", "没有种子人群，未更新人群包；请先写入种子用户后重试", 0),
    JOB_AUDIENCE_VECTORS_UNAVAILABLE("JOB_AUDIENCE_VECTORS_UNAVAILABLE", "有人群缺少可用种子向量，未完整更新；成功人群可能已更新，缺失人群原包保留，请补齐向量后重试", 0),
    JOB_EXPLORATION_EMPTY_SNAPSHOT("JOB_EXPLORATION_EMPTY_SNAPSHOT", "本次没有广告事件，全局曝光总数已发布为 0；未出现的广告与创意旧统计保留，请确认输入窗口", 0),
    JOB_BEHAVIOR_SOURCE_INVALID("JOB_BEHAVIOR_SOURCE_INVALID", "行为数据来源不支持，请使用 user_behavior 或 behavior_log", 0),
    JOB_ITEM_SOURCE_INVALID("JOB_ITEM_SOURCE_INVALID", "物品数据来源不支持，请使用 item 或 item_local", 0),
    JOB_AD_EVENT_SOURCE_INVALID("JOB_AD_EVENT_SOURCE_INVALID", "广告事件来源不支持，请使用 ad_event 或 ad_event_log", 0),
    JOB_AD_CALIBRATION_SAMPLES_INSUFFICIENT("JOB_AD_CALIBRATION_SAMPLES_INSUFFICIENT", "广告校准样本不足，原曲线保持不变；请补齐有效曝光与点击数据或调整分箱数", 0),
    JOB_AD_CALIBRATION_INPUT_INVALID("JOB_AD_CALIBRATION_INPUT_INVALID", "广告校准输入或计算结果包含无效数值，原曲线保持不变；请检查预测值与数据后重试", 0),
    JOB_NO_AD_REPORT_DATA("JOB_NO_AD_REPORT_DATA", "没有符合条件的广告曝光数据，本次未生成报表；请检查事件采集和时间条件", 0),
    JOB_INVALID_CLICK_COUNT_UNAVAILABLE("JOB_INVALID_CLICK_COUNT_UNAVAILABLE", "无效点击计数无法确认，本次报表未完成；请检查广告事件来源后重试", 0),
    JOB_ATTRIBUTION_MODEL_FALLBACK("JOB_ATTRIBUTION_MODEL_FALLBACK", "归因模型不受支持，本次使用 position 模型；请检查 model 参数", 0),
    JOB_NO_ATTRIBUTION_CONVERSIONS("JOB_NO_ATTRIBUTION_CONVERSIONS", "没有可归因的转化事件，本次未生成报表；请检查事件采集和时间窗口", 0),
    JOB_REPORT_OUTPUT_FAILED("JOB_REPORT_OUTPUT_FAILED", "报表写入未完成，可能留下部分文件；请检查输出目录和磁盘后重试", 0),
    JOB_NO_AD_QUALITY_INPUT("JOB_NO_AD_QUALITY_INPUT", "没有广告质量度所需的曝光数据，原结果保持不变；请检查事件来源和时间窗口", 0),
    JOB_NO_AD_QUALITY_OUTPUT("JOB_NO_AD_QUALITY_OUTPUT", "没有广告达到质量度计算门槛，原结果保持不变；请补齐数据或调整最少曝光数", 0),
    JOB_AD_QUALITY_INPUT_INVALID("JOB_AD_QUALITY_INPUT_INVALID", "广告质量度输入或计算结果无效，可能已有部分结果更新；请检查统计值和先验参数后重试", 0),
    JOB_AD_QUALITY_PUBLICATION_FAILED("JOB_AD_QUALITY_PUBLICATION_FAILED", "广告质量度发布未完成，Redis 或报表可能已部分写入；请核对已有结果并修复依赖后重试", 0),
    JOB_DATA_QUALITY_CHECK_FAILED("JOB_DATA_QUALITY_CHECK_FAILED", "部分数据质量检查失败，巡检尚未完成；请查看各项状态并修复数据或依赖后重试", 0),
    JOB_DATA_QUALITY_INCOMPLETE("JOB_DATA_QUALITY_INCOMPLETE", "数据质量检查缺少有效数据或完整状态，无法确认巡检通过；请补齐数据后重新运行", 0),
    JOB_DATA_QUALITY_BREACHED("JOB_DATA_QUALITY_BREACHED", "数据质量巡检已完成，发现指标越阈值；请检查报表中的对应指标", 0);

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
