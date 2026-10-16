import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { beforeEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ get: vi.fn(), weight: vi.fn() }));
vi.mock('../../../api/experiment', () => ({
  getExperiment: mocks.get,
  setVariantWeight: mocks.weight,
  clearOverride: vi.fn(),
  setGlobalEnabled: vi.fn(),
  setLayerEnabled: vi.fn(),
}));
vi.mock('../../../components/experiment/abReport', () => ({
  useAbReport: () => ({ isLoading: false, isError: false }),
  variantOnlineStat: vi.fn(),
}));
import ExperimentConsole from '../ExperimentConsole';
import { ClientError, formatError } from '../../../api/errors';

beforeEach(() => {
  vi.clearAllMocks();
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
  mocks.get.mockResolvedValue({
    staticEnabled: true,
    enabled: true,
    staticLayers: { rank: { salt: 'test', enabled: true, variants: { v1: 50, v2: 50, v3: 0 } } },
    overrides: {},
  });
});
it('逐变体写入中途失败停止后续写并刷新实际配置，不提示全部成功', async () => {
  mocks.weight
    .mockResolvedValueOnce({ ok: true })
    .mockRejectedValueOnce(new ClientError('EXPERIMENT_WRITE_UNCONFIRMED'));
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <App>
      <QueryClientProvider client={client}>
        <ExperimentConsole />
      </QueryClientProvider>
    </App>,
  );
  fireEvent.click(await screen.findByRole('button', { name: /保\s*存/ }));
  expect(await screen.findByText(formatError('EXPERIMENT_WRITE_UNCONFIRMED'))).toBeInTheDocument();
  expect(mocks.weight.mock.calls).toEqual([
    ['rank', 'v1', 50],
    ['rank', 'v2', 50],
  ]);
  await waitFor(() => expect(mocks.get.mock.calls.length).toBeGreaterThanOrEqual(2));
  expect(screen.queryByText('已保存 rank 层权重')).not.toBeInTheDocument();
});
