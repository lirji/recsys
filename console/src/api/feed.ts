import { http } from './client';
import type { BlendedFeedResponse } from './types';

export async function getFeed(p: {
  q?: string;
  userId: number;
  size: number;
  scene?: string;
}): Promise<BlendedFeedResponse> {
  const { data } = await http.get<BlendedFeedResponse>('/api/feed', {
    params: { q: p.q || undefined, userId: p.userId, size: p.size, scene: p.scene || undefined },
  });
  return data;
}
