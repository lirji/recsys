import { CanceledError } from 'axios';
import { http } from './client';
import { ClientError } from './errors';
import { advertiserId as exactId } from './advertiserIds';
import { exposureRequestOptions, type ExposureIdentity } from './exposure';
import { resolveToken } from '../auth/oidc';
import { exposureAuthScope, exposureEpoch } from '../exposure/identity';

export const BILLING_FAMILIES = ['IMPRESSION', 'CLICK', 'CONVERSION'] as const;
export type BillingFamily = (typeof BILLING_FAMILIES)[number];
export const FUNDS_STATUS = {
  BALANCED: 'BALANCED',
  MISMATCH: 'MISMATCH',
  ACCOUNT_MISSING: 'ACCOUNT_MISSING',
} as const;
export type FundsStatus = (typeof FUNDS_STATUS)[keyof typeof FUNDS_STATUS];
export type ProjectionStatus = 'CAUGHT_UP' | 'LAGGING' | 'MISMATCH';
export interface BillingAccountView {
  limitAmount: string;
  heldAmount: string;
  chargedAmount: string;
  availableAmount: string;
  version: string;
}
export interface BillingFamilyView {
  family: BillingFamily;
  heldCount: string;
  heldAmount: string;
  expiredHeldCount: string;
  ledgerCount: string;
  ledgerAmount: string;
  missingLedgerCount: string;
  invalidLedgerCount: string;
  pendingOutboxCount: string;
  deadOutboxCount: string;
  missingOutboxCount: string;
  oldestPendingAt: string | null;
  factCount: string;
  factAmount: string;
  missingFactCount: string;
  invalidFactCount: string;
  unexpectedFactCount: string;
  lastAppliedAt: string | null;
  summaryPresent: boolean;
  summaryCount: string;
  summaryAmount: string;
  projectionStatus: ProjectionStatus;
}
export interface SandboxReconciliation {
  schemaVersion: 1;
  trafficClass: 'SANDBOX';
  currency: 'CNY';
  scope: { advertiserId: string; budgetDate: string };
  observedAt: string;
  account: BillingAccountView | null;
  fundsStatus: FundsStatus;
  heldDelta: string | null;
  chargedDelta: string | null;
  families: BillingFamilyView[];
}

const invalid = () => new ClientError('RECONCILIATION_CLIENT_INVALID');
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
  return value as Record<string, unknown>;
}
function count(value: unknown): string {
  if (typeof value !== 'string') throw invalid();
  try {
    return exactId(value, true);
  } catch {
    throw invalid();
  }
}
function amount(value: unknown, signed = false): string {
  if (
    typeof value !== 'string' ||
    !(signed ? /^-?(?:0|[1-9]\d{0,30})\.\d{6}$/ : /^(?:0|[1-9]\d{0,30})\.\d{6}$/).test(value) ||
    value === '-0.000000'
  )
    throw invalid();
  return value;
}
/** 日期按预算日文本处理，不借浏览器时区将它改成另一天。 */
export function validBudgetDate(value: string): boolean {
  if (!/^(?!0000)\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
function time(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !validBudgetDate(value.slice(0, 10)) ||
    !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(value)
  )
    throw invalid();
  return value;
}
function nullableTime(value: unknown) {
  return value === null ? null : time(value);
}
function family(value: unknown, expected: BillingFamily): BillingFamilyView {
  const f = object(value);
  if (
    f.family !== expected ||
    typeof f.summaryPresent !== 'boolean' ||
    !['CAUGHT_UP', 'LAGGING', 'MISMATCH'].includes(String(f.projectionStatus))
  )
    throw invalid();
  const result: BillingFamilyView = {
    family: expected,
    heldCount: count(f.heldCount),
    heldAmount: amount(f.heldAmount),
    expiredHeldCount: count(f.expiredHeldCount),
    ledgerCount: count(f.ledgerCount),
    ledgerAmount: amount(f.ledgerAmount),
    missingLedgerCount: count(f.missingLedgerCount),
    invalidLedgerCount: count(f.invalidLedgerCount),
    pendingOutboxCount: count(f.pendingOutboxCount),
    deadOutboxCount: count(f.deadOutboxCount),
    missingOutboxCount: count(f.missingOutboxCount),
    oldestPendingAt: nullableTime(f.oldestPendingAt),
    factCount: count(f.factCount),
    factAmount: amount(f.factAmount),
    missingFactCount: count(f.missingFactCount),
    invalidFactCount: count(f.invalidFactCount),
    unexpectedFactCount: count(f.unexpectedFactCount),
    lastAppliedAt: nullableTime(f.lastAppliedAt),
    summaryPresent: f.summaryPresent,
    summaryCount: count(f.summaryCount),
    summaryAmount: amount(f.summaryAmount),
    projectionStatus: f.projectionStatus as ProjectionStatus,
  };
  if (
    BigInt(result.expiredHeldCount) > BigInt(result.heldCount) ||
    (!result.summaryPresent && (result.summaryCount !== '0' || result.summaryAmount !== '0.000000'))
  )
    throw invalid();
  return result;
}
/** DTO只做协议及范围校验，金额和核对结论仍取owner，不在浏览器重新相加账务。 */
export function parseSandboxReconciliation(
  value: unknown,
  advertiserId: string,
  budgetDate: string,
): SandboxReconciliation {
  const r = object(value);
  const scope = object(r.scope);
  if (
    r.schemaVersion !== 1 ||
    r.trafficClass !== 'SANDBOX' ||
    r.currency !== 'CNY' ||
    scope.advertiserId !== exactId(advertiserId) ||
    scope.budgetDate !== budgetDate ||
    !validBudgetDate(budgetDate) ||
    !Array.isArray(r.families) ||
    r.families.length !== BILLING_FAMILIES.length ||
    !Object.values(FUNDS_STATUS).includes(r.fundsStatus as FundsStatus)
  )
    throw invalid();
  let account: BillingAccountView | null = null;
  if (r.account !== null) {
    const a = object(r.account);
    account = {
      limitAmount: amount(a.limitAmount),
      heldAmount: amount(a.heldAmount),
      chargedAmount: amount(a.chargedAmount),
      availableAmount: amount(a.availableAmount),
      version: count(a.version),
    };
  }
  if (
    (account === null) !== (r.fundsStatus === FUNDS_STATUS.ACCOUNT_MISSING) ||
    (account === null && (r.heldDelta !== null || r.chargedDelta !== null))
  )
    throw invalid();
  return {
    schemaVersion: 1,
    trafficClass: 'SANDBOX',
    currency: 'CNY',
    scope: { advertiserId, budgetDate },
    observedAt: time(r.observedAt),
    account,
    fundsStatus: r.fundsStatus as FundsStatus,
    heldDelta: account === null ? null : amount(r.heldDelta, true),
    chargedDelta: account === null ? null : amount(r.chargedDelta, true),
    families: r.families.map((f, i) => family(f, BILLING_FAMILIES[i]!)),
  };
}
/** 返回后再核对实际token作用域，旧身份的迟到响应不能进入报表缓存。 */
export async function assertReportIdentity(identity: ExposureIdentity, signal?: AbortSignal) {
  const token = await resolveToken();
  if (
    signal?.aborted ||
    exposureEpoch() !== identity.epoch ||
    exposureAuthScope(token) !== identity.scope
  ) {
    throw new CanceledError('身份已变化，资金报表响应失效');
  }
}
export async function sandboxReconciliation(
  advertiserId: string,
  budgetDate: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
): Promise<SandboxReconciliation> {
  if (!validBudgetDate(budgetDate)) throw new ClientError('RECONCILIATION_INVALID_ARGUMENT');
  const id = exactId(advertiserId);
  const { data } = await http.get<unknown>(
    `/api/advertiser/${id}/sandbox-reconciliation/${budgetDate}`,
    exposureRequestOptions(identity, signal),
  );
  await assertReportIdentity(identity, signal);
  return parseSandboxReconciliation(data, id, budgetDate);
}
