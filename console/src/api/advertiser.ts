import { http } from './client';
import type {
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
  const { data } = await http.get<AdvertiserView[]>('/api/advertiser');
  return data;
}
export async function getAdvertiser(id: number): Promise<AdvertiserView> {
  const { data } = await http.get<AdvertiserView>(`/api/advertiser/${id}`);
  return data;
}
export async function createAdvertiser(req: AdvertiserUpsert): Promise<AdvertiserView> {
  const { data } = await http.post<AdvertiserView>('/api/advertiser', req);
  return data;
}
export async function updateAdvertiser(id: number, req: AdvertiserUpsert): Promise<AdvertiserView> {
  const { data } = await http.put<AdvertiserView>(`/api/advertiser/${id}`, req);
  return data;
}
export async function advertiserReport(id: number): Promise<AdReportRow[]> {
  const { data } = await http.get<AdReportRow[]>(`/api/advertiser/${id}/report`);
  return data;
}

// ===== 广告 =====
export async function listAds(advertiserId: number): Promise<AdView[]> {
  const { data } = await http.get<AdView[]>(`/api/advertiser/${advertiserId}/ad`);
  return data;
}
export async function getAd(adId: number): Promise<AdView> {
  const { data } = await http.get<AdView>(`/api/advertiser/ad/${adId}`);
  return data;
}
export async function createAd(advertiserId: number, req: AdUpsert): Promise<AdView> {
  const { data } = await http.post<AdView>(`/api/advertiser/${advertiserId}/ad`, req);
  return data;
}
export async function updateAd(adId: number, req: AdUpsert): Promise<AdView> {
  const { data } = await http.put<AdView>(`/api/advertiser/ad/${adId}`, req);
  return data;
}
export async function setAdStatus(adId: number, status: string): Promise<AdView> {
  const { data } = await http.put<AdView>(`/api/advertiser/ad/${adId}/status`, null, { params: { status } });
  return data;
}
export async function reviewAd(adId: number, decision: 'approve' | 'reject', reason?: string): Promise<AdView> {
  const { data } = await http.post<AdView>(`/api/advertiser/ad/${adId}/review`, null, {
    params: { decision, reason: reason || undefined },
  });
  return data;
}
export async function submitReview(adId: number): Promise<AdView> {
  const { data } = await http.post<AdView>(`/api/advertiser/ad/${adId}/submit-review`);
  return data;
}
export async function deleteAd(adId: number): Promise<void> {
  await http.delete(`/api/advertiser/ad/${adId}`);
}

// ===== 创意 =====
export async function listCreatives(adId: number): Promise<CreativeView[]> {
  const { data } = await http.get<CreativeView[]>(`/api/advertiser/ad/${adId}/creative`);
  return data;
}
export async function createCreative(adId: number, req: CreativeUpsert): Promise<CreativeView> {
  const { data } = await http.post<CreativeView>(`/api/advertiser/ad/${adId}/creative`, req);
  return data;
}
export async function updateCreative(id: number, req: CreativeUpsert): Promise<CreativeView> {
  const { data } = await http.put<CreativeView>(`/api/advertiser/creative/${id}`, req);
  return data;
}
export async function deleteCreative(id: number): Promise<void> {
  await http.delete(`/api/advertiser/creative/${id}`);
}

// ===== 竞价词 =====
export async function listBidwords(adId: number): Promise<BidwordView[]> {
  const { data } = await http.get<BidwordView[]>(`/api/advertiser/ad/${adId}/bidword`);
  return data;
}
export async function createBidword(adId: number, req: BidwordUpsert): Promise<BidwordView> {
  const { data } = await http.post<BidwordView>(`/api/advertiser/ad/${adId}/bidword`, req);
  return data;
}
export async function updateBidword(id: number, req: BidwordUpsert): Promise<BidwordView> {
  const { data } = await http.put<BidwordView>(`/api/advertiser/bidword/${id}`, req);
  return data;
}
export async function deleteBidword(id: number): Promise<void> {
  await http.delete(`/api/advertiser/bidword/${id}`);
}
