import { useCallback, useRef, useState } from 'react';

// 每个调试台一份内存请求历史栈(最新在前)。每次成功请求 push 一条 {参数, 响应, 时间, 耗时},
// 供「历史抽屉」重跑 + 选两条做 A/B 对比。刷新即清空(内存,不落盘,避免响应体膨胀 storage)。
export interface HistoryEntry<P = unknown, D = unknown> {
  id: string;
  ts: number;
  durationMs: number;
  params: P;
  data: D;
}

export function useRequestHistory<P, D>(max = 20) {
  const [entries, setEntries] = useState<HistoryEntry<P, D>[]>([]);
  // 选中做对比的条目 id(最多 2 条)。
  const [selected, setSelected] = useState<string[]>([]);
  const seq = useRef(0);

  const push = useCallback(
    (params: P, data: D, durationMs: number) => {
      setEntries((prev) => {
        const e: HistoryEntry<P, D> = { id: `h${(seq.current += 1)}`, ts: Date.now(), durationMs, params, data };
        return [e, ...prev].slice(0, max);
      });
    },
    [max],
  );

  const toggleSelect = useCallback((id: string) => {
    setSelected((prev) => {
      if (prev.includes(id)) return prev.filter((x) => x !== id);
      // 只保留最近选中的 2 条(FIFO 淘汰最早那条)。
      return [...prev, id].slice(-2);
    });
  }, []);

  const clear = useCallback(() => {
    setEntries([]);
    setSelected([]);
  }, []);

  return { entries, push, clear, selected, toggleSelect };
}
