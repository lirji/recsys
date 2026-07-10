import axios, { AxiosError } from 'axios';
import { clearAuth, getToken } from './auth';
import { notifyError } from './notify';
import { redirectToLogin } from './nav';

// 同源:baseURL 用相对路径 ''。页面/静态资源/接口都从网关 :8080(或 dev 的 Vite :5173 proxy 到 :8080)发出。
export const http = axios.create({
  baseURL: '',
  timeout: 20_000,
});

// 有 token 就带 Bearer,没有就直接发(公开只读接口无需 token)。登录由登录页显式完成,不再隐式自动登录。
http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (r) => r,
  (error: AxiosError) => {
    const status = error.response?.status;
    // 401 = 会话过期/未登录。登录页拿不到密码,无法静默重登 → 清身份 + 跳登录页。
    if (status === 401) {
      clearAuth();
      redirectToLogin();
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
