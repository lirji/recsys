import { useCallback, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';

// 调试参数 ↔ URL 同步(仅做「可分享深链」,非双向回放):
// - initial:首次渲染从 URL 读取(缺省用传入默认),用来 seed 页面 state → 分享的链接可精确复现。
// - write:把当前参数写回 URL(replace,不堆历史)。数字键按数字解析,空值不写。
export function useUrlParams<T extends Record<string, string | number>>(defaults: T) {
  const [sp, setSp] = useSearchParams();
  const initialRef = useRef<T | null>(null);
  if (initialRef.current === null) {
    const out = { ...defaults };
    (Object.keys(defaults) as (keyof T)[]).forEach((k) => {
      const v = sp.get(String(k));
      if (v != null && v !== '') {
        out[k] = (typeof defaults[k] === 'number' ? Number(v) || defaults[k] : v) as T[keyof T];
      }
    });
    initialRef.current = out;
  }

  const write = useCallback(
    (params: Partial<T>) => {
      const next = new URLSearchParams();
      Object.entries(params).forEach(([k, v]) => {
        if (v !== '' && v != null) next.set(k, String(v));
      });
      setSp(next, { replace: true });
    },
    [setSp],
  );

  return { initial: initialRef.current, write };
}
