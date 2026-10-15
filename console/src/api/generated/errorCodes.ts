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
