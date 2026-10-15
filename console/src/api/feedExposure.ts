import { http } from './client';
import {
  DeliveryState,
  PreparationStatus,
  exposureRequestOptions,
  type ExposureIdentity,
  type SandboxAdItem,
} from './exposure';
import {
  NATURAL_TEXT_POLICY,
  naturalUuid,
  positiveLong,
  validateNaturalItem,
  type NaturalItem,
} from './naturalExposure';

export const FeedEntryKind = { NATURAL: 'NATURAL', AD: 'AD' } as const;
export const AdDisposition = {
  NOT_REQUESTED: 'NOT_REQUESTED',
  CANDIDATES_AVAILABLE: 'CANDIDATES_AVAILABLE',
  NO_ELIGIBLE_ADS: 'NO_ELIGIBLE_ADS',
  CANDIDATES_UNAVAILABLE: 'CANDIDATES_UNAVAILABLE',
} as const;
export interface FeedDeliveryInput {
  userId: string;
  size: number;
  scene: string;
  query: string | null;
}
export type FeedEntry =
  | { slot: number; kind: typeof FeedEntryKind.NATURAL; natural: NaturalItem; ad: null }
  | { slot: number; kind: typeof FeedEntryKind.AD; natural: null; ad: SandboxAdItem };
export interface FeedDeliveryView extends FeedDeliveryInput {
  operationId: string;
  trafficClass: 'SANDBOX';
  state: (typeof DeliveryState)[keyof typeof DeliveryState];
  policy: typeof NATURAL_TEXT_POLICY;
  bucket: string;
  traceId: string | null;
  modelLineage: 'UNVERIFIED';
  adDisposition: (typeof AdDisposition)[keyof typeof AdDisposition] | null;
  items: FeedEntry[];
}
const textWithin = (value: unknown, max: number): value is string =>
  typeof value === 'string' && value.length <= max;
const exactAmount = (value: unknown): value is string =>
  typeof value === 'string' && /^(0|[1-9][0-9]{0,13})\.[0-9]{6}$/.test(value);
export function validFeedInput(value: unknown): value is FeedDeliveryInput {
  if (!value || typeof value !== 'object') return false;
  const input = value as FeedDeliveryInput;
  return (
    positiveLong(input.userId) &&
    Number.isInteger(input.size) &&
    input.size >= 1 &&
    input.size <= 50 &&
    textWithin(input.scene, 100) &&
    !!input.scene.trim() &&
    (input.query === null || (textWithin(input.query, 500) && !!input.query.trim()))
  );
}
/** 全计划校验通过后才能采集；未知广告允许占位，但不能改变已冻结的自然位置。 */
export function validateFeedDelivery(id: string, input: FeedDeliveryInput, view: FeedDeliveryView) {
  if (
    !view ||
    view.operationId !== id ||
    view.userId !== input.userId ||
    view.size !== input.size ||
    view.scene !== input.scene ||
    view.query !== input.query ||
    view.trafficClass !== 'SANDBOX' ||
    view.modelLineage !== 'UNVERIFIED' ||
    !Object.values(DeliveryState).includes(view.state) ||
    !view.policy ||
    Object.entries(NATURAL_TEXT_POLICY).some(
      ([k, v]) => view.policy[k as keyof typeof NATURAL_TEXT_POLICY] !== v,
    ) ||
    !textWithin(view.bucket, 2000) ||
    (view.traceId !== null && !textWithin(view.traceId, 2000)) ||
    !Array.isArray(view.items) ||
    view.items.length > input.size ||
    (view.adDisposition === null
      ? view.state !== DeliveryState.PREPARING ||
        view.items.length !== 0 ||
        view.bucket !== '' ||
        view.traceId !== ''
      : !Object.values(AdDisposition).includes(view.adDisposition))
  )
    throw new Error('混排响应与原请求或可见政策不匹配');
  const items = new Set<string>();
  const qualifications = new Set<string>();
  for (const [index, entry] of view.items.entries()) {
    if (!entry || entry.slot !== index + 1) throw new Error('混排最终位置非法');
    let itemId: string;
    let qualificationId: string;
    if (entry.kind === FeedEntryKind.NATURAL && entry.ad === null) {
      validateNaturalItem(entry.natural, entry.slot);
      itemId = entry.natural.itemId;
      qualificationId = entry.natural.qualificationId;
    } else if (entry.kind === FeedEntryKind.AD && entry.natural === null) {
      const ad = entry.ad;
      if (
        !ad ||
        ad.slot !== entry.slot ||
        ad.slot > 10 ||
        view.adDisposition !== AdDisposition.CANDIDATES_AVAILABLE ||
        !naturalUuid(ad.childId) ||
        !positiveLong(ad.itemId) ||
        !positiveLong(ad.adId) ||
        !positiveLong(ad.advertiserId) ||
        !(ad.creativeId === '0' || positiveLong(ad.creativeId)) ||
        !textWithin(ad.title, 2000) ||
        !ad.title.trim()
      )
        throw new Error('混排广告身份或正文非法');
      const prep = ad.preparation;
      if (prep === null) {
        if (view.state === DeliveryState.COMPLETE)
          throw new Error('广告结果未知，整次交付不能显示完成');
      } else if (
        !prep ||
        prep.childId !== ad.childId ||
        !Object.values(PreparationStatus).includes(prep.status)
      ) {
        throw new Error('广告准备结果身份非法');
      } else if (prep.status === PreparationStatus.QUALIFIED) {
        if (
          !exactAmount(prep.amount) ||
          !textWithin(prep.qualificationToken, 16384) ||
          !prep.qualificationToken.trim() ||
          !textWithin(prep.expiresAt, 64) ||
          !Number.isFinite(Date.parse(prep.expiresAt))
        )
          throw new Error('广告计费资格不完整，未开始采集');
      } else if (
        !(prep.amount === '' || exactAmount(prep.amount)) ||
        prep.qualificationToken !== '' ||
        (prep.expiresAt !== null &&
          (!textWithin(prep.expiresAt, 64) || !Number.isFinite(Date.parse(prep.expiresAt))))
      ) {
        throw new Error('广告拒绝结果非法');
      }
      itemId = ad.itemId;
      qualificationId = ad.childId;
    } else throw new Error('混排类型不受支持');
    if (items.has(itemId) || qualifications.has(qualificationId)) throw new Error('混排身份重复');
    items.add(itemId);
    qualifications.add(qualificationId);
  }
  if (
    view.state === DeliveryState.PREPARING &&
    view.adDisposition !== null &&
    !view.items.some((e) => e.kind === FeedEntryKind.AD && e.ad.preparation === null)
  )
    throw new Error('混排准备状态与条目结论不一致');
}
/** JSON 属性顺序不属于业务身份，避免网关编码顺序变化误报计划改写。 */
const canonical = (value: unknown) =>
  JSON.stringify(value, (_, item: unknown) =>
    item && typeof item === 'object' && !Array.isArray(item)
      ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b)))
      : item,
  );
/** 后来的恢复响应只能补齐原准备结果；不能重新混排或改写已交付资格。 */
export function validateFeedProgress(previous: FeedDeliveryView | null, next: FeedDeliveryView) {
  if (!previous || previous.operationId !== next.operationId || previous.adDisposition === null)
    return;
  const frozen = (view: FeedDeliveryView) => ({
    ...view,
    state: undefined,
    items: view.items.map((e) =>
      e.kind === FeedEntryKind.AD ? { ...e, ad: { ...e.ad, preparation: null } } : e,
    ),
  });
  if (
    (previous.state === DeliveryState.COMPLETE && next.state !== DeliveryState.COMPLETE) ||
    canonical(frozen(previous)) !== canonical(frozen(next))
  )
    throw new Error('恢复响应改变了已冻结混排');
  previous.items.forEach((e, index) => {
    if (
      e.kind === FeedEntryKind.AD &&
      e.ad.preparation !== null &&
      canonical(e.ad.preparation) !== canonical(next.items[index].ad?.preparation)
    )
      throw new Error('恢复响应改变了原广告准备结论');
  });
}
export async function putFeedDelivery(
  id: string,
  input: FeedDeliveryInput,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.put<FeedDeliveryView>(
      '/api/sandbox/feed-deliveries/' + encodeURIComponent(id),
      input,
      exposureRequestOptions(identity, signal),
    )
  ).data;
}
export async function getFeedDelivery(
  id: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
) {
  return (
    await http.get<FeedDeliveryView>(
      '/api/sandbox/feed-deliveries/' + encodeURIComponent(id),
      exposureRequestOptions(identity, signal),
    )
  ).data;
}
