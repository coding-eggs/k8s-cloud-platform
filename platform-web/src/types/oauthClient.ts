/** OAuth 客户端管理（与 platform-auth 的 ClientAdmin* DTO 同形） */

export type OAuthClientType = 'CONFIDENTIAL' | 'PUBLIC'

/** 详情 —— 后端绝不返回任何密钥材料，只有「有没有」与 kid */
export interface OAuthClient {
  id: string
  clientId: string
  clientName: string
  type: OAuthClientType
  builtin: boolean
  enabled: boolean
  grantTypes: string[]
  scopes: string[]
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  requireAuthorizationConsent: boolean
  accessTokenTimeToLive: number
  refreshTokenTimeToLive: number | null
  jwtType: 'JWS' | 'JWE'
  jwsSigAlg: string
  jweKeyAlg: string | null
  jweEncMethod: string | null
  jweJwkUrl: string | null
  hasClientSecret: boolean
  hasJwsSecret: boolean
  hasJweSecret: boolean
  jwsSecretKid: string | null
  jweSecretKid: string | null
  clientIdIssuedAt: string | null
}

export interface OAuthClientRequest {
  id?: string
  clientId?: string
  clientName: string
  type?: OAuthClientType
  grantTypes: string[]
  scopes: string[]
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  requireAuthorizationConsent: boolean
  accessTokenTimeToLive: number
  refreshTokenTimeToLive: number
  jwtType: 'JWS' | 'JWE'
  jwsSigAlg: string
  jweKeyAlg?: string | null
  jweEncMethod?: string | null
  jweJwkUrl?: string | null
}

/** 本次新生成的明文密钥 —— 只出现这一次 */
export interface OAuthClientSecrets {
  clientSecret?: string
  jwsSecret?: string
  jweSecret?: string
}

export interface OAuthClientCreated {
  client: OAuthClient
  secrets: OAuthClientSecrets | null
}

export interface OAuthScope {
  name: string
  description: string
}

export type OAuthSecretKind = 'client' | 'jws' | 'jwe'
