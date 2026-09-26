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
// 当前租户上下文：仅记录「下次续期带谁」，非凭证（spec §4.3）。token 与它必须在同一函数内成对写。
const CURRENT_TENANT_KEY = 'platform_current_tenant'
// 用户在本标签页显式选了「平台视图」的标记：刷新/重载后 bootstrap 不再自动切回唯一租户
// （否则单租户成员切平台视图会被静默弹回）。sessionStorage=按标签页隔离，新标签恢复 §4.5 自动进入。
const PLATFORM_VIEW_CHOICE_KEY = 'platform_view_choice'

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

/** 当前租户上下文（localStorage 里的 platform_current_tenant） */
export interface TenantContext {
  tenantId: string
  tenantName?: string
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
    // 授权码登录只产 base token（无租户上下文，spec §4.1）：成对清掉 current_tenant，
    // 否则残留的旧租户会在下次续期时被 renewAccessToken 自动带上，静默把用户切进未选的租户。
    setCurrentTenant(null)
    // 新登录 = 新会话：撤销旧标签页可能留下的「显式平台视图」选择，让 §4.5 时序重新生效
    clearPlatformViewChoice()
    notifyTokenChanged()
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

// ==================== token 变更广播 ====================
//
// TOKEN_KEY 与 current_tenant 只在下方四个收口点成对写（spec §4.3「禁止分开读写」的延伸）：
// handleCallback（登录）、switchTenant（切租户）、renewAccessToken（到期续期）、logout。
// 权限 store（stores/permission.ts）订阅本事件同步 decode —— 各处不必手动 import refreshPermission，
// 避免 auth 层反向依赖 store 层造成循环。

const tokenListeners = new Set<() => void>()

/** 订阅 token/租户上下文变更；返回取消订阅函数 */
export function onTokenChanged(fn: () => void): () => void {
  tokenListeners.add(fn)
  return () => tokenListeners.delete(fn)
}

function notifyTokenChanged(): void {
  for (const fn of tokenListeners) {
    try {
      fn()
    } catch {
      /* 订阅者异常不影响其余 */
    }
  }
}

export function getUserInfo(): UserInfo {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? normalizeUser(JSON.parse(raw) as IdTokenClaims) : {}
  } catch {
    return {}
  }
}

/** 当前租户上下文；null = 平台视图（base token，无租户）。仅用于「下次续期带谁」。 */
export function getCurrentTenant(): TenantContext | null {
  try {
    const raw = localStorage.getItem(CURRENT_TENANT_KEY)
    return raw ? (JSON.parse(raw) as TenantContext) : null
  } catch {
    return null
  }
}

/** 设置/清空当前租户上下文。只在 switchTenant 内调用（与 token 成对写，见 spec §4.3）。 */
function setCurrentTenant(t: TenantContext | null): void {
  if (t) {
    localStorage.setItem(CURRENT_TENANT_KEY, JSON.stringify(t))
  } else {
    localStorage.removeItem(CURRENT_TENANT_KEY)
  }
}

// ---------- 「显式平台视图」标记（按标签页，sessionStorage） ----------
//
// 单租户成员在切换器里主动选「平台视图」→ 落标记；刷新/重载后 permission bootstrap 见标记
// 跳过 §4.5 自动进入，否则用户会被静默切回唯一租户。清除时机：显式选进某租户 / 重新登录 / 登出。
// 注意：续期 invalid_grant 的自动回落（dropTenantContextOnInvalidGrant）不是用户选择，不置标记——
// 那属于「上下文丢了」，下次 bootstrap 自动切回其唯一可用租户是期望行为。

/** 用户显式选择平台视图（切换器调用，须在触发 reload 之前） */
export function markPlatformViewChoice(): void {
  sessionStorage.setItem(PLATFORM_VIEW_CHOICE_KEY, '1')
}

/** 用户显式选择进入某租户 → 撤销此前的平台视图选择 */
export function clearPlatformViewChoice(): void {
  sessionStorage.removeItem(PLATFORM_VIEW_CHOICE_KEY)
}

/** 本标签页是否被用户显式定于平台视图 */
export function hasPlatformViewChoice(): boolean {
  return sessionStorage.getItem(PLATFORM_VIEW_CHOICE_KEY) === '1'
}

/** 退出：停掉保活定时器并清本地 token（服务端会话由 auth server 管理，登出后自然过期/可主动失效） */
export function logout(): void {
  stopSessionMaintenance()
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(CURRENT_TENANT_KEY)
  clearPlatformViewChoice() // 会话终结：显式平台视图选择随会话作废（handleCallback 亦有兜底清除）
  notifyTokenChanged()
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

/** session-renewal 请求结果：拿到新 token，或失败（并区分 invalid_grant——租户上下文已不可用） */
type RenewalOutcome = { ok: true; token: string } | { ok: false; invalidGrant: boolean }

/** POST /oauth2/token（grant=session-renewal）。tenantId 非空则带 tenant_id。 */
async function requestRenewalToken(tenantId?: string | null): Promise<RenewalOutcome> {
  const body = new URLSearchParams({ grant_type: RENEW_GRANT, client_id: CLIENT_ID })
  if (tenantId) body.set('tenant_id', tenantId)
  try {
    // credentials:'include' → 跨域携带 platform-auth 的会话 Cookie（服务端 CORS 已 allowCredentials）
    const resp = await fetch(`${ISSUER}/oauth2/token`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    })
    const data = (await resp.json().catch(() => ({}))) as { access_token?: string; error?: string }
    if (resp.ok && data.access_token) return { ok: true, token: data.access_token }
    return { ok: false, invalidGrant: data.error === 'invalid_grant' }
  } catch {
    return { ok: false, invalidGrant: false }
  }
}

/**
 * token 与租户上下文的唯一写入收口（spec §4.3「成对写、禁止分开读写」）：
 * 两处都写完再广播，权限 store 同步读到的必是一对自洽的新值。
 */
function commitRenewedToken(token: string, tenant: TenantContext | null): void {
  localStorage.setItem(TOKEN_KEY, token) // 单 token：覆盖
  setCurrentTenant(tenant)
  notifyTokenChanged()
}

/**
 * 手动切换的「代数」：switchTenant 每成功一次 +1。
 * 后台续期（renewIfDue / invalid_grant 补签）在 POST 前记下代数、commit 时比对——
 * await 期间若发生过用户主动切换，这次续期拿的是旧上下文的 token，直接丢弃
 * （切换结果权威，续期下个 tick 自然重来）。没有它，「续期在途 + 用户切租户」
 * 会出现旧租户 token 覆盖新租户 token 的静默串号。
 */
let switchEpoch = 0

/** 后台续期专用提交：代数未变才写（见 switchEpoch 注释）；手动切换走 commitRenewedToken 并推进代数 */
function commitRenewedTokenIfUnchanged(token: string, tenant: TenantContext | null, epoch: number): void {
  if (epoch !== switchEpoch) return
  commitRenewedToken(token, tenant)
}

/**
 * 续期遇 invalid_grant ⇒ 当前租户上下文已不可续签（被移出 / 租户禁用 / 租户被禁用）。
 * 按 spec §4.3 清 current_tenant 回落平台视图，并立即补签一个 base token：
 * 失败时旧 token 仍在原处（本就是租户 token），不重签就会让 UI 停在
 * 「旧租户权限 + 平台视图」的中间态最长 10min（下个 keepalive tick 才纠正）。
 * 已进入 null 上下文时不再进入本函数（getCurrentTenant() 为假），无递归风险。
 */
function dropTenantContextOnInvalidGrant(outcome: Extract<RenewalOutcome, { ok: false }>): void {
  if (!outcome.invalidGrant || !getCurrentTenant()) return
  const epoch = switchEpoch
  setCurrentTenant(null)
  notifyTokenChanged() // 切换器先反映「平台视图」（权限暂仍是旧租户闭包，base token 落地即收敛）
  void requestRenewalToken(null).then((r) => {
    if (r.ok) {
      commitRenewedTokenIfUnchanged(r.token, null, epoch) // 期间用户手动切换过 → 不覆盖
      scheduleRenew()
    }
  })
}

/**
 * 用会话 Cookie（根凭证）续期 access_token，不重走授权码。
 * 成功写回 localStorage 并返回 true；会话已失效则返回 false（调用方应引导重新登录）。
 * 必须携带当前租户 tenant_id：不带 = auth server 签发无租户上下文的 base token，
 * 到期静默把用户踢出当前租户（spec §4.3 的头号静默坑）。
 */
export async function renewAccessToken(): Promise<boolean> {
  const epoch = switchEpoch
  const tenant = getCurrentTenant()
  const r = await requestRenewalToken(tenant?.tenantId)
  if (!r.ok) {
    // 期间用户已手动切换成功 → 这次失败基于旧视角，忽略即可（新上下文下个 tick 自然续）
    if (epoch === switchEpoch) dropTenantContextOnInvalidGrant(r)
    return false
  }
  commitRenewedTokenIfUnchanged(r.token, tenant, epoch)
  return true
}

/** switchTenant 在途计数：renewIfDue 期间跳过自动续期，防止「带旧/空上下文的重续」覆盖切换结果
 *  （两者都是 session-renewal POST；并发时序不确定，切租户必须独占 token 写入） */
let switchInFlight = 0

/**
 * 切换/进入租户：用会话 Cookie 重新签发含租户上下文的 token。null=退回平台视图(base)。
 * 成功→在同一函数内成对写 TOKEN_KEY 与 current_tenant（spec §4.3「禁止分开读写」）。
 * 失败→零写入（旧 token/旧上下文原样保留）：回落由调用方显式 switchTenant(null) 完成，
 * 从而保证「平台视图」拿到的必是无租户的 base token，而非残留旧租户上下文的旧 token。
 * @returns 是否切换成功（失败=无权进入该租户/会话失效，由调用方提示并回落；不跳登录页）
 */
export async function switchTenant(tenantId: string | null, tenantName?: string): Promise<boolean> {
  switchInFlight++
  let r: RenewalOutcome
  try {
    r = await requestRenewalToken(tenantId)
  } finally {
    switchInFlight--
  }
  if (!r.ok) return false
  switchEpoch++ // 作废所有在途后台续期（它们拿的是旧上下文 token）
  // 成功进入某租户 = 用户显式收回了平台视图选择（失败零写入，不动标记；平台方向置标记归切换器管）
  if (tenantId) clearPlatformViewChoice()
  commitRenewedToken(r.token, tenantId ? { tenantId, tenantName } : null)
  scheduleRenew() // 用新 token 的 exp 重排续期
  return true
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
  if (switchInFlight > 0) return // 手动切租户在途 → 让 switchTenant 独占 token 写入
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
