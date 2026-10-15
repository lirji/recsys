import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { getSystemOps } from '../../../api/system';
import type { SystemOps } from '../../../api/types';
import OpsConsole from '../OpsConsole';

vi.mock('../../../api/system', () => ({ getSystemOps: vi.fn() }));
function mount(snapshot: SystemOps) {
  vi.mocked(getSystemOps).mockResolvedValue(snapshot);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter>
          <OpsConsole />
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  vi.clearAllMocks();
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
it('不可读状态显示未知和本地安全码，不显示默认配置或无记录', async () => {
  mount({ redisAvailable: false, message: 'private SQL token', tuning: {}, jobs: [] });
  expect(await screen.findByText(/\[CONSOLE_OPS_UNAVAILABLE\]/)).toBeInTheDocument();
  expect(screen.getByText('运行配置未知')).toBeInTheDocument();
  expect(screen.getByText('作业状态未知')).toBeInTheDocument();
  expect(
    screen.queryByText(/private SQL token|暂无热配置覆盖|尚无 job:status/),
  ).not.toBeInTheDocument();
});
it('可用的真实空保持空态，并正确说明状态来自run-dag', async () => {
  mount({ redisAvailable: true, message: null, tuning: {}, jobs: [] });
  expect(await screen.findByText('暂无热配置覆盖')).toBeInTheDocument();
  expect(screen.getByText('尚无 job:status 记录。通过 run-dag 执行后会出现。')).toBeInTheDocument();
  expect(screen.queryByText('作业状态未知')).not.toBeInTheDocument();
});
it('旧失败记录与未知状态不回显自由详情，新码保留结果不确定的说明', async () => {
  mount({
    redisAvailable: true,
    message: null,
    tuning: {},
    jobs: [
      {
        name: 'legacy',
        status: 'fail',
        updatedAt: null,
        detail: 'private SQL token',
        raw: 'private',
      },
      {
        name: 'current',
        status: 'fail',
        updatedAt: null,
        detail: 'private SQL token',
        raw: null,
        code: 'JOB_STATUS_WRITE_FAILED',
      },
      {
        name: 'broken',
        status: 'private arbitrary status',
        updatedAt: 'private SQL timestamp',
        detail: 'private',
        raw: null,
        code: 'BAD_REQUEST',
      },
    ],
  });
  expect(await screen.findByText(/\[JOB_EXECUTION_FAILED\]/)).toBeInTheDocument();
  expect(screen.getByText(/\[JOB_STATUS_WRITE_FAILED\].*业务效果可能已生效/)).toBeInTheDocument();
  expect(screen.getByText(/\[JOB_STATUS_INVALID\]/)).toBeInTheDocument();
  expect(screen.getAllByText('失败')).toHaveLength(2);
  expect(screen.getByText('未知')).toBeInTheDocument();
  expect(screen.queryByText(/private|BAD_REQUEST/)).not.toBeInTheDocument();
});
it('成功旧记录丢弃历史自由详情但保留完成状态', async () => {
  mount({
    redisAvailable: true,
    message: null,
    tuning: {},
    jobs: [
      {
        name: 'hot',
        status: 'ok',
        updatedAt: '2026-10-16 05:00:00',
        detail: 'private SQL token',
        raw: 'private',
      },
    ],
  });
  expect(await screen.findByText('完成')).toBeInTheDocument();
  expect(screen.getByText('2026-10-16 05:00:00')).toBeInTheDocument();
  expect(screen.queryByText(/private/)).not.toBeInTheDocument();
});
