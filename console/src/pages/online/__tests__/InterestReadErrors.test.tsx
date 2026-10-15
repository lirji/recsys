import type { ReactNode } from 'react';
import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getInterests } from '../../../api/user';
import { getUserProfile } from '../../../api/user360';
import ColdStartInterests from '../ColdStartInterests';
import User360 from '../../project/User360';

vi.mock('../../../api/user', () => ({ getInterests: vi.fn(), saveInterests: vi.fn() }));
vi.mock('../../../api/user360', () => ({ getUserProfile: vi.fn() }));
vi.mock('../../../hooks/useGlobalUser', () => ({
  useGlobalUser: () => ({ userId: 1, setUserId: vi.fn() }),
}));
vi.mock('../../../hooks/useItemMeta', () => ({ useItemMeta: () => () => undefined }));

const failedRead = {
  isAxiosError: true,
  response: {
    status: 503,
    data: { code: 'USER_INTEREST_QUERY_FAILED', message: 'private SQL token' },
  },
};
const publicMessage = '[USER_INTEREST_QUERY_FAILED] 兴趣信息暂时无法读取，请稍后重试';

function mount(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter>{children}</MemoryRouter>
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

describe('兴趣读取的真实页面错误展示', () => {
  it('读取失败显示稳定错误码，不把空表单当作已加载画像', async () => {
    vi.mocked(getInterests).mockRejectedValue(failedRead);
    mount(<ColdStartInterests />);
    expect(await screen.findByText(publicMessage)).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByText(/private SQL token/)).not.toBeInTheDocument();
  });

  it('真实空兴趣仍允许用户编辑保存', async () => {
    vi.mocked(getInterests).mockResolvedValue({ userId: 1, categories: [] });
    mount(<ColdStartInterests />);
    expect(await screen.findByRole('combobox')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /保.*存/ })).toBeEnabled();
    expect(screen.queryByText(publicMessage)).not.toBeInTheDocument();
  });

  it('用户360局部失败使用同一安全目录，其余已取得的数据保持可见', async () => {
    vi.mocked(getInterests).mockRejectedValue(failedRead);
    vi.mocked(getUserProfile).mockResolvedValue({
      userId: 1,
      exists: true,
      profileJson: null,
      profileUpdatedAt: null,
      hasEmbedding: false,
      stats: {
        totalInteractions: 3,
        actionCounts: { click: 3 },
        distinctCategories: 1,
        bucketsSeen: [],
      },
      recentBehavior: [],
      realtime: {
        available: true,
        rtCategoryPrefs: {},
        recentSeqItems: [],
        recCached: false,
        seenCount: 0,
      },
    });
    mount(<User360 />);
    expect(await screen.findByText(publicMessage)).toBeInTheDocument();
    expect(screen.getByText('click: 3')).toBeInTheDocument();
    expect(screen.queryByText(/private SQL token/)).not.toBeInTheDocument();
  });
});
