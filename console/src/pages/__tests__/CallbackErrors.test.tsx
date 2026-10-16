import { StrictMode } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ complete: vi.fn() }));
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ completeSignIn: mocks.complete }) }));
import CallbackPage from '../CallbackPage';
import { ClientError, formatError } from '../../api/errors';
import type { KnownErrorCode } from '../../api/generated/errorCodes';
let sequence = 0;
beforeEach(() => {
  mocks.complete.mockReset();
  window.history.replaceState({}, '', `/callback?state=test-${++sequence}`);
});
it.each<[unknown, KnownErrorCode]>([
  [new Error('No matching state PRIVATE_TOKEN'), 'OIDC_STATE_INVALID'],
  [new Error('invalid grant PRIVATE_TOKEN'), 'OIDC_CODE_INVALID'],
  [new Error('PRIVATE_UNKNOWN_DETAIL'), 'OIDC_LOGIN_FAILED'],
  [{ raw: 'PRIVATE_OBJECT' }, 'OIDC_LOGIN_FAILED'],
  [new ClientError('OIDC_TOKEN_INVALID'), 'OIDC_TOKEN_INVALID'],
])('登录回调安全分类并保留重试入口：%j', async (error, code) => {
  mocks.complete.mockRejectedValue(error);
  render(
    <StrictMode>
      <MemoryRouter initialEntries={['/callback']}>
        <Routes>
          <Route path="/callback" element={<CallbackPage />} />
          <Route path="/login" element={<div>重新登录页面</div>} />
        </Routes>
      </MemoryRouter>
    </StrictMode>,
  );
  expect(await screen.findByText(formatError(code))).toBeInTheDocument();
  expect(document.body.textContent).not.toContain('PRIVATE_');
  expect(mocks.complete).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '重新登录' }));
  expect(await screen.findByText('重新登录页面')).toBeInTheDocument();
});
