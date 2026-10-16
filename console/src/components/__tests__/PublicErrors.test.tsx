import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ErrorBoundary from '../ErrorBoundary';
import AbSignificancePanel from '../experiment/AbSignificancePanel';
import { formatError } from '../../api/errors';

function Fragile({ broken }: { broken: boolean }) {
  if (broken) throw new Error('PRIVATE_SQL_TOKEN_AND_STACK');
  return <div>页面恢复</div>;
}
beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}));
afterEach(() => vi.restoreAllMocks());
describe('公开组件错误', () => {
  it('渲染失败只显示目录，重试和路由变化均可恢复', () => {
    const { rerender } = render(
      <ErrorBoundary resetKey="a">
        <Fragile broken />
      </ErrorBoundary>,
    );
    expect(screen.getByText(formatError('UI_RENDER_FAILED'))).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('PRIVATE_');
    expect(screen.queryByText(/错误堆栈/)).not.toBeInTheDocument();
    rerender(
      <ErrorBoundary resetKey="a">
        <Fragile broken={false} />
      </ErrorBoundary>,
    );
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(screen.getByText('页面恢复')).toBeInTheDocument();
    rerender(
      <ErrorBoundary resetKey="a">
        <Fragile broken />
      </ErrorBoundary>,
    );
    rerender(
      <ErrorBoundary resetKey="b">
        <Fragile broken={false} />
      </ErrorBoundary>,
    );
    expect(screen.getByText('页面恢复')).toBeInTheDocument();
  });
  it('报表局部失败安全展示，保留放量操作说明', () => {
    render(
      <AbSignificancePanel isLoading={false} isError error={new Error('PRIVATE_REPORT_DETAIL')} />,
    );
    expect(screen.getByText(/\[UNKNOWN_ERROR\]/)).toBeInTheDocument();
    expect(screen.getByText(/放量控件不受影响/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('PRIVATE_');
  });
});
