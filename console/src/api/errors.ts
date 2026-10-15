import { AxiosError, isAxiosError, isCancel } from 'axios';
import {
  defaultHttpErrorCodes,
  errorCodes,
  httpErrorVariants,
  type KnownErrorCode,
} from './generated/errorCodes';

/** 前端主动拒绝也必须选择目录码；构造入口不接收服务端消息或任意展示文本。 */
export class ClientError extends Error {
  constructor(public readonly code: KnownErrorCode) {
    super(errorCodes[code].message);
    this.name = 'ClientError';
  }
}

export interface ApiErrorInfo {
  status?: number;
  code: string;
  /** 页面直接展示此字段，确保既有调用点也能看到错误码。 */
  message: string;
  traceId?: string;
  cancelled: boolean;
  url?: string;
}

/** 本地提示同样只能选择目录文案，不能将任意异常详情拼接给用户。 */
export function formatError(code: KnownErrorCode): string {
  return `[${code}] ${errorCodes[code].message}`;
}

function isKnownCode(code: string): code is KnownErrorCode {
  return Object.prototype.hasOwnProperty.call(errorCodes, code);
}

function record(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

// HTTP协议中的错误范围；来自不可信响应的状态也必须校验，成功状态不能映射成错误事实。
const HTTP_ERROR_STATUS_MIN = 400;
const HTTP_ERROR_STATUS_MAX = 599;

function httpErrorStatus(value: unknown): number | undefined {
  return typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= HTTP_ERROR_STATUS_MIN &&
    value <= HTTP_ERROR_STATUS_MAX
    ? value
    : undefined;
}

/** 兼容旧后端和未知新code；响应中的message/detail/HTML/SQL永远不是可信展示来源。 */
export function toApiError(error: unknown): ApiErrorInfo {
  if (error instanceof ClientError) {
    return { code: error.code, message: formatError(error.code), cancelled: false };
  }
  let code: KnownErrorCode = 'UNKNOWN_ERROR';
  const aborted =
    ((typeof DOMException !== 'undefined' && error instanceof DOMException) ||
      error instanceof Error) &&
    error.name === 'AbortError';
  if (isCancel(error) || aborted) {
    return {
      code: 'REQUEST_CANCELLED',
      message: formatError('REQUEST_CANCELLED'),
      cancelled: true,
    };
  }
  if (!isAxiosError(error)) return { code, message: formatError(code), cancelled: false };

  const status = httpErrorStatus(error.response?.status);
  if (status !== undefined) {
    code = defaultHttpErrorCodes[status] ?? 'UNKNOWN_ERROR';
  } else if (
    !error.response &&
    (error.code === AxiosError.ECONNABORTED || error.code === AxiosError.ETIMEDOUT)
  ) {
    code = 'REQUEST_TIMEOUT';
  } else if (!error.response && (error.code === AxiosError.ERR_NETWORK || error.request != null)) {
    code = 'NETWORK_ERROR';
  }

  const body = record(error.response?.data);
  const wireCode = body?.code;
  let displayCode: string = code;
  // 只接受受限格式的新码；已知码若与真实HTTP状态冲突，则采用真实状态的提示。
  if (
    status !== undefined &&
    typeof wireCode === 'string' &&
    /^[A-Z][A-Z0-9_]{0,63}$/.test(wireCode)
  ) {
    if (!isKnownCode(wireCode)) displayCode = wireCode;
    else if (errorCodes[wireCode].httpStatus === status) displayCode = code = wireCode;
  }
  // 响应文本只作目录候选的精确选择器；展示的仍是本地固定文本，不拼接任何远端内容。
  let message: string = errorCodes[code].message;
  if (
    wireCode === code &&
    status === errorCodes[code].httpStatus &&
    typeof body?.message === 'string'
  ) {
    const variant = httpErrorVariants[code]?.find(
      (candidate) =>
        errorCodes[candidate].httpStatus === status &&
        errorCodes[candidate].message === body.message,
    );
    if (variant) message = errorCodes[variant].message;
  }
  const rawTraceId = body?.traceId;
  const traceId =
    typeof rawTraceId === 'string' && /^[a-zA-Z0-9_-]{1,128}$/.test(rawTraceId)
      ? rawTraceId
      : undefined;
  // 查询参数可能含凭据或业务内容；保留路径用于排查，不携带查询/片段/URL用户信息。
  let url: string | undefined;
  if (error.config?.url) {
    try {
      url = new URL(error.config.url, 'https://recsys.invalid').pathname;
    } catch {
      /* 非法URL不展示。 */
    }
  }
  return {
    status,
    code: displayCode,
    message: `[${displayCode}] ${message}`,
    traceId,
    cancelled: false,
    url,
  };
}
