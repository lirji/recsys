import { useQuery } from '@tanstack/react-query';
import { getItems } from '../api/items';
import type { ItemMeta } from '../api/types';

// 批量拉物料元数据并按 id 归约成查表函数。返回 meta(id):有则 ItemMeta,无(缺失/未加载/请求失败)则 undefined。
// queryKey 按去重排序后的 id 集合缓存,同一批 id 只请求一次;staleTime=Infinity(物料元数据基本不变)。
// 请求失败静默降级(返回空表),调用方退回 #itemId 展示。
export function useItemMeta(ids: number[]): (id: number) => ItemMeta | undefined {
  const uniq = Array.from(new Set(ids)).sort((a, b) => a - b);
  const { data } = useQuery({
    queryKey: ['itemMeta', uniq],
    queryFn: () => getItems(uniq),
    enabled: uniq.length > 0,
    staleTime: Infinity,
    retry: false,
  });
  const map = new Map<number, ItemMeta>();
  for (const m of data ?? []) map.set(m.itemId, m);
  return (id: number) => map.get(id);
}
