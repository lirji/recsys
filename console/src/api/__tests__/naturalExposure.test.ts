import { expect, it, vi } from 'vitest';
import {
  NATURAL_TEXT_POLICY,
  validNaturalInput,
  validateNaturalDelivery,
  putNaturalDelivery,
  getNaturalDelivery,
  putNaturalImpression,
  getNaturalImpression,
  type NaturalDeliveryInput,
  type NaturalDeliveryView,
} from '../naturalExposure';
import { http } from '../client';
vi.mock('../client', () => ({ http: { put: vi.fn(), get: vi.fn() } }));
const input: NaturalDeliveryInput = {
  userId: '9223372036854775807',
  kind: 'RECOMMEND',
  query: null,
  size: 3,
  scene: 'home',
};
const id = '11111111-1111-1111-1111-111111111111';
const q = '22222222-2222-2222-2222-222222222222';
function view(): NaturalDeliveryView {
  return {
    ...input,
    operationId: id,
    trafficClass: 'SANDBOX',
    state: 'COMPLETE',
    policy: NATURAL_TEXT_POLICY,
    bucket: '',
    traceId: null,
    modelLineage: 'UNVERIFIED',
    items: [
      {
        slot: 1,
        itemId: '9007199254740993',
        title: '冻结文本',
        category: '文章',
        description: '原内容',
        score: 0.8,
        recallFrom: ['test'],
        reason: null,
        qualificationId: q,
        qualificationToken: 'nq1.test.body.sig',
        issuedAt: '2026-10-15T01:00:00.000Z',
        expiresAt: '2026-10-15T01:05:00.000Z',
        contentHash: 'a'.repeat(64),
      },
    ],
  };
}
it('自然契约保留 long 字符串、可空解释、已批准政策和空结果', () => {
  expect(validNaturalInput(input)).toBe(true);
  expect(() => validateNaturalDelivery(id, input, view())).not.toThrow();
  expect(() => validateNaturalDelivery(id, input, { ...view(), items: [] })).not.toThrow();
  expect(validNaturalInput({ ...input, userId: '9223372036854775808' })).toBe(false);
  expect(validNaturalInput({ ...input, userId: 123 })).toBe(false);
  expect(validNaturalInput({ ...input, query: 'query' })).toBe(false);
  expect(validNaturalInput({ ...input, kind: 'SEARCH', query: '   ' })).toBe(false);
  expect(validNaturalInput({ ...input, kind: 'SEARCH', query: 'action' })).toBe(true);
});
it('原请求、未知政策、改位置/期限、重复资格或缺正文全部拒绝采集', () => {
  for (const patch of [
    { userId: '1' },
    { scene: 'other' },
    { kind: 'SEARCH' },
    { size: 4 },
    { query: 'other' },
    { policy: { ...NATURAL_TEXT_POLICY, minDurationMs: 999 } },
    { trafficClass: 'REAL' },
  ])
    expect(() =>
      validateNaturalDelivery(id, input, { ...view(), ...patch } as NaturalDeliveryView),
    ).toThrow();
  for (const patch of [
    { title: '' },
    { slot: 2 },
    { expiresAt: '2026-10-15T01:05:01.000Z' },
    { itemId: 123 },
    { score: Infinity },
  ]) {
    const bad = view();
    Object.assign(bad.items[0], patch);
    expect(() => validateNaturalDelivery(id, input, bad)).toThrow();
  }
  const repeated = view();
  repeated.items.push({ ...repeated.items[0], slot: 2 });
  expect(() => validateNaturalDelivery(id, input, repeated)).toThrow();
});
it('真实自然端点使用身份守卫、5s 超时和 422 持久结果，无旧收费路径', async () => {
  vi.mocked(http.put).mockResolvedValue({ data: view() });
  vi.mocked(http.get).mockResolvedValue({ data: view() });
  const identity = { scope: 'subject', epoch: 2 },
    controller = new AbortController();
  await putNaturalDelivery(id, input, identity, controller.signal);
  await getNaturalDelivery(id, identity);
  const receipt = {
    qualificationToken: 'nq1.test',
    visibleDurationMs: 1000,
    minVisibleRatioBps: 5000,
    pageVisible: true,
  };
  await putNaturalImpression(q, receipt, identity);
  await getNaturalImpression(q, identity);
  expect(http.put).toHaveBeenNthCalledWith(1, '/api/sandbox/natural-deliveries/' + id, input, {
    expectedExposureScope: 'subject',
    expectedExposureEpoch: 2,
    signal: controller.signal,
    timeout: 5000,
  });
  expect(vi.mocked(http.put).mock.calls[1][0]).toBe('/api/sandbox/natural-impressions/' + q);
  const validate = vi.mocked(http.put).mock.calls[1][2]!.validateStatus!;
  expect(validate(422)).toBe(true);
  expect(validate(200)).toBe(true);
  expect(validate(503)).toBe(false);
  expect(validate(403)).toBe(false);
  expect(vi.mocked(http.get).mock.calls.map((c) => c[0])).toEqual([
    '/api/sandbox/natural-deliveries/' + id,
    '/api/sandbox/natural-impressions/' + q,
  ]);
});
