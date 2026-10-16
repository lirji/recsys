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
export type ActionMode = 'click' | 'conversion';
export type ActionKind = 'VISIBLE' | 'CLICK';
export interface ActionAdItem extends SandboxAdItem {
  bidType: 'CPC' | 'OCPC' | 'CPA';
  priceUnit: 'PER_CLICK' | 'PER_CONVERSION';
  pricingVersion: 'CLICK_GSP_CNY6_V1' | 'CPA_GSP_CNY6_V1';
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
    | 'WAITING_FOR_CONVERSION'
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
  outcomeId?: string | null;
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
export function parseActionSettlement(
  raw: unknown,
  id: string,
  mode: ActionMode = 'click',
): ActionSettlement {
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
      'WAITING_FOR_CONVERSION',
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
    clickReceiptId: mode === 'click' ? nullable(v.clickReceiptId, uuid) : null,
    outcomeId: mode === 'conversion' ? nullable(v.outcomeId, uuid) : null,
    issuedAt: instant(v.issuedAt),
    expiresAt: instant(v.expiresAt),
    preparedAt: instant(v.preparedAt),
    closedAt: nullable(v.closedAt, instant),
    closeReason: nullable(v.closeReason, (x) =>
      one(
        x,
        mode === 'click'
          ? ['CLICK_CAPTURED', 'DELIVERY_ABORTED', 'FRAUD_REJECTED', 'EXPIRED']
          : ['CONVERSION_CAPTURED', 'DELIVERY_ABORTED', 'EXPIRED'],
      ),
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
  const actionId = mode === 'click' ? result.clickReceiptId : result.outcomeId;
  if (state === ActionState.CAPTURED)
    assertAction(
      result.visibleReceiptId && actionId && result.chargedAmount === result.quotedAmount,
    );
  const progress =
    state === ActionState.HELD
      ? result.visibleReceiptId
        ? mode === 'click'
          ? 'WAITING_FOR_CLICK'
          : 'WAITING_FOR_CONVERSION'
        : actionId
          ? 'WAITING_FOR_VISIBLE'
          : 'WAITING_FOR_BOTH'
      : state;
  assertAction(
    result.progress === progress &&
      !(state === ActionState.HELD && result.visibleReceiptId && actionId),
  );
  return result;
}
export function parseActionResult(
  raw: unknown,
  id: string,
  qualificationId: string,
  kind: ActionKind,
  mode: ActionMode = 'click',
): ActionResult {
  const v = object(raw),
    r = object(v.receipt);
  const receipt: ActionReceipt = {
    receiptId: uuid(r.receiptId),
    qualificationId: uuid(r.qualificationId),
    kind: mode === 'click' ? one(r.kind, ['VISIBLE', 'CLICK']) : ActionKinds.VISIBLE,
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
  return { receipt, settlement: parseActionSettlement(v.settlement, qualificationId, mode) };
}
export function parseActionDelivery(
  raw: unknown,
  operationId: string,
  input: SandboxDeliveryInput,
  mode: ActionMode = 'click',
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
      bidType: one(a.bidType, mode === 'click' ? ['CPC', 'OCPC'] : ['CPA']),
      priceUnit: one(a.priceUnit, mode === 'click' ? ['PER_CLICK'] : ['PER_CONVERSION']),
      pricingVersion: one(
        a.pricingVersion,
        mode === 'click' ? ['CLICK_GSP_CNY6_V1'] : ['CPA_GSP_CNY6_V1'],
      ),
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
  mode: ActionMode = 'click',
) {
  assertAction(UUID_PATTERN.test(id) && validDeliveryInput(input));
  const url = `/api/sandbox/${mode}-ad-deliveries/` + encodeURIComponent(id);
  const options = exposureRequestOptions(identity, signal);
  const response =
    method === 'put' ? await http.put(url, input, options) : await http.get(url, options);
  return parseActionDelivery(response.data, id, input, mode);
}
export async function actionReceipt(
  method: 'put' | 'get',
  id: string,
  childId: string,
  kind: ActionKind,
  input: ActionInput | null,
  identity: ExposureIdentity,
  signal?: AbortSignal,
  mode: ActionMode = 'click',
) {
  const url =
    (mode === 'click' ? '/api/sandbox/click-receipts/' : '/api/sandbox/conversion-impressions/') +
    encodeURIComponent(id);
  const options = {
    ...exposureRequestOptions(identity, signal),
    validateStatus: (s: number) => s === 200 || s === 422,
  };
  const response =
    method === 'put'
      ? await http.put(
          url,
          mode === 'conversion' && input
            ? {
                qualificationToken: input.qualificationToken,
                visibleDurationMs: input.visibleDurationMs,
                minVisibleRatioBps: input.minVisibleRatioBps,
                pageVisible: input.pageVisible,
              }
            : input,
          options,
        )
      : await http.get(url, options);
  return parseActionResult(response.data, id, childId, kind, mode);
}
export async function actionSettlement(
  id: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
  mode: ActionMode = 'click',
) {
  const response = await http.get(
    `/api/sandbox/${mode}-settlements/` + encodeURIComponent(id),
    exposureRequestOptions(identity, signal),
  );
  return parseActionSettlement(response.data, id, mode);
}

export const ConversionStatus = {
  WAITING: 'ACCEPTED_WAITING',
  CAPTURED: 'ACCEPTED_CAPTURED',
  DUPLICATE: 'DUPLICATE_QUALIFICATION',
} as const;
export interface ConversionInput {
  kind: 'CONVERSION';
  advertiserId: string;
  qualificationToken: string;
  occurredAt: string;
}
export interface ConversionOutcome {
  outcomeId: string;
  advertiserId: string;
  eventId: string;
  qualificationId: string;
  status:
    | 'ACCEPTED_WAITING'
    | 'ACCEPTED_CAPTURED'
    | 'DUPLICATE_QUALIFICATION'
    | 'OUTSIDE_ATTRIBUTION'
    | 'EXPIRED'
    | 'NOT_HELD';
  occurredAt: string;
  receivedAt: string;
  decidedAt: string;
  canonicalOutcomeId: string | null;
  chargedForThisOutcome: boolean;
  chargedAmount: string | null;
  settlement: Pick<
    ActionSettlement,
    'state' | 'progress' | 'budgetDate' | 'quotedAmount' | 'chargedAmount' | 'ledgerId' | 'closedAt'
  >;
}
/** 微秒精度按字符串比较，不能经 Date 毫秒截断后误认成同一业务时间。 */
const normalizedInstant = (s: string) => s.replace(/(\.\d*?)0+Z$/, '$1Z').replace('.Z', 'Z');
export function validOccurredAt(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?Z$/.test(value) &&
    Number.isFinite(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 19) === value.slice(0, 19)
  );
}
export function parseConversionOutcome(
  raw: unknown,
  advertiserId: string,
  eventId: string,
  expected?: { qualificationId?: string; occurredAt?: string },
): ConversionOutcome {
  const v = object(raw),
    s = object(v.settlement);
  const settlement: ConversionOutcome['settlement'] = {
    state: one(s.state, ['HELD', 'CAPTURED', 'RELEASED', 'EXPIRED', 'REJECTED']),
    progress: one(s.progress, [
      'WAITING_FOR_BOTH',
      'WAITING_FOR_VISIBLE',
      'WAITING_FOR_CONVERSION',
      'CAPTURED',
      'RELEASED',
      'EXPIRED',
      'REJECTED',
    ]),
    budgetDate: str(s.budgetDate, 10),
    quotedAmount: amount(s.quotedAmount),
    chargedAmount: nullable(s.chargedAmount, amount),
    ledgerId: nullable(s.ledgerId, uuid),
    closedAt: nullable(s.closedAt, instant),
  };
  const result: ConversionOutcome = {
    outcomeId: uuid(v.outcomeId),
    advertiserId: str(v.advertiserId, 19),
    eventId: uuid(v.eventId),
    qualificationId: uuid(v.qualificationId),
    status: one(v.status, [
      'ACCEPTED_WAITING',
      'ACCEPTED_CAPTURED',
      'DUPLICATE_QUALIFICATION',
      'OUTSIDE_ATTRIBUTION',
      'EXPIRED',
      'NOT_HELD',
    ]),
    occurredAt: instant(v.occurredAt),
    receivedAt: instant(v.receivedAt),
    decidedAt: instant(v.decidedAt),
    canonicalOutcomeId: nullable(v.canonicalOutcomeId, uuid),
    chargedForThisOutcome: v.chargedForThisOutcome as boolean,
    chargedAmount: nullable(v.chargedAmount, amount),
    settlement,
  };
  assertAction(
    result.advertiserId === advertiserId &&
      validLongId(advertiserId) &&
      result.eventId === eventId &&
      typeof result.chargedForThisOutcome === 'boolean',
  );
  assertAction(!expected?.qualificationId || result.qualificationId === expected.qualificationId);
  assertAction(
    !expected?.occurredAt ||
      normalizedInstant(result.occurredAt) === normalizedInstant(expected.occurredAt),
  );
  assertAction(/^\d{4}-\d{2}-\d{2}$/.test(settlement.budgetDate));
  const captured = settlement.state === ActionState.CAPTURED;
  assertAction((settlement.state !== ActionState.HELD) === (settlement.closedAt !== null));
  assertAction(
    captured === (settlement.ledgerId !== null) && captured === (settlement.chargedAmount !== null),
  );
  assertAction(!captured || settlement.chargedAmount === settlement.quotedAmount);
  assertAction(result.chargedForThisOutcome === (result.chargedAmount !== null));
  const canonical = [ConversionStatus.WAITING, ConversionStatus.CAPTURED].some(
    (s) => s === result.status,
  );
  assertAction(!canonical || result.canonicalOutcomeId === result.outcomeId);
  assertAction(result.chargedForThisOutcome === (captured && canonical));
  assertAction(!result.chargedForThisOutcome || result.chargedAmount === settlement.chargedAmount);
  assertAction(
    result.status !== ConversionStatus.DUPLICATE ||
      (!!result.canonicalOutcomeId && result.canonicalOutcomeId !== result.outcomeId),
  );
  assertAction(
    settlement.state === ActionState.HELD
      ? settlement.progress.startsWith('WAITING_FOR_')
      : settlement.progress === settlement.state,
  );
  return result;
}
/** 每次 PUT/GET 均由服务端强制 edit/fullyConsistent，旧管理权限快照不参与此调用。 */
export async function conversionEvent(
  method: 'put' | 'get',
  advertiserId: string,
  eventId: string,
  input: ConversionInput | null,
  identity: ExposureIdentity,
  signal?: AbortSignal,
  qualificationId?: string,
) {
  assertAction(validLongId(advertiserId) && UUID_PATTERN.test(eventId));
  if (method === 'put')
    assertAction(input && input.advertiserId === advertiserId && validOccurredAt(input.occurredAt));
  const url = `/api/advertiser/${encodeURIComponent(advertiserId)}/sandbox-conversions/${encodeURIComponent(eventId)}`;
  const options = {
    ...exposureRequestOptions(identity, signal),
    validateStatus: (s: number) => s === 200 || s === 422,
  };
  const response =
    method === 'put'
      ? await http.put(
          url,
          { qualificationToken: input!.qualificationToken, occurredAt: input!.occurredAt },
          options,
        )
      : await http.get(url, options);
  return parseConversionOutcome(response.data, advertiserId, eventId, {
    qualificationId,
    occurredAt: input?.occurredAt,
  });
}
