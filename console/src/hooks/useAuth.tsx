import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { clearAuth, getCurrentUser, login, type AuthUser } from '../api/auth';
import { AUTH_MODE, type AuthMode } from '../config/auth';
import {
  getUserManager,
  purgeOidcSession,
  sanitizeReturnTo,
  userFromCasdoorToken,
} from '../auth/oidc';
import { redirectToLogin } from '../api/nav';

// 全局身份态:当前登录用户 + 登录 / 切换 / 退出。改动后失效全部 react-query 缓存,让当前页用新身份重取。
// 双驱动(config/auth.ts 的 AUTH_MODE):
//   legacy(默认) —— 演示登录,持久化在 localStorage,行为与接入前逐字一致;
//   oidc —— Casdoor 统一登录,会话由 oidc-client-ts 自管(sessionStorage),user 是它的内存镜像,
//           经事件(userLoaded/userUnloaded/silentRenewError)保持同步。
interface AuthCtx {
  user: AuthUser | null;
  /** 当前认证模式(UI 据此分叉:登录页/身份切换器)。 */
  mode: AuthMode;
  /**
   * 引导完成标志:oidc 模式下从 sessionStorage 恢复会话是异步的,ready=false 期间路由守卫
   * 必须等待(渲染 loading)而不是重定向登录——否则硬刷新首帧 user 还是 null,守卫抢跑把已
   * 登录用户弹回 /login(掉登录态)。legacy 同步恢复,恒 true。
   */
  ready: boolean;
  switching: boolean;
  /** legacy 专用:演示账号登录。oidc 模式下调用是编程错误,直接抛。 */
  signIn: (username: string, password: string) => Promise<void>;
  /** oidc 专用:跳 Casdoor 授权页(returnTo 经 OIDC state 往返,回调后回原页)。 */
  signInOidc: (returnTo: string) => Promise<void>;
  /** oidc 专用:回调页收口——完成 code 兑换、user 落状态后才返回(防守卫竞态),返回消毒后的 returnTo。 */
  completeSignIn: () => Promise<string>;
  switchUser: (username: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  // legacy 初值从 localStorage 读(刷新保持登录);oidc 初值 null,由 bootstrap effect 从 oidc 存储恢复。
  const [user, setUser] = useState<AuthUser | null>(() =>
    AUTH_MODE === 'legacy' ? getCurrentUser() : null,
  );
  // legacy 同步恢复即就绪;oidc 等 bootstrap(异步读 sessionStorage)完成才就绪。
  const [ready, setReady] = useState(AUTH_MODE === 'legacy');
  const [switching, setSwitching] = useState(false);

  // —— oidc:启动引导 + 事件同步(单一状态源 = oidc-client-ts 存储,这里只做镜像) ——
  useEffect(() => {
    if (AUTH_MODE !== 'oidc') return;
    // R1:进入 oidc 模式即清 legacy 三键,残留旧 token 既不误发(resolveToken 已隔离)也不残留。
    clearAuth();
    let disposed = false;
    let cleanup: (() => void) | null = null;
    getUserManager()
      .then((um) => {
        if (disposed) return;
        // 刷新恢复:有未过期存储会话则直接建镜像;过期/坏 token 不建会话(401 单飞续期兜底)。
        // 无论恢复与否,恢复动作完成后才置 ready —— 守卫在此之前不得重定向(掉登录态 bug 的根因)。
        um.getUser()
          .then((u) => {
            if (!disposed && u && !u.expired) setUser(userFromCasdoorToken(u.access_token));
          })
          .catch(() => {})
          .finally(() => {
            if (!disposed) setReady(true);
          });
        const onLoaded = (u: { access_token?: string }) =>
          setUser(userFromCasdoorToken(u.access_token));
        const onUnloaded = () => setUser(null);
        um.events.addUserLoaded(onLoaded);
        um.events.addUserUnloaded(onUnloaded);
        um.events.addSilentRenewError(onUnloaded);
        cleanup = () => {
          um.events.removeUserLoaded(onLoaded);
          um.events.removeUserUnloaded(onUnloaded);
          um.events.removeSilentRenewError(onUnloaded);
        };
      })
      .catch(() => {
        // UserManager 装载失败(如配置/网络):放行到登录页给可读错误,不能卡在 loading。
        if (!disposed) setReady(true);
      });
    return () => {
      disposed = true;
      cleanup?.();
    };
  }, []);

  // 登录页调用(legacy):登录 → 回显身份 → 失效缓存(用新 token 重取)。
  const signIn = useCallback(
    async (username: string, password: string) => {
      if (AUTH_MODE !== 'legacy') throw new Error('signIn 仅 legacy 模式可用,oidc 请用 signInOidc');
      await login(username, password);
      setUser(getCurrentUser());
      await queryClient.invalidateQueries();
    },
    [queryClient],
  );

  // 登录页调用(oidc):整页跳 Casdoor 授权,returnTo 塞进 OIDC state 往返。
  const signInOidc = useCallback(async (returnTo: string) => {
    const um = await getUserManager();
    await um.signinRedirect({ state: { returnTo } });
  }, []);

  // 回调页调用(oidc):兑换 code → 派生身份 → setUser 之后才返回,CallbackPage 再导航(防 RequireAuth 弹回)。
  const completeSignIn = useCallback(async (): Promise<string> => {
    const um = await getUserManager();
    const oidcUser = await um.signinRedirectCallback();
    const derived = userFromCasdoorToken(oidcUser.access_token);
    if (!derived) {
      // 坏 token 不建会话(幽灵登录防护):清掉库里刚写入的会话再报错。
      await purgeOidcSession();
      throw new Error('登录凭证无法解析(access_token 缺少用户信息)');
    }
    setUser(derived);
    await queryClient.invalidateQueries();
    const state = oidcUser.state as { returnTo?: unknown } | undefined;
    return sanitizeReturnTo(state?.returnTo) ?? '/overview';
  }, [queryClient]);

  // 顶栏切换 = 以另一 demo 用户重登(legacy 专属;oidc 下切换行已隐藏,防御性 no-op)。
  const switchUser = useCallback(
    async (username: string) => {
      if (AUTH_MODE !== 'legacy') return;
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

  // 退出。legacy:清身份态 → user=null,路由守卫拦回登录页(调用方负责导航,现状不变)。
  // oidc:直接 signoutRedirect(库内部自取 id_token_hint 并 removeUser,先手动清会丢 hint 导致
  // Casdoor 端会话不结束、下次登录被 SSO 秒登);失败才兜底本地清 + 经 nav holder 跳登录
  // (AuthProvider 在 Router 外,拿不到 useNavigate)。导航全权在此,AppLayout 不再补 navigate。
  const logout = useCallback(() => {
    if (AUTH_MODE === 'legacy') {
      clearAuth();
      setUser(null);
      return;
    }
    void (async () => {
      try {
        const um = await getUserManager();
        await um.signoutRedirect();
      } catch {
        await purgeOidcSession();
        clearAuth();
        setUser(null);
        redirectToLogin();
      }
    })();
  }, []);

  const value = useMemo<AuthCtx>(
    () => ({
      user,
      mode: AUTH_MODE,
      ready,
      switching,
      signIn,
      signInOidc,
      completeSignIn,
      switchUser,
      logout,
    }),
    [user, ready, switching, signIn, signInOidc, completeSignIn, switchUser, logout],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth must be used within AuthProvider');
  return v;
}
