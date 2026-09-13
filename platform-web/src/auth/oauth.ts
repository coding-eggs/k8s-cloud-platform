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

// 会话续期 grant（自定义）：用 HttpOnly 会话 Cookie 作根凭证重新签发 access_token，不重走授权码
const RENEW_GRANT = 'urn:coding:grant-type:session-renewal'
// 会话保活间隔：每 10min 滑动一次根凭证（远小于 8h session TTL，留足容错）
const KEEPALIVE_INTERVAL_MS = 10 * 60 * 1000
// access_token 到期前多久主动续期（秒）
const RENEW_BEFORE_EXPIRY_S = 60

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
    // 登录成功 → 启动会话保活 + access_token 到期前自动续期（幂等，重复调用无副作用）
    startSessionMaintenance()
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

/** 退出：停掉保活定时器并清本地 token（服务端会话由 auth server 管理，登出后自然过期/可主动失效） */
export function logout(): void {
  stopSessionMaintenance()
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

// ==================== 会话保活 + access_token 自动续期 ====================
//
// 根凭证 = platform-auth 的 HttpOnly 会话 Cookie（8h 滑动）；access_token 只是它的短期派生物。
//   - keepalive：定期写一个会话属性 → Redis 重置 TTL，让「能登录多久」跟随活跃时长而非固定 8h。
//   - renew    ：token 到期前用 session-renewal grant 重新签发，用户全程无感、不重登。
//   - visibilitychange：后台标签页的定时器会被浏览器节流/冻结，回到前台立即补跑一次，兜底后台空窗。
//
// 接入点：登录成功（handleCallback）自动 start；页面刷新后恢复已有会话时也应各调一次 startSessionMaintenance()。

let keepaliveTimer: number | null = null
let renewTimer: number | null = null

/** 解出 JWT 的 exp（unix 秒）；解析失败返回 0 */
function tokenExpSeconds(token: string): number {
  try {
    const payload = token.split('.')[1]
    if (!payload) return 0
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return (JSON.parse(json) as { exp?: number }).exp ?? 0
  } catch {
    return 0
  }
}

/**
 * 用会话 Cookie（根凭证）续期 access_token，不重走授权码。
 * 成功写回 localStorage 并返回 true；会话已失效则返回 false（调用方应引导重新登录）。
 */
export async function renewAccessToken(): Promise<boolean> {
  const body = new URLSearchParams({
    grant_type: RENEW_GRANT,
    client_id: CLIENT_ID,
  })
  try {
    // credentials:'include' → 跨域携带 platform-auth 的会话 Cookie（服务端 CORS 已 allowCredentials）
    const resp = await fetch(`${ISSUER}/oauth2/token`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    })
    if (!resp.ok) return false
    const data = (await resp.json()) as { access_token?: string }
    if (!data.access_token) return false
    localStorage.setItem(TOKEN_KEY, data.access_token)
    return true
  } catch {
    return false
  }
}

/** 会话滑动续期。返回会话是否仍有效（false = 根凭证已失效，应重登） */
async function keepalive(): Promise<boolean> {
  try {
    const resp = await fetch(`${ISSUER}/session/keepalive`, { credentials: 'include' })
    if (!resp.ok) return false
    const data = (await resp.json()) as { code?: number }
    // 会话失效时后端返回 USER_UN_LOGIN（非 200 code），HTTP 仍是 200 → 必须看 body.code
    return data.code === 200
  } catch {
    return false
  }
}

/** token 到期前主动续期；续成功后按新 token 的 exp 重新排程 */
async function renewIfDue(): Promise<void> {
  const token = getAccessToken()
  if (!token) return
  const exp = tokenExpSeconds(token)
  const now = Math.floor(Date.now() / 1000)
  // exp - now <= buffer 同时覆盖「快到期」与「已过期」（后台冻结导致 renewTimer 没跑）两种情况
  if (exp > 0 && exp - now <= RENEW_BEFORE_EXPIRY_S) {
    if (await renewAccessToken()) scheduleRenew()
  }
}

/** 按当前 token 的 exp 排一个「到期前」的一次性续期定时器 */
function scheduleRenew(): void {
  if (renewTimer != null) {
    clearTimeout(renewTimer)
    renewTimer = null
  }
  const token = getAccessToken()
  const exp = token ? tokenExpSeconds(token) : 0
  if (!exp) return
  const delay = Math.max(1000, (exp - RENEW_BEFORE_EXPIRY_S) * 1000 - Date.now())
  renewTimer = window.setTimeout(renewIfDue, delay)
}

/** 一次保活 tick：滑会话；token 快到期就续（兜底 renewTimer 被冻结的情况） */
function tick(): void {
  void keepalive().then((alive) => {
    if (alive) void renewIfDue()
  })
}

/** 回到前台立即补跑（后台期间定时器可能被浏览器节流/冻结） */
function onVisibility(): void {
  if (document.visibilityState === 'visible') tick()
}

/**
 * 启动会话维护（keepalive 定时 + token 到期前续期 + 回前台补跑）。幂等，可重复调用。
 * @returns 停止函数
 */
export function startSessionMaintenance(): () => void {
  stopSessionMaintenance()
  scheduleRenew() // 按当前 token 排首次「到期前」续期
  tick() // 立即滑一次会话
  keepaliveTimer = window.setInterval(tick, KEEPALIVE_INTERVAL_MS)
  document.addEventListener('visibilitychange', onVisibility)
  return stopSessionMaintenance
}

/** 停止会话维护（登出时调用） */
export function stopSessionMaintenance(): void {
  if (keepaliveTimer != null) {
    clearInterval(keepaliveTimer)
    keepaliveTimer = null
  }
  if (renewTimer != null) {
    clearTimeout(renewTimer)
    renewTimer = null
  }
  document.removeEventListener('visibilitychange', onVisibility)
}
