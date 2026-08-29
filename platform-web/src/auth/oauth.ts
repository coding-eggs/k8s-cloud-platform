/**
 * OAuth2 授权码 + PKCE（公共客户端，无 client secret）。
 * 对接 platform-auth（Spring Authorization Server）：
 *   - 授权端点 {issuer}/oauth2/authorize
 *   - 令牌端点 {issuer}/oauth2/token
 */

const ISSUER = import.meta.env.VITE_OAUTH_ISSUER
const CLIENT_ID = import.meta.env.VITE_OAUTH_CLIENT_ID
const REDIRECT_URI = import.meta.env.VITE_OAUTH_REDIRECT_URI

const TOKEN_KEY = 'platform_access_token'
const USER_KEY = 'platform_user'
const STATE_KEY = 'oauth2_state'
const VERIFIER_KEY = 'oauth2_code_verifier'

export interface UserInfo {
  name?: string
  email?: string
  sub?: string
}

/** token 原始 claims（本平台：显示名/邮箱在 data 里） */
interface IdTokenClaims extends Partial<UserInfo> {
  data?: { username?: string; displayName?: string; email?: string }
}

function normalizeUser(claims: IdTokenClaims): UserInfo {
  return {
    name: claims.name ?? claims.data?.displayName ?? claims.data?.username ?? claims.sub,
    email: claims.email ?? claims.data?.email,
    sub: claims.sub,
  }
}

/** base64url 编码（无填充） */
function base64UrlEncode(input: Uint8Array | string): string {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : input
  let bin = ''
  bytes.forEach((b) => (bin += String.fromCharCode(b)))
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** 生成随机 code_verifier（43~128 位 URL-safe 字符） */
function generateCodeVerifier(length = 64): string {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return base64UrlEncode(bytes)
}

/** SHA-256 → base64url（S256 code_challenge） */
async function sha256Challenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64UrlEncode(new Uint8Array(digest))
}

/** 生成随机 state（防 CSRF） */
function generateState(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return base64UrlEncode(bytes)
}

/** 跳转授权端点发起登录 */
export async function login(): Promise<void> {
  const state = generateState()
  const verifier = generateCodeVerifier()
  const challenge = await sha256Challenge(verifier)
  sessionStorage.setItem(STATE_KEY, state)
  sessionStorage.setItem(VERIFIER_KEY, verifier)

  const params = new URLSearchParams({
    response_type: 'code',
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    scope: 'openid profile',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  })
  window.location.href = `${ISSUER}/oauth2/authorize?${params.toString()}`
}

/** 从 id_token（JWT）中解出用户信息（不校验签名，仅展示用）。
 * 本平台 token 无标准 name claim，显示名在 data.displayName / data.username 里，逐级兜底到 sub */
function decodeIdToken(idToken: string): UserInfo {
  try {
    const payload = idToken.split('.')[1]
    if (!payload) return {}
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const claims = JSON.parse(json) as IdTokenClaims
    return normalizeUser(claims)
  } catch {
    return {}
  }
}

/**
 * 处理回调：用 code + code_verifier 换 token。
 * @returns 是否成功
 */
export async function handleCallback(code: string, state: string): Promise<boolean> {
  const expectedState = sessionStorage.getItem(STATE_KEY)
  const verifier = sessionStorage.getItem(VERIFIER_KEY)
  sessionStorage.removeItem(STATE_KEY)
  sessionStorage.removeItem(VERIFIER_KEY)

  if (!expectedState || expectedState !== state) {
    return false
  }
  if (!verifier) {
    return false
  }

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: REDIRECT_URI,
    client_id: CLIENT_ID,
    code_verifier: verifier,
  })

  try {
    const resp = await fetch(`${ISSUER}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    })
    if (!resp.ok) return false
    const data = (await resp.json()) as { access_token?: string; id_token?: string }
    if (!data.access_token) return false
    localStorage.setItem(TOKEN_KEY, data.access_token)
    if (data.id_token) {
      localStorage.setItem(USER_KEY, JSON.stringify(decodeIdToken(data.id_token)))
    }
    return true
  } catch {
    return false
  }
}

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getUserInfo(): UserInfo {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? normalizeUser(JSON.parse(raw) as IdTokenClaims) : {}
  } catch {
    return {}
  }
}

/** 退出：清本地 token（服务端 refresh/会话由 auth server 管理） */
export function logout(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
