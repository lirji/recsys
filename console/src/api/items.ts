import { http } from './client';
import type { ItemMeta } from './types';

// 批量查物料元数据(标题/类目/标签)。ids 逗号分隔;后端跳过非数字/去重/截断,缺失的 id 不返回。
export async function getItems(ids: number[]): Promise<ItemMeta[]> {
  if (ids.length === 0) return [];
  const { data } = await http.get<ItemMeta[]>('/api/console/items', {
    params: { ids: ids.join(',') },
  });
  return data;
}
