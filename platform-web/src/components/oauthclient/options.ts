/** OAuth 客户端表单 / 列表 / 详情共用的可选项与显示名 */

export const GRANT_AUTH_CODE = 'authorization_code'
export const GRANT_REFRESH = 'refresh_token'
export const GRANT_CLIENT_CREDENTIALS = 'client_credentials'
export const GRANT_DEVICE_CODE = 'urn:ietf:params:oauth:grant-type:device_code'
export const GRANT_TOKEN_EXCHANGE = 'urn:ietf:params:oauth:grant-type:token-exchange'
export const GRANT_SESSION_RENEWAL = 'urn:coding:grant-type:session-renewal'

export interface GrantOption {
  value: string
  label: string
  /** 公共客户端能否使用（SAS 只对 PKCE 换 token 请求做公共客户端认证） */
  publicOk: boolean
}

export const GRANT_OPTIONS: GrantOption[] = [
  { value: GRANT_AUTH_CODE, label: '授权码（单点登录）', publicOk: true },
  { value: GRANT_REFRESH, label: '刷新令牌', publicOk: false },
  { value: GRANT_CLIENT_CREDENTIALS, label: '客户端凭据（无用户）', publicOk: false },
  { value: GRANT_DEVICE_CODE, label: '设备码', publicOk: false },
  { value: GRANT_TOKEN_EXCHANGE, label: 'Token 交换', publicOk: false },
  { value: GRANT_SESSION_RENEWAL, label: '会话续期', publicOk: true },
]

export const AUTH_METHOD_OPTIONS = [
  { value: 'client_secret_basic', label: 'Client Secret Basic' },
  { value: 'client_secret_post', label: 'Client Secret Post' },
  { value: 'private_key_jwt', label: 'Private Key JWT' },
  { value: 'none', label: 'none（同时允许按公共客户端 + PKCE 认证）' },
]

export function grantLabel(g: string): string {
  return GRANT_OPTIONS.find((o) => o.value === g)?.label.replace(/（.*）/, '') ?? g
}

export function authMethodLabel(m: string): string {
  return AUTH_METHOD_OPTIONS.find((o) => o.value === m)?.label.replace(/（.*）/, '') ?? m
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
