import axios, { HttpStatusCode } from 'axios';
import { type ExposureIdentity, type ImpressionInput } from '../api/exposure';
import { exposureEpoch, onExposureRevoked } from './identity';

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
export interface ReceiptResult {
  receiptId: string;
  qualificationId: string;
  status: string;
}
export interface ReceiptEntry<R extends ReceiptResult> {
  readonly childId: string;
  readonly receiptId: string;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly input: ImpressionInput | null;
  readonly phase: ReceiptPhase;
  readonly attempts: number;
  readonly nextAt: number;
  readonly result?: R;
}
export type ReceiptTransport<R> = {
  put: (
    id: string,
    input: ImpressionInput,
    identity: ExposureIdentity,
    signal?: AbortSignal,
  ) => Promise<R>;
  get: (id: string, identity: ExposureIdentity, signal?: AbortSignal) => Promise<R>;
};
export interface ReceiptProtocol<R extends ReceiptResult> {
  storageKey: string;
  validate: (entry: Pick<ReceiptEntry<R>, 'childId' | 'receiptId'>, result: R) => void;
  known: (result: R) => R;
  accepted: (result: R) => boolean;
}
const terminal = (phase: ReceiptPhase) =>
  (
    [
      ReceiptPhase.ACCEPTED,
      ReceiptPhase.REJECTED,
      ReceiptPhase.BLOCKED,
      ReceiptPhase.EXPIRED,
    ] as ReceiptPhase[]
  ).includes(phase);

/** 共享有界恢复算法；具体协议负责结果校验，服务端原结果是唯一业务事实。 */
export class ReceiptQueueCore<R extends ReceiptResult> {
  private identity: ExposureIdentity | null = null;
  private entries = new Map<string, ReceiptEntry<R>>();
  private listeners = new Set<() => void>();
  private requests = new Map<string, AbortController>();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private unsubscribe: () => void;
  storageAvailable = true;
  constructor(
    private protocol: ReceiptProtocol<R>,
    private storage: Storage | null,
    private transport: ReceiptTransport<R>,
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
      const raw = this.storage?.getItem(this.protocol.storageKey);
      if (raw && new TextEncoder().encode(raw).length <= MAX_BYTES) {
        const saved = JSON.parse(raw) as { scope?: unknown; entries?: unknown };
        if (
          saved.scope === identity.scope &&
          Array.isArray(saved.entries) &&
          saved.entries.length <= MAX_RECEIPTS
        ) {
          for (const value of saved.entries) {
            if (this.validSaved(value)) {
              const entry = value as ReceiptEntry<R>;
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
                  result: entry.result && this.protocol.known(entry.result),
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
  enqueue(childId: string, expiresAt: string, input: ImpressionInput): ReceiptEntry<R> | null {
    if (!this.identity || this.identity.epoch !== exposureEpoch() || !UUID.test(childId))
      return null;
    const old = this.entries.get(childId);
    if (old) return old;
    this.prune();
    const expiry = Date.parse(expiresAt);
    if (this.entries.size >= MAX_RECEIPTS || !Number.isFinite(expiry) || expiry <= this.now())
      return null;
    const entry: ReceiptEntry<R> = Object.freeze({
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
  /** 手动恢复仅查询原 ID，不重置自动重试次数或创建新业务意图。 */
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
      this.storage?.removeItem(this.protocol.storageKey);
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
  private update(entry: ReceiptEntry<R>) {
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
        this.storage.setItem(this.protocol.storageKey, data);
      }
    } catch {
      this.storageAvailable = false;
    }
    this.listeners.forEach((listener) => listener());
  }
  private async run(entry: ReceiptEntry<R>, queryOnly: boolean) {
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
      let result: R;
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
      this.protocol.validate(entry, result);
      this.update({
        ...current,
        result: this.protocol.known(result),
        input: null,
        phase: this.protocol.accepted(result) ? ReceiptPhase.ACCEPTED : ReceiptPhase.REJECTED,
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
  private validSaved(value: unknown): value is ReceiptEntry<R> {
    if (!value || typeof value !== 'object') return false;
    const e = value as ReceiptEntry<R>;
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
        this.protocol.validate(e, e.result);
      } catch {
        return false;
      }
      const accepted = this.protocol.accepted(e.result);
      if (
        (e.phase === ReceiptPhase.ACCEPTED) !== accepted ||
        !([ReceiptPhase.ACCEPTED, ReceiptPhase.REJECTED] as ReceiptPhase[]).includes(e.phase)
      )
        return false;
    }
    return true;
  }
}
