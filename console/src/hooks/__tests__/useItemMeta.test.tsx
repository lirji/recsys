import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { getItems } from '../../api/items';
import { useItemMeta } from '../useItemMeta';

vi.mock('../../api/items', () => ({ getItems: vi.fn() }));

it('标题补全HTTP失败保留undefined回退，重新取得数据后恢复标题', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { gcTime: 0 } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  vi.mocked(getItems).mockRejectedValue({
    response: { status: 503, data: { code: 'CONSOLE_ITEM_QUERY_FAILED' } },
  });
  const { result, unmount } = renderHook(() => useItemMeta([7, 7]), { wrapper });
  await waitFor(() => expect(client.getQueryState(['itemMeta', [7]])?.status).toBe('error'));
  expect(result.current(7)).toBeUndefined();
  expect(vi.mocked(getItems)).toHaveBeenCalledTimes(1);
  vi.mocked(getItems).mockResolvedValue([
    { itemId: 7, title: '恢复的标题', category: 'Drama', tags: [] },
  ]);
  await client.refetchQueries({ queryKey: ['itemMeta', [7]] });
  await waitFor(() => expect(result.current(7)?.title).toBe('恢复的标题'));
  unmount();
  client.clear();
});
