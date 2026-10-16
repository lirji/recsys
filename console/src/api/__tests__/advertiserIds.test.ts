import { beforeEach, expect, it, vi } from 'vitest';
import { http } from '../client';
import {
  getAd,
  listCreatives,
  updateCreative,
  deleteCreative,
  updateAd,
  getAdvertiserPermissions,
} from '../advertiser';
import { advertiserId, normalizeAdvertiser, normalizeBidword } from '../advertiserIds';

vi.mock('../client', () => ({ http: { get: vi.fn(), put: vi.fn(), delete: vi.fn() } }));
const exact = '1306381397547552769';
const creative = {
  creativeId: Number(exact),
  creativeIdText: exact,
  adId: 1,
  title: '真实创意',
  landingUrl: '/ad',
  status: 'approved',
};
beforeEach(() => vi.clearAllMocks());

it('真实 Snowflake 经响应、编辑和删除 URL 全程保真，原数值字段不能污染文本', async () => {
  vi.mocked(http.get).mockResolvedValue({ data: [creative] });
  vi.mocked(http.put).mockResolvedValue({ data: creative });
  const [row] = await listCreatives('1');
  expect(row.creativeId).toBe(exact);
  await updateCreative(row.creativeId, { title: '更新' });
  await deleteCreative(row.creativeId);
  expect(http.put).toHaveBeenCalledWith(`/api/advertiser/creative/${exact}`, { title: '更新' });
  expect(http.delete).toHaveBeenCalledWith(`/api/advertiser/creative/${exact}`);
});

it('详情路由与清除定向的请求使用原字符串，并兼容旧安全整数', async () => {
  const row = {
    adId: Number(exact),
    adIdText: exact,
    advertiserId: 1,
    itemId: 10,
    audienceId: null,
    bidwords: [],
    creatives: [],
    reviewStatus: 'pending_review',
  };
  vi.mocked(http.get).mockResolvedValue({ data: row });
  vi.mocked(http.put).mockResolvedValue({ data: row });
  expect((await getAd(exact)).advertiserId).toBe('1');
  await updateAd(exact, { title: '变更', audienceId: '0' });
  expect(http.get).toHaveBeenCalledWith(`/api/advertiser/ad/${exact}`);
  expect(http.put).toHaveBeenCalledWith(`/api/advertiser/ad/${exact}`, {
    title: '变更',
    audienceId: '0',
  });
  expect(
    normalizeAdvertiser({
      advertiserId: 1,
      name: 'a',
      dailyBudget: 1,
      status: 'active',
      spentToday: null,
      remainingBudget: null,
    }).advertiserId,
  ).toBe('1');
  expect(
    normalizeBidword({
      id: Number(exact),
      idText: exact,
      adId: 1,
      keyword: 'a',
      matchType: 'EXACT',
      bid: 1,
      bidMode: 'CPC',
    }).id,
  ).toBe(exact);
});

it.each([Number(exact), 1.5, 0, -1, '01', '1e5', '9223372036854775808', '../1'])(
  '拒绝无法精确解释的标识 %s',
  (value) => {
    expect(() => advertiserId(value)).toThrow();
  },
);

it('旧不安全 ID 不猜测修复，权限响应缺字段不默认允许', async () => {
  vi.mocked(http.get).mockResolvedValue({ data: [{ ...creative, creativeIdText: undefined }] });
  await expect(listCreatives('1')).rejects.toMatchObject({ code: 'ADVERTISER_RESPONSE_INVALID' });
  vi.mocked(http.get).mockResolvedValue({ data: { createAdvertiser: true } });
  await expect(getAdvertiserPermissions()).rejects.toMatchObject({
    code: 'ADVERTISER_RESPONSE_INVALID',
  });
});
