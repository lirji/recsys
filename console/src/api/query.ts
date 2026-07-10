import { http } from './client';
import type { StructuredQuery } from './types';

export async function parseQuery(p: { q: string; userId: number }): Promise<StructuredQuery> {
  const { data } = await http.get<StructuredQuery>('/api/query/parse', {
    params: { q: p.q, userId: p.userId },
  });
  return data;
}
