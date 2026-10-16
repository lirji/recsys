import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import SandboxBillingReportPanel from '../SandboxBillingReportPanel';
import {
  sandboxReconciliation,
  type SandboxReconciliation,
} from '../../../api/sandboxReconciliation';
import {
  reportFixture,
  REPORT_ADVERTISER,
  REPORT_DAY,
} from '../../../api/__tests__/sandboxReconciliation.fixture';
import type { ExposureIdentity } from '../../../api/exposure';
import { AxiosError } from 'axios';

vi.mock('../../../api/sandboxReconciliation', async (original) => ({
  ...(await original<typeof import('../../../api/sandboxReconciliation')>()),
  sandboxReconciliation: vi.fn(),
}));
const load = vi.mocked(sandboxReconciliation);
let client: QueryClient;
const identity = { scope: 'actor-a', epoch: 0 };
function body(who: ExposureIdentity | null = identity) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter
        initialEntries={[`/advertiser/${REPORT_ADVERTISER}/report?budgetDate=${REPORT_DAY}`]}
      >
        <SandboxBillingReportPanel advertiserId={REPORT_ADVERTISER} identity={who} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}
beforeEach(() => {
  load.mockReset();
  load.mockResolvedValue(reportFixture());
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
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
afterEach(() => {
  cleanup();
  client.clear();
  vi.unstubAllGlobals();
});
it('账户资金一致与三类投影落后分别显示，展开可看缺失证据', async () => {
  render(body());
  expect(await screen.findByText('0.600006')).toBeInTheDocument();
  expect(screen.getByText('资金核对一致')).toBeInTheDocument();
  expect(screen.getAllByText('尚未追平')).toHaveLength(3);
  expect(screen.getByText('0.200002')).toBeInTheDocument();
  const expand = document.querySelector<HTMLButtonElement>('.ant-table-row-expand-icon');
  expect(expand).not.toBeNull();
  fireEvent.click(expand!);
  expect(await screen.findByText('缺失投影事实笔数')).toBeInTheDocument();
});
it('日期草稿不触发读取，提交才切换；旧结果迟到不能覆盖新日期', async () => {
  let finish!: (value: SandboxReconciliation) => void;
  load.mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  render(body());
  await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
  fireEvent.change(screen.getByLabelText('原预算日（Asia/Shanghai）'), {
    target: { value: '2026-10-15' },
  });
  expect(load).toHaveBeenCalledTimes(1);
  load.mockResolvedValueOnce(reportFixture('2026-10-15'));
  fireEvent.submit(screen.getByRole('form', { name: '查询账户日资金' }));
  expect(await screen.findByText('0.600006')).toBeInTheDocument();
  await act(async () => {
    const old = reportFixture();
    old.account!.chargedAmount = '99.999999';
    finish(old);
  });
  expect(screen.queryByText('99.999999')).not.toBeInTheDocument();
  expect(screen.getByText(/当前查询预算日 2026-10-15/)).toBeInTheDocument();
});
it('非法日期保留输入并零额不替换缺失账户', async () => {
  const absent = reportFixture();
  absent.account = null;
  absent.fundsStatus = 'ACCOUNT_MISSING';
  absent.heldDelta = null;
  absent.chargedDelta = null;
  load.mockResolvedValue(absent);
  render(body());
  expect(await screen.findByText('该预算日尚未建立账户')).toBeInTheDocument();
  expect(screen.queryByText('预算额度（CNY）')).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('原预算日（Asia/Shanghai）'), {
    target: { value: '2026-02-31' },
  });
  fireEvent.submit(screen.getByRole('form', { name: '查询账户日资金' }));
  expect(screen.getByText(/请输入有效日期/)).toBeInTheDocument();
  expect(load).toHaveBeenCalledTimes(1);
});
it('身份撤销立即隐藏旧资金，迟到数据不能重新出现', async () => {
  const view = render(body());
  expect(await screen.findByText('0.600006')).toBeInTheDocument();
  view.rerender(body(null));
  expect(screen.queryByText('0.600006')).not.toBeInTheDocument();
  await waitFor(() =>
    expect(
      client
        .getQueryCache()
        .findAll({ queryKey: ['sandbox-reconciliation', identity.scope, identity.epoch] }),
    ).toHaveLength(0),
  );
});
it('撤权刷新隐藏旧快照并移除缓存，不自动请求循环，显式重查可恢复', async () => {
  render(body());
  expect(await screen.findByText('0.600006')).toBeInTheDocument();
  load.mockRejectedValueOnce(
    new AxiosError('untrusted', undefined, undefined, undefined, {
      status: 403,
      data: { code: 'FORBIDDEN' },
      statusText: '',
      headers: {},
      config: {} as never,
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '刷新快照' }));
  await waitFor(() => expect(screen.getByText(/当前未展示资金快照/)).toBeInTheDocument());
  expect(screen.queryByText('0.600006')).not.toBeInTheDocument();
  await waitFor(() =>
    expect(
      client
        .getQueryCache()
        .findAll({ queryKey: ['sandbox-reconciliation', identity.scope, identity.epoch] }),
    ).toHaveLength(0),
  );
  expect(load).toHaveBeenCalledTimes(2);
  fireEvent.click(screen.getByRole('button', { name: '刷新快照' }));
  expect(await screen.findByText('0.600006')).toBeInTheDocument();
  expect(load).toHaveBeenCalledTimes(3);
});
