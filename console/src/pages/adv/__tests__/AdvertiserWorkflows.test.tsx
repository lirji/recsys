import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import * as api from '../../../api/advertiser';
import type { AdView, AdvertiserPermissions } from '../../../api/types';
import AdDetail from '../AdDetail';
import AdvertiserList from '../AdvertiserList';
import AdList from '../AdList';
import CreativePanel from '../../../components/adv/CreativePanel';
import BidwordPanel from '../../../components/adv/BidwordPanel';

vi.mock('../../../api/advertiser', () => ({
  getAd: vi.fn(),
  listCreatives: vi.fn(),
  listBidwords: vi.fn(),
  getAdvertiserPermissions: vi.fn(),
  updateAd: vi.fn(),
  reviewAd: vi.fn(),
  submitReview: vi.fn(),
  setAdStatus: vi.fn(),
  deleteAd: vi.fn(),
  createCreative: vi.fn(),
  updateCreative: vi.fn(),
  deleteCreative: vi.fn(),
  createBidword: vi.fn(),
  updateBidword: vi.fn(),
  deleteBidword: vi.fn(),
  listAdvertisers: vi.fn(),
  createAdvertiser: vi.fn(),
  listAds: vi.fn(),
  createAd: vi.fn(),
}));
vi.mock('../../../hooks/useAuth', () => ({
  useAuth: () => ({
    user: { username: 'operator', roles: ['ADVERTISER'] },
    ready: true,
    switching: false,
  }),
}));
const id = '1306381397547552769';
const permissions: AdvertiserPermissions = {
  createAdvertiser: false,
  reviewAds: true,
  editAds: true,
  manageAdvertiser: false,
};
const sample: AdView = {
  adId: id,
  advertiserId: '1',
  itemId: '10',
  title: '测试广告',
  landingUrl: '/landing',
  qualityScore: 1,
  status: 'active',
  optimizationType: 'CPC',
  audienceId: null,
  hasEmbedding: false,
  creatives: [],
  bidwords: [],
  reviewStatus: 'pending_review',
  reviewReason: '等待复核',
};
const failure = {
  isAxiosError: true,
  response: { status: 503, data: { code: 'SERVICE_UNAVAILABLE' } },
};
function mount(element = <AdDetail />, path = `/advertiser/ad/${id}`) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/advertiser/ad/:adId" element={element} />
            <Route path="/advertiser" element={element} />
            <Route path="/advertiser/:advId/ads" element={element} />
          </Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getAd).mockResolvedValue(sample);
  vi.mocked(api.getAdvertiserPermissions).mockResolvedValue(permissions);
  vi.mocked(api.listCreatives).mockResolvedValue([]);
  vi.mocked(api.listBidwords).mockResolvedValue([]);
  vi.mocked(api.listAdvertisers).mockResolvedValue([]);
  vi.mocked(api.listAds).mockResolvedValue([]);
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

it('无定向广告的编辑发送0；失败保留输入，成功后才关闭', async () => {
  vi.mocked(api.updateAd).mockRejectedValueOnce(failure).mockResolvedValueOnce(sample);
  mount();
  const edit = await screen.findByRole('button', { name: /^编\s*辑$/ });
  await waitFor(() => expect(edit).toBeEnabled());
  fireEvent.click(edit);
  const dialog = await screen.findByRole('dialog', { name: '编辑广告' });
  fireEvent.change(within(dialog).getByLabelText('标题'), { target: { value: '保留新标题' } });
  fireEvent.click(within(dialog).getByRole('button', { name: /确\s*定|OK/ }));
  await waitFor(() =>
    expect(api.updateAd).toHaveBeenCalledWith(
      id,
      expect.objectContaining({ title: '保留新标题', audienceId: '0' }),
    ),
  );
  expect(within(dialog).getByLabelText('标题')).toHaveValue('保留新标题');
  await waitFor(() =>
    expect(within(dialog).getByRole('button', { name: /确\s*定|OK/ })).toBeEnabled(),
  );
  fireEvent.click(within(dialog).getByRole('button', { name: /确\s*定|OK/ }));
  await waitFor(() =>
    expect(screen.queryByRole('dialog', { name: '编辑广告' })).not.toBeInTheDocument(),
  );
  expect(api.updateAd).toHaveBeenCalledTimes(2);
});

it('驳回失败保留原因，成功后读取实际审核状态，不显示示意投放中', async () => {
  vi.mocked(api.reviewAd)
    .mockRejectedValueOnce(failure)
    .mockImplementationOnce(async () => {
      const rejected = { ...sample, reviewStatus: 'rejected', reviewReason: '补充落地页' };
      vi.mocked(api.getAd).mockResolvedValue(rejected);
      return rejected;
    });
  mount();
  const reject = await screen.findByRole('button', { name: /^驳\s*回$/ });
  await waitFor(() => expect(reject).toBeEnabled());
  expect(screen.getByText('待审核')).toBeInTheDocument();
  expect(screen.queryByText('投放中')).not.toBeInTheDocument();
  fireEvent.click(reject);
  const dialog = await screen.findByRole('dialog', { name: '驳回广告' });
  fireEvent.change(within(dialog).getByLabelText('驳回原因'), { target: { value: '补充落地页' } });
  fireEvent.click(within(dialog).getByRole('button', { name: /确\s*定|OK/ }));
  await waitFor(() => expect(api.reviewAd).toHaveBeenCalledTimes(1));
  expect(within(dialog).getByLabelText('驳回原因')).toHaveValue('补充落地页');
  await waitFor(() =>
    expect(within(dialog).getByRole('button', { name: /确\s*定|OK/ })).toBeEnabled(),
  );
  fireEvent.click(within(dialog).getByRole('button', { name: /确\s*定|OK/ }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.getByText('审核说明').closest('tr')).toHaveTextContent('补充落地页');
  expect(api.reviewAd).toHaveBeenLastCalledWith(id, 'reject', '补充落地页');
});

it('写入进行中阻止重复审核请求', async () => {
  let finish!: (value: AdView) => void;
  vi.mocked(api.reviewAd).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  mount();
  const approve = await screen.findByRole('button', { name: '审核通过' });
  await waitFor(() => expect(approve).toBeEnabled());
  fireEvent.click(approve);
  fireEvent.click(approve);
  expect(api.reviewAd).toHaveBeenCalledTimes(1);
  expect(approve).toBeDisabled();
  finish({ ...sample, reviewStatus: 'approved' });
  await waitFor(() => expect(approve).toBeEnabled());
});

it('只读身份不能编辑、删除、送审、审核或新增子资源', async () => {
  vi.mocked(api.getAdvertiserPermissions).mockResolvedValue({
    createAdvertiser: false,
    editAds: false,
    manageAdvertiser: false,
    reviewAds: false,
  });
  mount();
  await screen.findByText('等待复核');
  for (const name of [
    /^编\s*辑$/,
    /^删\s*除$/,
    '重新送审',
    '审核通过',
    /^驳\s*回$/,
    '新增创意',
    '新增竞价词',
  ]) {
    expect(screen.getByRole('button', { name })).toBeDisabled();
  }
  expect(api.updateAd).not.toHaveBeenCalled();
});

it('权限依赖失败有重试，成功恢复后才开放已允许的按钮', async () => {
  vi.mocked(api.getAdvertiserPermissions)
    .mockRejectedValueOnce(failure)
    .mockResolvedValueOnce(permissions);
  mount();
  const retry = await screen.findByRole('button', { name: '重试权限查询' });
  expect(screen.getByRole('button', { name: /^编\s*辑$/ })).toBeDisabled();
  fireEvent.click(retry);
  await waitFor(() => expect(screen.getByRole('button', { name: /^编\s*辑$/ })).toBeEnabled());
});

it('缺少审核字段的旧响应显示未知，不伪造已通过', async () => {
  vi.mocked(api.getAd).mockResolvedValue({
    ...sample,
    reviewStatus: undefined,
    reviewReason: undefined,
  });
  mount();
  expect(await screen.findByText('审核状态未知')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '审核通过' })).toBeDisabled();
});

it('开户按钮按平台权限而不是广告主角色开放', async () => {
  mount(<AdvertiserList />, '/advertiser');
  const button = await screen.findByRole('button', { name: '新建广告主' });
  await waitFor(() => expect(api.getAdvertiserPermissions).toHaveBeenCalled());
  expect(button).toBeDisabled();
});

it('新建广告被拒绝后保留草稿，刷新到撤销权限时禁用弹窗提交', async () => {
  vi.mocked(api.createAd).mockImplementation(async () => {
    vi.mocked(api.getAdvertiserPermissions).mockResolvedValue({ ...permissions, editAds: false });
    throw { isAxiosError: true, response: { status: 403, data: { code: 'FORBIDDEN' } } };
  });
  mount(<AdList />, '/advertiser/1/ads');
  const create = await screen.findByRole('button', { name: '新建广告' });
  await waitFor(() => expect(create).toBeEnabled());
  fireEvent.click(create);
  const dialog = await screen.findByRole('dialog', { name: '新建广告' });
  fireEvent.change(within(dialog).getByLabelText('关联 itemId(创意/向量来源)'), {
    target: { value: id },
  });
  fireEvent.change(within(dialog).getByLabelText('标题'), { target: { value: '保留创建草稿' } });
  const submit = within(dialog).getByRole('button', { name: /确\s*定|OK/ });
  fireEvent.click(submit);
  await waitFor(() => expect(api.createAd).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(submit).toBeDisabled());
  expect(within(dialog).getByLabelText('标题')).toHaveValue('保留创建草稿');
  fireEvent.click(submit);
  expect(api.createAd).toHaveBeenCalledTimes(1);
});

it.each(['creative', 'bidword'])('%s 加载失败有明确重试，不作为空列表', async (kind) => {
  const load = kind === 'creative' ? vi.mocked(api.listCreatives) : vi.mocked(api.listBidwords);
  load.mockRejectedValueOnce(failure).mockResolvedValueOnce([]);
  mount(
    kind === 'creative' ? (
      <CreativePanel adId={id} advertiserId="1" canEdit />
    ) : (
      <BidwordPanel adId={id} advertiserId="1" canEdit />
    ),
  );
  expect(await screen.findByRole('button', { name: /^重\s*试$/ })).toBeInTheDocument();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /^重\s*试$/ }));
  await screen.findByRole('table');
  expect(load).toHaveBeenCalledTimes(2);
});
