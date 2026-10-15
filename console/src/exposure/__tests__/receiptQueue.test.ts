import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import { ReceiptQueue, MAX_RECEIPTS, RECEIPT_STORAGE_KEY } from '../receiptQueue';
import { exposureEpoch, revokeExposureSession } from '../identity';
import type { ImpressionInput, ImpressionResult } from '../../api/exposure';

const input: ImpressionInput = {
  qualificationToken: 'signed-proof',
  visibleDurationMs: 1000,
  minVisibleRatioBps: 5000,
  pageVisible: true,
};
const queues: ReceiptQueue[] = [];
const identity = () => ({ scope: 'test-subject', epoch: exposureEpoch() });
const statusError = (status: number) =>
  new AxiosError('status', undefined, undefined, undefined, { status } as never);
const expiry = () => new Date(Date.now() + 300000).toISOString();
const flush = async () => {
  for (let i = 0; i < 12; i += 1) await Promise.resolve();
};
function result(id: string, child: string): ImpressionResult {
  return {
    receiptId: id,
    qualificationId: child,
    status: 'ACCEPTED',
    ledgerId: crypto.randomUUID(),
    canonicalReceiptId: id,
    chargedAmount: '0.123456',
    budgetDate: '2026-10-15',
    acceptedAt: new Date().toISOString(),
    decidedAt: new Date().toISOString(),
  };
}
function setup(put = vi.fn(), get = vi.fn(), storage: Storage | null = sessionStorage) {
  const queue = new ReceiptQueue(storage, { put, get });
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
describe('可恢复的可见回执队列', () => {
  it('同资格只分配一次 ID，确认后才显示金额并清除资格票据', async () => {
    const child = crypto.randomUUID();
    const put = vi.fn(async (id: string) => result(id, child));
    const { queue } = setup(put);
    const first = queue.enqueue(child, expiry(), input)!;
    expect(queue.enqueue(child, expiry(), input)?.receiptId).toBe(first.receiptId);
    await flush();
    expect(put).toHaveBeenCalledTimes(1);
    expect(queue.get(child)?.phase).toBe('accepted');
    expect(sessionStorage.getItem(RECEIPT_STORAGE_KEY)).not.toContain('signed-proof');
  });
  it('未知结果先查，同 ID 原负载重试，晚到 ACK 不重复发送新意图', async () => {
    const child = crypto.randomUUID();
    const put = vi
      .fn()
      .mockRejectedValueOnce(new Error('timeout'))
      .mockImplementation(async (id: string) => result(id, child));
    const get = vi.fn().mockRejectedValue(statusError(404));
    const { queue } = setup(put, get);
    const first = queue.enqueue(child, expiry(), input)!;
    await flush();
    expect(queue.get(child)?.phase).toBe('unknown');
    await vi.advanceTimersByTimeAsync(1000);
    await flush();
    expect(get).toHaveBeenCalledWith(first.receiptId, identity(), expect.any(AbortSignal));
    expect(put.mock.calls.map((call) => call[0])).toEqual([first.receiptId, first.receiptId]);
    expect(put.mock.calls[1][1]).toEqual(input);
    expect(queue.get(child)?.phase).toBe('accepted');
  });
  it('刷新中断保留原 ID，恢复先查账本，不把发送中当成功', async () => {
    const child = crypto.randomUUID();
    const pending = new Promise<ImpressionResult>(() => {});
    const first = setup(vi.fn().mockReturnValue(pending)).queue;
    const entry = first.enqueue(child, expiry(), input)!;
    first.dispose();
    const get = vi.fn(async () => result(entry.receiptId, child));
    const next = setup(vi.fn(), get);
    await flush();
    expect(next.put).not.toHaveBeenCalled();
    expect(next.queue.get(child)?.receiptId).toBe(entry.receiptId);
    expect(next.queue.get(child)?.phase).toBe('accepted');
  });
  it('到期后的 404 保持待确认，仅查询，自动重试总数有上限', async () => {
    const child = crypto.randomUUID();
    const { queue, put, get } = setup(
      vi.fn().mockRejectedValue(new Error('timeout')),
      vi.fn().mockRejectedValue(statusError(404)),
    );
    queue.enqueue(child, new Date(Date.now() + 100).toISOString(), input);
    await flush();
    await vi.advanceTimersByTimeAsync(180000);
    expect(put).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledTimes(5);
    expect(queue.get(child)?.phase).toBe('unknown');
    expect(queue.get(child)?.input).toBeNull();
    await vi.advanceTimersByTimeAsync(120000);
    expect(get).toHaveBeenCalledTimes(5);
    queue.query(child);
    await flush();
    expect(get).toHaveBeenCalledTimes(6);
  });
  it('退出即中止在途并清队列，旧 ACK 不得污染新主体', async () => {
    let resolve!: (r: ImpressionResult) => void;
    const put = vi.fn(
      () =>
        new Promise<ImpressionResult>((r) => {
          resolve = r;
        }),
    );
    const { queue } = setup(put);
    const child = crypto.randomUUID();
    const entry = queue.enqueue(child, expiry(), input)!;
    const signal = (put.mock.calls as unknown as unknown[][])[0][3] as AbortSignal;
    revokeExposureSession();
    expect(signal.aborted).toBe(true);
    queue.bind({ scope: 'other-subject', epoch: exposureEpoch() });
    resolve(result(entry.receiptId, child));
    await flush();
    expect(queue.get(child)).toBeUndefined();
    expect(sessionStorage.getItem(RECEIPT_STORAGE_KEY)).not.toContain(child);
  });
  it('队列容量、请求并发及会话范围有界', async () => {
    const { queue, put } = setup(vi.fn(() => new Promise(() => {})));
    for (let i = 0; i < MAX_RECEIPTS; i += 1)
      expect(queue.enqueue(crypto.randomUUID(), expiry(), input)).not.toBeNull();
    expect(queue.enqueue(crypto.randomUUID(), expiry(), input)).toBeNull();
    expect(put).toHaveBeenCalledTimes(2);
    queue.bind({ scope: 'different', epoch: exposureEpoch() });
    expect(put).toHaveBeenCalledTimes(2);
    expect(JSON.parse(sessionStorage.getItem(RECEIPT_STORAGE_KEY)!).entries).toHaveLength(0);
  });
  it('离线不发送，存储不可用仍可在内存确认，非法恢复数据不入队', async () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
    const child = crypto.randomUUID();
    const put = vi.fn(async (id: string) => result(id, child));
    const { queue } = setup(put, vi.fn(), null);
    queue.enqueue(child, expiry(), input);
    expect(put).not.toHaveBeenCalled();
    expect(queue.storageAvailable).toBe(false);
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    queue.kick();
    await flush();
    expect(queue.get(child)?.phase).toBe('accepted');
    sessionStorage.setItem(
      RECEIPT_STORAGE_KEY,
      '{"scope":"test-subject","entries":[{"phase":"accepted"}]}',
    );
    const invalid = setup();
    await flush();
    expect(invalid.put).not.toHaveBeenCalled();
  });
});

it('恢复时拒绝状态与结果矛盾的数据，并只保留已知字段', async () => {
  const child = crypto.randomUUID(),
    receiptId = crypto.randomUUID();
  const saved = {
    childId: child,
    receiptId,
    createdAt: Date.now(),
    expiresAt: Date.now() + 300000,
    nextAt: Date.now(),
    attempts: 1,
    phase: 'rejected',
    input: null,
    result: result(receiptId, child),
  };
  sessionStorage.setItem(
    RECEIPT_STORAGE_KEY,
    JSON.stringify({ scope: 'test-subject', entries: [saved] }),
  );
  expect(setup().queue.get(child)).toBeUndefined();
  sessionStorage.setItem(
    RECEIPT_STORAGE_KEY,
    JSON.stringify({
      scope: 'test-subject',
      entries: [
        {
          ...saved,
          phase: 'accepted',
          authorization: 'unwanted-secret',
          result: { ...saved.result, qualificationToken: 'unwanted-secret' },
        },
      ],
    }),
  );
  const restored = setup().queue;
  expect(restored.get(child)?.phase).toBe('accepted');
  expect(sessionStorage.getItem(RECEIPT_STORAGE_KEY)).not.toContain('unwanted-secret');
});
