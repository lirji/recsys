import { http } from './client';
import { ClientError } from './errors';
import {
  exposureRequestOptions,
  type ExposureIdentity,
  type SandboxDeliveryInput,
  type SandboxAdItem,
} from './exposure';

// 稳定协议代码沿 C11；共享比较使用同一集合，避免 UI 与恢复逻辑漂移。
export const ActionState = {
  HELD: 'HELD',
  CAPTURED: 'CAPTURED',
  RELEASED: 'RELEASED',
  EXPIRED: 'EXPIRED',
  REJECTED: 'REJECTED',
} as const;
export const ActionKinds = { VISIBLE: 'VISIBLE', CLICK: 'CLICK' } as const;
export const DeliveryState = { COMPLETE: 'COMPLETE', PREPARING: 'PREPARING' } as const;
export const PreparationStatus = { QUALIFIED: 'QUALIFIED' } as const;
export const ReceiptStatus = { ALREADY_ACCEPTED: 'ALREADY_ACCEPTED' } as const;
export const ACTION_POLICY = {
  textMax: 2048,
  tokenMax: 8192,
  minVisibleMs: 1000,
  maxVisibleMs: 300000,
  minRatioBps: 5000,
  fullRatioBps: 10000,
} as const;
export type ActionMode = 'click';
export type ActionKind = 'VISIBLE' | 'CLICK';
export interface ActionAdItem extends SandboxAdItem {
  bidType: 'CPC' | 'OCPC';
  priceUnit: 'PER_CLICK';
  pricingVersion: 'CLICK_GSP_CNY6_V1';
}
export interface ActionDelivery extends SandboxDeliveryInput {
  operationId: string;
  trafficClass: 'SANDBOX';
  state: 'PREPARING' | 'COMPLETE';
  items: ActionAdItem[];
}
export interface ActionInput {
  qualificationToken: string;
  kind: ActionKind;
  visibleDurationMs: number;
  minVisibleRatioBps: number;
  pageVisible: boolean;
}
export interface ActionSettlement {
  qualificationId: string;
  decision: 'HELD' | 'INSUFFICIENT_BUDGET' | 'ACCOUNT_MISSING' | 'QUOTE_EXPIRED';
  state: 'HELD' | 'CAPTURED' | 'RELEASED' | 'EXPIRED' | 'REJECTED';
  progress:
    | 'WAITING_FOR_BOTH'
    | 'WAITING_FOR_VISIBLE'
    | 'WAITING_FOR_CLICK'
    | 'CAPTURED'
    | 'RELEASED'
    | 'EXPIRED'
    | 'REJECTED';
  budgetDate: string;
  quotedAmount: string;
  chargedAmount: string | null;
  ledgerId: string | null;
  version: number;
  visibleReceiptId: string | null;
  clickReceiptId: string | null;
  issuedAt: string;
  expiresAt: string;
  preparedAt: string;
  closedAt: string | null;
  closeReason: string | null;
}
export interface ActionReceipt {
  receiptId: string;
  qualificationId: string;
  kind: ActionKind;
  status:
    | 'ACCEPTED_WAITING'
    | 'ACCEPTED_CAPTURED'
    | 'ALREADY_ACCEPTED'
    | 'POLICY_NOT_MET'
    | 'FRAUD_REJECTED'
    | 'EXPIRED'
    | 'NOT_HELD';
  canonicalReceiptId: string | null;
  acceptedAt: string | null;
  decidedAt: string;
}
export interface ActionResult {
  receipt: ActionReceipt;
  settlement: ActionSettlement;
}
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const validLongId = (v: unknown): v is string =>
  typeof v === 'string' && /^[1-9]\d{0,18}$/.test(v) && BigInt(v) <= 9223372036854775807n;
export function assertAction(condition: unknown): asserts condition {
  if (!condition) throw new ClientError('SANDBOX_ACTION_RESPONSE_INVALID');
}
const object = (v: unknown): Record<string, unknown> => {
  assertAction(v && typeof v === 'object' && !Array.isArray(v));
  return v as Record<string, unknown>;
};
const str = (v: unknown, max: number = ACTION_POLICY.textMax) => {
  assertAction(typeof v === 'string' && v.length <= max);
  return v;
};
const uuid = (v: unknown) => {
  assertAction(typeof v === 'string' && UUID_PATTERN.test(v));
  return v;
};
const nullable = <T>(v: unknown, parse: (v: unknown) => T) => (v === null ? null : parse(v));
const amount = (v: unknown) => {
  assertAction(typeof v === 'string' && /^\d{1,24}\.\d{6}$/.test(v));
  return v;
};
const instant = (v: unknown) => {
  const s = str(v, 40);
  assertAction(s.endsWith('Z') && Number.isFinite(Date.parse(s)));
  return s;
};
const one = <T extends string>(v: unknown, values: readonly T[]): T => {
  assertAction(typeof v === 'string' && values.includes(v as T));
  return v as T;
};
const int = (v: unknown, max = Number.MAX_SAFE_INTEGER) => {
  assertAction(typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 && v <= max);
  return v;
};
const validText = (value: unknown, max: number) =>
  typeof value === 'string' && !!value.trim() && value.length <= max;
export function validDeliveryInput(v: SandboxDeliveryInput): boolean {
  return (
    validLongId(v?.userId) &&
    typeof v.query === 'string' &&
    !!v.query.trim() &&
    v.query.length <= 500 &&
    validText(v.scene, 100) &&
    Number.isInteger(v.slots) &&
    v.slots >= 1 &&
    v.slots <= 10
  );
}
/** 接口与会话恢复共用白名单，扩展字段不会把票据带进结果缓存。 */
export function parseActionSettlement(raw: unknown, id: string): ActionSettlement {
  const v = object(raw);
  const state = one(v.state, ['HELD', 'CAPTURED', 'RELEASED', 'EXPIRED', 'REJECTED']);
  const result: ActionSettlement = {
    qualificationId: uuid(v.qualificationId),
    decision: one(v.decision, ['HELD', 'INSUFFICIENT_BUDGET', 'ACCOUNT_MISSING', 'QUOTE_EXPIRED']),
    state,
    progress: one(v.progress, [
      'WAITING_FOR_BOTH',
      'WAITING_FOR_VISIBLE',
      'WAITING_FOR_CLICK',
      'CAPTURED',
      'RELEASED',
      'EXPIRED',
      'REJECTED',
    ]),
    budgetDate: str(v.budgetDate, 10),
    quotedAmount: amount(v.quotedAmount),
    chargedAmount: nullable(v.chargedAmount, amount),
    ledgerId: nullable(v.ledgerId, uuid),
    version: int(v.version),
    visibleReceiptId: nullable(v.visibleReceiptId, uuid),
    clickReceiptId: nullable(v.clickReceiptId, uuid),
    issuedAt: instant(v.issuedAt),
    expiresAt: instant(v.expiresAt),
    preparedAt: instant(v.preparedAt),
    closedAt: nullable(v.closedAt, instant),
    closeReason: nullable(v.closeReason, (x) =>
      one(x, ['CLICK_CAPTURED', 'DELIVERY_ABORTED', 'FRAUD_REJECTED', 'EXPIRED']),
    ),
  };
  assertAction(result.qualificationId === id && /^\d{4}-\d{2}-\d{2}$/.test(result.budgetDate));
  assertAction(
    (state === ActionState.CAPTURED) === (result.ledgerId !== null) &&
      (state === ActionState.CAPTURED) === (result.chargedAmount !== null),
  );
  const terminal = ['CAPTURED', 'RELEASED', 'EXPIRED'].includes(state);
  assertAction(
    terminal === (result.closedAt !== null) && terminal === (result.closeReason !== null),
  );
  if (state === ActionState.CAPTURED)
    assertAction(
      result.visibleReceiptId &&
        result.clickReceiptId &&
        result.chargedAmount === result.quotedAmount,
    );
  const progress =
    state === ActionState.HELD
      ? result.visibleReceiptId
        ? 'WAITING_FOR_CLICK'
        : result.clickReceiptId
          ? 'WAITING_FOR_VISIBLE'
          : 'WAITING_FOR_BOTH'
      : state;
  assertAction(
    result.progress === progress &&
      !(state === ActionState.HELD && result.visibleReceiptId && result.clickReceiptId),
  );
  return result;
}
export function parseActionResult(
  raw: unknown,
  id: string,
  qualificationId: string,
  kind: ActionKind,
): ActionResult {
  const v = object(raw),
    r = object(v.receipt);
  const receipt: ActionReceipt = {
    receiptId: uuid(r.receiptId),
    qualificationId: uuid(r.qualificationId),
    kind: one(r.kind, ['VISIBLE', 'CLICK']),
    status: one(r.status, [
      'ACCEPTED_WAITING',
      'ACCEPTED_CAPTURED',
      'ALREADY_ACCEPTED',
      'POLICY_NOT_MET',
      'FRAUD_REJECTED',
      'EXPIRED',
      'NOT_HELD',
    ]),
    canonicalReceiptId: nullable(r.canonicalReceiptId, uuid),
    acceptedAt: nullable(r.acceptedAt, instant),
    decidedAt: instant(r.decidedAt),
  };
  assertAction(
    receipt.receiptId === id &&
      receipt.qualificationId === qualificationId &&
      receipt.kind === kind,
  );
  const accepted = ['ACCEPTED_WAITING', 'ACCEPTED_CAPTURED', 'ALREADY_ACCEPTED'].includes(
    receipt.status,
  );
  assertAction(accepted === !!receipt.canonicalReceiptId && accepted === !!receipt.acceptedAt);
  assertAction(
    !accepted ||
      receipt.status === ReceiptStatus.ALREADY_ACCEPTED ||
      receipt.canonicalReceiptId === receipt.receiptId,
  );
  return { receipt, settlement: parseActionSettlement(v.settlement, qualificationId) };
}
export function parseActionDelivery(
  raw: unknown,
  operationId: string,
  input: SandboxDeliveryInput,
): ActionDelivery {
  const v = object(raw);
  assertAction(
    v.operationId === operationId &&
      v.trafficClass === 'SANDBOX' &&
      v.userId === input.userId &&
      v.query === input.query &&
      v.scene === input.scene &&
      v.slots === input.slots &&
      Array.isArray(v.items) &&
      v.items.length <= input.slots,
  );
  const items = v.items.map((rawItem): ActionAdItem => {
    const a = object(rawItem);
    assertAction(
      [a.adId, a.itemId, a.advertiserId].every(validLongId) &&
        (a.creativeId === '0' || validLongId(a.creativeId)),
    );
    const childId = uuid(a.childId);
    const p = a.preparation === null ? null : object(a.preparation);
    const preparation = p && {
      childId: uuid(p.childId),
      status: one(p.status, [
        'QUALIFIED',
        'INSUFFICIENT_BUDGET',
        'QUOTE_EXPIRED',
        'ACCOUNT_MISSING',
        'OFFER_EXPIRED',
      ]),
      amount: amount(p.amount),
      qualificationToken: nullable(p.qualificationToken, (x) => str(x, 8192)),
      expiresAt: nullable(p.expiresAt, instant),
    };
    assertAction(!preparation || preparation.childId === childId);
    if (preparation?.status === PreparationStatus.QUALIFIED)
      assertAction(preparation.qualificationToken && preparation.expiresAt);
    return {
      slot: int(a.slot, 10),
      childId,
      adId: a.adId as string,
      itemId: a.itemId as string,
      advertiserId: a.advertiserId as string,
      creativeId: a.creativeId as string,
      title: str(a.title),
      bidType: one(a.bidType, ['CPC', 'OCPC']),
      priceUnit: one(a.priceUnit, ['PER_CLICK']),
      pricingVersion: one(a.pricingVersion, ['CLICK_GSP_CNY6_V1']),
      preparation,
    };
  });
  assertAction(
    new Set(items.map((i) => i.childId)).size === items.length &&
      items.every((i, n) => i.slot === n + 1),
  );
  const state = one(v.state, ['PREPARING', 'COMPLETE']);
  assertAction(state !== DeliveryState.COMPLETE || items.every((i) => i.preparation !== null));
  return { ...input, operationId, trafficClass: 'SANDBOX', state, items };
}
export async function actionDelivery(
  method: 'put' | 'get',
  id: string,
  input: SandboxDeliveryInput,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  assertAction(UUID_PATTERN.test(id) && validDeliveryInput(input));
  const url = '/api/sandbox/click-ad-deliveries/' + encodeURIComponent(id);
  const options = exposureRequestOptions(identity, signal);
  const response =
    method === 'put' ? await http.put(url, input, options) : await http.get(url, options);
  return parseActionDelivery(response.data, id, input);
}
export async function actionReceipt(
  method: 'put' | 'get',
  id: string,
  childId: string,
  kind: ActionKind,
  input: ActionInput | null,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  const url = '/api/sandbox/click-receipts/' + encodeURIComponent(id);
  const options = {
    ...exposureRequestOptions(identity, signal),
    validateStatus: (s: number) => s === 200 || s === 422,
  };
  const response =
    method === 'put' ? await http.put(url, input, options) : await http.get(url, options);
  return parseActionResult(response.data, id, childId, kind);
}
export async function actionSettlement(
  id: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  const response = await http.get(
    '/api/sandbox/click-settlements/' + encodeURIComponent(id),
    exposureRequestOptions(identity, signal),
  );
  return parseActionSettlement(response.data, id);
}
