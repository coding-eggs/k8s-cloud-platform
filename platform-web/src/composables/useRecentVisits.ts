/**
 * 最近访问：localStorage 环形缓冲，最多 8 条。
 *
 * 这是「常用入口」里成本最低、命中率最高的一块 —— 不需要用户手动收藏，
 * 也不落后端（只在本机本浏览器，不跨设备）。
 *
 * 写入点在 `router.afterEach`，因此必须在 `app.use(router)` 之后安装（见 main.ts）。
 */
import type { RouteLocationNormalized, Router } from 'vue-router'
import { labelOf, resolveDomain, type NavDomainKey, type NavFace } from '@/nav'
import { usePermission } from '@/stores/permission'

const STORAGE_KEY = 'platform_nav_recent'
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
  const domain = resolveDomain(face, to.path)
  if (!domain) return

  const next: RecentVisit = {
    path: to.path,
    title: (to.meta.title as string) ?? to.path,
    domain,
    domainLabel: labelOf(domain),
    face,
    ts: Date.now(),
  }
  const rest = readRecentVisits().filter((v) => v.path !== next.path)
  writeRecentVisits([next, ...rest].slice(0, MAX_ITEMS))
}

let installed = false

/** 幂等：重复调用只装一次 */
export function installRecentVisitsTracking(router: Router): void {
  if (installed) return
  installed = true
  router.afterEach((to) => recordVisit(to))
}
