/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OAUTH_ISSUER: string
  readonly VITE_OAUTH_CLIENT_ID: string
  /** 可选（构建期）：不配则回落到运行时配置 / 页面 origin + /callback */
  readonly VITE_OAUTH_REDIRECT_URI?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/**
 * 运行时配置（`/runtime-config.js`，由 platform-web 镜像的 nginx 按容器环境变量现生成）。
 * 开发环境为空对象（public/runtime-config.js 占位），一律走回落链。
 */
interface PlatformRuntimeConfig {
  /** OAuth 回调地址；空 = 未配置（见 oauth.ts 的优先级） */
  oauthRedirectUri?: string
}

interface Window {
  __PLATFORM_RUNTIME_CONFIG__?: PlatformRuntimeConfig
}
