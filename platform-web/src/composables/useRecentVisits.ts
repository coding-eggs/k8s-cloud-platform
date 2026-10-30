/**
 * 最近访问：localStorage 环形缓冲，最多 8 条。
 *
 * 这是「常用入口」里成本最低、命中率最高的一块 —— 不需要用户手动收藏，
 * 也不落后端（只在本机本浏览器，不跨设备）。
 *
 * 写入点在 `router.afterEach`，因此必须在 `app.use(router)` 之后安装（见 main.ts）。
 */
import type { RouteLocationNormalized, Router } from 'vue-router'
import { labelOf, resolveNavLocation, type NavDomainKey, type NavFace } from '@/nav'
import { usePermission } from '@/stores/permission'

/** v2：2026-10-30「应用」「交付」两域合并，旧记录里带着已不存在的域 key 与旧域名（「交付」），
 *  直接换键丢弃，避免总览的最近访问里冒出已经不存在的域标签。 */
const STORAGE_KEY = 'platform_nav_recent_v2'
const MAX_ITEMS = 8

export interface RecentVisit {
  path: string
  title: string
  domain: NavDomainKey
  domainLabel: string
  face: NavFace
  ts: number
}

export function readRecentVisits(): RecentVisit[] {
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
    if (!Array.isArray(raw)) return []
    return (raw as RecentVisit[]).filter((v) => v && typeof v.path === 'string' && typeof v.title === 'string')
  } catch {
    // 损坏的 JSON / 隐私模式下 localStorage 抛异常：当作没有记录，不影响页面
    return []
  }
}

export function clearRecentVisits(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* 忽略 */
  }
}

function writeRecentVisits(list: RecentVisit[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list))
  } catch {
    /* 配额满 / 隐私模式：不记即可 */
  }
}

function recordVisit(to: RouteLocationNormalized): void {
  if (to.meta.public) return
  // 详情/编辑页不进最近访问：列表里全是同类条目会变噪声，且它们依赖 query
  if (to.meta.nav === 'hidden' || !to.meta.domain) return

  const perm = usePermission()
  const face: NavFace = perm.currentTenant ? 'tenant' : 'platform'
  const loc = resolveNavLocation(face, to.path)
  // 来自另一个面的页面（管理员深链到平台页）不记：它不属于本面的域，记进来会在
  // 本面的「最近访问」里出现一条点进去是另一面页面的条目。
  if (!loc || loc.otherFace) return

  const next: RecentVisit = {
    path: to.path,
    title: (to.meta.title as string) ?? to.path,
    domain: loc.domain,
    domainLabel: labelOf(loc.domain),
    face,
    ts: Date.now(),
  }
  // 去重键是 path + face：both 面的页面（Git 凭据、链路追踪）在两个视图各算一条
  const rest = readRecentVisits().filter((v) => !(v.path === next.path && v.face === next.face))
  writeRecentVisits([next, ...rest].slice(0, MAX_ITEMS))
}

let installed = false

/** 幂等：重复调用只装一次 */
export function installRecentVisitsTracking(router: Router): void {
  if (installed) return
  installed = true
  router.afterEach((to) => recordVisit(to))
}
