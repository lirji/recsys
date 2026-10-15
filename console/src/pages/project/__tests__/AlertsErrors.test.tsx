import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAlerts } from '../../../api/alerts';
import Alerts from '../Alerts';

vi.mock('../../../api/alerts', () => ({ getAlerts: vi.fn() }));

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter>
          <Alerts />
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

describe('告警页区分观测结果与系统健康承诺', () => {
  it('空结果只描述已取得信号，不承诺整个系统健康', async () => {
    vi.mocked(getAlerts).mockResolvedValue([]);
    mount();
    expect(await screen.findByText('当前已取得的信号未发现告警')).toBeInTheDocument();
    expect(screen.queryByText(/系统健康/)).not.toBeInTheDocument();
  });

  it('信号缺失的WARN保留稳定错误码，不渲染为空列表', async () => {
    const message =
      '数据质量巡检 · [CONSOLE_DIAGNOSIS_CHECK_FAILED] 诊断检查未完成，请检查对应服务或报表';
    vi.mocked(getAlerts).mockResolvedValue([
      { level: 'WARN', source: 'data-quality', message, ts: 1 },
    ]);
    mount();
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText('当前已取得的信号未发现告警')).not.toBeInTheDocument();
  });

  it('HTTP失败走公共安全错误展示，不泄漏响应原文', async () => {
    vi.mocked(getAlerts).mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 503,
        data: { code: 'CONSOLE_REPORT_UNAVAILABLE', message: 'private SQL token' },
      },
    });
    mount();
    expect(
      await screen.findByText(
        '[CONSOLE_REPORT_UNAVAILABLE] 报表暂时不可用，请检查数据源或稍后重试',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/private SQL token/)).not.toBeInTheDocument();
    expect(screen.queryByText('当前已取得的信号未发现告警')).not.toBeInTheDocument();
  });
});
