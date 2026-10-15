import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getInterests } from '../../../api/user';
import { getUserProfile } from '../../../api/user360';
import type { UserProfileView } from '../../../api/types';
import User360 from '../User360';

vi.mock('../../../api/user', () => ({ getInterests: vi.fn() }));
vi.mock('../../../api/user360', () => ({ getUserProfile: vi.fn() }));
vi.mock('../../../hooks/useGlobalUser', () => ({
  useGlobalUser: () => ({ userId: 7, setUserId: vi.fn() }),
}));
vi.mock('../../../hooks/useItemMeta', () => ({ useItemMeta: () => () => undefined }));

function successfulView(): UserProfileView {
  return {
    userId: 7,
    exists: true,
    profileJson: '{"label":"kept profile"}',
    profileUpdatedAt: null,
    hasEmbedding: true,
    stats: {
      totalInteractions: 3,
      actionCounts: { click: 3 },
      distinctCategories: 1,
      bucketsSeen: ['A'],
    },
    recentBehavior: [],
    realtime: {
      available: true,
      rtCategoryPrefs: {},
      recentSeqItems: [],
      recCached: false,
      seenCount: 0,
    },
  };
}
function mount(view: UserProfileView) {
  vi.mocked(getUserProfile).mockResolvedValue(view);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter>
          <User360 />
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getInterests).mockResolvedValue({ userId: 7, categories: [] });
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

describe('用户360部分读取失败', () => {
  it('只把失败部分显示为未知，保留成功的画像、行为计数和分桶', async () => {
    const view = successfulView();
    view.stats.distinctCategories = null;
    view.errors = { categories: 'private SQL token' };
    mount(view);
    expect(
      await screen.findByText(/交互类目 · \[CONSOLE_USER_DATA_UNAVAILABLE\]/),
    ).toBeInTheDocument();
    expect(screen.getByText('未知')).toBeInTheDocument();
    expect(screen.getByText('click: 3')).toBeInTheDocument();
    expect(screen.getByText(/kept profile/)).toBeInTheDocument();
    expect(screen.getByText('A')).toBeInTheDocument();
    expect(screen.queryByText(/private SQL token/)).not.toBeInTheDocument();
  });

  it('全未知不显示用户不存在、向量缺失或真实空实时态，且不回显错误原文', async () => {
    const view = successfulView();
    view.exists = null;
    view.hasEmbedding = null;
    view.profileJson = null;
    view.stats = {
      totalInteractions: null,
      actionCounts: {},
      distinctCategories: null,
      bucketsSeen: [],
    };
    view.realtime = {
      available: false,
      rtCategoryPrefs: {},
      recentSeqItems: [],
      recCached: null,
      seenCount: null,
    };
    view.errors = Object.fromEntries(
      [
        'profile',
        'embedding',
        'actions',
        'categories',
        'buckets',
        'recent',
        'realtime',
        'private unknown section',
      ].map((s) => [s, 'private SQL token']),
    );
    mount(view);
    expect(await screen.findByText('部分信息暂时不可读取')).toBeInTheDocument();
    expect(screen.getByText('近期行为(未知)')).toBeInTheDocument();
    expect(screen.getByText(/实时态 · \[CONSOLE_USER_REALTIME_UNAVAILABLE\]/)).toBeInTheDocument();
    expect(screen.getAllByText('未知').length).toBeGreaterThan(5);
    expect(screen.queryByText(/无画像与行为记录|无实时偏好|private/)).not.toBeInTheDocument();
    expect(screen.queryByText('缺失')).not.toBeInTheDocument();
    // 兴趣是独立且成功的空查询，可以显示无；这里只检查失败的缓存字段。
    expect(
      screen.getByText('结果缓存(cache:rec)').closest('th')?.nextElementSibling,
    ).toHaveTextContent('未知');
  });

  it('确定不存在时保持原用户空态', async () => {
    mount({ ...successfulView(), exists: false, profileJson: null });
    expect(await screen.findByText('用户 7 无画像与行为记录(未知用户)')).toBeInTheDocument();
    expect(screen.queryByText('部分信息暂时不可读取')).not.toBeInTheDocument();
  });

  it('旧成功响应不含errors仍显示真实零值和空偏好', async () => {
    mount(successfulView());
    expect(await screen.findByText('click: 3')).toBeInTheDocument();
    expect(screen.getByText('已就绪')).toBeInTheDocument();
    expect(screen.getByText('无实时偏好')).toBeInTheDocument();
    expect(screen.queryByText('未知')).not.toBeInTheDocument();
    expect(screen.queryByText('部分信息暂时不可读取')).not.toBeInTheDocument();
  });

  it('兼容旧实时态不可用响应，旧0/false不被当作确定结果', async () => {
    const view = successfulView();
    view.realtime.available = false;
    mount(view);
    expect(
      await screen.findByText(/实时态 · \[CONSOLE_USER_REALTIME_UNAVAILABLE\]/),
    ).toBeInTheDocument();
    expect(screen.getByText('实时态未知')).toBeInTheDocument();
    expect(screen.queryByText('无实时偏好')).not.toBeInTheDocument();
    expect(screen.getAllByText('未知')).toHaveLength(3);
    expect(screen.getByText('click: 3')).toBeInTheDocument();
  });

  it('兴趣查询仍在途时显示加载中', async () => {
    vi.mocked(getInterests).mockReturnValue(new Promise(() => {}));
    mount(successfulView());
    expect(await screen.findByText('加载中')).toBeInTheDocument();
  });
});
