import type { ActionMode } from '../api/sandboxActions';
import { useSyncExternalStore } from 'react';
import { actionQueue } from '../exposure/actionReceiptQueue';

/** 只读当前服务端资金快照；按钮刷新和后台恢复共用队列请求上限。 */
export function useActionSettlement(id: string, mode: ActionMode = 'click') {
  const queue = actionQueue();
  useSyncExternalStore(queue.subscribe, queue.snapshot);
  return {
    settlement: queue.settlement(id, mode),
    error: queue.settlementError(id, mode),
    busy: queue.busy(`settlement:${mode}:${id}`),
    refresh: () => void queue.querySettlement(id, mode),
  };
}
