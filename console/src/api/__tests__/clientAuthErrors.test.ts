import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AxiosError, CanceledError, type AxiosAdapter } from 'axios';
import { http } from '../client';
import { showApiError } from '../errors';

const auth = vi.hoisted(() => ({ renew: vi.fn(), purge: vi.fn(), redirect: vi.fn() }));
vi.mock('../../config/auth', () => ({ AUTH_MODE: 'oidc' }));
vi.mock('../../auth/oidc', () => ({
  resolveToken: async () => 'fixture-token',
  silentRenew: () => auth.renew(),
  purgeOidcSession: () => auth.purge(),
}));
vi.mock('../nav', () => ({ redirectToLogin: () => auth.redirect() }));

const originalAdapter = http.defaults.adapter;
beforeEach(() => {
  vi.clearAllMocks();
});
afterEach(() => {
  http.defaults.adapter = originalAdapter;
});

it.each([true, false])(
  '续期失败或再次401只清会话一次，不无限重发：renewFails=%s',
  async (renewFails) => {
    if (renewFails) auth.renew.mockRejectedValue(new Error('PRIVATE_RENEWAL'));
    else auth.renew.mockResolvedValue({ access_token: 'renewed' });
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      throw new AxiosError('PRIVATE_HTTP', undefined, config, undefined, { status: 401 } as never);
    });
    http.defaults.adapter = adapter;
    const display = vi.fn();
    await http.get('/api/private-fixture').catch((error) => showApiError(error, display));
    expect(adapter).toHaveBeenCalledTimes(renewFails ? 1 : 2);
    expect(auth.renew).toHaveBeenCalledTimes(1);
    expect(auth.purge).toHaveBeenCalledTimes(1);
    expect(auth.redirect).toHaveBeenCalledTimes(1);
    expect(display).toHaveBeenCalledOnce();
    expect(display.mock.calls[0][0]).toContain('[UNAUTHORIZED]');
    expect(display.mock.calls[0][0]).not.toContain('PRIVATE');
  },
);

it('取消经完整HTTP拦截链返回后没有错误通知、续期或清会话', async () => {
  http.defaults.adapter = async () => {
    throw new CanceledError('PRIVATE_CANCEL');
  };
  const display = vi.fn();
  await http.get('/api/cancelled-fixture').catch((error) => showApiError(error, display));
  expect(display).not.toHaveBeenCalled();
  expect(auth.renew).not.toHaveBeenCalled();
  expect(auth.purge).not.toHaveBeenCalled();
  expect(auth.redirect).not.toHaveBeenCalled();
});
