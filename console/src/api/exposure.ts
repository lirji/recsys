import { HttpStatusCode } from 'axios';
import { http } from './client';

export const PreparationStatus = {
  QUALIFIED: 'QUALIFIED',
  INSUFFICIENT_BUDGET: 'INSUFFICIENT_BUDGET',
  QUOTE_EXPIRED: 'QUOTE_EXPIRED',
  ACCOUNT_MISSING: 'ACCOUNT_MISSING',
  OFFER_EXPIRED: 'OFFER_EXPIRED',
} as const;
export const DeliveryState = {
  PREPARING: 'PREPARING',
  COMPLETE: 'COMPLETE',
} as const;
export const ImpressionStatus = {
  ACCEPTED: 'ACCEPTED',
  ALREADY_ACCEPTED: 'ALREADY_ACCEPTED',
  POLICY_NOT_MET: 'POLICY_NOT_MET',
  EXPIRED: 'EXPIRED',
  NOT_HELD: 'NOT_HELD',
} as const;

export interface ExposureIdentity {
  scope: string;
  epoch: number;
}
export interface SandboxDeliveryInput {
  userId: string;
  query: string;
  slots: number;
  scene: string;
}
export interface SandboxPreparation {
  childId: string;
  status: (typeof PreparationStatus)[keyof typeof PreparationStatus];
  amount: string;
  qualificationToken: string | null;
  expiresAt: string | null;
}
export interface SandboxAdItem {
  slot: number;
  childId: string;
  adId: string;
  itemId: string;
  advertiserId: string;
  creativeId: string;
  title: string;
  preparation: SandboxPreparation | null;
}
export interface SandboxDeliveryView extends SandboxDeliveryInput {
  operationId: string;
  trafficClass: 'SANDBOX';
  state: (typeof DeliveryState)[keyof typeof DeliveryState];
  items: SandboxAdItem[];
}
export interface ImpressionInput {
  qualificationToken: string;
  visibleDurationMs: number;
  minVisibleRatioBps: number;
  pageVisible: boolean;
}
export interface ImpressionResult {
  receiptId: string;
  qualificationId: string;
  status: (typeof ImpressionStatus)[keyof typeof ImpressionStatus];
  ledgerId: string | null;
  canonicalReceiptId: string | null;
  chargedAmount: string;
  budgetDate: string | null;
  acceptedAt: string | null;
  decidedAt: string;
}
function options(identity: ExposureIdentity, signal?: AbortSignal) {
  return {
    expectedExposureScope: identity.scope,
    expectedExposureEpoch: identity.epoch,
    signal,
    timeout: 5000,
  };
}
export async function putSandboxDelivery(
  id: string,
  input: SandboxDeliveryInput,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.put<SandboxDeliveryView>(
      '/api/sandbox/search-ad-deliveries/' + encodeURIComponent(id),
      input,
      options(identity, signal),
    )
  ).data;
}
export async function getSandboxDelivery(
  id: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.get<SandboxDeliveryView>(
      '/api/sandbox/search-ad-deliveries/' + encodeURIComponent(id),
      options(identity, signal),
    )
  ).data;
}
export async function putImpression(
  id: string,
  input: ImpressionInput,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  // 422 是持久化业务拒绝，应读取原结果；其他失败保持未知/权限错误。
  return (
    await http.put<ImpressionResult>(
      '/api/sandbox/ad-impressions/' + encodeURIComponent(id),
      input,
      {
        ...options(identity, signal),
        validateStatus: (status) =>
          status === HttpStatusCode.Ok || status === HttpStatusCode.UnprocessableEntity,
      },
    )
  ).data;
}
export async function getImpression(id: string, identity: ExposureIdentity, signal?: AbortSignal) {
  return (
    await http.get<ImpressionResult>(
      '/api/sandbox/ad-impressions/' + encodeURIComponent(id),
      options(identity, signal),
    )
  ).data;
}
