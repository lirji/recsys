import { AUTH_MODE } from '../config/auth';

// 只是发送前的身份隔离护栏；实际权限仍由服务端验证 JWT。
let epoch = 0;
const listeners = new Set<() => void>();
export const exposureEpoch = () => epoch;
export function onExposureRevoked(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
/** 切换开始就撤销旧任务，不能等 React 下一次 effect 才阻止新 token 发送。 */
export function revokeExposureSession() {
  epoch += 1;
  try {
    for (const key of [
      'recsys.sandbox.delivery.v1',
      'recsys.sandbox.feed.v1',
      'recsys.sandbox.receipts.v1',
      'recsys.sandbox.natural.receipts.v1',
      'recsys.sandbox.natural.recommend.v1',
      'recsys.sandbox.natural.search.v1',
    ])
      sessionStorage.removeItem(key);
  } catch {
    /* 存储禁用不阻止内存撤销。 */
  }
  listeners.forEach((listener) => listener());
}
export function exposureAuthScope(token: string | null): string | null {
  if (!token) return null;
  try {
    const part = token.split('.')[1];
    const bytes = atob(part.replace(/-/g, '+').replace(/_/g, '/'));
    const claims = JSON.parse(
      new TextDecoder().decode(Uint8Array.from(bytes, (c) => c.charCodeAt(0))),
    ) as Record<string, unknown>;
    const issuer = claims.iss ?? '';
    const tenant = claims.tenant_id ?? claims.tenantId ?? '';
    if (
      typeof claims.sub !== 'string' ||
      !claims.sub ||
      claims.sub.length > 512 ||
      typeof issuer !== 'string' ||
      issuer.length > 2048 ||
      typeof tenant !== 'string' ||
      tenant.length > 512
    )
      return null;
    return JSON.stringify([window.location.origin, AUTH_MODE, issuer, claims.sub, tenant]);
  } catch {
    return null;
  }
}

// 其他标签页切换 legacy token，同样不能让当前页复用旧意图。
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (AUTH_MODE === 'legacy' && (event.key === 'recsys_token' || event.key === null))
      revokeExposureSession();
  });
}
