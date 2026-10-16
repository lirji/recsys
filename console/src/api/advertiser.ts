import { http } from './client';
import { ClientError } from './errors';
import { exposureRequestOptions, type ExposureIdentity } from './exposure';
import { assertReportIdentity } from './sandboxReconciliation';
import {
  advertiserId as exactId,
  normalizeAdvertiser,
  normalizeAd,
  normalizeCreative,
  normalizeBidword,
  normalizeReport,
  type AdvertiserWire,
  type AdWire,
  type CreativeWire,
  type BidwordWire,
  type ReportWire,
} from './advertiserIds';
import type {
  AdvertiserPermissions,
  AdReportRow,
  AdUpsert,
  AdView,
  AdvertiserUpsert,
  AdvertiserView,
  BidwordUpsert,
  BidwordView,
  CreativeUpsert,
  CreativeView,
} from './types';

// ===== 广告主 =====
export async function listAdvertisers(): Promise<AdvertiserView[]> {
  const { data } = await http.get<AdvertiserWire[]>('/api/advertiser');
  return data.map(normalizeAdvertiser);
}
export async function getAdvertiser(id: string): Promise<AdvertiserView> {
  const { data } = await http.get<AdvertiserWire>(`/api/advertiser/${exactId(id)}`);
  return normalizeAdvertiser(data);
}
export async function createAdvertiser(req: AdvertiserUpsert): Promise<AdvertiserView> {
  const { data } = await http.post<AdvertiserWire>('/api/advertiser', req);
  return normalizeAdvertiser(data);
}
export async function updateAdvertiser(id: string, req: AdvertiserUpsert): Promise<AdvertiserView> {
  const { data } = await http.put<AdvertiserWire>(`/api/advertiser/${exactId(id)}`, req);
  return normalizeAdvertiser(data);
}
export async function advertiserReport(
  id: string,
  identity: ExposureIdentity,
  signal?: AbortSignal,
): Promise<AdReportRow[]> {
  const { data } = await http.get<ReportWire[]>(
    `/api/advertiser/${exactId(id)}/report`,
    exposureRequestOptions(identity, signal),
  );
  await assertReportIdentity(identity, signal);
  return data.map(normalizeReport);
}

// ===== 广告 =====
export async function listAds(advertiserId: string): Promise<AdView[]> {
  const { data } = await http.get<AdWire[]>(`/api/advertiser/${exactId(advertiserId)}/ad`);
  return data.map(normalizeAd);
}
export async function getAd(adId: string): Promise<AdView> {
  const { data } = await http.get<AdWire>(`/api/advertiser/ad/${exactId(adId)}`);
  return normalizeAd(data);
}
export async function createAd(advertiserId: string, req: AdUpsert): Promise<AdView> {
  const { data } = await http.post<AdWire>(
    `/api/advertiser/${exactId(advertiserId)}/ad`,
    adInput(req),
  );
  return normalizeAd(data);
}
export async function updateAd(adId: string, req: AdUpsert): Promise<AdView> {
  const { data } = await http.put<AdWire>(`/api/advertiser/ad/${exactId(adId)}`, adInput(req));
  return normalizeAd(data);
}

/** 表单字符串在发送前再验证；省略/null 保持原语义，0 只允许清除定向。 */
function adInput(req: AdUpsert): AdUpsert {
  return {
    ...req,
    ...(req.itemId != null ? { itemId: exactId(req.itemId) } : {}),
    ...(req.audienceId != null && req.audienceId !== ''
      ? { audienceId: exactId(req.audienceId, true) }
      : req.audienceId === ''
        ? { audienceId: null }
        : {}),
  };
}
export async function setAdStatus(adId: string, status: string): Promise<AdView> {
  const { data } = await http.put<AdWire>(`/api/advertiser/ad/${exactId(adId)}/status`, null, {
    params: { status },
  });
  return normalizeAd(data);
}
export async function reviewAd(
  adId: string,
  decision: 'approve' | 'reject',
  reason?: string,
): Promise<AdView> {
  const { data } = await http.post<AdWire>(`/api/advertiser/ad/${exactId(adId)}/review`, null, {
    params: { decision, reason: reason || undefined },
  });
  return normalizeAd(data);
}
export async function submitReview(adId: string): Promise<AdView> {
  const { data } = await http.post<AdWire>(`/api/advertiser/ad/${exactId(adId)}/submit-review`);
  return normalizeAd(data);
}
export async function deleteAd(adId: string): Promise<void> {
  await http.delete(`/api/advertiser/ad/${exactId(adId)}`);
}

// ===== 创意 =====
export async function listCreatives(adId: string): Promise<CreativeView[]> {
  const { data } = await http.get<CreativeWire[]>(`/api/advertiser/ad/${exactId(adId)}/creative`);
  return data.map(normalizeCreative);
}
export async function createCreative(adId: string, req: CreativeUpsert): Promise<CreativeView> {
  const { data } = await http.post<CreativeWire>(
    `/api/advertiser/ad/${exactId(adId)}/creative`,
    req,
  );
  return normalizeCreative(data);
}
export async function updateCreative(id: string, req: CreativeUpsert): Promise<CreativeView> {
  const { data } = await http.put<CreativeWire>(`/api/advertiser/creative/${exactId(id)}`, req);
  return normalizeCreative(data);
}
export async function deleteCreative(id: string): Promise<void> {
  await http.delete(`/api/advertiser/creative/${exactId(id)}`);
}

// ===== 竞价词 =====
export async function listBidwords(adId: string): Promise<BidwordView[]> {
  const { data } = await http.get<BidwordWire[]>(`/api/advertiser/ad/${exactId(adId)}/bidword`);
  return data.map(normalizeBidword);
}
export async function createBidword(adId: string, req: BidwordUpsert): Promise<BidwordView> {
  const { data } = await http.post<BidwordWire>(`/api/advertiser/ad/${exactId(adId)}/bidword`, req);
  return normalizeBidword(data);
}
export async function updateBidword(id: string, req: BidwordUpsert): Promise<BidwordView> {
  const { data } = await http.put<BidwordWire>(`/api/advertiser/bidword/${exactId(id)}`, req);
  return normalizeBidword(data);
}
export async function deleteBidword(id: string): Promise<void> {
  await http.delete(`/api/advertiser/bidword/${exactId(id)}`);
}

/** 从后端读取当前作用域的有效权限，缺字段不能默认开放写入口。 */
export async function getAdvertiserPermissions(
  id?: string,
  signal?: AbortSignal,
): Promise<AdvertiserPermissions> {
  const path = id ? `/api/advertiser/${exactId(id)}/permissions` : '/api/advertiser/permissions';
  const { data } = await http.get<AdvertiserPermissions>(path, { signal });
  if (
    !data ||
    ['createAdvertiser', 'reviewAds', 'editAds', 'manageAdvertiser'].some(
      (key) => typeof data[key as keyof AdvertiserPermissions] !== 'boolean',
    )
  ) {
    throw new ClientError('ADVERTISER_RESPONSE_INVALID');
  }
  return data;
}
