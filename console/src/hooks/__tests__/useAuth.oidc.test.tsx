import { describe, expect, it, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

// oidc 模式下的 AuthProvider 行为:回调收口/坏 token 拒建会话/登出兜底/bootstrap 不触发静默流。
const mocks = vi.hoisted(() => {
  const um = {
    getUser: vi.fn(),
    signinRedirect: vi.fn(),
    signinRedirectCallback: vi.fn(),
    signinSilent: vi.fn(),
    signoutRedirect: vi.fn(),
    removeUser: vi.fn(),
    events: {
      addUserLoaded: vi.fn(),
      addUserUnloaded: vi.fn(),
      addSilentRenewError: vi.fn(),
      removeUserLoaded: vi.fn(),
      removeUserUnloaded: vi.fn(),
      removeSilentRenewError: vi.fn(),
    },
  };
  return { um, purge: vi.fn(), redirect: vi.fn() };
});

vi.mock('../../config/auth', () => ({
  AUTH_MODE: 'oidc',
  CASDOOR: { issuer: 'http://localhost:8000', clientId: 'c', scope: 'openid' },
}));

vi.mock('../../auth/oidc', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../../auth/oidc')>();
  return {
    ...orig,
    getUserManager: () => Promise.resolve(mocks.um),
    purgeOidcSession: mocks.purge,
  };
});

vi.mock('../../api/nav', () => ({
  redirectToLogin: mocks.redirect,
  setRedirectToLogin: vi.fn(),
}));

import { AuthProvider, useAuth } from '../useAuth';

function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = btoa(JSON.stringify(payload))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `h.${b64}.sig`;
}

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <AuthProvider>{children}</AuthProvider>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  mocks.um.getUser.mockResolvedValue(null);
});

describe('AuthProvider(oidc 模式)', () => {
  it('bootstrap:无存储会话 → 未登录,且不触发静默续期(免无谓 Casdoor 往返)', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(mocks.um.getUser).toHaveBeenCalled());
    expect(result.current.user).toBeNull();
    expect(mocks.um.signinSilent).not.toHaveBeenCalled();
    expect(result.current.mode).toBe('oidc');
  });

  it('completeSignIn:兑换成功 → user 先落状态再返回消毒后的 returnTo(防守卫弹回竞态)', async () => {
    mocks.um.signinRedirectCallback.mockResolvedValue({
      access_token: fakeJwt({ name: 'rowner1', groups: ['recsys/advertisers'] }),
      state: { returnTo: '/advertiser/5?tab=ads' },
    });
    const { result } = renderHook(() => useAuth(), { wrapper });

    let returnTo = '';
    await act(async () => {
      returnTo = await result.current.completeSignIn();
    });

    expect(returnTo).toBe('/advertiser/5?tab=ads');
    expect(result.current.user).toEqual({ username: 'rowner1', roles: ['ADVERTISER'] });
  });

  it('completeSignIn:外站 returnTo 被消毒 → 回默认页', async () => {
    mocks.um.signinRedirectCallback.mockResolvedValue({
      access_token: fakeJwt({ name: 'x', groups: [] }),
      state: { returnTo: '//evil.com' },
    });
    const { result } = renderHook(() => useAuth(), { wrapper });
    let returnTo = '';
    await act(async () => {
      returnTo = await result.current.completeSignIn();
    });
    expect(returnTo).toBe('/overview');
  });

  it('completeSignIn:坏 token → 抛错 + 清刚写入的会话 + 不建会话(幽灵登录防护)', async () => {
    mocks.um.signinRedirectCallback.mockResolvedValue({ access_token: 'garbage', state: {} });
    const { result } = renderHook(() => useAuth(), { wrapper });

    // 注意用 async 包裹逐 await(act(promise)+.rejects 会在 purge 落账前观察到拒绝,断言竞态)。
    let caught: unknown = null;
    await act(async () => {
      try {
        await result.current.completeSignIn();
      } catch (e) {
        caught = e;
      }
    });
    expect(caught).toBeInstanceOf(Error);
    expect(String(caught)).toMatch(/无法解析/);
    expect(mocks.purge).toHaveBeenCalled();
    expect(result.current.user).toBeNull();
  });

  it('logout:signoutRedirect 失败 → 兜底清会话 + 经 nav holder 跳登录(硬刷新不复活死会话)', async () => {
    mocks.um.signoutRedirect.mockRejectedValue(new Error('casdoor down'));
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.logout());

    await waitFor(() => expect(mocks.purge).toHaveBeenCalled());
    expect(mocks.redirect).toHaveBeenCalled();
    expect(result.current.user).toBeNull();
  });

  it('signIn(legacy 专用)在 oidc 模式下直接抛(契约防误用);switchUser no-op', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    await expect(result.current.signIn('a', 'b')).rejects.toThrow(/legacy/);
    await act(() => result.current.switchUser('admin'));
    expect(result.current.user).toBeNull();
  });
});
