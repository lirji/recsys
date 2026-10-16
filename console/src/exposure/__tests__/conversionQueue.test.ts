import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import { ActionReceiptQueue, ACTION_STORAGE_KEY, outcomeKey } from '../actionReceiptQueue';
import { exposureEpoch, revokeExposureSession } from '../identity';
import {
  parseConversionOutcome,
  validOccurredAt,
  type ConversionInput,
  type ConversionOutcome,
} from '../../api/sandboxActions';
const queues: ActionReceiptQueue[] = [];
const input: ConversionInput = {
  kind: 'CONVERSION',
  advertiserId: '9223372036854775807',
  qualificationToken: 'private-proof',
  occurredAt: '2026-10-16T02:00:00.123456Z',
};
const failure = (status: number) =>
  new AxiosError('status', undefined, undefined, undefined, { status } as never);
const flush = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};
function outcome(id: string, captured = false, duplicate = false): ConversionOutcome {
  const original = crypto.randomUUID();
  return {
    outcomeId: original,
    advertiserId: input.advertiserId,
    eventId: id,
    qualificationId: crypto.randomUUID(),
    status: duplicate ? 'DUPLICATE_QUALIFICATION' : 'ACCEPTED_WAITING',
    occurredAt: input.occurredAt,
    receivedAt: new Date().toISOString(),
    decidedAt: new Date().toISOString(),
    canonicalOutcomeId: duplicate ? crypto.randomUUID() : original,
    chargedForThisOutcome: captured && !duplicate,
    chargedAmount: captured && !duplicate ? '0.234567' : null,
    settlement: {
      state: captured ? 'CAPTURED' : 'HELD',
      progress: captured ? 'CAPTURED' : 'WAITING_FOR_VISIBLE',
      budgetDate: '2026-10-16',
      quotedAmount: '0.234567',
      chargedAmount: captured ? '0.234567' : null,
      ledgerId: captured ? crypto.randomUUID() : null,
      closedAt: captured ? new Date().toISOString() : null,
    },
  };
}
function setup(conversion = vi.fn()) {
  const q = new ActionReceiptQueue(sessionStorage, {
    receipt: vi.fn(),
    settlement: vi.fn(),
    conversion,
  });
  queues.push(q);
  q.bind({ scope: 'actor', epoch: exposureEpoch() });
  return q;
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
it('业务事件冻结时间、丢 ACK 刷新先查，404 原负载恢复', async () => {
  const event = crypto.randomUUID(),
    first = setup(vi.fn().mockRejectedValue(new Error('lost')));
  first.submitConversion(event, input);
  await flush();
  first.dispose();
  const conversion = vi.fn(async (method, _adv, id) => {
    if (method === 'get') throw failure(404);
    return outcome(id, true);
  });
  const q = setup(conversion);
  await flush();
  expect(conversion.mock.calls.map((c) => c[0])).toEqual(['get', 'put']);
  expect(conversion.mock.calls[1].slice(1, 4)).toEqual([input.advertiserId, event, input]);
  expect(q.get(outcomeKey(input.advertiserId, event))?.outcome?.chargedForThisOutcome).toBe(true);
  expect(sessionStorage.getItem(ACTION_STORAGE_KEY)).not.toContain('private-proof');
});
it('原 WAITING 可以查询实收，重复资格自身不收费；微秒串时间拒绝', async () => {
  const id = crypto.randomUUID();
  const waiting = outcome(id),
    captured = {
      ...waiting,
      chargedForThisOutcome: true,
      chargedAmount: '0.234567',
      settlement: outcome(id, true).settlement,
    };
  const transport = vi.fn().mockResolvedValueOnce(waiting).mockResolvedValue(captured);
  const q = setup(transport);
  q.submitConversion(id, input);
  await flush();
  expect(q.get(outcomeKey(input.advertiserId, id))?.outcome?.chargedForThisOutcome).toBe(false);
  await vi.advanceTimersByTimeAsync(2000);
  expect(q.get(outcomeKey(input.advertiserId, id))?.outcome?.chargedForThisOutcome).toBe(true);
  const duplicate = outcome(id, true, true);
  expect(parseConversionOutcome(duplicate, input.advertiserId, id).chargedAmount).toBeNull();
  expect(() =>
    parseConversionOutcome(
      { ...duplicate, chargedForThisOutcome: true, chargedAmount: '0.234567' },
      input.advertiserId,
      id,
    ),
  ).toThrow();
  expect(() =>
    parseConversionOutcome(waiting, input.advertiserId, id, {
      occurredAt: '2026-10-16T02:00:00.123455Z',
    }),
  ).toThrow();
  expect(validOccurredAt('2026-10-16T02:00:00.1234567Z')).toBe(false);
  expect(validOccurredAt('2026-02-30T02:00:00Z')).toBe(false);
});
it('403 清除证明且不重 PUT，SDK 故障不伪造成功，撤销清全部事件', async () => {
  const id = crypto.randomUUID(),
    transport = vi.fn().mockRejectedValue(failure(403)),
    q = setup(transport);
  q.submitConversion(id, input);
  await flush();
  const key = outcomeKey(input.advertiserId, id);
  expect(q.get(key)?.phase).toBe('blocked');
  expect(q.get(key)?.input).toBeNull();
  await vi.advanceTimersByTimeAsync(10000);
  expect(transport).toHaveBeenCalledTimes(1);
  transport.mockRejectedValue(failure(503));
  q.query(key);
  await flush();
  expect(q.get(key)?.outcome).toBeUndefined();
  expect(transport.mock.calls[1][0]).toBe('get');
  revokeExposureSession();
  expect(q.get(key)).toBeUndefined();
});
it('CPA 和 CPC 共享容量，两种可见协议不会共用回执或结算', () => {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
  const q = setup();
  const child = crypto.randomUUID(),
    expiry = new Date(Date.now() + 300000).toISOString();
  const visible = {
    kind: 'VISIBLE' as const,
    qualificationToken: 'private-proof',
    visibleDurationMs: 1000,
    minVisibleRatioBps: 5000,
    pageVisible: true,
  };
  expect(q.enqueue(child, expiry, visible, 'click')?.key).not.toBe(
    q.enqueue(child, expiry, visible, 'conversion')?.key,
  );
  for (let i = 0; i < 98; i++)
    expect(q.submitConversion(crypto.randomUUID(), input)).not.toBeNull();
  expect(q.submitConversion(crypto.randomUUID(), input)).toBeNull();
  expect(q.watchConversion(input.advertiserId, crypto.randomUUID())).toBeNull();
});
it('同一业务事件不接受替换时间或证明，已确认事件只能查询', async () => {
  const id = crypto.randomUUID(),
    q = setup(vi.fn().mockResolvedValue(outcome(id)));
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
  const first = q.submitConversion(id, input);
  expect(q.submitConversion(id, input)).toBe(first);
  expect(q.submitConversion(id, { ...input, occurredAt: '2026-10-16T02:00:01Z' })).toBeNull();
  expect(q.submitConversion(id, { ...input, qualificationToken: 'different-proof' })).toBeNull();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
  q.kick();
  await flush();
  expect(q.submitConversion(id, input)).toBeNull();
  expect(q.watchConversion(input.advertiserId, id)).not.toBeNull();
  expect(() =>
    parseConversionOutcome(
      {
        ...outcome(id),
        settlement: { ...outcome(id).settlement, closedAt: new Date().toISOString() },
      },
      input.advertiserId,
      id,
    ),
  ).toThrow();
});
