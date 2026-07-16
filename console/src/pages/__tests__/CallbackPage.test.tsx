import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StrictMode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// 回调页:StrictMode 双挂载只兑换一次 code;成功导航 returnTo;失败展示错误态。
const mocks = vi.hoisted(() => ({ completeSignIn: vi.fn() }));

vi.mock('../../hooks/useAuth', () => ({
  useAuth: () => ({ completeSignIn: mocks.completeSignIn }),
}));

import CallbackPage from '../CallbackPage';

function renderCallback(search: string) {
  // 模块级单飞按 location.search 去重:每个用例用不同 search,互不串。
  window.history.replaceState({}, '', `/callback${search}`);
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={[`/callback${search}`]}>
        <Routes>
          <Route path="/callback" element={<CallbackPage />} />
          <Route path="/advertiser/5" element={<div>ADV-5</div>} />
          <Route path="/overview" element={<div>OVERVIEW</div>} />
          <Route path="/login" element={<div>LOGIN</div>} />
        </Routes>
      </MemoryRouter>
    </StrictMode>,
  );
}

beforeEach(() => {
  mocks.completeSignIn.mockReset();
});

describe('CallbackPage', () => {
  it('StrictMode 双挂载只兑换一次 code,成功后导航到 returnTo', async () => {
    mocks.completeSignIn.mockResolvedValue('/advertiser/5');
    renderCallback('?code=c1&state=s1');

    expect(await screen.findByText('ADV-5')).toBeInTheDocument();
    // 关键断言:effect 因 StrictMode 跑了两次,但 code 兑换(completeSignIn)只发生一次。
    expect(mocks.completeSignIn).toHaveBeenCalledTimes(1);
  });

  it('兑换失败 → 错误态 + 重新登录出口,不导航', async () => {
    mocks.completeSignIn.mockRejectedValue(new Error('No matching state found in storage'));
    renderCallback('?code=c2&state=s2');

    expect(await screen.findByText('登录失败')).toBeInTheDocument();
    expect(screen.getByText(/请重新登录/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重新登录/ })).toBeInTheDocument();
  });
});
