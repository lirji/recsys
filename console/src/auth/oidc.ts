import type { User, UserManager } from 'oidc-client-ts';
import { AUTH_MODE, CASDOOR } from '../config/auth';
import { getToken, type AuthUser, type Role } from '../api/auth';

// Casdoor OIDC(授权码 + PKCE)薄封装:UserManager 惰性单例 + 身份派生纯函数 + 模式感知取 token。
// oidc-client-ts 经动态 import 引入 —— legacy 默认构建首包完全不含该库(引入即安全)。
// token 由库自管 sessionStorage(关标签即清、refresh_token 轮换由库处理);不写 legacy 的 localStorage 三键。

// —— UserManager 惰性单例(仅 oidc 模式会被调用) ——
let managerPromise: Promise<UserManager> | null = null;

export function getUserManager(): Promise<UserManager> {
  if (!managerPromise) {
    managerPromise = import('oidc-client-ts').then(
      ({ UserManager: UM, WebStorageStateStore }) =>
        new UM({
          authority: CASDOOR.issuer, // discovery 自动拉 authorize/token/end_session 端点
          client_id: CASDOOR.clientId,
          redirect_uri: `${window.location.origin}/callback`,
          post_logout_redirect_uri: `${window.location.origin}/login`,
          response_type: 'code', // 授权码 + PKCE(库对 code 流默认 S256)
          scope: CASDOOR.scope, // offline_access → refresh_token 续期(免 iframe/第三方 cookie)
          loadUserInfo: false, // 身份从 access_token 解,免 userinfo 请求与 CORS
          // 单实例、续期只由 client.ts 的 401 单飞驱动;不开自动续期避免双续期路径打架。
          automaticSilentRenew: false,
          userStore: new WebStorageStateStore({ store: window.sessionStorage }),
        }),
    );
  }
  return managerPromise;
}

// —— 纯函数(可单测) ——

/** 解 JWT payload(不验签,验签在网关);坏 token → null。 */
export function decodeJwtPayload(token: string | null | undefined): Record<string, unknown> | null {
  if (!token) return null;
  const parts = token.split('.');
  if (parts.length < 2) return null;
  try {
    let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    b64 += '='.repeat((4 - (b64.length % 4)) % 4);
    const parsed = JSON.parse(atob(b64)) as unknown;
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

// Casdoor groups(全路径 <org>/<group>)→ 前端角色。与网关 EdgeCasdoorProperties.groupRoles 同源;
// 未映射的组一律 USER 兜底(收敛三值枚举,防 ROLE_META[unknown] 渲染崩溃)。
const GROUP_ROLES: Record<string, Role> = { admins: 'ADMIN', advertisers: 'ADVERTISER' };

/** 从 Casdoor access_token 派生前端身份;解不出用户名 → null(坏 token 不建会话,视为未登录)。 */
export function userFromCasdoorToken(accessToken: string | null | undefined): AuthUser | null {
  const payload = decodeJwtPayload(accessToken);
  if (!payload) return null;
  const username =
    (typeof payload.name === 'string' && payload.name) ||
    (typeof payload.preferred_username === 'string' && payload.preferred_username) ||
    '';
  if (!username) return null;
  const roles: Role[] = [];
  if (Array.isArray(payload.groups)) {
    for (const g of payload.groups) {
      const s = String(g);
      const i = s.lastIndexOf('/');
      const role = GROUP_ROLES[i >= 0 ? s.slice(i + 1) : s];
      if (role && !roles.includes(role)) roles.push(role);
    }
  }
  if (roles.length === 0) roles.push('USER');
  return { username, roles };
}

/** returnTo 消毒:仅允许站内绝对路径(防经 Casdoor state 往返的开放重定向);其余 → null。 */
export function sanitizeReturnTo(value: unknown): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null;
  return value;
}

/**
 * 模式感知取当前请求凭证:oidc 读 oidc-client-ts 的 sessionStorage(未过期才用),
 * legacy 读 localStorage。oidc 模式绝不读 legacy token(残留旧 HS256 token 不误发,R1)。
 */
export async function resolveToken(): Promise<string | null> {
  if (AUTH_MODE === 'oidc') {
    try {
      const um = await getUserManager();
      const user = await um.getUser();
      return user && !user.expired && user.access_token ? user.access_token : null;
    } catch {
      return null;
    }
  }
  return getToken();
}

// —— 401 单飞静默续期(client.ts 消费):共享 in-flight promise 防惊群/refresh_token 轮换连锁失效 ——
let refreshing: Promise<User | null> | null = null;

export async function silentRenew(): Promise<User | null> {
  const um = await getUserManager();
  if (!refreshing) {
    refreshing = um.signinSilent().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

/** 续期失败/登出兜底:清 oidc 会话(触发 userUnloaded 事件 → AuthProvider 置空 user)。 */
export async function purgeOidcSession(): Promise<void> {
  try {
    const um = await getUserManager();
    await um.removeUser();
  } catch {
    // 清理是尽力而为:失败也不阻断上层跳登录
  }
}
