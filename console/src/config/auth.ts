// 认证模式配置:唯一的 import.meta.env 出口(其余模块一律从这里取,便于测试 mock 与回滚)。
// legacy(默认) = 现有演示登录(网关 RECSYS_EDGE_CASDOOR=false);oidc = Casdoor 统一登录(网关须同步开
// RECSYS_EDGE_CASDOOR=true,前后端开关必须成对,错配会一直 401,见 docs/09)。

export type AuthMode = 'legacy' | 'oidc';

function parseMode(raw: string | undefined): AuthMode {
  const v = (raw ?? 'legacy').trim().toLowerCase();
  if (v === 'legacy' || v === 'oidc') return v;
  // 拼错不静默生效也不炸页面:回退 legacy 并告警(构建期常量,dev 控制台可见)。
  console.warn(`[auth] VITE_AUTH_MODE 非法值 "${raw}",回退 legacy`);
  return 'legacy';
}

export const AUTH_MODE: AuthMode = parseMode(import.meta.env.VITE_AUTH_MODE);

// Casdoor 参数(仅 oidc 模式使用)。client_id 钉死 shared app 的 -org-recsys 派生 id:
// 网关按 <base>-org-* 家族放行且 org 钉死 recsys(单租户前端,不做登录页输租户)。
export const CASDOOR = {
  issuer: import.meta.env.VITE_CASDOOR_ISSUER ?? 'http://localhost:8000',
  clientId: import.meta.env.VITE_CASDOOR_CLIENT_ID ?? 'ragshared0client00000001-org-recsys',
  scope: import.meta.env.VITE_CASDOOR_SCOPE ?? 'openid profile offline_access',
} as const;
