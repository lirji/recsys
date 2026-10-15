// 由contracts/errors.json生成；请运行python3 scripts/generate_error_codes.py，勿手工修改。
export const errorCodes = {
  BAD_REQUEST: {
    message: '请求参数不合法，请检查后重试',
    httpStatus: 400,
  },
  VALIDATION_FAILED: {
    message: '请求参数校验失败',
    httpStatus: 400,
  },
  UNAUTHORIZED: {
    message: '请先登录后再操作',
    httpStatus: 401,
  },
  UNAUTHENTICATED: {
    message: '需要有效身份',
    httpStatus: 401,
  },
  FORBIDDEN: {
    message: '无权访问该资源',
    httpStatus: 403,
  },
  NOT_FOUND: {
    message: '请求的资源不存在',
    httpStatus: 404,
  },
  METHOD_NOT_ALLOWED: {
    message: '请求方法不支持',
    httpStatus: 405,
  },
  CONFLICT: {
    message: '请求与当前状态冲突，请刷新后重试',
    httpStatus: 409,
  },
  UNSUPPORTED_MEDIA_TYPE: {
    message: '请求内容格式不支持',
    httpStatus: 415,
  },
  UNPROCESSABLE_ENTITY: {
    message: '请求内容无法处理，请检查业务条件',
    httpStatus: 422,
  },
  TOO_MANY_REQUESTS: {
    message: '请求过于频繁，请稍后重试',
    httpStatus: 429,
  },
  INTERNAL_SERVER_ERROR: {
    message: '服务器内部错误',
    httpStatus: 500,
  },
  INTERNAL_ERROR: {
    message: '服务器内部错误',
    httpStatus: 500,
  },
  BAD_GATEWAY: {
    message: '上游服务暂时不可用，请稍后重试',
    httpStatus: 502,
  },
  SERVICE_UNAVAILABLE: {
    message: '服务暂时不可用，请稍后重试',
    httpStatus: 503,
  },
  GATEWAY_TIMEOUT: {
    message: '请求处理超时，请查询原操作结果',
    httpStatus: 504,
  },
  NETWORK_ERROR: {
    message: '网络连接失败，请检查网络并确认操作结果',
    httpStatus: 0,
  },
  REQUEST_TIMEOUT: {
    message: '请求超时，请查询操作结果后再决定是否重试',
    httpStatus: 408,
  },
  REQUEST_CANCELLED: {
    message: '请求已取消',
    httpStatus: 0,
  },
  UNKNOWN_ERROR: {
    message: '操作未完成，请稍后重试或联系管理员',
    httpStatus: 0,
  },
  NOT_ACCEPTABLE: {
    message: '请求的响应格式不支持',
    httpStatus: 406,
  },
  PAYLOAD_TOO_LARGE: {
    message: '请求内容过大，请缩小后重试',
    httpStatus: 413,
  },
  CONVERSION_400: {
    message: '转化请求未通过校验或当前不可访问',
    httpStatus: 400,
  },
  CONVERSION_401: {
    message: '转化请求未通过校验或当前不可访问',
    httpStatus: 401,
  },
  CONVERSION_403: {
    message: '转化请求未通过校验或当前不可访问',
    httpStatus: 403,
  },
  CONVERSION_404: {
    message: '转化请求未通过校验或当前不可访问',
    httpStatus: 404,
  },
  CONVERSION_409: {
    message: '转化请求未通过校验或当前不可访问',
    httpStatus: 409,
  },
  CONVERSION_503: {
    message: '转化结果未知，请保留原业务ID查询或重试',
    httpStatus: 503,
  },
  NATURAL_RECEIPT_400: {
    message: '自然回执未通过校验或当前不可访问',
    httpStatus: 400,
  },
  NATURAL_RECEIPT_401: {
    message: '自然回执未通过校验或当前不可访问',
    httpStatus: 401,
  },
  NATURAL_RECEIPT_403: {
    message: '自然回执未通过校验或当前不可访问',
    httpStatus: 403,
  },
  NATURAL_RECEIPT_404: {
    message: '自然回执未通过校验或当前不可访问',
    httpStatus: 404,
  },
  NATURAL_RECEIPT_409: {
    message: '自然回执未通过校验或当前不可访问',
    httpStatus: 409,
  },
  NATURAL_RECEIPT_503: {
    message: '自然回执结果未知，请保留原 receiptId 查询或重试',
    httpStatus: 503,
  },
  CLICK_RECEIPT_400: {
    message: '点击请求未通过校验或当前不可访问',
    httpStatus: 400,
  },
  CLICK_RECEIPT_401: {
    message: '点击请求未通过校验或当前不可访问',
    httpStatus: 401,
  },
  CLICK_RECEIPT_403: {
    message: '点击请求未通过校验或当前不可访问',
    httpStatus: 403,
  },
  CLICK_RECEIPT_404: {
    message: '点击请求未通过校验或当前不可访问',
    httpStatus: 404,
  },
  CLICK_RECEIPT_409: {
    message: '点击请求未通过校验或当前不可访问',
    httpStatus: 409,
  },
  CLICK_RECEIPT_503: {
    message: '点击结果未知，请按原ID查询或重试',
    httpStatus: 503,
  },
  CONVERSION_RECEIPT_400: {
    message: 'CPA请求未通过校验或当前不可访问',
    httpStatus: 400,
  },
  CONVERSION_RECEIPT_401: {
    message: 'CPA请求未通过校验或当前不可访问',
    httpStatus: 401,
  },
  CONVERSION_RECEIPT_403: {
    message: 'CPA请求未通过校验或当前不可访问',
    httpStatus: 403,
  },
  CONVERSION_RECEIPT_404: {
    message: 'CPA请求未通过校验或当前不可访问',
    httpStatus: 404,
  },
  CONVERSION_RECEIPT_409: {
    message: 'CPA请求未通过校验或当前不可访问',
    httpStatus: 409,
  },
  CONVERSION_RECEIPT_503: {
    message: 'CPA结果未知，请按原ID查询或重试',
    httpStatus: 503,
  },
  RECEIPT_400: {
    message: '回执请求未通过校验或当前不可访问',
    httpStatus: 400,
  },
  RECEIPT_401: {
    message: '回执请求未通过校验或当前不可访问',
    httpStatus: 401,
  },
  RECEIPT_403: {
    message: '回执请求未通过校验或当前不可访问',
    httpStatus: 403,
  },
  RECEIPT_404: {
    message: '回执请求未通过校验或当前不可访问',
    httpStatus: 404,
  },
  RECEIPT_409: {
    message: '回执请求未通过校验或当前不可访问',
    httpStatus: 409,
  },
  RECEIPT_503: {
    message: '账务结果未知，请按原 receiptId 查询或重试',
    httpStatus: 503,
  },
  DELIVERY_400: {
    message: '交付请求未通过校验或当前不可访问',
    httpStatus: 400,
  },
  DELIVERY_401: {
    message: '交付请求未通过校验或当前不可访问',
    httpStatus: 401,
  },
  DELIVERY_403: {
    message: '交付请求未通过校验或当前不可访问',
    httpStatus: 403,
  },
  DELIVERY_404: {
    message: '交付请求未通过校验或当前不可访问',
    httpStatus: 404,
  },
  DELIVERY_409: {
    message: '交付请求未通过校验或当前不可访问',
    httpStatus: 409,
  },
  DELIVERY_503: {
    message: '交付结果未知，请保留原 operationId 查询或重试',
    httpStatus: 503,
  },
  NATURAL_DELIVERY_400: {
    message: '自然交付请求未通过校验或当前不可访问',
    httpStatus: 400,
  },
  NATURAL_DELIVERY_401: {
    message: '自然交付请求未通过校验或当前不可访问',
    httpStatus: 401,
  },
  NATURAL_DELIVERY_403: {
    message: '自然交付请求未通过校验或当前不可访问',
    httpStatus: 403,
  },
  NATURAL_DELIVERY_404: {
    message: '自然交付请求未通过校验或当前不可访问',
    httpStatus: 404,
  },
  NATURAL_DELIVERY_409: {
    message: '自然交付请求未通过校验或当前不可访问',
    httpStatus: 409,
  },
  NATURAL_DELIVERY_503: {
    message: '自然交付结果未知，请保留原 operationId 查询或重试',
    httpStatus: 503,
  },
  FEED_DELIVERY_400: {
    message: '混排交付请求未通过校验或当前不可访问',
    httpStatus: 400,
  },
  FEED_DELIVERY_401: {
    message: '混排交付请求未通过校验或当前不可访问',
    httpStatus: 401,
  },
  FEED_DELIVERY_403: {
    message: '混排交付请求未通过校验或当前不可访问',
    httpStatus: 403,
  },
  FEED_DELIVERY_404: {
    message: '混排交付请求未通过校验或当前不可访问',
    httpStatus: 404,
  },
  FEED_DELIVERY_409: {
    message: '混排交付请求未通过校验或当前不可访问',
    httpStatus: 409,
  },
  FEED_DELIVERY_503: {
    message: '混排交付结果未知，请保留原 operationId 查询或重试',
    httpStatus: 503,
  },
  AUTH_INVALID_CREDENTIALS: {
    message: '用户名或密码错误',
    httpStatus: 401,
  },
  AUTH_TOKEN_ISSUANCE_FAILED: {
    message: '登录服务暂时不可用，请稍后重试',
    httpStatus: 500,
  },
  IDENTITY_PROPAGATION_FAILED: {
    message: '身份传递失败，请稍后重试',
    httpStatus: 500,
  },
  NATURAL_DELIVERY_RESPONSE_MISMATCH: {
    message: '自然交付响应与原请求或可见政策不匹配',
    httpStatus: 0,
  },
  NATURAL_DELIVERY_DUPLICATE_IDENTITY: {
    message: '自然交付身份重复，未开始采集',
    httpStatus: 0,
  },
  NATURAL_DELIVERY_INCOMPLETE_CONTENT: {
    message: '自然交付内容或资格不完整，未开始采集',
    httpStatus: 0,
  },
  FEED_DELIVERY_RESPONSE_MISMATCH: {
    message: '混排响应与原请求或可见政策不匹配',
    httpStatus: 0,
  },
  FEED_DELIVERY_INVALID_SLOT: {
    message: '混排最终位置非法',
    httpStatus: 0,
  },
  FEED_DELIVERY_INVALID_AD: {
    message: '混排广告身份或正文非法',
    httpStatus: 0,
  },
  FEED_DELIVERY_AD_RESULT_UNKNOWN: {
    message: '广告结果未知，整次交付不能显示完成',
    httpStatus: 0,
  },
  FEED_DELIVERY_INVALID_PREPARATION: {
    message: '广告准备结果身份非法',
    httpStatus: 0,
  },
  FEED_DELIVERY_INCOMPLETE_QUALIFICATION: {
    message: '广告计费资格不完整，未开始采集',
    httpStatus: 0,
  },
  FEED_DELIVERY_INVALID_REJECTION: {
    message: '广告拒绝结果非法',
    httpStatus: 0,
  },
  FEED_DELIVERY_UNSUPPORTED_KIND: {
    message: '混排类型不受支持',
    httpStatus: 0,
  },
  FEED_DELIVERY_DUPLICATE_IDENTITY: {
    message: '混排身份重复',
    httpStatus: 0,
  },
  FEED_DELIVERY_INCONSISTENT_STATE: {
    message: '混排准备状态与条目结论不一致',
    httpStatus: 0,
  },
  FEED_DELIVERY_CHANGED_PLAN: {
    message: '恢复响应改变了已冻结混排',
    httpStatus: 0,
  },
  FEED_DELIVERY_CHANGED_PREPARATION: {
    message: '恢复响应改变了原广告准备结论',
    httpStatus: 0,
  },
  RPC_BUDGET_ACCOUNT_NOT_FOUND: {
    message: '当前未查到账户',
    httpStatus: 0,
  },
  RPC_BUDGET_COMMAND_NOT_FOUND: {
    message: '当前未查到命令结果',
    httpStatus: 0,
  },
  RPC_BUDGET_CALLER_FORBIDDEN: {
    message: '调用方无账务权限',
    httpStatus: 0,
  },
  RPC_BUDGET_PAYLOAD_CONFLICT: {
    message: 'commandId 已用于不同预算命令',
    httpStatus: 0,
  },
  RPC_BUDGET_INVALID_ARGUMENT: {
    message: '预算命令参数非法',
    httpStatus: 0,
  },
  RPC_BUDGET_RESULT_UNKNOWN: {
    message: '账务结果未知，请保留原 commandId 查询或重试',
    httpStatus: 0,
  },
  RPC_DELIVERY_FEED_DISABLED: {
    message: 'sandbox feed 候选未启用',
    httpStatus: 0,
  },
  RPC_DELIVERY_NOT_FOUND: {
    message: '当前未观察到准备结果',
    httpStatus: 0,
  },
  RPC_DELIVERY_FORBIDDEN: {
    message: '交付证明或主体不匹配',
    httpStatus: 0,
  },
  RPC_DELIVERY_CONFLICT: {
    message: '交付标识负载冲突',
    httpStatus: 0,
  },
  RPC_DELIVERY_INVALID_ARGUMENT: {
    message: '交付请求非法',
    httpStatus: 0,
  },
  RPC_DELIVERY_RESULT_UNKNOWN: {
    message: '结果未知，请按原 ID 查询或重试',
    httpStatus: 0,
  },
  RPC_RECEIPT_NOT_FOUND: {
    message: '当前未观察到回执结果',
    httpStatus: 0,
  },
  RPC_RECEIPT_CONFLICT: {
    message: '回执负载冲突',
    httpStatus: 0,
  },
  RPC_RECEIPT_FORBIDDEN: {
    message: '资格或主体无效',
    httpStatus: 0,
  },
  RPC_RECEIPT_INVALID_ARGUMENT: {
    message: '回执格式非法',
    httpStatus: 0,
  },
  RPC_RECEIPT_RESULT_UNKNOWN: {
    message: '账务结果未知，请保留原 receiptId 查询或重试',
    httpStatus: 0,
  },
  RPC_CLICK_NOT_FOUND: {
    message: '当前未观察到原结果',
    httpStatus: 0,
  },
  RPC_CLICK_FORBIDDEN: {
    message: '点击证明或主体无效',
    httpStatus: 0,
  },
  RPC_CLICK_CONFLICT: {
    message: '点击标识负载冲突',
    httpStatus: 0,
  },
  RPC_CLICK_INVALID_ARGUMENT: {
    message: '点击请求格式非法',
    httpStatus: 0,
  },
  RPC_CLICK_RESULT_UNKNOWN: {
    message: '结果未知，请保留原ID查询或重试',
    httpStatus: 0,
  },
  RPC_CONVERSION_NOT_FOUND: {
    message: '当前未观察到原结果',
    httpStatus: 0,
  },
  RPC_CONVERSION_FORBIDDEN: {
    message: 'CPA证明或主体无效',
    httpStatus: 0,
  },
  RPC_CONVERSION_CONFLICT: {
    message: 'CPA标识负载冲突',
    httpStatus: 0,
  },
  RPC_CONVERSION_INVALID_ARGUMENT: {
    message: 'CPA请求格式非法',
    httpStatus: 0,
  },
  RPC_CONVERSION_RESULT_UNKNOWN: {
    message: '结果未知，请保留原ID查询或重试',
    httpStatus: 0,
  },
  BUDGET_INVALID_ARGUMENT: {
    message: '预算命令参数非法',
    httpStatus: 400,
  },
  BUDGET_COMMAND_CONFLICT: {
    message: 'commandId 已用于不同预算命令',
    httpStatus: 409,
  },
  BUDGET_RESULT_NOT_OBSERVED: {
    message: '当前未查到账户或命令；在途命令请保留原 commandId 查询或重试',
    httpStatus: 404,
  },
  BUDGET_RESULT_UNKNOWN: {
    message: '账务结果未知，请保留原 commandId 查询或重试',
    httpStatus: 503,
  },
  BUDGET_RESPONSE_INVALID: {
    message: '账务响应无法验证，请保留原 commandId 查询',
    httpStatus: 502,
  },
  BUDGET_DEPENDENCY_UNAVAILABLE: {
    message: '账务服务暂不可用，请保留原 commandId 查询或重试',
    httpStatus: 503,
  },
  RPC_INTERNAL_CALLER_FORBIDDEN: {
    message: '内部调用方无服务访问权限',
    httpStatus: 0,
  },
  RPC_INTERNAL_ACCESS_FAILURE: {
    message: '内部服务身份不可用，请保留原ID查询或重试',
    httpStatus: 0,
  },
  RPC_DELIVERY_REPLY_INVALID: {
    message: '交付响应无法验证，请保留原 operationId 查询',
    httpStatus: 0,
  },
  RPC_RECEIPT_REPLY_INVALID: {
    message: '账务响应无法验证，请保留原 receiptId 查询',
    httpStatus: 0,
  },
  RPC_CLICK_REPLY_INVALID: {
    message: '点击响应无法验证，请按原ID查询',
    httpStatus: 0,
  },
  RPC_CONVERSION_REPLY_INVALID: {
    message: 'CPA响应无法验证，请按原ID查询',
    httpStatus: 0,
  },
  RPC_AD_QUERY_FAILED: {
    message: '广告查询暂不可用',
    httpStatus: 0,
  },
  RPC_AD_REPORT_QUERY_FAILED: {
    message: '广告报表查询暂不可用',
    httpStatus: 0,
  },
  RPC_AD_WRITE_RESULT_UNKNOWN: {
    message: '广告写入结果未知，请核查原业务记录',
    httpStatus: 0,
  },
  RPC_CONTENT_QUERY_FAILED: {
    message: '内容查询暂不可用',
    httpStatus: 0,
  },
  RPC_USER_QUERY_FAILED: {
    message: '用户偏好查询暂不可用',
    httpStatus: 0,
  },
  RPC_USER_WRITE_RESULT_UNKNOWN: {
    message: '用户偏好保存结果未知',
    httpStatus: 0,
  },
  AD_WRITE_RESULT_UNKNOWN: {
    message: '广告回传结果未知，请核查原业务记录，避免重复提交',
    httpStatus: 503,
  },
  USER_INTEREST_WRITE_RESULT_UNKNOWN: {
    message: '兴趣保存结果未知，请稍后核查',
    httpStatus: 503,
  },
  USER_INTEREST_QUERY_FAILED: {
    message: '兴趣信息暂时无法读取，请稍后重试',
    httpStatus: 503,
  },
  CONSOLE_METRICS_UNAVAILABLE: {
    message: '系统指标暂时不可用，请检查观测服务与查询配置',
    httpStatus: 0,
  },
  CONSOLE_HEALTH_TARGET_MISSING: {
    message: '未配置健康探测地址',
    httpStatus: 0,
  },
  CONSOLE_HEALTH_UNAVAILABLE: {
    message: '健康探测暂时不可达',
    httpStatus: 0,
  },
  CONSOLE_HEALTH_HTTP_FAILED: {
    message: '健康探测返回非成功状态',
    httpStatus: 0,
  },
  CONSOLE_HEALTH_STATUS_UNKNOWN: {
    message: '健康状态未知，请检查探测结果',
    httpStatus: 0,
  },
  CONSOLE_HEALTH_UNHEALTHY: {
    message: '服务健康检查未通过',
    httpStatus: 0,
  },
  CONSOLE_DIAGNOSIS_CHECK_FAILED: {
    message: '诊断检查未完成，请检查对应服务或报表',
    httpStatus: 0,
  },
  CONSOLE_DIAGNOSIS_INCOMPLETE: {
    message: '诊断数据缺失或无效，暂时无法判定正常',
    httpStatus: 0,
  },
  CONSOLE_REPORT_UNAVAILABLE: {
    message: '报表暂时不可用，请检查数据源或稍后重试',
    httpStatus: 503,
  },
  CONSOLE_REPORT_READ_FAILED: {
    message: '报表内容无法读取，请检查文件或稍后重试',
    httpStatus: 500,
  },
  CONSOLE_USER_DATA_UNAVAILABLE: {
    message: '该部分用户数据暂时无法读取，请稍后重试',
    httpStatus: 0,
  },
  CONSOLE_USER_REALTIME_UNAVAILABLE: {
    message: '用户实时态暂时无法读取，请稍后重试',
    httpStatus: 0,
  },
  CONSOLE_ITEM_QUERY_FAILED: {
    message: '物品信息暂时无法读取，请稍后重试',
    httpStatus: 503,
  },
  JOB_UNKNOWN: {
    message: '作业名称不存在，请检查 --job 或 --targets',
    httpStatus: 0,
  },
  JOB_INVALID_ARGUMENT: {
    message: '作业参数或依赖计划不合法，请检查参数和作业依赖',
    httpStatus: 0,
  },
  JOB_EXECUTION_FAILED: {
    message: '作业执行失败，可能已有部分结果，请核对状态后再重试',
    httpStatus: 0,
  },
  JOB_DEPENDENCY_FAILED: {
    message: '上游作业未完成，本作业尚未执行',
    httpStatus: 0,
  },
  JOB_ALREADY_RUNNING: {
    message: '已有作业管线在执行，请等待完成后重试',
    httpStatus: 0,
  },
  JOB_LOCK_UNAVAILABLE: {
    message: '无法确认作业执行锁，本次未开始执行，请稍后重试',
    httpStatus: 0,
  },
  JOB_STATUS_WRITE_FAILED: {
    message: '作业状态未能确认，业务效果可能已生效，请核对后再重试',
    httpStatus: 0,
  },
  JOB_STATUS_INVALID: {
    message: '作业状态记录无法识别，当前结果未知，请核对执行记录',
    httpStatus: 0,
  },
  CONSOLE_OPS_UNAVAILABLE: {
    message: '运行配置与作业状态暂时无法读取，请稍后重试',
    httpStatus: 0,
  },
  JOB_LOCK_RELEASE_FAILED: {
    message: '作业执行已结束，但锁释放未能确认，请核对锁状态后再运行',
    httpStatus: 0,
  },
} as const;

export type KnownErrorCode = keyof typeof errorCodes;

export const defaultHttpErrorCodes: Readonly<Record<number, KnownErrorCode>> = {
  400: 'BAD_REQUEST',
  401: 'UNAUTHORIZED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  409: 'CONFLICT',
  415: 'UNSUPPORTED_MEDIA_TYPE',
  422: 'UNPROCESSABLE_ENTITY',
  429: 'TOO_MANY_REQUESTS',
  500: 'INTERNAL_SERVER_ERROR',
  502: 'BAD_GATEWAY',
  503: 'SERVICE_UNAVAILABLE',
  504: 'GATEWAY_TIMEOUT',
  408: 'REQUEST_TIMEOUT',
  406: 'NOT_ACCEPTABLE',
  413: 'PAYLOAD_TOO_LARGE',
};

export const httpErrorVariants: Readonly<
  Partial<Record<KnownErrorCode, readonly KnownErrorCode[]>>
> = {
  BAD_REQUEST: ['BUDGET_INVALID_ARGUMENT'],
  CONFLICT: ['BUDGET_COMMAND_CONFLICT'],
  NOT_FOUND: ['BUDGET_RESULT_NOT_OBSERVED'],
  SERVICE_UNAVAILABLE: ['BUDGET_RESULT_UNKNOWN', 'BUDGET_DEPENDENCY_UNAVAILABLE'],
  BAD_GATEWAY: ['BUDGET_RESPONSE_INVALID'],
};
