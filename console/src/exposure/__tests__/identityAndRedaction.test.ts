import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import { http } from '../../api/client';
import { exposureAuthScope, exposureEpoch, revokeExposureSession } from '../identity';
import { redactExposureSecrets } from '../redact';

const auth = vi.hoisted(() => ({ token: '', renew: vi.fn() }));
vi.mock('../../config/auth', () => ({ AUTH_MODE: 'oidc' }));
vi.mock('../../auth/oidc', () => ({
  resolveToken: async () => auth.token,
  silentRenew: () => auth.renew(),
  purgeOidcSession: vi.fn(),
}));
vi.mock('../../api/nav', () => ({ redirectToLogin: vi.fn() }));
const token = (subject: string) =>
  'header.' +
  btoa(JSON.stringify({ iss: 'test-issuer', sub: subject, tenant_id: 'sandbox' })) +
  '.signature';
const oldAdapter = http.defaults.adapter;
beforeEach(() => {
  auth.token = token('admin');
  auth.renew.mockReset();
});
afterEach(() => {
  http.defaults.adapter = oldAdapter;
});
it('发送前解析到的 Bearer 主体与预期不同就取消，不碰网络适配器', async () => {
  const identity = {
    expectedExposureScope: exposureAuthScope(auth.token)!,
    expectedExposureEpoch: exposureEpoch(),
  };
  auth.token = token('another-admin');
  const adapter = vi.fn();
  http.defaults.adapter = adapter;
  await expect(http.put('/api/sandbox/test', {}, identity)).rejects.toThrow('身份已变化');
  expect(adapter).not.toHaveBeenCalled();
});
it('相同主体续期仍只重试一次，换主体的续期不能重发旧回执', async () => {
  const identity = {
    expectedExposureScope: exposureAuthScope(auth.token)!,
    expectedExposureEpoch: exposureEpoch(),
  };
  const adapter = vi.fn(async (config) => {
    if (!config._retried)
      throw new AxiosError('401', undefined, config, undefined, { status: 401 } as never);
    return { data: 'ack', status: 200, statusText: 'OK', headers: {}, config };
  });
  http.defaults.adapter = adapter;
  auth.renew.mockImplementation(async () => ({ access_token: auth.token }));
  await expect(http.put('/api/sandbox/test', {}, identity)).resolves.toHaveProperty('data', 'ack');
  expect(adapter).toHaveBeenCalledTimes(2);
  adapter.mockClear();
  auth.renew.mockImplementation(async () => {
    auth.token = token('other');
    return { access_token: auth.token };
  });
  await expect(http.put('/api/sandbox/test', {}, identity)).rejects.toThrow('身份已变化');
  expect(adapter).toHaveBeenCalledTimes(1);
});
it('退出再登录同主体也不能让旧 epoch 发起请求', async () => {
  const identity = {
    expectedExposureScope: exposureAuthScope(auth.token)!,
    expectedExposureEpoch: exposureEpoch(),
  };
  revokeExposureSession();
  const adapter = vi.fn();
  http.defaults.adapter = adapter;
  await expect(http.get('/api/sandbox/test', identity)).rejects.toThrow('身份已变化');
  expect(adapter).not.toHaveBeenCalled();
});
it('脱敏递归副本覆盖历史/原始 JSON 使用的票据字段，不修改活跃响应', () => {
  const response = {
    operationId: 'id',
    items: [{ preparation: { qualificationToken: 'secret', amount: '0.000001' } }],
    nested: { offerToken: 'secret-offer', access_token: 'bearer', Authorization: 'Bearer xyz' },
  };
  const redacted = redactExposureSecrets(response);
  expect(JSON.stringify(redacted)).not.toContain('secret');
  expect(redacted.nested.Authorization).toBe('[REDACTED]');
  expect(response.items[0].preparation.qualificationToken).toBe('secret');
  expect(redacted.items[0].preparation.amount).toBe('0.000001');
});
