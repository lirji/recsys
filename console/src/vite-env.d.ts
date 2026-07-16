/// <reference types="vite/client" />

// 构建期注入的认证配置(见 src/config/auth.ts,唯一消费出口)。
interface ImportMetaEnv {
  /** legacy(默认,演示登录) | oidc(Casdoor 统一登录) */
  readonly VITE_AUTH_MODE?: string;
  readonly VITE_CASDOOR_ISSUER?: string;
  readonly VITE_CASDOOR_CLIENT_ID?: string;
  readonly VITE_CASDOOR_SCOPE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
