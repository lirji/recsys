import { http } from './client';
import type { RecommendResponse } from './types';

export async function getRecommend(p: {
  userId: number;
  size: number;
  scene?: string;
  q?: string;
}): Promise<RecommendResponse> {
  const { data } = await http.get<RecommendResponse>('/api/recommend', {
    params: { userId: p.userId, size: p.size, scene: p.scene || undefined, q: p.q || undefined },
  });
  return data;
}

export async function getSearch(p: {
  q: string;
  userId: number;
  size: number;
}): Promise<RecommendResponse> {
  const { data } = await http.get<RecommendResponse>('/api/search', {
    params: { q: p.q, userId: p.userId, size: p.size },
  });
  return data;
}
