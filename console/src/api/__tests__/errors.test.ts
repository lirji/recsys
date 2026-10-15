import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import { describe, expect, it } from 'vitest';
import { formatError, toApiError } from '../errors';

function responseError(status: number, data: unknown) {
  const config = {
    headers: new AxiosHeaders(),
    url: 'https://secret:password@example.test/api/action?token=private#details',
  };
  return new AxiosError(
    'SQL password=private',
    AxiosError.ERR_BAD_RESPONSE,
    config,
    {},
    {
      status,
      statusText: 'private',
      data,
      headers: {},
      config,
    },
  );
}

describe('统一安全错误展示', () => {
  it.each([200, 399, 600, 400.5, Number.NaN])('非法错误状态%s不伪造网络失败', (status) => {
    const error = toApiError(responseError(status, { code: 'BAD_REQUEST' }));
    expect(error.status).toBeUndefined();
    expect(error.message).toBe(formatError('UNKNOWN_ERROR'));
  });
  it('保留业务码、traceId和原ID恢复提示，不采用服务端任意文案', () => {
    expect(
      toApiError(
        responseError(503, { code: 'DELIVERY_503', message: 'secret SQL', traceId: 'abc-123' }),
      ),
    ).toEqual({
      status: 503,
      code: 'DELIVERY_503',
      message: '[DELIVERY_503] 交付结果未知，请保留原 operationId 查询或重试',
      traceId: 'abc-123',
      cancelled: false,
      url: '/api/action',
    });
  });

  it.each([
    'CONVERSION',
    'NATURAL_RECEIPT',
    'CLICK_RECEIPT',
    'CONVERSION_RECEIPT',
    'RECEIPT',
    'DELIVERY',
    'NATURAL_DELIVERY',
    'FEED_DELIVERY',
  ])('保留%s的结果未知与查询恢复语义', (family) => {
    const error = toApiError(responseError(503, { code: `${family}_503` }));
    expect(error.message).toContain(`[${family}_503]`);
    expect(error.message).toContain('结果未知');
    expect(error.message).toContain('查询');
  });

  it.each([
    undefined,
    null,
    '',
    '<html>secret SQL</html>',
    ['secret'],
    { message: 'password=private' },
    { error: 'secret', stack: 'SQL' },
  ])('旧版或非法响应体不泄漏内容: %j', (data) => {
    expect(toApiError(responseError(500, data)).message).toBe(
      '[INTERNAL_SERVER_ERROR] 服务器内部错误',
    );
  });

  it('未知新码显示受限code并按HTTP状态安全回退，兼容新旧版本共存', () => {
    expect(
      toApiError(responseError(409, { code: 'FUTURE_CONFLICT', message: 'secret' })).message,
    ).toBe('[FUTURE_CONFLICT] 请求与当前状态冲突，请刷新后重试');
  });

  it.each(['<script>', 'A'.repeat(65), '__proto__', 'BAD_REQUEST\nsecret', 500, null])(
    '非法code不进入界面: %j',
    (code) => {
      expect(toApiError(responseError(403, { code })).message).toBe(formatError('FORBIDDEN'));
    },
  );

  it('已知code的状态不匹配时以真实HTTP状态为准', () => {
    expect(toApiError(responseError(503, { code: 'BAD_REQUEST' })).message).toBe(
      formatError('SERVICE_UNAVAILABLE'),
    );
  });

  it.each(['a'.repeat(129), 'abc\nprivate', '<script>', { value: 'secret' }])(
    '丢弃不受限traceId: %j',
    (traceId) => {
      expect(toApiError(responseError(500, { traceId })).traceId).toBeUndefined();
    },
  );

  it.each([AxiosError.ECONNABORTED, AxiosError.ETIMEDOUT])('超时%s提醒查询原操作结果', (code) => {
    expect(toApiError(new AxiosError('secret', code)).message).toBe(formatError('REQUEST_TIMEOUT'));
  });

  it('网络失败和取消保持不同语义，不修改原错误或触发重试', () => {
    expect(toApiError(new AxiosError('secret', AxiosError.ERR_NETWORK)).message).toBe(
      formatError('NETWORK_ERROR'),
    );
    const cancelled = new CanceledError('身份已变化，secret');
    expect(toApiError(cancelled)).toEqual({
      code: 'REQUEST_CANCELLED',
      message: formatError('REQUEST_CANCELLED'),
      cancelled: true,
    });
    expect(cancelled.message).toContain('身份已变化');
    expect(toApiError(new DOMException('secret', 'AbortError')).cancelled).toBe(true);
  });

  it.each([new Error('secret SQL'), 'secret', { message: 'secret' }, null])(
    '未知本地错误只使用安全兜底',
    (error) => {
      expect(toApiError(error).message).toBe(formatError('UNKNOWN_ERROR'));
    },
  );
});
