import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { clearAuth, getCurrentUser, login, type AuthUser } from '../api/auth';

// 全局身份态:当前登录用户 + 登录 / 切换 / 退出。改动后失效全部 react-query 缓存,让当前页用新身份重取。
// 持久化在 localStorage(见 api/auth.ts),刷新保持登录;入口是登录页(LoginPage),不再隐式自动登录。
interface AuthCtx {
  user: AuthUser | null;
  switching: boolean;
  signIn: (username: string, password: string) => Promise<void>;
  switchUser: (username: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  // 初值从 localStorage 读:刷新后若已登录则直接进应用,否则为 null → 路由守卫拦到登录页。
  const [user, setUser] = useState<AuthUser | null>(() => getCurrentUser());
  const [switching, setSwitching] = useState(false);

  // 登录页调用:登录 → 回显身份 → 失效缓存(用新 token 重取)。
  const signIn = useCallback(
    async (username: string, password: string) => {
      await login(username, password);
      setUser(getCurrentUser());
      await queryClient.invalidateQueries();
    },
    [queryClient],
  );

  // 顶栏切换 = 以另一 demo 用户重登,停留在应用内。带 switching 态驱动 chip loading。
  const switchUser = useCallback(
    async (username: string) => {
      setSwitching(true);
      try {
        clearAuth();
        await login(username, username); // 密码 = 用户名
        setUser(getCurrentUser());
        // 换了身份 → 失效所有查询,当前页自动用新 token 重取(高/低权限切换时数据刷新或看到 403 提示)。
        await queryClient.invalidateQueries();
      } finally {
        setSwitching(false); // 成功/失败都复位
      }
    },
    [queryClient],
  );

  // 退出:清身份态 → user=null,路由守卫会把用户拦回登录页。
  const logout = useCallback(() => {
    clearAuth();
    setUser(null);
  }, []);

  const value = useMemo<AuthCtx>(
    () => ({ user, switching, signIn, switchUser, logout }),
    [user, switching, signIn, switchUser, logout],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth must be used within AuthProvider');
  return v;
}
