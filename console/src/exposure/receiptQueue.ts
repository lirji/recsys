import axios, { HttpStatusCode } from 'axios';
import {
  ImpressionStatus,
  getImpression,
  putImpression,
  type ExposureIdentity,
  type ImpressionInput,
  type ImpressionResult,
} from '../api/exposure';
import { exposureEpoch, onExposureRevoked } from './identity';

export const RECEIPT_STORAGE_KEY = 'recsys.sandbox.receipts.v1';
export const MAX_RECEIPTS = 100;
const MAX_BYTES = 512 * 1024;
const MAX_AGE_MS = 60 * 60 * 1000;
const MAX_ATTEMPTS = 6;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const ReceiptPhase = {
  PENDING: 'pending',
  SENDING: 'sending',
  UNKNOWN: 'unknown',
  ACCEPTED: 'accepted',
  REJECTED: 'rejected',
  BLOCKED: 'blocked',
  EXPIRED: 'expired',
} as const;
export type ReceiptPhase = (typeof ReceiptPhase)[keyof typeof ReceiptPhase];
export interface ReceiptEntry {
  readonly childId: string;
  readonly receiptId: string;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly input: ImpressionInput | null;
  readonly phase: ReceiptPhase;
  readonly attempts: number;
  readonly nextAt: number;
  readonly result?: ImpressionResult;
}
type Transport = { put: typeof putImpression; get: typeof getImpression };
const terminal = (phase: ReceiptPhase) =>
  (
    [
      ReceiptPhase.ACCEPTED,
      ReceiptPhase.REJECTED,
      ReceiptPhase.BLOCKED,
      ReceiptPhase.EXPIRED,
    ] as ReceiptPhase[]
  ).includes(phase);

/** 会话队列只是同 ID 恢复优化，不是账本；金额/是否收费只采用服务端原结果。 */
export class ReceiptQueue {
  private identity: ExposureIdentity | null = null;
  private entries = new Map<string, ReceiptEntry>();
  private listeners = new Set<() => void>();
  private requests = new Map<string, AbortController>();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private unsubscribe: () => void;
  storageAvailable = true;
  constructor(
    private storage: Storage | null,
    private transport: Transport = { put: putImpression, get: getImpression },
    private now: () => number = Date.now,
  ) {
    this.unsubscribe = onExposureRevoked(() => this.clear());
  }
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  get = (childId: string) => this.entries.get(childId);
  /** 主体不一致的旧会话不能借当前 Bearer 恢复。 */
  bind(identity: ExposureIdentity) {
    if (identity.epoch !== exposureEpoch()) return;
    if (this.identity?.scope === identity.scope && this.identity.epoch === identity.epoch) return;
    this.cancel();
    this.entries.clear();
    this.identity = identity;
    try {
      const raw = this.storage?.getItem(RECEIPT_STORAGE_KEY);
      if (raw && new TextEncoder().encode(raw).length <= MAX_BYTES) {
        const saved = JSON.parse(raw) as { scope?: unknown; entries?: unknown };
        if (
          saved.scope === identity.scope &&
          Array.isArray(saved.entries) &&
          saved.entries.length <= MAX_RECEIPTS
        ) {
          for (const value of saved.entries) {
            if (this.validSaved(value)) {
              const entry = value as ReceiptEntry;
              this.entries.set(
                entry.childId,
                Object.freeze({
                  childId: entry.childId,
                  receiptId: entry.receiptId,
                  createdAt: entry.createdAt,
                  expiresAt: entry.expiresAt,
                  attempts: entry.attempts,
                  nextAt: entry.nextAt,
                  input: terminal(entry.phase)
                    ? null
                    : entry.input &&
                      Object.freeze({
                        qualificationToken: entry.input.qualificationToken,
                        visibleDurationMs: entry.input.visibleDurationMs,
                        minVisibleRatioBps: entry.input.minVisibleRatioBps,
                        pageVisible: true,
                      }),
                  result: entry.result && this.knownResult(entry.result),
                  phase: entry.phase === ReceiptPhase.SENDING ? ReceiptPhase.UNKNOWN : entry.phase,
                }),
              );
            }
          }
        }
      }
    } catch {
      this.storageAvailable = false;
    }
    this.publish();
    this.kick();
  }
  /** 只有观测达标才调用；同资格重挂载、刷新继续使用原 receiptId。 */
  enqueue(childId: string, expiresAt: string, input: ImpressionInput): ReceiptEntry | null {
    if (!this.identity || this.identity.epoch !== exposureEpoch() || !UUID.test(childId))
      return null;
    const old = this.entries.get(childId);
    if (old) return old;
    this.prune();
    const expiry = Date.parse(expiresAt);
    if (this.entries.size >= MAX_RECEIPTS || !Number.isFinite(expiry) || expiry <= this.now())
      return null;
    const entry: ReceiptEntry = Object.freeze({
      childId,
      receiptId: crypto.randomUUID(),
      createdAt: this.now(),
      expiresAt: expiry,
      input: Object.freeze({ ...input }),
      phase: ReceiptPhase.PENDING,
      attempts: 0,
      nextAt: this.now(),
    });
    const candidate = JSON.stringify({
      scope: this.identity.scope,
      entries: [...this.entries.values(), entry],
    });
    if (new TextEncoder().encode(candidate).length > MAX_BYTES) return null;
    this.entries.set(childId, entry);
    this.publish();
    this.kick();
    return entry;
  }
  /** 手动恢复仅查询原 ID，不重置自动重试次数或创建新收费意图。 */
  query(childId: string) {
    const entry = this.entries.get(childId);
    if (!entry || terminal(entry.phase) || this.requests.size >= 2 || this.requests.has(childId))
      return;
    void this.run(entry, true);
  }
  /** 在线恢复仅处理已经达到政策的条目，不补造离线期间的曝光。 */
  kick = () => {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    if (!this.identity || this.identity.epoch !== exposureEpoch() || !navigator.onLine) return;
    this.prune();
    for (const entry of this.entries.values()) {
      if (this.requests.size >= 2) break;
      if (
        !terminal(entry.phase) &&
        entry.attempts < MAX_ATTEMPTS &&
        !this.requests.has(entry.childId) &&
        entry.nextAt <= this.now()
      ) {
        void this.run(entry, false);
      }
    }
    const future = [...this.entries.values()].filter(
      (e) => !terminal(e.phase) && e.attempts < MAX_ATTEMPTS && !this.requests.has(e.childId),
    );
    if (future.length)
      this.timer = setTimeout(
        this.kick,
        Math.max(100, Math.min(...future.map((e) => e.nextAt)) - this.now()),
      );
  };
  clear() {
    this.cancel();
    this.identity = null;
    this.entries.clear();
    try {
      this.storage?.removeItem(RECEIPT_STORAGE_KEY);
    } catch {
      this.storageAvailable = false;
    }
    this.listeners.forEach((listener) => listener());
  }
  dispose() {
    this.cancel();
    this.unsubscribe();
    this.listeners.clear();
  }
  private cancel() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.requests.forEach((controller) => controller.abort());
    this.requests.clear();
  }
  private prune() {
    for (const entry of this.entries.values()) {
      if (this.now() - entry.createdAt >= MAX_AGE_MS) this.entries.delete(entry.childId);
    }
  }
  private update(entry: ReceiptEntry) {
    this.entries.set(entry.childId, Object.freeze(entry));
    this.publish();
  }
  private publish() {
    try {
      if (!this.storage) this.storageAvailable = false;
      else if (this.identity) {
        const data = JSON.stringify({
          scope: this.identity.scope,
          entries: [...this.entries.values()],
        });
        if (new TextEncoder().encode(data).length > MAX_BYTES) throw new Error('会话队列超过容量');
        this.storage.setItem(RECEIPT_STORAGE_KEY, data);
      }
    } catch {
      this.storageAvailable = false;
    }
    this.listeners.forEach((listener) => listener());
  }
  private async run(entry: ReceiptEntry, queryOnly: boolean) {
    const identity = this.identity;
    if (!identity || identity.epoch !== exposureEpoch()) return;
    if (entry.attempts === 0 && this.now() >= entry.expiresAt) {
      this.update({ ...entry, input: null, phase: ReceiptPhase.EXPIRED });
      return;
    }
    const controller = new AbortController();
    this.requests.set(entry.childId, controller);
    const current = {
      ...entry,
      phase: ReceiptPhase.SENDING,
      attempts: entry.attempts + (queryOnly ? 0 : 1),
    };
    this.update(current);
    const sameIdentity = () =>
      this.identity === identity &&
      exposureEpoch() === identity.epoch &&
      !controller.signal.aborted;
    try {
      let result: ImpressionResult;
      if (entry.attempts > 0 || queryOnly || !entry.input) {
        try {
          result = await this.transport.get(entry.receiptId, identity, controller.signal);
        } catch (error) {
          if (
            !sameIdentity() ||
            queryOnly ||
            !axios.isAxiosError(error) ||
            error.response?.status !== HttpStatusCode.NotFound ||
            !entry.input ||
            this.now() >= entry.expiresAt
          )
            throw error;
          result = await this.transport.put(
            entry.receiptId,
            entry.input,
            identity,
            controller.signal,
          );
        }
      } else
        result = await this.transport.put(
          entry.receiptId,
          entry.input,
          identity,
          controller.signal,
        );
      if (!sameIdentity()) return;
      this.validateResult(entry, result);
      this.update({
        ...current,
        result: this.knownResult(result),
        input: null,
        phase:
          result.status === ImpressionStatus.ACCEPTED ||
          result.status === ImpressionStatus.ALREADY_ACCEPTED
            ? ReceiptPhase.ACCEPTED
            : ReceiptPhase.REJECTED,
      });
    } catch (error) {
      if (!sameIdentity()) return;
      const status = axios.isAxiosError(error) ? error.response?.status : undefined;
      const blocked =
        status === HttpStatusCode.BadRequest ||
        status === HttpStatusCode.Unauthorized ||
        status === HttpStatusCode.Forbidden ||
        status === HttpStatusCode.Conflict;
      this.update({
        ...current,
        phase: blocked ? ReceiptPhase.BLOCKED : ReceiptPhase.UNKNOWN,
        input: blocked || this.now() >= entry.expiresAt ? null : entry.input,
        nextAt: this.now() + Math.min(30000, 1000 * 2 ** Math.min(current.attempts - 1, 5)),
      });
    } finally {
      if (this.requests.get(entry.childId) === controller) this.requests.delete(entry.childId);
      if (sameIdentity()) this.kick();
    }
  }
  private validateResult(entry: ReceiptEntry, result: ImpressionResult) {
    if (
      !result ||
      result.receiptId !== entry.receiptId ||
      result.qualificationId !== entry.childId ||
      ![
        ImpressionStatus.ACCEPTED,
        ImpressionStatus.ALREADY_ACCEPTED,
        ImpressionStatus.POLICY_NOT_MET,
        ImpressionStatus.EXPIRED,
        ImpressionStatus.NOT_HELD,
      ].includes(result.status) ||
      !/^\d{1,14}\.\d{6}$/.test(result.chargedAmount) ||
      !Number.isFinite(Date.parse(result.decidedAt)) ||
      ((
        [
          ImpressionStatus.ACCEPTED,
          ImpressionStatus.ALREADY_ACCEPTED,
        ] as ImpressionResult['status'][]
      ).includes(result.status) &&
        (!result.ledgerId ||
          !UUID.test(result.ledgerId) ||
          !result.canonicalReceiptId ||
          !UUID.test(result.canonicalReceiptId) ||
          !result.acceptedAt ||
          !Number.isFinite(Date.parse(result.acceptedAt)) ||
          !result.budgetDate ||
          !/^\d{4}-\d{2}-\d{2}$/.test(result.budgetDate))) ||
      (!(
        [
          ImpressionStatus.ACCEPTED,
          ImpressionStatus.ALREADY_ACCEPTED,
        ] as ImpressionResult['status'][]
      ).includes(result.status) &&
        (result.chargedAmount !== '0.000000' ||
          result.ledgerId !== null ||
          result.acceptedAt !== null))
    ) {
      throw new Error('回执结果不能确认，请按原 ID 查询');
    }
  }
  /** 会话只保留已知结果字段，避免扩展响应/存储混入票据或无限增长的调试内容。 */
  private knownResult(result: ImpressionResult): ImpressionResult {
    const {
      receiptId,
      qualificationId,
      status,
      ledgerId,
      canonicalReceiptId,
      chargedAmount,
      budgetDate,
      acceptedAt,
      decidedAt,
    } = result;
    return {
      receiptId,
      qualificationId,
      status,
      ledgerId,
      canonicalReceiptId,
      chargedAmount,
      budgetDate,
      acceptedAt,
      decidedAt,
    };
  }
  private validSaved(value: unknown): value is ReceiptEntry {
    if (!value || typeof value !== 'object') return false;
    const e = value as ReceiptEntry;
    if (
      typeof e.childId !== 'string' ||
      !UUID.test(e.childId) ||
      typeof e.receiptId !== 'string' ||
      !UUID.test(e.receiptId) ||
      !Number.isFinite(e.createdAt) ||
      e.createdAt > this.now() ||
      this.now() - e.createdAt >= MAX_AGE_MS ||
      !Number.isFinite(e.expiresAt) ||
      !Number.isFinite(e.nextAt) ||
      !Number.isInteger(e.attempts) ||
      e.attempts < 0 ||
      e.attempts > MAX_ATTEMPTS ||
      ![
        ReceiptPhase.PENDING,
        ReceiptPhase.SENDING,
        ReceiptPhase.UNKNOWN,
        ReceiptPhase.ACCEPTED,
        ReceiptPhase.REJECTED,
        ReceiptPhase.BLOCKED,
        ReceiptPhase.EXPIRED,
      ].includes(e.phase)
    )
      return false;
    if (
      e.input !== null &&
      (!e.input ||
        typeof e.input.qualificationToken !== 'string' ||
        e.input.qualificationToken.length > 16384 ||
        !Number.isInteger(e.input.visibleDurationMs) ||
        e.input.visibleDurationMs < 1000 ||
        e.input.visibleDurationMs > 300000 ||
        !Number.isInteger(e.input.minVisibleRatioBps) ||
        e.input.minVisibleRatioBps < 5000 ||
        e.input.minVisibleRatioBps > 10000 ||
        e.input.pageVisible !== true)
    )
      return false;
    if ((e.phase === ReceiptPhase.ACCEPTED || e.phase === ReceiptPhase.REJECTED) && !e.result)
      return false;
    if (e.result) {
      try {
        this.validateResult(e, e.result);
      } catch {
        return false;
      }
      const accepted = (
        [
          ImpressionStatus.ACCEPTED,
          ImpressionStatus.ALREADY_ACCEPTED,
        ] as ImpressionResult['status'][]
      ).includes(e.result.status);
      if (
        (e.phase === ReceiptPhase.ACCEPTED) !== accepted ||
        !([ReceiptPhase.ACCEPTED, ReceiptPhase.REJECTED] as ReceiptPhase[]).includes(e.phase)
      )
        return false;
    }
    return true;
  }
}
let singleton: ReceiptQueue | undefined;
export function receiptQueue() {
  if (!singleton) {
    let storage: Storage | null;
    try {
      storage = sessionStorage;
    } catch {
      storage = null;
    }
    singleton = new ReceiptQueue(storage);
    window.addEventListener('online', singleton.kick);
  }
  return singleton;
}
