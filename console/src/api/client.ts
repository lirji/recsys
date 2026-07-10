import axios, { AxiosError } from 'axios';

// 同源:baseURL 用相对路径 ''。页面/静态资源/接口都从网关 :8080(或 dev 的 Vite :5173 proxy 到 :8080)发出。
export const http = axios.create({
  baseURL: '',
  timeout: 20_000,
});

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
