import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import { ActionReceiptQueue, ACTION_STORAGE_KEY, actionKey } from '../actionReceiptQueue';
import { exposureEpoch, revokeExposureSession } from '../identity';
import {
  parseActionResult,
  type ActionInput,
  type ActionKind,
  type ActionResult,
} from '../../api/sandboxActions';
const click: ActionInput = {
  kind: 'CLICK',
  qualificationToken: 'opaque-proof',
  visibleDurationMs: 0,
  minVisibleRatioBps: 0,
  pageVisible: false,
};
const visible: ActionInput = {
  ...click,
  kind: 'VISIBLE',
  visibleDurationMs: 1000,
  minVisibleRatioBps: 5000,
  pageVisible: true,
};
const identity = () => ({ scope: 'test-subject', epoch: exposureEpoch() });
const expiry = () => new Date(Date.now() + 300000).toISOString();
const error = (status: number) =>
  new AxiosError('status', undefined, undefined, undefined, { status } as never);
const queues: ActionReceiptQueue[] = [];
const flush = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};
function result(id: string, child: string, kind: ActionKind, captured = false): ActionResult {
  return {
    receipt: {
      receiptId: id,
      qualificationId: child,
      kind,
      status: 'ACCEPTED_WAITING',
      canonicalReceiptId: id,
      acceptedAt: new Date().toISOString(),
      decidedAt: new Date().toISOString(),
    },
    settlement: {
      qualificationId: child,
      decision: 'HELD',
      budgetDate: '2026-10-16',
      quotedAmount: '0.000000',
      issuedAt: new Date().toISOString(),
      expiresAt: expiry(),
      preparedAt: new Date().toISOString(),
      state: captured ? 'CAPTURED' : 'HELD',
      progress: captured
        ? 'CAPTURED'
        : kind === 'CLICK'
          ? 'WAITING_FOR_VISIBLE'
          : 'WAITING_FOR_CLICK',
      version: captured ? 2 : 1,
      visibleReceiptId: captured || kind === 'VISIBLE' ? crypto.randomUUID() : null,
      clickReceiptId: captured || kind === 'CLICK' ? crypto.randomUUID() : null,
      ledgerId: captured ? crypto.randomUUID() : null,
      chargedAmount: captured ? '0.000000' : null,
      closedAt: captured ? new Date().toISOString() : null,
      closeReason: captured ? 'CLICK_CAPTURED' : null,
    },
  };
}
function setup(receipt = vi.fn(), settlement = vi.fn(), storage: Storage | null = sessionStorage) {
  const queue = new ActionReceiptQueue(storage, { receipt, settlement });
  queues.push(queue);
  queue.bind(identity());
  return { queue, receipt };
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
describe('CPC 双条件恢复', () => {
  it('CLICK 保留零观测和稳定 ID，原 WAITING 查询可返回零元实收', async () => {
    const receipt = vi.fn(async (method, id, child, kind) =>
      result(id, child, kind, method === 'get'),
    );
    const { queue } = setup(receipt);
    const child = crypto.randomUUID();
    const e = queue.enqueue(child, expiry(), click)!;
    expect(queue.enqueue(child, expiry(), click)?.id).toBe(e.id);
    await flush();
    expect(receipt.mock.calls[0].slice(0, 5)).toEqual(['put', e.id, child, 'CLICK', click]);
    expect(queue.settlement(child)?.chargedAmount).toBeNull();
    expect(sessionStorage.getItem(ACTION_STORAGE_KEY)).not.toContain('opaque-proof');
    await vi.advanceTimersByTimeAsync(2000);
    expect(receipt.mock.calls.map((c) => c[0])).toEqual(['put', 'get']);
    expect(queue.get(e.key)?.result?.receipt.status).toBe('ACCEPTED_WAITING');
    expect(queue.settlement(child)?.chargedAmount).toBe('0.000000');
    queue.query(e.key);
    await flush();
    expect(receipt.mock.calls.at(-1)?.[0]).toBe('get');
  });
  it('丢 ACK 刷新先 GET，404 后按原 ID 和负载 PUT', async () => {
    const first = setup(vi.fn().mockRejectedValue(new Error('lost ACK'))).queue;
    const child = crypto.randomUUID(),
      e = first.enqueue(child, expiry(), click)!;
    await flush();
    first.dispose();
    const receipt = vi.fn(async (method, id, child, kind) => {
      if (method === 'get') throw error(404);
      return result(id, child, kind, true);
    });
    const next = setup(receipt);
    await flush();
    expect(receipt.mock.calls.map((c) => c[0])).toEqual(['get', 'put']);
    expect(receipt.mock.calls[1].slice(1, 5)).toEqual([e.id, child, 'CLICK', click]);
    expect(next.queue.get(e.key)?.attempts).toBe(2);
  });
  it('不同 kind 独立、旧版本不覆盖新结算，串号和错误金额拒绝', async () => {
    const { queue } = setup(
      vi.fn(async (_, id, child, kind) => result(id, child, kind, kind === 'CLICK')),
    );
    const child = crypto.randomUUID();
    queue.enqueue(child, expiry(), click);
    queue.enqueue(child, expiry(), visible);
    await flush();
    expect(queue.settlement(child)?.state).toBe('CAPTURED');
    expect(queue.get(actionKey(child, 'VISIBLE'))?.id).not.toBe(
      queue.get(actionKey(child, 'CLICK'))?.id,
    );
    const id = crypto.randomUUID();
    expect(() => parseActionResult(result(id, child, 'CLICK'), id, child, 'VISIBLE')).toThrow();
    expect(() =>
      parseActionResult(result(id, child, 'CLICK'), id, crypto.randomUUID(), 'CLICK'),
    ).toThrow();
    const bad = result(id, child, 'CLICK', true);
    bad.settlement.chargedAmount = null;
    expect(() => parseActionResult(bad, id, child, 'CLICK')).toThrow();
  });
  it('过期清票据、自动最多六次，409 阻止重写但允许原 ID 查询', async () => {
    const { queue, receipt } = setup(vi.fn().mockRejectedValue(error(503)));
    const e = queue.enqueue(crypto.randomUUID(), new Date(Date.now() + 100).toISOString(), click)!;
    await flush();
    await vi.advanceTimersByTimeAsync(200000);
    expect(receipt).toHaveBeenCalledTimes(6);
    expect(receipt.mock.calls.filter((c) => c[0] === 'put')).toHaveLength(1);
    expect(queue.get(e.key)?.input).toBeNull();
    queue.query(e.key);
    await flush();
    expect(receipt).toHaveBeenCalledTimes(7);
    receipt.mockRejectedValue(error(409));
    queue.query(e.key);
    await flush();
    expect(queue.get(e.key)?.phase).toBe('blocked');
    await vi.advanceTimersByTimeAsync(30000);
    expect(receipt).toHaveBeenCalledTimes(8);
  });
  it('容量并发有界，撤销立即中止，迟到响应不能污染新身份', async () => {
    let resolve!: (v: ActionResult) => void;
    const receipt = vi.fn(
      () =>
        new Promise<ActionResult>((r) => {
          resolve = r;
        }),
    );
    const { queue } = setup(receipt);
    const child = crypto.randomUUID(),
      e = queue.enqueue(child, expiry(), click)!;
    for (let i = 1; i < 100; i++) queue.enqueue(crypto.randomUUID(), expiry(), click);
    expect(queue.enqueue(crypto.randomUUID(), expiry(), click)).toBeNull();
    expect(receipt).toHaveBeenCalledTimes(2);
    const signal = (receipt.mock.calls as unknown as unknown[][])[0][6] as AbortSignal;
    revokeExposureSession();
    expect(signal.aborted).toBe(true);
    queue.bind({ scope: 'different', epoch: exposureEpoch() });
    resolve(result(e.id, child, 'CLICK'));
    await flush();
    expect(queue.get(e.key)).toBeUndefined();
  });
  it('拒绝错误 CLICK 恢复负载，禁用存储仍有明确内存边界', () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
    const { queue } = setup();
    const e = queue.enqueue(crypto.randomUUID(), expiry(), click)!;
    queue.dispose();
    const saved = JSON.parse(sessionStorage.getItem(ACTION_STORAGE_KEY)!);
    saved.entries[0].input.pageVisible = true;
    sessionStorage.setItem(ACTION_STORAGE_KEY, JSON.stringify(saved));
    expect(setup().queue.get(e.key)).toBeUndefined();
    expect(setup(vi.fn(), vi.fn(), null).queue.storageAvailable).toBe(false);
  });
});
