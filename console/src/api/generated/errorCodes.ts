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
  STREAM_CATEGORY_LOAD_FAILED: {
    message: '物品类目加载失败，实时特征作业未就绪，请检查数据源后重试',
    httpStatus: 0,
  },
  JOB_EMBEDDING_QUOTA_EXHAUSTED: {
    message:
      '向量化配额耗尽，本次回填未完成；已有结果保留，可在配额恢复后使用 --skip-existing 续跑',
    httpStatus: 0,
  },
  JOB_EMBEDDING_PARTIAL_FAILED: {
    message: '部分向量回填失败，已有结果保留，请核对失败项后使用 --skip-existing 续跑',
    httpStatus: 0,
  },
  JOB_EMBEDDING_INVALID_RESULT: {
    message: '向量结果的维度或数值不合法，请检查模型配置与响应',
    httpStatus: 0,
  },
  JOB_REPORT_INPUT_UNAVAILABLE: {
    message: '报表输入目录不存在或不可读取，请检查 --eval-dir 与上游产物',
    httpStatus: 0,
  },
  JOB_REPORT_PARTIAL_FAILED: {
    message: '部分报表未能发布，已发布结果保留，请修复输入或数据源后重试',
    httpStatus: 0,
  },
  JOB_INTERRUPTED: {
    message: '作业被中断，可能已有部分结果，请核对后再运行',
    httpStatus: 0,
  },
  JOB_INPUT_UNAVAILABLE: {
    message: '导入文件不存在或不可读取，请检查输入路径并先生成上游产物',
    httpStatus: 0,
  },
  JOB_INPUT_INVALID: {
    message: '输入数据格式或数值不合法，请检查输入文件；可能已有部分结果',
    httpStatus: 0,
  },
  JOB_IMPORT_PARTIAL_FAILED: {
    message: '部分输入行未能导入，已有结果保留，请修复文件后重跑',
    httpStatus: 0,
  },
  JOB_R9_REQUIRED_GROUP_EMPTY: {
    message: 'R9 要求三组向量均非空，请先生成全部输入产物',
    httpStatus: 0,
  },
  JOB_R9_INVALID_VECTOR: {
    message: 'R9 向量须为64维有限数值并近似L2归一化，请检查训练产物',
    httpStatus: 0,
  },
  JOB_R9_INVALID_IDENTITY: {
    message: 'R9 向量版本为空或同组身份重复，请检查输入文件',
    httpStatus: 0,
  },
  JOB_R9_VERSION_MISMATCH: {
    message: 'R9 三组向量必须使用同一个模型版本，请检查输入产物',
    httpStatus: 0,
  },
  JOB_R9_NO_INPUT: {
    message: '没有可发布的R9向量，本次未发布，请先生成输入产物',
    httpStatus: 0,
  },
  JOB_POPULARITY_FALLBACK: {
    message: '缺少可选评分文件，本次使用0热度导入物品，后续补齐评分后可重跑',
    httpStatus: 0,
  },
  JOB_NO_BEHAVIOR: {
    message: '没有符合条件的行为数据，本次未更新热榜；请检查输入来源与时间范围',
    httpStatus: 0,
  },
  JOB_NO_ITEMS: {
    message: '物品语料为空，本次未更新；请先导入物品或检查目录来源',
    httpStatus: 0,
  },
  JOB_NO_POSITIVE_FEEDBACK: {
    message: '没有符合条件的正反馈，本次未生成结果；请检查行为输入与评分阈值',
    httpStatus: 0,
  },
  JOB_NO_CATEGORY_STATS: {
    message: '没有可关联的类目行为，本次未更新统计；请检查物品类目与行为来源',
    httpStatus: 0,
  },
  JOB_NO_ITEM_VECTORS: {
    message: '没有可用物品向量，本次未更新用户向量；请先完成物品向量回填',
    httpStatus: 0,
  },
  JOB_NO_USER_VECTORS: {
    message: '正反馈未命中可用物品向量，本次未更新用户向量；请检查输入覆盖范围',
    httpStatus: 0,
  },
  JOB_NO_RECALL_OUTPUT: {
    message: '输入未形成可发布的相似召回列表；请检查共现数据与过滤参数',
    httpStatus: 0,
  },
  JOB_NO_RATINGS: {
    message: '没有评分事件，本次未生成样本；请先导入行为或检查输入来源',
    httpStatus: 0,
  },
  JOB_NO_IMPRESSIONS: {
    message: '没有曝光事件，本次未生成样本；请先积累曝光或运行 sim-rec-events',
    httpStatus: 0,
  },
  JOB_NO_AD_CLICKS: {
    message: '没有符合条件的广告点击，本次未生成样本；请检查输入或运行 sim-ad-events',
    httpStatus: 0,
  },
  JOB_NO_MATURE_ASSIGNMENTS: {
    message: '没有已成熟的实验分配，本次未生成样本；请检查 as-of、时间范围与归因窗口',
    httpStatus: 0,
  },
  JOB_SAMPLES_ALL_NEGATIVE: {
    message: '样本已生成，但全部为负标签；请检查曝光后的正反馈与归因窗口再训练',
    httpStatus: 0,
  },
  JOB_SAMPLES_MISSING_ITEM: {
    message: '样本已生成，但已跳过无物品关联的广告点击；请检查广告物品映射及样本覆盖范围',
    httpStatus: 0,
  },
  JOB_NO_SAMPLE_OUTPUT: {
    message: '没有可用样本产出，输出可能仅含表头；请补齐广告物品关联后重新生成',
    httpStatus: 0,
  },
  JOB_GENERATION_INPUT_EMPTY: {
    message: '输入物品为空，未生成新数据；如启用了 clear，旧数据可能已清除，请补齐物品后重试',
    httpStatus: 0,
  },
  JOB_NO_ADS: {
    message: '没有可用广告，未生成模拟事件；请先运行 seed-ads 或检查广告目录',
    httpStatus: 0,
  },
  JOB_SEED_CLEAR_FAILED: {
    message: '清理未完整完成，尚未开始生成广告；部分旧数据可能已清除，请检查存储并核对后重试',
    httpStatus: 0,
  },
  JOB_CREATIVE_INPUT_UNAVAILABLE: {
    message: '创意目录读取失败，未开始生成事件；请检查分片表与连接后重试',
    httpStatus: 0,
  },
  JOB_CREATIVE_ATTRIBUTION_UNAVAILABLE: {
    message: '创意目录为空，模拟事件仍已生成，但不包含创意归因；请补齐创意后再评估创意效果',
    httpStatus: 0,
  },
  JOB_NO_EVALUATION_USERS: {
    message: '没有可评估的测试正例，请检查评分数据、时间切分和历史物品过滤条件',
    httpStatus: 0,
  },
  JOB_NO_EVALUATION_OUTPUT: {
    message: '没有可用的评估结果，请检查召回候选与历史过滤条件；已生成的零用户报告不能用于效果判断',
    httpStatus: 0,
  },
  JOB_CALIBRATION_SAMPLES_INSUFFICIENT: {
    message: '校准样本不足，原曲线保持不变；请增加有效用户或候选，或调整分箱数',
    httpStatus: 0,
  },
  JOB_TUNING_STRATEGY_UNSUPPORTED: {
    message: '调参仅支持 mmoe 或 din 多目标策略，请检查 strategy 参数',
    httpStatus: 0,
  },
  JOB_MODEL_HEADS_UNAVAILABLE: {
    message: '没有可用的 pCTR/pCVR 模型输出，请确认多目标模型已启用且加载成功',
    httpStatus: 0,
  },
  JOB_MODEL_HEADS_PARTIAL: {
    message: '部分候选缺少 pCTR/pCVR，已过滤这些候选；结果仅覆盖可用的模型输出',
    httpStatus: 0,
  },
  JOB_EVALUATION_RESULT_INVALID: {
    message: '模型输出或评估计算包含无效数值，请检查模型及调参范围后重试',
    httpStatus: 0,
  },
  JOB_EVALUATION_PARTIAL_FAILED: {
    message:
      '部分用户评估失败，作业未完整完成；已生成的部分结果可能保留，请修复依赖或模型输出后重试',
    httpStatus: 0,
  },
  JOB_NO_BUCKET_IMPRESSIONS: {
    message: '没有满足条件的分桶曝光数据，未生成报表；请检查曝光采集、时间窗口和筛选条件',
    httpStatus: 0,
  },
  JOB_CUPED_START_REQUIRED: {
    message: '启用 CUPED 时必须提供 since 实验开始时间，请补齐参数后重试',
    httpStatus: 0,
  },
  JOB_DELAY_SAMPLES_INSUFFICIENT: {
    message: '可用转化延迟样本不足，原延迟模型保持不变；请补齐数据或调整采样条件后重试',
    httpStatus: 0,
  },
  JOB_DELAY_MODEL_INVALID: {
    message: '转化延迟均值或模型参数无效，原延迟模型保持不变；请检查延迟样本后重试',
    httpStatus: 0,
  },
  JOB_NO_AUDIENCE_SEEDS: {
    message: '没有种子人群，未更新人群包；请先写入种子用户后重试',
    httpStatus: 0,
  },
  JOB_AUDIENCE_VECTORS_UNAVAILABLE: {
    message:
      '有人群缺少可用种子向量，未完整更新；成功人群可能已更新，缺失人群原包保留，请补齐向量后重试',
    httpStatus: 0,
  },
  JOB_EXPLORATION_EMPTY_SNAPSHOT: {
    message:
      '本次没有广告事件，全局曝光总数已发布为 0；未出现的广告与创意旧统计保留，请确认输入窗口',
    httpStatus: 0,
  },
  JOB_BEHAVIOR_SOURCE_INVALID: {
    message: '行为数据来源不支持，请使用 user_behavior 或 behavior_log',
    httpStatus: 0,
  },
  JOB_ITEM_SOURCE_INVALID: {
    message: '物品数据来源不支持，请使用 item 或 item_local',
    httpStatus: 0,
  },
  JOB_AD_EVENT_SOURCE_INVALID: {
    message: '广告事件来源不支持，请使用 ad_event 或 ad_event_log',
    httpStatus: 0,
  },
  JOB_AD_CALIBRATION_SAMPLES_INSUFFICIENT: {
    message: '广告校准样本不足，原曲线保持不变；请补齐有效曝光与点击数据或调整分箱数',
    httpStatus: 0,
  },
  JOB_AD_CALIBRATION_INPUT_INVALID: {
    message: '广告校准输入或计算结果包含无效数值，原曲线保持不变；请检查预测值与数据后重试',
    httpStatus: 0,
  },
  JOB_NO_AD_REPORT_DATA: {
    message: '没有符合条件的广告曝光数据，本次未生成报表；请检查事件采集和时间条件',
    httpStatus: 0,
  },
  JOB_INVALID_CLICK_COUNT_UNAVAILABLE: {
    message: '无效点击计数无法确认，本次报表未完成；请检查广告事件来源后重试',
    httpStatus: 0,
  },
  JOB_ATTRIBUTION_MODEL_FALLBACK: {
    message: '归因模型不受支持，本次使用 position 模型；请检查 model 参数',
    httpStatus: 0,
  },
  JOB_NO_ATTRIBUTION_CONVERSIONS: {
    message: '没有可归因的转化事件，本次未生成报表；请检查事件采集和时间窗口',
    httpStatus: 0,
  },
  JOB_REPORT_OUTPUT_FAILED: {
    message: '报表写入未完成，可能留下部分文件；请检查输出目录和磁盘后重试',
    httpStatus: 0,
  },
  JOB_NO_AD_QUALITY_INPUT: {
    message: '没有广告质量度所需的曝光数据，原结果保持不变；请检查事件来源和时间窗口',
    httpStatus: 0,
  },
  JOB_NO_AD_QUALITY_OUTPUT: {
    message: '没有广告达到质量度计算门槛，原结果保持不变；请补齐数据或调整最少曝光数',
    httpStatus: 0,
  },
  JOB_AD_QUALITY_INPUT_INVALID: {
    message: '广告质量度输入或计算结果无效，可能已有部分结果更新；请检查统计值和先验参数后重试',
    httpStatus: 0,
  },
  JOB_AD_QUALITY_PUBLICATION_FAILED: {
    message: '广告质量度发布未完成，Redis 或报表可能已部分写入；请核对已有结果并修复依赖后重试',
    httpStatus: 0,
  },
  JOB_DATA_QUALITY_CHECK_FAILED: {
    message: '部分数据质量检查失败，巡检尚未完成；请查看各项状态并修复数据或依赖后重试',
    httpStatus: 0,
  },
  JOB_DATA_QUALITY_INCOMPLETE: {
    message: '数据质量检查缺少有效数据或完整状态，无法确认巡检通过；请补齐数据后重新运行',
    httpStatus: 0,
  },
  JOB_DATA_QUALITY_BREACHED: {
    message: '数据质量巡检已完成，发现指标越阈值；请检查报表中的对应指标',
    httpStatus: 0,
  },
  JOB_NO_OCPC_TARGET: {
    message: '未找到可校准的 oCPC 广告目标，请补齐广告配置后重试',
    httpStatus: 0,
  },
  JOB_OCPC_DELAY_MISSING: {
    message: '延迟转化模型缺失，本次使用原始转化计数；可先生成延迟模型再重新校准',
    httpStatus: 0,
  },
  JOB_OCPC_INVALID_DATA: {
    message: 'oCPC 校准数据或模型无效；部分广告主系数可能已更新，请修复数据后重新运行',
    httpStatus: 0,
  },
  JOB_OCPC_FAILED: {
    message: 'oCPC 校准未完成；部分广告主系数可能已更新，请检查数据源和发布状态后重试',
    httpStatus: 0,
  },
  JOB_MULTIMODAL_NO_INPUT: {
    message: '没有可融合的多模态输入，未更新向量；请检查海报目录与文本向量',
    httpStatus: 0,
  },
  JOB_MULTIMODAL_UNSUPPORTED: {
    message:
      '当前模型不支持所需的图像向量，回填未完成；部分物品可能已更新，请检查模型后核对回填范围',
    httpStatus: 0,
  },
  JOB_MULTIMODAL_PARTIAL_FAILED: {
    message: '多模态回填未完整完成，部分物品可能已更新；请检查图像、向量结果及数据源后核对回填范围',
    httpStatus: 0,
  },
  JOB_BANDIT_NO_INPUT: {
    message: '缺少可用推荐曝光，原 Bandit 模型保持不变；请补齐曝光数据后重试',
    httpStatus: 0,
  },
  JOB_BANDIT_MODEL_FALLBACK: {
    message: '增量 Bandit 模型缺失或不兼容，本次从零累计；请核对输入窗口与模型版本',
    httpStatus: 0,
  },
  JOB_BANDIT_MODEL_INVALID: {
    message: 'Bandit 特征或模型包含无效数值，未发布新模型；请检查特征和模型状态',
    httpStatus: 0,
  },
  JOB_BANDIT_FAILED: {
    message: 'Bandit 作业未完成，请检查依赖并核对模型发布状态后重试',
    httpStatus: 0,
  },
  JOB_FTRL_NO_INPUT: {
    message: 'FTRL 训练物品为空或正样本少于100条，原模型保持不变；请补齐训练输入',
    httpStatus: 0,
  },
  JOB_FTRL_MODEL_FALLBACK: {
    message: '增量 FTRL 状态缺失或无效，本次从零训练；请核对输入窗口与模型状态',
    httpStatus: 0,
  },
  JOB_FTRL_MODEL_INVALID: {
    message: 'FTRL 训练状态或权重包含无效数值，未发布新模型；请检查训练参数与状态',
    httpStatus: 0,
  },
  JOB_FTRL_FAILED: {
    message: 'FTRL 作业未完成，权重和续训状态可能仅部分更新；请核对两个发布结果后重试',
    httpStatus: 0,
  },
  UI_RENDER_FAILED: {
    message: '页面渲染失败，请重试或切换到其他页面',
    httpStatus: 0,
  },
  CLIPBOARD_UNAVAILABLE: {
    message: '复制失败，请检查剪贴板权限或手动选择内容复制',
    httpStatus: 0,
  },
  SEARCH_QUERY_REQUIRED: {
    message: '请输入搜索词后重试',
    httpStatus: 0,
  },
  OIDC_TENANT_REQUIRED: {
    message: '请输入租户',
    httpStatus: 0,
  },
  OIDC_CONFIGURATION_INVALID: {
    message: 'OIDC 租户或 clientId 配置不完整或不一致，请联系管理员',
    httpStatus: 0,
  },
  OIDC_TENANT_NOT_ALLOWED: {
    message: '当前租户尚未开放，请使用系统配置的租户',
    httpStatus: 0,
  },
  OIDC_TOKEN_INVALID: {
    message: '登录凭证无法解析，请重新登录',
    httpStatus: 0,
  },
  AUTH_MODE_UNSUPPORTED: {
    message: '当前认证模式不支持 legacy 登录，请使用统一登录',
    httpStatus: 0,
  },
  OIDC_STATE_INVALID: {
    message: '登录会话不匹配，链接可能已过期或被重放，请重新登录',
    httpStatus: 0,
  },
  OIDC_CODE_INVALID: {
    message: '授权码已失效或已使用，请重新登录',
    httpStatus: 0,
  },
  OIDC_LOGIN_FAILED: {
    message: '统一登录未完成，请重新登录或检查认证服务',
    httpStatus: 0,
  },
  DELIVERY_RESPONSE_MISMATCH: {
    message: '交付响应与原请求身份不匹配，请保留原 operationId 查询',
    httpStatus: 0,
  },
  DELIVERY_STORAGE_UNAVAILABLE: {
    message: '交付无法写入会话存储，请保留原 operationId；刷新后无法自动恢复',
    httpStatus: 0,
  },
  EXPERIMENT_STORE_UNAVAILABLE: {
    message: '实验配置存储不可用，本次未执行写入，请检查存储配置后重试',
    httpStatus: 0,
  },
  EXPERIMENT_WRITE_UNCONFIRMED: {
    message: '实验配置写入结果未能确认，可能已有部分配置生效，请刷新并核对实际配置',
    httpStatus: 0,
  },
  ADVERTISER_RESPONSE_INVALID: {
    message: '广告管理数据不完整或标识无法精确读取，请刷新或联系管理员',
    httpStatus: 0,
  },
  SANDBOX_ACTION_RESPONSE_INVALID: {
    message: '事件或结算响应与原请求不一致，请保留原标识查询',
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
