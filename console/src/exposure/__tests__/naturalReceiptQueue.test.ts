import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import { NaturalReceiptQueue, NATURAL_RECEIPT_STORAGE_KEY } from '../naturalReceiptQueue';
import { ReceiptQueue, RECEIPT_STORAGE_KEY } from '../receiptQueue';
import { exposureEpoch, revokeExposureSession } from '../identity';
import type { NaturalImpressionResult } from '../../api/naturalExposure';
const input = {
  qualificationToken: 'nq1.test.proof.signature',
  visibleDurationMs: 1000,
  minVisibleRatioBps: 5000,
  pageVisible: true,
};
const queues: Array<{ dispose: () => void }> = [];
const identity = () => ({ scope: 'natural-subject', epoch: exposureEpoch() });
const expiry = () => new Date(Date.now() + 300000).toISOString();
const flush = async () => {
  for (let i = 0; i < 12; i++) await Promise.resolve();
};
const accepted = (id: string, q: string): NaturalImpressionResult => ({
  receiptId: id,
  qualificationId: q,
  status: 'ACCEPTED',
  eventId: q,
  canonicalReceiptId: id,
  acceptedAt: new Date().toISOString(),
  decidedAt: new Date().toISOString(),
});
const httpError = (status: number) =>
  new AxiosError('status', undefined, undefined, undefined, { status } as never);
function setup(put = vi.fn(), get = vi.fn()) {
  const queue = new NaturalReceiptQueue(sessionStorage, { put, get });
  queues.push(queue);
  queue.bind(identity());
  return { queue, put, get };
}
beforeEach(() => {
  vi.useFakeTimers();
  sessionStorage.clear();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
});
afterEach(() => {
  queues.splice(0).forEach((q) => q.dispose());
  vi.useRealTimers();
});
it('自然接受只有事实字段，同资格保持原 ID 并清理票据/未知扩展', async () => {
  const q = crypto.randomUUID();
  const { queue, put } = setup(
    vi.fn(async (id: string) => ({
      ...accepted(id, q),
      chargedAmount: '99',
      qualificationToken: 'secret',
    })),
  );
  const entry = queue.enqueue(q, expiry(), input)!;
  await flush();
  expect(queue.enqueue(q, expiry(), input)?.receiptId).toBe(entry.receiptId);
  expect(put).toHaveBeenCalledTimes(1);
  expect(queue.get(q)?.phase).toBe('accepted');
  expect(queue.get(q)?.result).toEqual(accepted(entry.receiptId, q));
  expect(sessionStorage.getItem(NATURAL_RECEIPT_STORAGE_KEY)).not.toMatch(
    /secret|chargedAmount|nq1/,
  );
});
it('丢 ACK 刷新先查原 ID，404 只在期限内重发原 body', async () => {
  const q = crypto.randomUUID();
  const first = setup(vi.fn().mockRejectedValue(new Error('timeout'))).queue;
  const original = first.enqueue(q, expiry(), input)!;
  await flush();
  first.dispose();
  const next = setup(
    vi.fn(async (id: string) => accepted(id, q)),
    vi.fn().mockRejectedValue(httpError(404)),
  );
  await vi.advanceTimersByTimeAsync(1000);
  expect(next.get).toHaveBeenCalledWith(original.receiptId, identity(), expect.any(AbortSignal));
  expect(next.put).toHaveBeenCalledWith(
    original.receiptId,
    input,
    identity(),
    expect.any(AbortSignal),
  );
  expect(next.queue.get(q)?.phase).toBe('accepted');
});
it('已有事实返回 canonical 原回执，持久政策拒绝没有事件且不重试', async () => {
  const q = crypto.randomUUID(),
    canonical = crypto.randomUUID();
  const { queue, put } = setup(
    vi.fn(async (id: string) => ({
      ...accepted(id, q),
      status: 'ALREADY_ACCEPTED',
      canonicalReceiptId: canonical,
    })),
  );
  queue.enqueue(q, expiry(), input);
  await flush();
  expect(queue.get(q)?.result?.canonicalReceiptId).toBe(canonical);
  const rejectedQ = crypto.randomUUID();
  put.mockImplementation(async (id: string) => ({
    receiptId: id,
    qualificationId: rejectedQ,
    status: 'POLICY_NOT_MET',
    eventId: null,
    canonicalReceiptId: null,
    acceptedAt: null,
    decidedAt: new Date().toISOString(),
  }));
  queue.enqueue(rejectedQ, expiry(), input);
  await flush();
  await vi.advanceTimersByTimeAsync(60000);
  expect(queue.get(rejectedQ)?.phase).toBe('rejected');
  expect(put).toHaveBeenCalledTimes(2);
});
it.each(['eventId', 'qualificationId', 'canonicalReceiptId', 'decidedAt'])(
  '不接受损坏的 %s，未知结果不能伪成功',
  async (field) => {
    const q = crypto.randomUUID();
    const { queue } = setup(
      vi.fn(async (id: string) => ({ ...accepted(id, q), [field]: 'invalid' })),
    );
    queue.enqueue(q, expiry(), input);
    await flush();
    expect(queue.get(q)?.phase).toBe('unknown');
  },
);
it('广告和自然资源独立，退出立即撤销两队列与两页面意图', async () => {
  const pending = new Promise<never>(() => {});
  const { queue, put } = setup(vi.fn(() => pending));
  const adPut = vi.fn(() => pending);
  const ads = new ReceiptQueue(sessionStorage, { put: adPut, get: vi.fn() });
  queues.push(ads);
  ads.bind(identity());
  for (let i = 0; i < 100; i++) queue.enqueue(crypto.randomUUID(), expiry(), input);
  expect(queue.enqueue(crypto.randomUUID(), expiry(), input)).toBeNull();
  expect(put).toHaveBeenCalledTimes(2);
  expect(ads.enqueue(crypto.randomUUID(), expiry(), input)).not.toBeNull();
  expect(adPut).toHaveBeenCalledTimes(1);
  sessionStorage.setItem('recsys.sandbox.natural.recommend.v1', 'intent');
  sessionStorage.setItem('recsys.sandbox.natural.search.v1', 'intent');
  revokeExposureSession();
  expect(sessionStorage.getItem(NATURAL_RECEIPT_STORAGE_KEY)).toBeNull();
  expect(sessionStorage.getItem(RECEIPT_STORAGE_KEY)).toBeNull();
  expect(sessionStorage.getItem('recsys.sandbox.natural.recommend.v1')).toBeNull();
  expect(sessionStorage.getItem('recsys.sandbox.natural.search.v1')).toBeNull();
  expect((put.mock.calls as unknown as unknown[][])[0][3]).toHaveProperty('aborted', true);
});
it('到期后仅查询，不把 404 认作尚未曝光；自动恢复六轮有界', async () => {
  const q = crypto.randomUUID();
  const { queue, put, get } = setup(
    vi.fn().mockRejectedValue(new Error('timeout')),
    vi.fn().mockRejectedValue(httpError(404)),
  );
  queue.enqueue(q, new Date(Date.now() + 100).toISOString(), input);
  await flush();
  await vi.advanceTimersByTimeAsync(180000);
  expect(put).toHaveBeenCalledTimes(1);
  expect(get).toHaveBeenCalledTimes(5);
  expect(queue.get(q)?.phase).toBe('unknown');
  expect(queue.get(q)?.input).toBeNull();
});
