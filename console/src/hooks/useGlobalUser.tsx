import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

// 全局 userId / scene:多数在线调试台共享同一个「当前用户」,顶栏改一次全站生效。持久化到 localStorage。
interface GlobalUserCtx {
  userId: number;
  scene: string;
  setUserId: (v: number) => void;
  setScene: (v: string) => void;
}

const Ctx = createContext<GlobalUserCtx | null>(null);

const LS_USER = 'recsys.console.userId';
const LS_SCENE = 'recsys.console.scene';

export function GlobalUserProvider({ children }: { children: ReactNode }) {
  const [userId, setUserIdState] = useState<number>(() => {
    const raw = localStorage.getItem(LS_USER);
    const n = raw ? Number(raw) : 1;
    return Number.isFinite(n) && n > 0 ? n : 1;
  });
  const [scene, setSceneState] = useState<string>(() => localStorage.getItem(LS_SCENE) ?? 'feed');

  const value = useMemo<GlobalUserCtx>(
    () => ({
      userId,
      scene,
      setUserId: (v) => {
        setUserIdState(v);
        localStorage.setItem(LS_USER, String(v));
      },
      setScene: (v) => {
        setSceneState(v);
        localStorage.setItem(LS_SCENE, v);
      },
    }),
    [userId, scene],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useGlobalUser(): GlobalUserCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('useGlobalUser must be used within GlobalUserProvider');
  return v;
}
