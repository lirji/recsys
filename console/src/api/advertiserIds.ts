import { ClientError } from './errors';
import type { AdReportRow, AdView, AdvertiserView, BidwordView, CreativeView } from './types';

type WireIds<T, K extends keyof T> = Omit<T, K> & {
  [P in K]: unknown;
} & { [P in K as `${P & string}Text`]?: unknown };
export type AdvertiserWire = WireIds<AdvertiserView, 'advertiserId'>;
export type CreativeWire = WireIds<CreativeView, 'creativeId' | 'adId'>;
export type BidwordWire = WireIds<BidwordView, 'id' | 'adId'>;
export type ReportWire = WireIds<AdReportRow, 'adId'>;
export type AdWire = WireIds<
  Omit<AdView, 'bidwords' | 'creatives'>,
  'adId' | 'advertiserId' | 'itemId' | 'audienceId'
> & {
  bidwords: BidwordWire[];
  creatives: CreativeWire[];
};

/** ID 是标识而非数值；旧服务的不安全整数已丢失信息，必须拒绝继续写操作。 */
export function advertiserId(value: unknown, allowZero = false): string {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw invalid();
  const text = typeof value === 'number' ? String(value) : value;
  if (
    typeof text !== 'string' ||
    !/^(0|[1-9]\d{0,18})$/.test(text) ||
    BigInt(text) > 9223372036854775807n ||
    (!allowZero && text === '0')
  )
    throw invalid();
  return text;
}

const invalid = () => new ClientError('ADVERTISER_RESPONSE_INVALID');

function readId(value: unknown, text: unknown, allowZero = false): string {
  if (text === undefined) return advertiserId(value, allowZero);
  const exact = advertiserId(text, allowZero);
  // 大数字原字段仅用于旧客户端兼容；有精确文本时不再尝试修复已舍入的值。
  if (
    (typeof value === 'string' || (typeof value === 'number' && Number.isSafeInteger(value))) &&
    advertiserId(value, allowZero) !== exact
  )
    throw invalid();
  return exact;
}

export function normalizeAdvertiser(v: AdvertiserWire): AdvertiserView {
  return { ...v, advertiserId: readId(v.advertiserId, v.advertiserIdText) };
}
export function normalizeCreative(v: CreativeWire): CreativeView {
  return {
    ...v,
    creativeId: readId(v.creativeId, v.creativeIdText),
    adId: readId(v.adId, v.adIdText),
  };
}
export function normalizeBidword(v: BidwordWire): BidwordView {
  return { ...v, id: readId(v.id, v.idText), adId: readId(v.adId, v.adIdText) };
}
export function normalizeAd(v: AdWire): AdView {
  if (!Array.isArray(v.bidwords) || !Array.isArray(v.creatives)) throw invalid();
  return {
    ...v,
    adId: readId(v.adId, v.adIdText),
    advertiserId: readId(v.advertiserId, v.advertiserIdText),
    itemId: readId(v.itemId, v.itemIdText, true),
    audienceId:
      v.audienceId == null && v.audienceIdText == null
        ? null
        : readId(v.audienceId, v.audienceIdText),
    bidwords: v.bidwords.map(normalizeBidword),
    creatives: v.creatives.map(normalizeCreative),
  };
}
export function normalizeReport(v: ReportWire): AdReportRow {
  return { ...v, adId: readId(v.adId, v.adIdText) };
}
