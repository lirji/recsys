import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { clearAuth } from './auth';
import { notifyError } from './notify';
import { redirectToLogin } from './nav';
import { AUTH_MODE } from '../config/auth';
import { purgeOidcSession, resolveToken, silentRenew } from '../auth/oidc';

// 同源:baseURL 用相对路径 ''。页面/静态资源/接口都从网关 :8080(或 dev 的 Vite :5173 proxy 到 :8080)发出。
export const http = axios.create({
  baseURL: '',
  timeout: 20_000,
});

// 有 token 就带 Bearer,没有就直接发(公开只读接口无需 token)。取数按模式分派(auth/oidc.ts):
// oidc 读 oidc-client-ts 的 sessionStorage,legacy 读 localStorage —— oidc 下残留的旧 legacy token 绝不误发。
http.interceptors.request.use(async (config) => {
  const token = await resolveToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    const status = error.response?.status;
    if (status === 401) {
      if (AUTH_MODE === 'oidc') {
        // oidc:先单飞静默续期(refresh_token)重试一次;仍失败才清会话跳登录。
        const original = error.config as
          | (InternalAxiosRequestConfig & { _retried?: boolean })
          | undefined;
        if (original && !original._retried) {
          original._retried = true;
          try {
            const user = await silentRenew();
            if (user?.access_token) {
              original.headers.Authorization = `Bearer ${user.access_token}`;
              return http(original);
            }
          } catch {
            // 续期失败,落到清会话
          }
        }
        // removeUser 触发 userUnloaded → AuthProvider 置空 user → 守卫生效;再显式跳登录页。
        await purgeOidcSession();
        redirectToLogin();
      } else {
        // legacy:登录页拿不到密码,无法静默重登 → 清身份 + 跳登录页。
        clearAuth();
        redirectToLogin();
      }
    } else if (status === 403) {
      // 403 = 已登录但角色权限不足(切到低权限身份打受限页)。给全局友好提示;固定 key 去重(并发多请求只显示一条)。
      notifyError('当前身份无权访问该接口(需更高角色)', 'rbac-403');
    }
    return Promise.reject(error);
  },
);

export interface ApiErrorInfo {
  status?: number;
  message: string;
  url?: string;
}

export function toApiError(e: unknown): ApiErrorInfo {
  if (e instanceof AxiosError) {
    const status = e.response?.status;
    const data = e.response?.data as unknown;
    let message = e.message;
    if (typeof data === 'string' && data) message = data;
    else if (data && typeof data === 'object') {
      const rec = data as Record<string, unknown>;
      message = (rec.message as string) ?? (rec.error as string) ?? JSON.stringify(data);
    }
    return { status, message, url: e.config?.url };
  }
  return { message: e instanceof Error ? e.message : String(e) };
}
