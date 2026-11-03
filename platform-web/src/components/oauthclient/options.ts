/** OAuth 客户端表单 / 列表 / 详情共用的可选项与显示名 */

export const GRANT_AUTH_CODE = 'authorization_code'
export const GRANT_REFRESH = 'refresh_token'
export const GRANT_CLIENT_CREDENTIALS = 'client_credentials'
export const GRANT_DEVICE_CODE = 'urn:ietf:params:oauth:grant-type:device_code'
export const GRANT_SESSION_RENEWAL = 'urn:coding:grant-type:session-renewal'

export interface GrantOption {
  value: string
  label: string
  desc: string
  /** 公共客户端能否使用（SAS 只对 PKCE 换 token 请求做公共客户端认证） */
  publicOk: boolean
}

export const GRANT_OPTIONS: GrantOption[] = [
  { value: GRANT_AUTH_CODE, label: '授权码', desc: '用户跳到平台登录后回到对方系统，网站和 App 单点登录用这个', publicOk: true },
  { value: GRANT_REFRESH, label: '刷新令牌', desc: 'Access Token 过期后不打扰用户直接续期', publicOk: false },
  { value: GRANT_CLIENT_CREDENTIALS, label: '客户端凭据', desc: '系统对系统调用，token 里没有任何用户信息', publicOk: false },
  { value: GRANT_DEVICE_CODE, label: '设备码', desc: '电视、命令行等不便输入的设备，在另一台设备上确认登录', publicOk: false },
  { value: GRANT_SESSION_RENEWAL, label: '会话续期', desc: '凭平台登录会话续期 token，不需要刷新令牌', publicOk: true },
]

export const AUTH_METHOD_OPTIONS = [
  { value: 'client_secret_basic', label: 'Client Secret Basic', desc: 'client_secret 放在 HTTP Basic 头里，最常见' },
  { value: 'client_secret_post', label: 'Client Secret Post', desc: 'client_secret 放在请求体表单里' },
  { value: 'private_key_jwt', label: 'Private Key JWT', desc: '对方用私钥签名证明身份，平台用其公钥验证，不需要 client_secret' },
  { value: 'none', label: 'none', desc: '不认证客户端，只靠 PKCE；可以和上面的方式同时勾选' },
]

export function grantLabel(g: string): string {
  return GRANT_OPTIONS.find((o) => o.value === g)?.label ?? g
}

export function authMethodLabel(m: string): string {
  return AUTH_METHOD_OPTIONS.find((o) => o.value === m)?.label ?? m
}

export const HMAC = ['HS256', 'HS384', 'HS512']
export const ASYM_JWS = ['RS256', 'RS384', 'RS512', 'ES256', 'ES384', 'ES512']
export const SYM_JWE = ['dir', 'A128KW', 'A192KW', 'A256KW', 'A128GCMKW', 'A192GCMKW', 'A256GCMKW']
export const ASYM_JWE = [
  'RSA-OAEP',
  'RSA-OAEP-256',
  'RSA-OAEP-384',
  'RSA-OAEP-512',
  'ECDH-ES',
  'ECDH-ES+A128KW',
  'ECDH-ES+A192KW',
  'ECDH-ES+A256KW',
]
export const ENC = ['A128GCM', 'A192GCM', 'A256GCM', 'A128CBC-HS256', 'A192CBC-HS384', 'A256CBC-HS512']
