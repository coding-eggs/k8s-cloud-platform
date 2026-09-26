/**
 * 权限 store（模块级单例，与 context.ts / nodeCatalog.ts 同套路）。
 *
 * 数据源：access_token 的 `data` claim（= 后端 TokenUserInfo：permissions/platformRoles/tenantInfo）。
 * 本平台 platform-web-client 的 jwtType=JWS，payload 可 base64 解码；解码只用于 UI 显隐，
 * **权威鉴权永远在后端**（PermissionAuthorizationManager + k8s-server 边界）。
 * 若 token 解不出（JWE/损坏），走 `POST /user/me` 的权威兜底（Task 19 登录即端点）。
 *
 * 刷新时机：oauth.ts 在「token+租户上下文成对写」的四个收口点（登录换票 / switchTenant /
 * 续期 / logout）广播 onTokenChanged，本模块订阅并同步 decode —— 见 spec §4.3。
 */
import { computed, reactive } from 'vue'
import {
  getAccessToken,
  getCurrentTenant,
  hasPlatformViewChoice,
  onTokenChanged,
  switchTenant,
  type TenantContext,
} from '@/auth/oauth'
import { userApi } from '@/api'
import type { TokenUserInfo } from '@/types'

/** 平台管理员角色 code（platform_role.code，内置 admin；代管主态，spec §4.4） */
const ADMIN_ROLE = 'admin'
/** platform_tenant.status：1 正常 0 禁用（禁用租户不可进入） */
const TENANT_ENABLED = 1

const state = reactive({
  /** 权限数据是否已可用（decode 成功或 /user/me 兜底完成；路由守卫据此等待） */
  ready: false,
  permissions: [] as string[],
  platformRoles: [] as string[],
  /** 当前租户上下文（与 token 成对，null = 平台视图） */
  tenant: null as TenantContext | null,
  bootstrapping: false,
})

/** 从 JWS access_token 解出 data claim；非 JWS / 无 claim / 结构异常返回 null */
function decodeDataClaim(token: string): TokenUserInfo | null {
  try {
    const payload = token.split('.')[1]
    if (!payload) return null
    // base64url → 二进制 → UTF-8（中文 claim 不能直接 atob 字符串）
    const bin = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0))
    const claims = JSON.parse(new TextDecoder().decode(bytes)) as { data?: TokenUserInfo }
    return claims.data ?? null
  } catch {
    return null
  }
}

/** 同步 decode（token 变更后调用；解不出则置 ready=false 等 load() 兜底） */
function refreshPermission(): void {
  const token = getAccessToken()
  if (!token) {
    state.permissions = []
    state.platformRoles = []
    state.tenant = null
    state.ready = true // 无 token：守卫会先跳登录，这里不阻塞
    return
  }
  const claims = decodeDataClaim(token)
  if (claims) {
    state.permissions = claims.permissions ?? []
    state.platformRoles = claims.platformRoles ?? []
    state.tenant = getCurrentTenant()
    state.ready = true
  } else {
    state.ready = false // JWE/损坏 → 交给 load() 的 /user/me 权威兜底
  }
}

/**
 * 确保权限就绪：优先同步 decode；解不出（JWE 等）→ `POST /user/me` 拉权威数据（一次 await）。
 * /user/me 失败（会话已失效由 http.ts 统一跳登录）→ 空权限 + ready，菜单收敛到无权限态。
 */
async function load(): Promise<void> {
  const token = getAccessToken()
  if (!token) {
    refreshPermission()
    return
  }
  if (decodeDataClaim(token)) {
    refreshPermission()
    return
  }
  try {
    const me = await userApi.me()
    state.permissions = me.permissions ?? []
    state.platformRoles = me.platformRoles ?? []
    state.tenant = getCurrentTenant()
  } catch {
    state.permissions = []
    state.platformRoles = []
    state.tenant = getCurrentTenant()
  } finally {
    state.ready = true
  }
}

/** 每次页面加载（含刷新）只引导一遍；登录后由 router 守卫触发 */
let bootstrapped = false

/**
 * 登录成功 / 会话恢复后的引导（spec §4.5 产品时序）：
 *   admin → 平台视图（看具体租户用筛选器，代管轨 §4.4，不自动切）；
 *   非 admin 且当前无租户上下文 → /user/my-tenants：0 个平台视图；
 *   恰好 1 个启用租户 → 自动 switchTenant 进入（失败=invalid_grant，容忍，留在平台视图）；
 *   多个 → 留在平台视图，由顶栏切换器自选。
 * 已在租户上下文（刷新恢复 / 手动切换后）不重复自动切，避免覆盖用户显式选择。
 * 本标签页曾显式选过「平台视图」（sessionStorage 标记，切换器落）→ 同样跳过自动切，
 * 否则单租户成员 reload 后会被静默弹回唯一租户（标记在重新登录/登出/显式选租户时清除）。
 * 调用点：router.beforeEach 首次非 public 导航里 await —— 早于页面 mount，
 * 自动切换后页面数据即用新上下文，无需额外刷新。
 */
async function bootstrap(): Promise<void> {
  if (bootstrapped || state.bootstrapping) return
  state.bootstrapping = true
  try {
    await load()
    if (getAccessToken()) {
      if (
        !state.platformRoles.includes(ADMIN_ROLE) &&
        !getCurrentTenant() &&
        !hasPlatformViewChoice()
      ) {
        try {
          const tenants = await userApi.myTenants()
          const enabled = tenants.filter((t) => t.status === TENANT_ENABLED)
          const only = enabled.length === 1 ? enabled[0] : undefined
          // 失败（invalid_grant/网络）→ 留在平台视图，切换器仍可手动重试
          if (only) await switchTenant(only.id, only.name)
        } catch {
          /* 拉不到（会话失效等）→ 不自动切，后续 401 流程兜底 */
        }
      }
    }
    bootstrapped = true
  } finally {
    state.bootstrapping = false
  }
}

export function usePermission() {
  // 与 memory「Vue store ref 解包坑」同源：reactive 包装，属性访问即解包值，
  // 模板/守卫直接 perm.isAdmin / perm.has(...)，无需 .value、也不要把对象丢进 v-for。
  return reactive({
    ready: computed(() => state.ready),
    permissions: computed(() => state.permissions),
    platformRoles: computed(() => state.platformRoles),
    isAdmin: computed(() => state.platformRoles.includes(ADMIN_ROLE)),
    /** 当前租户上下文；null = 平台视图（base token） */
    currentTenant: computed(() => state.tenant),
    has: (code: string) => state.permissions.includes(code),
    /** ANY-of：命中任一权限点即通过（与后端 seed 的端点多行 ANY-of 语义对齐，Task 18 裁定） */
    hasAny: (codes: string[]) => codes.some((c) => state.permissions.includes(c)),
    load,
    bootstrap,
  })
}

// 模块加载即恢复一次（router/index.ts 引用本模块 → 首屏守卫前 permissions 已就绪，JWS 路径零异步）
refreshPermission()
// 登录换票 / 切租户 / 到期续期 / 登出 —— token 成对写收口点的统一刷新订阅
onTokenChanged(refreshPermission)
