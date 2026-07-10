import axios from 'axios';

// P0 安全:登录 + token / 身份管理。
// 全站启用了边缘认证(网关校验 JWT),写侧/管理接口(实验管理、冷启动兴趣、广告主 CRUD、行为上报)需带 Bearer token。
// 用户先过登录页(LoginPage)登录 → token/username/roles 落 localStorage(刷新保持登录);顶栏可切换 admin/advertiser/user 演示 RBAC。
// 用裸 axios 调 /api/auth/login(不走带拦截器的 http 实例,避免死循环)。

const TOKEN_KEY = 'recsys_token';
const USERNAME_KEY = 'recsys_username';
const ROLES_KEY = 'recsys_roles';
const DEFAULT_USER = { username: 'admin', password: 'admin' };

export type Role = 'ADMIN' | 'ADVERTISER' | 'USER';

export interface AuthUser {
  username: string;
  roles: Role[];
}

/** 可切换的演示用户(密码 = 用户名)。 */
export const DEMO_USERS = ['admin', 'advertiser', 'user'] as const;

interface LoginResponse {
  token: string;
  tokenType?: string;
  expiresInSeconds?: number;
  roles?: string[];
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

/** 只清 token(供 client.ts 401 重登用),保留 username/roles 供回显。 */
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

/** 清空全部身份态(token + username + roles)。切换身份前调用。 */
export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USERNAME_KEY);
  localStorage.removeItem(ROLES_KEY);
}

/** 从 localStorage 读当前身份;无 username/roles 记录则返回 null。 */
export function getCurrentUser(): AuthUser | null {
  const username = localStorage.getItem(USERNAME_KEY);
  const rolesRaw = localStorage.getItem(ROLES_KEY);
  if (!username || !rolesRaw) return null;
  try {
    const roles = JSON.parse(rolesRaw) as unknown;
    if (!Array.isArray(roles)) return null;
    return { username, roles: roles as Role[] };
  } catch {
    return null;
  }
}

export async function login(
  username: string = DEFAULT_USER.username,
  password: string = DEFAULT_USER.password,
): Promise<string> {
  const resp = await axios.post<LoginResponse>('/api/auth/login', { username, password });
  const data = resp.data;
  // 落盘全部身份态:token + 我们请求的 username + 响应里的 roles(响应直接带 roles,无需解 JWT)。
  localStorage.setItem(TOKEN_KEY, data.token);
  localStorage.setItem(USERNAME_KEY, username);
  localStorage.setItem(ROLES_KEY, JSON.stringify(Array.isArray(data.roles) ? data.roles : []));
  return data.token;
}
