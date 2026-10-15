import axios, { AxiosError, CanceledError, type InternalAxiosRequestConfig } from 'axios';
import { clearAuth } from './auth';
import { notifyError } from './notify';
import { redirectToLogin } from './nav';
import { AUTH_MODE } from '../config/auth';
import { purgeOidcSession, resolveToken, silentRenew } from '../auth/oidc';

import { exposureAuthScope, exposureEpoch } from '../exposure/identity';
import { formatError } from './errors';
import { errorCodes } from './generated/errorCodes';

export { toApiError, type ApiErrorInfo } from './errors';

declare module 'axios' {
  interface AxiosRequestConfig {
    expectedExposureScope?: string;
    expectedExposureEpoch?: number;
  }
}

// 同源:baseURL 用相对路径 ''。页面/静态资源/接口都从网关 :8080(或 dev 的 Vite :5173 proxy 到 :8080)发出。
export const http = axios.create({
  baseURL: '',
  timeout: 20_000,
});

// 有 token 就带 Bearer,没有就直接发(公开只读接口无需 token)。取数按模式分派(auth/oidc.ts):
// oidc 读 oidc-client-ts 的 sessionStorage,legacy 读 localStorage —— oidc 下残留的旧 legacy token 绝不误发。
http.interceptors.request.use(async (config) => {
  const token = await resolveToken();
  if (
    config.expectedExposureScope !== undefined &&
    (exposureAuthScope(token) !== config.expectedExposureScope ||
      exposureEpoch() !== config.expectedExposureEpoch)
  ) {
    throw new CanceledError('身份已变化，原曝光任务停止发送');
  }
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    const status = error.response?.status;
    if (status === errorCodes.UNAUTHORIZED.httpStatus) {
      if (AUTH_MODE === 'oidc') {
        // oidc:先单飞静默续期(refresh_token)重试一次;仍失败才清会话跳登录。
        const original = error.config as
          (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined;
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
    } else if (status === errorCodes.FORBIDDEN.httpStatus) {
      // 403 = 已登录但角色权限不足(切到低权限身份打受限页)。给全局友好提示;固定 key 去重(并发多请求只显示一条)。
      notifyError(formatError('FORBIDDEN'), 'rbac-403');
    }
    return Promise.reject(error);
  },
);
