import { HttpStatusCode } from 'axios';
import { http } from './client';
import {
  DeliveryState,
  exposureRequestOptions,
  type ExposureIdentity,
  type ImpressionInput,
} from './exposure';

export const NaturalKind = { RECOMMEND: 'RECOMMEND', SEARCH: 'SEARCH' } as const;
export type NaturalKind = (typeof NaturalKind)[keyof typeof NaturalKind];
export const NaturalReceiptStatus = {
  ACCEPTED: 'ACCEPTED',
  ALREADY_ACCEPTED: 'ALREADY_ACCEPTED',
  POLICY_NOT_MET: 'POLICY_NOT_MET',
  EXPIRED: 'EXPIRED',
} as const;
export const NATURAL_TEXT_POLICY = Object.freeze({
  version: 'natural-text-v1',
  minRatioBps: 5000,
  minDurationMs: 1000,
  ttlMs: 300000,
});
export interface NaturalDeliveryInput {
  userId: string;
  kind: NaturalKind;
  size: number;
  scene: string;
  query: string | null;
}
export interface NaturalItem {
  slot: number;
  itemId: string;
  title: string;
  category: string;
  description: string;
  score: number;
  recallFrom: string[];
  reason: string | null;
  qualificationId: string;
  qualificationToken: string;
  issuedAt: string;
  expiresAt: string;
  contentHash: string;
}
export interface NaturalDeliveryView extends NaturalDeliveryInput {
  operationId: string;
  trafficClass: 'SANDBOX';
  state: (typeof DeliveryState)[keyof typeof DeliveryState];
  policy: typeof NATURAL_TEXT_POLICY;
  bucket: string;
  traceId: string | null;
  modelLineage: 'UNVERIFIED';
  items: NaturalItem[];
}
export interface NaturalImpressionResult {
  receiptId: string;
  qualificationId: string;
  status: (typeof NaturalReceiptStatus)[keyof typeof NaturalReceiptStatus];
  eventId: string | null;
  canonicalReceiptId: string | null;
  acceptedAt: string | null;
  decidedAt: string;
}
export const naturalUuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export const positiveLong = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9][0-9]{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n;
const textWithin = (value: unknown, max: number): value is string =>
  typeof value === 'string' && value.length <= max;
/** 存储不是可信输入；完整验证原意图，禁止把旧/损坏请求当成新交付。 */
export function validNaturalInput(value: unknown): value is NaturalDeliveryInput {
  if (!value || typeof value !== 'object') return false;
  const input = value as NaturalDeliveryInput;
  return (
    positiveLong(input.userId) &&
    Number.isInteger(input.size) &&
    input.size >= 1 &&
    input.size <= 50 &&
    textWithin(input.scene, 100) &&
    !!input.scene.trim() &&
    ((input.kind === NaturalKind.RECOMMEND && input.query === null) ||
      (input.kind === NaturalKind.SEARCH && textWithin(input.query, 500) && !!input.query.trim()))
  );
}
/** 采集前核对原请求、已批准政策与完整冻结内容；未知响应不能成为曝光意图。 */
export function validateNaturalDelivery(
  id: string,
  input: NaturalDeliveryInput,
  view: NaturalDeliveryView,
) {
  if (
    !view ||
    view.operationId !== id ||
    view.trafficClass !== 'SANDBOX' ||
    view.userId !== input.userId ||
    view.kind !== input.kind ||
    view.size !== input.size ||
    view.scene !== input.scene ||
    view.query !== input.query ||
    view.modelLineage !== 'UNVERIFIED' ||
    !view.policy ||
    Object.entries(NATURAL_TEXT_POLICY).some(
      ([key, value]) => view.policy[key as keyof typeof NATURAL_TEXT_POLICY] !== value,
    ) ||
    !textWithin(view.bucket, 2000) ||
    (view.traceId !== null && !textWithin(view.traceId, 2000)) ||
    !Object.values(DeliveryState).includes(view.state) ||
    !Array.isArray(view.items) ||
    view.items.length > input.size ||
    (view.state === DeliveryState.PREPARING && view.items.length !== 0)
  )
    throw new Error('自然交付响应与原请求或可见政策不匹配');
  const qualifications = new Set<string>();
  const items = new Set<string>();
  for (const [index, item] of view.items.entries()) {
    if (
      !item ||
      item.slot !== index + 1 ||
      !positiveLong(item.itemId) ||
      items.has(item.itemId) ||
      !naturalUuid(item.qualificationId) ||
      qualifications.has(item.qualificationId) ||
      !textWithin(item.qualificationToken, 16384) ||
      !item.qualificationToken.startsWith('nq1.') ||
      !textWithin(item.title, 2000) ||
      !item.title.trim() ||
      !textWithin(item.category, 200) ||
      !textWithin(item.description, 16000) ||
      (item.reason !== null && !textWithin(item.reason, 4000)) ||
      !Number.isFinite(item.score) ||
      !Array.isArray(item.recallFrom) ||
      item.recallFrom.length > 50 ||
      item.recallFrom.some((s) => !textWithin(s, 100)) ||
      !/^[0-9a-f]{64}$/.test(item.contentHash) ||
      !Number.isFinite(Date.parse(item.issuedAt)) ||
      Date.parse(item.expiresAt) - Date.parse(item.issuedAt) !== NATURAL_TEXT_POLICY.ttlMs
    )
      throw new Error('自然交付内容或资格不完整，未开始采集');
    qualifications.add(item.qualificationId);
    items.add(item.itemId);
  }
}
export async function putNaturalDelivery(
  id: string,
  input: NaturalDeliveryInput,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.put<NaturalDeliveryView>(
      '/api/sandbox/natural-deliveries/' + encodeURIComponent(id),
      input,
      exposureRequestOptions(identity, signal),
    )
  ).data;
}
export async function getNaturalDelivery(
  id: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.get<NaturalDeliveryView>(
      '/api/sandbox/natural-deliveries/' + encodeURIComponent(id),
      exposureRequestOptions(identity, signal),
    )
  ).data;
}
export async function putNaturalImpression(
  id: string,
  input: ImpressionInput,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.put<NaturalImpressionResult>(
      '/api/sandbox/natural-impressions/' + encodeURIComponent(id),
      input,
      {
        ...exposureRequestOptions(identity, signal),
        validateStatus: (status) =>
          status === HttpStatusCode.Ok || status === HttpStatusCode.UnprocessableEntity,
      },
    )
  ).data;
}
export async function getNaturalImpression(
  id: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.get<NaturalImpressionResult>(
      '/api/sandbox/natural-impressions/' + encodeURIComponent(id),
      exposureRequestOptions(identity, signal),
    )
  ).data;
}
