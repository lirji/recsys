import { StrictMode } from 'react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  signInOidc: vi.fn(),
  signIn: vi.fn(),
  authMode: 'oidc' as 'legacy' | 'oidc',
}));

vi.mock('../../hooks/useAuth', () => ({
  useAuth: () => ({ signInOidc: mocks.signInOidc, signIn: mocks.signIn }),
}));
vi.mock('../../config/auth', () => ({
  get AUTH_MODE() {
    return mocks.authMode;
  },
  CASDOOR: {
    issuer: 'http://localhost:8000',
    clientId: 'ragshared0client00000001-org-recsys',
    organization: 'recsys',
    scope: 'openid profile offline_access',
  },
}));

import LoginPage from '../LoginPage';

beforeEach(() => {
  mocks.signInOidc.mockReset().mockResolvedValue(undefined);
  mocks.signIn.mockReset();
  mocks.authMode = 'oidc';
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
});

describe('LoginPage portal auto-login', () => {
  it('StrictMode 下合法 portal URL 只发起一次 OIDC，并保留安全深链', async () => {
    render(
      <StrictMode>
        <AntdApp>
          <MemoryRouter initialEntries={['/login?source=portal&auto=1&returnTo=%2Fadvertiser%2F5']}>
            <LoginPage />
          </MemoryRouter>
        </AntdApp>
      </StrictMode>,
    );

    await waitFor(() => expect(mocks.signInOidc).toHaveBeenCalledTimes(1));
    expect(mocks.signInOidc).toHaveBeenCalledWith('/advertiser/5');
  });

  it('普通登录页不自动发起 OIDC', async () => {
    render(
      <AntdApp>
        <MemoryRouter initialEntries={['/login']}>
          <LoginPage />
        </MemoryRouter>
      </AntdApp>,
    );
    await Promise.resolve();
    expect(mocks.signInOidc).not.toHaveBeenCalled();
  });

  it('普通 OIDC 登录页先校验租户，未知租户不跳 Casdoor', async () => {
    render(
      <AntdApp>
        <MemoryRouter initialEntries={['/login?returnTo=%2Fadvertiser%2F5']}>
          <LoginPage />
        </MemoryRouter>
      </AntdApp>,
    );

    fireEvent.change(screen.getByLabelText('租户（Casdoor Organization）'), {
      target: { value: 'acme' },
    });
    fireEvent.click(screen.getByRole('button', { name: /使用统一身份登录/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('当前推荐系统只开放租户 recsys');
    expect(mocks.signInOidc).not.toHaveBeenCalled();
  });

  it('安全租户提交后从目标 origin 发起 OIDC，并保留正式入口深链', async () => {
    render(
      <AntdApp>
        <MemoryRouter initialEntries={['/login?returnTo=%2Fadvertiser%2F5']}>
          <LoginPage />
        </MemoryRouter>
      </AntdApp>,
    );

    expect(screen.getByLabelText('租户（Casdoor Organization）')).toHaveValue('recsys');
    fireEvent.click(screen.getByRole('button', { name: /使用统一身份登录/ }));

    await waitFor(() => expect(mocks.signInOidc).toHaveBeenCalledTimes(1));
    expect(mocks.signInOidc).toHaveBeenCalledWith('/advertiser/5');
  });

  it('legacy 模式即使带合法 portal 参数也不自动发起 OIDC', async () => {
    mocks.authMode = 'legacy';
    render(
      <AntdApp>
        <MemoryRouter initialEntries={['/login?source=portal&auto=1&returnTo=%2Foverview']}>
          <LoginPage />
        </MemoryRouter>
      </AntdApp>,
    );
    await Promise.resolve();
    expect(mocks.signInOidc).not.toHaveBeenCalled();
  });
});
