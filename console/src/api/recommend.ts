import { http } from './client';
import type { RecommendResponse } from './types';

export async function getRecommend(p: {
  userId: number;
  size: number;
  scene?: string;
  q?: string;
  explain?: boolean;
  // 策略对比台调试参数:强制走指定 rank/rerank 策略或召回路(CSV),绕过实验分桶。留空则常规链路。
  rankStrategy?: string;
  rerankStrategy?: string;
  recallChannels?: string;
}): Promise<RecommendResponse> {
  const { data } = await http.get<RecommendResponse>('/api/recommend', {
    params: {
      userId: p.userId,
      size: p.size,
      scene: p.scene || undefined,
      q: p.q || undefined,
      explain: p.explain || undefined,
      rankStrategy: p.rankStrategy || undefined,
      rerankStrategy: p.rerankStrategy || undefined,
      recallChannels: p.recallChannels || undefined,
    },
  });
  return data;
}

export async function getSearch(p: {
  q: string;
  userId: number;
  size: number;
  explain?: boolean;
}): Promise<RecommendResponse> {
  const { data } = await http.get<RecommendResponse>('/api/search', {
    params: { q: p.q, userId: p.userId, size: p.size, explain: p.explain || undefined },
  });
  return data;
}
