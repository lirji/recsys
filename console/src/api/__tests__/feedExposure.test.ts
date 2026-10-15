import { ClientError } from '../errors';
import { expect, it, vi } from 'vitest';
import {
  validateFeedDelivery,
  validateFeedProgress,
  validFeedInput,
  putFeedDelivery,
  getFeedDelivery,
  type FeedDeliveryInput,
  type FeedDeliveryView,
} from '../feedExposure';
import { NATURAL_TEXT_POLICY } from '../naturalExposure';
import { http } from '../client';
vi.mock('../client', () => ({ http: { put: vi.fn(), get: vi.fn() } }));
const id = '11111111-1111-1111-1111-111111111111';
const q = '22222222-2222-2222-2222-222222222222';
const child = '33333333-3333-3333-3333-333333333333';
const input: FeedDeliveryInput = {
  userId: '9223372036854775807',
  size: 3,
  scene: 'feed',
  query: 'phone',
};
function view(): FeedDeliveryView {
  return {
    ...input,
    operationId: id,
    trafficClass: 'SANDBOX',
    state: 'PREPARING',
    policy: NATURAL_TEXT_POLICY,
    bucket: 'b',
    traceId: null,
    modelLineage: 'UNVERIFIED',
    adDisposition: 'CANDIDATES_AVAILABLE',
    items: [
      {
        slot: 1,
        kind: 'NATURAL',
        ad: null,
        natural: {
          slot: 1,
          itemId: '9007199254740993',
          title: '原正文',
          category: '文章',
          description: '描述',
          score: 0.8,
          recallFrom: [],
          reason: null,
          qualificationId: q,
          qualificationToken: 'nq1.test.body.sig',
          issuedAt: '2026-10-15T01:00:00.000Z',
          expiresAt: '2026-10-15T01:05:00.000Z',
          contentHash: 'a'.repeat(64),
        },
      },
      {
        slot: 2,
        kind: 'AD',
        natural: null,
        ad: {
          slot: 2,
          childId: child,
          adId: '1',
          itemId: '9007199254740994',
          advertiserId: '2',
          creativeId: '0',
          title: '广告正文',
          preparation: null,
        },
      },
    ],
  };
}
function completed(): FeedDeliveryView {
  const result = view();
  result.state = 'COMPLETE';
  result.items[1].ad!.preparation = {
    childId: child,
    status: 'QUALIFIED',
    amount: '0.123456',
    qualificationToken: 'v1.qualification.test',
    expiresAt: '2026-10-15T01:05:00Z',
  };
  return result;
}
it('混排支持大ID、已冻结自然与未知广告，并接受原子操作的精确结论', () => {
  expect(validFeedInput(input)).toBe(true);
  expect(validFeedInput({ ...input, query: null })).toBe(true);
  expect(validFeedInput({ ...input, userId: 123 })).toBe(false);
  expect(validFeedInput({ ...input, size: 51 })).toBe(false);
  expect(() => validateFeedDelivery(id, input, view())).not.toThrow();
  expect(() => validateFeedDelivery(id, input, completed())).not.toThrow();
  expect(() => validateFeedProgress(view(), completed())).not.toThrow();
  const denied = completed();
  denied.items[1].ad!.preparation = {
    childId: child,
    status: 'INSUFFICIENT_BUDGET',
    amount: '0.123456',
    qualificationToken: '',
    expiresAt: '2026-10-15T01:05:00Z',
  };
  expect(() => validateFeedDelivery(id, input, denied)).not.toThrow();
});
it('整个响应先验证，错误位置/类型/重复身份/政策/资格不能开始采集', () => {
  for (const patch of [
    { userId: '1' },
    { query: 'other' },
    { policy: { ...NATURAL_TEXT_POLICY, minDurationMs: 1 } },
    { adDisposition: 'UNKNOWN' },
    { state: 'COMPLETE' },
  ])
    expect(() =>
      validateFeedDelivery(id, input, { ...view(), ...patch } as FeedDeliveryView),
    ).toThrow(ClientError);
  for (const change of [
    (v: FeedDeliveryView) => {
      v.items[1].slot = 3;
    },
    (v: FeedDeliveryView) => {
      v.items[1].ad!.slot = 1;
    },
    (v: FeedDeliveryView) => {
      v.items[1].ad!.itemId = v.items[0].natural!.itemId;
    },
    (v: FeedDeliveryView) => {
      v.items[0].natural!.description = undefined as unknown as string;
    },
    (v: FeedDeliveryView) => {
      v.items[0].natural!.expiresAt = '2026-10-15T01:06:00Z';
    },
    (v: FeedDeliveryView) => {
      v.items[1].ad!.preparation = { ...completed().items[1].ad!.preparation!, amount: '0.1' };
    },
  ]) {
    const bad = view();
    change(bad);
    expect(() => validateFeedDelivery(id, input, bad)).toThrow(ClientError);
  }
});
it('恢复只补原结果，不能悄悄换自然资格/位置/金额或倒退完成状态', () => {
  const original = view();
  for (const mutate of [
    (v: FeedDeliveryView) => {
      v.items[0].natural!.qualificationToken = 'nq1.new';
    },
    (v: FeedDeliveryView) => {
      v.items[0].natural!.title = '变更正文';
    },
    (v: FeedDeliveryView) => {
      v.items.reverse();
    },
    (v: FeedDeliveryView) => {
      v.bucket = 'new';
    },
  ]) {
    const changed = completed();
    mutate(changed);
    expect(() => validateFeedProgress(original, changed)).toThrow(ClientError);
  }
  const changed = completed();
  changed.items[1].ad!.preparation!.amount = '1.000000';
  expect(() => validateFeedProgress(completed(), changed)).toThrow(ClientError);
  expect(() => validateFeedProgress(completed(), view())).toThrow(ClientError);
  const ordered = Object.fromEntries(
    Object.entries(completed()).reverse(),
  ) as unknown as FeedDeliveryView;
  expect(() => validateFeedProgress(view(), ordered)).not.toThrow();
});
it('未冻结/自然降级空结果与已冻结准备状态明确区分', () => {
  const pending = { ...view(), bucket: '', traceId: '', adDisposition: null, items: [] };
  expect(() => validateFeedDelivery(id, input, pending)).not.toThrow();
  expect(() =>
    validateFeedDelivery(id, input, {
      ...view(),
      state: 'COMPLETE',
      adDisposition: 'CANDIDATES_UNAVAILABLE',
      items: [],
    }),
  ).not.toThrow();
  expect(() => validateFeedDelivery(id, input, { ...view(), items: [view().items[0]] })).toThrow(
    ClientError,
  );
  expect(() =>
    validateFeedDelivery(id, input, { ...view(), adDisposition: 'CANDIDATES_UNAVAILABLE' }),
  ).toThrow(ClientError);
});
it('feed端点保留原意图与身份守卫，查询不新建交付', async () => {
  vi.mocked(http.put).mockResolvedValue({ data: view() });
  vi.mocked(http.get).mockResolvedValue({ data: view() });
  const owner = { scope: 'subject', epoch: 3 },
    controller = new AbortController();
  await putFeedDelivery(id, input, owner, controller.signal);
  await getFeedDelivery(id, owner);
  expect(http.put).toHaveBeenCalledWith('/api/sandbox/feed-deliveries/' + id, input, {
    expectedExposureScope: 'subject',
    expectedExposureEpoch: 3,
    signal: controller.signal,
    timeout: 5000,
  });
  expect(http.get).toHaveBeenCalledWith(
    '/api/sandbox/feed-deliveries/' + id,
    expect.objectContaining({ expectedExposureEpoch: 3, timeout: 5000 }),
  );
});
