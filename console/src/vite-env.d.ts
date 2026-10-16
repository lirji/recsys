/// <reference types="vite/client" />

// 构建期注入的认证配置(见 src/config/auth.ts,唯一消费出口)。
interface ImportMetaEnv {
  readonly VITE_SANDBOX_CLICK_ENABLED?: string;
  /** 最终混排 sandbox，默认关闭，须先启用对应 owner。 */
  readonly VITE_FEED_EXPOSURE_SANDBOX_ENABLED?: string;
  /** 自然图文 sandbox 显式启用，默认关闭。 */
  readonly VITE_NATURAL_EXPOSURE_SANDBOX_ENABLED?: string;
  /** legacy(默认,演示登录) | oidc(Casdoor 统一登录) */
  readonly VITE_AUTH_MODE?: string;
  readonly VITE_CASDOOR_ISSUER?: string;
  readonly VITE_CASDOOR_CLIENT_ID?: string;
  readonly VITE_CASDOOR_ORGANIZATION?: string;
  readonly VITE_CASDOOR_SCOPE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
