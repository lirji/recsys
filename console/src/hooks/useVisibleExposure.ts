import { useEffect, useRef, useState } from 'react';

export type MeasurementPhase =
  'waiting' | 'timing' | 'measured' | 'expired' | 'unsupported' | 'full' | 'unavailable';
/** 只测实际创意；页面隐藏/离屏/卸载重置，StrictMode 不会凭挂载产生曝光。 */
export function useVisibleExposure(
  enabled: boolean,
  expiresAt: string | null,
  onVisible: (durationMs: number, minRatioBps: number) => boolean,
) {
  const ref = useRef<HTMLDivElement>(null);
  const callback = useRef(onVisible);
  callback.current = onVisible;
  const [phase, setPhase] = useState<MeasurementPhase>('waiting');
  useEffect(() => {
    const element = ref.current;
    if (!enabled || !element || !expiresAt) return;
    if (typeof IntersectionObserver === 'undefined') {
      setPhase('unsupported');
      return;
    }
    let ratio = 0;
    let started: number | null = null;
    let measured = false;
    let suspended = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const reset = () => {
      if (timer) clearTimeout(timer);
      timer = undefined;
      started = null;
      if (!measured) setPhase('waiting');
    };
    const evaluate = () => {
      if (measured) return;
      if (Date.now() >= Date.parse(expiresAt)) {
        reset();
        setPhase('expired');
        return;
      }
      if (suspended || document.hidden || ratio < 0.5) {
        reset();
        return;
      }
      if (started === null) {
        started = performance.now();
        setPhase('timing');
      }
      const duration = performance.now() - started;
      if (duration >= 1000) {
        measured = callback.current(Math.min(300000, Math.floor(duration)), 5000);
        setPhase(measured ? 'measured' : 'full');
        return;
      }
      if (timer) clearTimeout(timer);
      timer = setTimeout(evaluate, Math.max(1, 1000 - duration));
    };
    const observer = new IntersectionObserver(
      (entries) => {
        const entry = entries.find((candidate) => candidate.target === element);
        if (!entry) return;
        ratio = entry.isIntersecting ? entry.intersectionRatio : 0;
        evaluate();
      },
      { threshold: [0, 0.5, 1] },
    );
    // 恢复前强制取得新交叉样本；不使用隐藏前已陈旧的位置。
    const hidden = () => {
      reset();
      ratio = 0;
      if (!suspended && !document.hidden) {
        observer.unobserve(element);
        observer.observe(element);
      }
    };
    // pagehide 可能先于 visibilitychange；BFCache 冻结期间不得重新起计时。
    const pageHide = () => {
      suspended = true;
      hidden();
    };
    const pageShow = () => {
      suspended = false;
      hidden();
    };
    observer.observe(element);
    document.addEventListener('visibilitychange', hidden);
    window.addEventListener('pagehide', pageHide);
    window.addEventListener('pageshow', pageShow);
    return () => {
      observer.disconnect();
      document.removeEventListener('visibilitychange', hidden);
      window.removeEventListener('pagehide', pageHide);
      window.removeEventListener('pageshow', pageShow);
      if (timer) clearTimeout(timer);
    };
  }, [enabled, expiresAt]);
  return { ref, phase };
}
