import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { getAdvertiserPermissions } from '../../api/advertiser';
import { useAdvertiserPermissions } from '../useAdvertiserPermissions';
import type { AuthUser } from '../../api/auth';
import type { AdvertiserPermissions } from '../../api/types';

const state = vi.hoisted(() => ({
  username: 'first',
  switching: false,
  roles: ['ADVERTISER'] as AuthUser['roles'],
}));
vi.mock('../useAuth', () => ({
  useAuth: () => ({
    ready: true,
    switching: state.switching,
    user: { username: state.username, roles: state.roles },
  }),
}));
vi.mock('../../api/advertiser', () => ({ getAdvertiserPermissions: vi.fn() }));
const allowed: AdvertiserPermissions = {
  createAdvertiser: true,
  reviewAds: true,
  editAds: true,
  manageAdvertiser: true,
};
const denied: AdvertiserPermissions = {
  createAdvertiser: false,
  reviewAds: false,
  editAds: false,
  manageAdvertiser: false,
};
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
let client: QueryClient;
beforeEach(() => {
  vi.resetAllMocks();
  state.username = 'first';
  state.switching = false;
  state.roles = ['ADVERTISER'];
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
});

it('切换身份立即禁写，迟到的上一身份响应不能给新身份授权', async () => {
  let oldResolve!: (v: AdvertiserPermissions) => void;
  let currentResolve!: (v: AdvertiserPermissions) => void;
  vi.mocked(getAdvertiserPermissions)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          oldResolve = resolve;
        }),
    )
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          currentResolve = resolve;
        }),
    );
  const hook = renderHook(() => useAdvertiserPermissions('1'), { wrapper });
  expect(hook.result.current.can('editAds')).toBe(false);
  state.switching = true;
  hook.rerender();
  expect(hook.result.current.can('reviewAds')).toBe(false);
  state.username = 'second';
  state.switching = false;
  hook.rerender();
  await act(async () => {
    oldResolve(allowed);
  });
  expect(hook.result.current.can('editAds')).toBe(false);
  await act(async () => {
    currentResolve(denied);
  });
  await waitFor(() => expect(hook.result.current.query.isPending).toBe(false));
  expect(hook.result.current.can('editAds')).toBe(false);
});

it('对象切换及重新读取权限期间不沿用旧许可，撤销后保持禁写', async () => {
  let finish!: (v: AdvertiserPermissions) => void;
  vi.mocked(getAdvertiserPermissions)
    .mockResolvedValueOnce(allowed)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
  const hook = renderHook(({ id }) => useAdvertiserPermissions(id), {
    wrapper,
    initialProps: { id: '1' },
  });
  await waitFor(() => expect(hook.result.current.can('editAds')).toBe(true));
  hook.rerender({ id: '2' });
  expect(hook.result.current.can('editAds')).toBe(false);
  await act(async () => {
    finish(denied);
  });
  await waitFor(() => expect(hook.result.current.query.isPending).toBe(false));
  expect(hook.result.current.can('editAds')).toBe(false);
});
