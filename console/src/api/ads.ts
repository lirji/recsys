import { http } from './client';
import type { SearchAdsResponse } from './types';

export async function getSearchAds(p: {
  q: string;
  userId: number;
  slots: number;
  scene: string;
}): Promise<SearchAdsResponse> {
  const { data } = await http.get<SearchAdsResponse>('/api/search-ads', {
    params: { q: p.q, userId: p.userId, slots: p.slots, scene: p.scene },
  });
  return data;
}

export async function postAdClick(p: { requestId: string; adId: number; userId: number }): Promise<void> {
  await http.post('/api/ad/click', null, { params: p });
}

export async function postAdConversion(p: {
  requestId: string;
  adId: number;
  userId: number;
}): Promise<void> {
  await http.post('/api/ad/conversion', null, { params: p });
}
