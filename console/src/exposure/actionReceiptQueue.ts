import { ACTION_POLICY, ActionKinds, ActionState } from '../api/sandboxActions';
import { HttpStatusCode } from 'axios';
import axios from 'axios';
import {
  actionReceipt,
  actionSettlement,
  assertAction,
  parseActionResult,
  parseActionSettlement,
  UUID_PATTERN,
  type ActionInput,
  type ActionKind,
  type ActionResult,
  type ActionSettlement,
} from '../api/sandboxActions';
import type { ExposureIdentity } from '../api/exposure';
import { toApiError } from '../api/errors';
import { exposureEpoch, onExposureRevoked } from './identity';

export const ACTION_STORAGE_KEY = 'recsys.sandbox.actions.v1';
export const ACTION_LIMITS = {
  entries: 100,
  bytes: 512 * 1024,
  age: 3600000,
  attempts: 6,
  concurrent: 2,
} as const;
export type ActionPhase = 'pending' | 'sending' | 'unknown' | 'accepted' | 'rejected' | 'blocked';
export interface ActionEntry {
  key: string;
  childId: string;
  id: string;
  kind: ActionKind;
  createdAt: number;
  expiresAt: number;
  input: Readonly<ActionInput> | null;
  phase: ActionPhase;
  attempts: number;
  nextAt: number;
  result?: ActionResult;
  error?: string;
}
export const actionKey = (childId: string, kind: ActionKind) => `click:${kind}:${childId}`;
export interface ActionTransport {
  receipt: typeof actionReceipt;
  settlement: typeof actionSettlement;
}
/** 新两条件协议独占有限预算；已接受只是事件终态，等待结算仍能原ID查询。 */
export class ActionReceiptQueue {
  private identity: ExposureIdentity | null = null;
  private entries = new Map<string, ActionEntry>();
  private settlements = new Map<string, ActionSettlement>();
  private settlementErrors = new Map<string, string>();
  private requests = new Map<string, AbortController>();
  private listeners = new Set<() => void>();
  private revision = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private unsubscribe: () => void;
  storageAvailable = true;
  constructor(
    private storage: Storage | null,
    private transport: ActionTransport = { receipt: actionReceipt, settlement: actionSettlement },
    private now: () => number = Date.now,
  ) {
    this.unsubscribe = onExposureRevoked(() => this.clear());
  }
  subscribe = (fn: () => void) => {
    this.listeners.add(fn);
    return () => {
      this.listeners.delete(fn);
    };
  };
  snapshot = () => this.revision;
  get = (key: string) => this.entries.get(key);
  settlement = (id: string) => this.settlements.get(id);
  settlementError = (id: string) => this.settlementErrors.get(id);
  busy = (key: string) => this.requests.has(key);
  private active(owner: ExposureIdentity, controller?: AbortController) {
    return (
      this.identity === owner && owner.epoch === exposureEpoch() && !controller?.signal.aborted
    );
  }
  bind(identity: ExposureIdentity) {
    if (
      identity.epoch !== exposureEpoch() ||
      (this.identity?.scope === identity.scope && this.identity.epoch === identity.epoch)
    )
      return;
    this.cancel();
    this.entries.clear();
    this.settlements.clear();
    this.settlementErrors.clear();
    this.identity = identity;
    try {
      const raw = this.storage?.getItem(ACTION_STORAGE_KEY);
      if (raw && new TextEncoder().encode(raw).length <= ACTION_LIMITS.bytes) {
        const saved = JSON.parse(raw);
        if (
          saved.scope === identity.scope &&
          Array.isArray(saved.entries) &&
          saved.entries.length <= ACTION_LIMITS.entries
        ) {
          for (const value of saved.entries) {
            const entry = this.restore(value);
            if (entry) this.entries.set(entry.key, entry);
          }
        }
      }
    } catch {
      this.storageAvailable = false;
    }
    this.publish();
    this.kick();
  }
  /** 同资格不同kind分开；重挂载/连点命中原意图，不生成第二条回执。 */
  enqueue(childId: string, expiresAt: string, input: ActionInput): ActionEntry | null {
    const owner = this.identity;
    if (!owner || !this.active(owner) || !UUID_PATTERN.test(childId)) return null;
    const key = actionKey(childId, input.kind),
      old = this.entries.get(key);
    if (old) return old;
    this.prune();
    const expiry = Math.min(Date.parse(expiresAt), this.now() + 300000);
    if (
      !this.validInput(input) ||
      this.entries.size >= ACTION_LIMITS.entries ||
      !Number.isFinite(expiry) ||
      expiry <= this.now()
    )
      return null;
    const entry: ActionEntry = {
      key,
      childId,
      kind: input.kind,
      id: crypto.randomUUID(),
      createdAt: this.now(),
      expiresAt: expiry,
      input: Object.freeze({ ...input }),
      phase: 'pending',
      attempts: 0,
      nextAt: this.now(),
    };
    if (this.size([...this.entries.values(), entry]) > ACTION_LIMITS.bytes) return null;
    this.entries.set(key, entry);
    this.publish();
    this.kick();
    return entry;
  }
  query(key: string) {
    const entry = this.entries.get(key);
    if (entry) void this.run(entry, true);
  }
  /** 查询当前资金事实与事件重试共享并发预算；不重复PUT已确认的条件。 */
  async querySettlement(id: string) {
    const owner = this.identity,
      key = `settlement:${id}`;
    if (
      !owner ||
      !this.active(owner) ||
      !UUID_PATTERN.test(id) ||
      this.requests.size >= ACTION_LIMITS.concurrent ||
      this.requests.has(key)
    )
      return;
    const controller = new AbortController();
    this.requests.set(key, controller);
    this.publish();
    try {
      const result = await this.transport.settlement(id, owner, controller.signal);
      if (this.active(owner, controller)) {
        this.remember(parseActionSettlement(result, id));
        this.settlementErrors.delete(id);
      }
    } catch (e) {
      if (this.active(owner, controller)) {
        if (this.settlementErrors.size >= ACTION_LIMITS.entries)
          this.settlementErrors.delete(this.settlementErrors.keys().next().value!);
        this.settlementErrors.set(id, toApiError(e).message);
      }
    } finally {
      if (this.requests.get(key) === controller) this.requests.delete(key);
      if (this.active(owner, controller)) {
        this.publish();
        this.kick();
      }
    }
  }
  kick = () => {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    if (!this.identity || !this.active(this.identity)) return;
    if (this.prune()) this.publish();
    if (!navigator.onLine) {
      this.scheduleExpiry();
      return;
    }
    for (const entry of this.entries.values()) {
      if (this.requests.size >= ACTION_LIMITS.concurrent) break;
      if (this.automatic(entry) && entry.nextAt <= this.now() && !this.requests.has(entry.key))
        void this.run(entry, false);
    }
    const deadlines = [...this.entries.values()]
      .filter((e) => this.automatic(e) && !this.requests.has(e.key))
      .map((e) => e.nextAt);
    this.scheduleExpiry(this.requests.size < ACTION_LIMITS.concurrent ? deadlines : []);
  };
  private automatic(e: ActionEntry) {
    return (
      e.attempts < ACTION_LIMITS.attempts &&
      !['blocked', 'rejected'].includes(e.phase) &&
      (!e.result || e.result.settlement.state === ActionState.HELD)
    );
  }
  private scheduleExpiry(deadlines: number[] = []) {
    for (const e of this.entries.values()) {
      if (e.input) deadlines.push(e.expiresAt);
      deadlines.push(e.createdAt + ACTION_LIMITS.age);
    }
    if (deadlines.length)
      this.timer = setTimeout(this.kick, Math.max(100, Math.min(...deadlines) - this.now()));
  }
  private remember(s: ActionSettlement) {
    const old = this.settlements.get(s.qualificationId);
    if (!old && this.settlements.size >= ACTION_LIMITS.entries)
      this.settlements.delete(this.settlements.keys().next().value!);
    if (!old || old.version <= s.version) this.settlements.set(s.qualificationId, s);
  }
  private async run(entry: ActionEntry, queryOnly: boolean) {
    const owner = this.identity;
    if (
      !owner ||
      !this.active(owner) ||
      this.requests.size >= ACTION_LIMITS.concurrent ||
      this.requests.has(entry.key)
    )
      return;
    const controller = new AbortController();
    this.requests.set(entry.key, controller);
    const current = {
      ...entry,
      phase: 'sending' as const,
      attempts: entry.attempts + (queryOnly ? 0 : 1),
      input: this.now() < entry.expiresAt ? entry.input : null,
      error: undefined,
    };
    this.entries.set(entry.key, current);
    this.publish();
    try {
      let raw: ActionResult;
      if (queryOnly || entry.attempts > 0 || !current.input || entry.result) {
        try {
          raw = await this.transport.receipt(
            'get',
            entry.id,
            entry.childId,
            entry.kind,
            null,
            owner,
            controller.signal,
          );
        } catch (error) {
          if (
            !this.active(owner, controller) ||
            queryOnly ||
            !axios.isAxiosError(error) ||
            error.response?.status !== HttpStatusCode.NotFound ||
            !current.input ||
            entry.result ||
            this.now() >= entry.expiresAt
          )
            throw error;
          raw = await this.transport.receipt(
            'put',
            entry.id,
            entry.childId,
            entry.kind,
            current.input,
            owner,
            controller.signal,
          );
        }
      } else
        raw = await this.transport.receipt(
          'put',
          entry.id,
          entry.childId,
          entry.kind,
          current.input,
          owner,
          controller.signal,
        );
      if (!this.active(owner, controller)) return;
      const result = parseActionResult(raw, entry.id, entry.childId, entry.kind);
      this.remember(result.settlement);
      this.entries.set(entry.key, {
        ...current,
        input: null,
        result,
        phase: ['ACCEPTED_WAITING', 'ACCEPTED_CAPTURED', 'ALREADY_ACCEPTED'].includes(
          result.receipt.status,
        )
          ? 'accepted'
          : 'rejected',
        nextAt: this.now() + this.backoff(current.attempts),
      });
    } catch (error) {
      if (!this.active(owner, controller)) return;
      const status = axios.isAxiosError(error) ? error.response?.status : undefined;
      const blocked = status !== undefined && [400, 401, 403, 409].includes(status);
      this.entries.set(entry.key, {
        ...current,
        input: blocked || this.now() >= entry.expiresAt ? null : current.input,
        phase: blocked ? 'blocked' : 'unknown',
        error: toApiError(error).message,
        nextAt: this.now() + this.backoff(current.attempts),
      });
    } finally {
      if (this.requests.get(entry.key) === controller) this.requests.delete(entry.key);
      if (this.active(owner, controller)) {
        this.publish();
        this.kick();
      }
    }
  }
  private backoff(attempts: number) {
    return Math.min(30000, 1000 * 2 ** Math.min(attempts, 5));
  }
  private validInput(input: ActionInput): boolean {
    return (
      !!input &&
      typeof input.qualificationToken === 'string' &&
      input.qualificationToken.length > 0 &&
      input.qualificationToken.length <= 8192 &&
      (input.kind === ActionKinds.CLICK
        ? input.visibleDurationMs === 0 &&
          input.minVisibleRatioBps === 0 &&
          input.pageVisible === false
        : input.kind === ActionKinds.VISIBLE &&
          Number.isInteger(input.visibleDurationMs) &&
          input.visibleDurationMs >= 1000 &&
          input.visibleDurationMs <= 300000 &&
          Number.isInteger(input.minVisibleRatioBps) &&
          input.minVisibleRatioBps >= ACTION_POLICY.minRatioBps &&
          input.minVisibleRatioBps <= ACTION_POLICY.fullRatioBps &&
          input.pageVisible === true)
    );
  }
  private restore(raw: unknown): ActionEntry | null {
    try {
      assertAction(raw && typeof raw === 'object');
      const e = raw as ActionEntry;
      assertAction(
        Object.keys(e).every((k) =>
          [
            'key',
            'childId',
            'id',
            'kind',
            'createdAt',
            'expiresAt',
            'input',
            'phase',
            'attempts',
            'nextAt',
          ].includes(k),
        ),
      );
      assertAction(
        UUID_PATTERN.test(e.id) &&
          UUID_PATTERN.test(e.childId) &&
          ['VISIBLE', 'CLICK'].includes(e.kind) &&
          e.key === actionKey(e.childId, e.kind),
      );
      assertAction(
        Number.isFinite(e.createdAt) &&
          e.createdAt <= this.now() &&
          this.now() - e.createdAt < ACTION_LIMITS.age &&
          Number.isFinite(e.expiresAt) &&
          e.expiresAt <= e.createdAt + 300000 &&
          Number.isFinite(e.nextAt) &&
          Number.isInteger(e.attempts) &&
          e.attempts >= 0 &&
          e.attempts <= ACTION_LIMITS.attempts,
      );
      assertAction(
        ['pending', 'sending', 'unknown', 'accepted', 'rejected', 'blocked'].includes(e.phase),
      );
      const input =
        e.input &&
        this.now() < e.expiresAt &&
        !['accepted', 'rejected', 'blocked'].includes(e.phase)
          ? e.input
          : null;
      if (input)
        assertAction(
          this.validInput(input) &&
            input.kind === e.kind &&
            Object.keys(input).every((k) =>
              [
                'qualificationToken',
                'kind',
                'visibleDurationMs',
                'minVisibleRatioBps',
                'pageVisible',
              ].includes(k),
            ),
        );
      return {
        key: e.key,
        childId: e.childId,
        id: e.id,
        kind: e.kind,
        createdAt: e.createdAt,
        expiresAt: e.expiresAt,
        input: input ? Object.freeze({ ...input }) : null,
        phase: ['blocked', 'rejected'].includes(e.phase) ? e.phase : 'unknown',
        attempts: Math.max(1, e.attempts),
        nextAt: this.now(),
      };
    } catch {
      return null;
    }
  }
  private serialized(entries: ActionEntry[]) {
    return entries.map(
      ({ key, childId, id, kind, createdAt, expiresAt, input, phase, attempts, nextAt }) => ({
        key,
        childId,
        id,
        kind,
        createdAt,
        expiresAt,
        input,
        phase,
        attempts,
        nextAt,
      }),
    );
  }
  private size(entries: ActionEntry[]) {
    return new TextEncoder().encode(
      JSON.stringify({ scope: this.identity?.scope, entries: this.serialized(entries) }),
    ).length;
  }
  private prune() {
    let changed = false;
    for (const [key, e] of this.entries) {
      if (this.now() - e.createdAt >= ACTION_LIMITS.age) {
        this.entries.delete(key);
        this.settlements.delete(e.childId);
        this.settlementErrors.delete(e.childId);
        changed = true;
      } else if (e.input && this.now() >= e.expiresAt) {
        this.entries.set(key, { ...e, input: null });
        changed = true;
      }
    }
    return changed;
  }
  private publish() {
    try {
      if (!this.storage) this.storageAvailable = false;
      else if (this.identity)
        this.storage.setItem(
          ACTION_STORAGE_KEY,
          JSON.stringify({
            scope: this.identity.scope,
            entries: this.serialized([...this.entries.values()]),
          }),
        );
    } catch {
      this.storageAvailable = false;
    }
    this.revision++;
    this.listeners.forEach((fn) => fn());
  }
  private cancel() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.requests.forEach((c) => c.abort());
    this.requests.clear();
  }
  clear() {
    this.cancel();
    this.identity = null;
    this.entries.clear();
    this.settlements.clear();
    this.settlementErrors.clear();
    try {
      this.storage?.removeItem(ACTION_STORAGE_KEY);
    } catch {
      this.storageAvailable = false;
    }
    this.publish();
  }
  dispose() {
    this.cancel();
    this.identity = null;
    this.unsubscribe();
    this.listeners.clear();
  }
}
let instance: ActionReceiptQueue | undefined;
export function actionQueue() {
  if (!instance) {
    let storage: Storage | null = null;
    try {
      storage = sessionStorage;
    } catch {
      /* 私密模式以提示明确内存恢复边界。 */
    }
    instance = new ActionReceiptQueue(storage);
    window.addEventListener('online', instance.kick);
  }
  return instance;
}
