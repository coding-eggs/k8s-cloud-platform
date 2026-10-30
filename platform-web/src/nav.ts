/**
 * 导航数据源：把 `router.getRoutes()` 的路由元数据折叠成「域 → 菜单项」。
 *
 * 顶栏域 tab、侧栏菜单、总览快捷入口、命令面板全部消费这里的结果，**不再有第二份菜单清单**。
 * 加一个页面 = 在路由上补 `meta.domain` / `meta.icon` / `meta.order`，菜单自动出现。
 *
 * ⚠️ 本模块 import 了 `@/router`，而 `router/index.ts` → `MainLayout.vue` → 本模块构成环。
 * ESM 活绑定下这是安全的，前提是**模块顶层不得访问 `router`**（只能在函数体里用）。
 */
import router from '@/router'
import type { RouteRecordNormalized } from 'vue-router'

export type NavFace = 'platform' | 'tenant'

export type NavDomainKey =
  | 'cluster'
  | 'network'
  | 'org'
  | 'ops'
  | 'app'
  | 'resource'
  | 'storage'
  | 'observe'

export interface NavItem {
  title: string
  path: string
  icon: string
  codes: string[]
}

export interface NavDomain {
  key: NavDomainKey
  label: string
  items: NavItem[]
}

/** `buildNav` 需要的权限判断；`usePermission()` 的返回值在结构上满足它 */
export interface PermissionFacade {
  has: (code: string) => boolean
  hasAny: (codes: string[]) => boolean
}

const LABELS: Record<NavDomainKey, string> = {
  cluster: '集群',
  network: '网络',
  org: '组织',
  ops: '运维',
  /** 「应用」与「交付」合成一个域（2026-10-30）：应用编排 / 中间件 → 流水线 / 凭据 / 镜像仓库，
   *  正好是「把东西造出来并跑起来」的一条线，6 项，仍在一屏之内。 */
  app: '应用',
  resource: '资源',
  storage: '存储',
  observe: '观测',
}

/** 两个视图各自的域顺序（租户视图 6 域、平台视图 4 域） */
const DOMAIN_LAYOUT: Record<NavFace, NavDomainKey[]> = {
  platform: ['cluster', 'network', 'org', 'ops'],
  tenant: ['app', 'resource', 'storage', 'network', 'observe', 'org'],
}

const VALID_DOMAINS = new Set<string>(Object.keys(LABELS))

export function labelOf(key: NavDomainKey): string {
  return LABELS[key]
}

/**
 * 页面码 → 面（这个页面归哪个视图）。
 * 两族都有 = both；没有页面码的页面（总览 / 个人中心）按 both 处理 ——
 * 它们没有 `domain`，不会进导航，这里只是给调用方一个确定值。
 */
export function faceOf(codes: string[] | undefined): 'platform' | 'tenant' | 'both' {
  if (!codes?.length) return 'both'
  const p = codes.some((c) => c.startsWith('platform:'))
  const t = codes.some((c) => c.startsWith('tenant:'))
  if (p && t) return 'both'
  return p ? 'platform' : 'tenant'
}

function faceMatches(itemFace: 'platform' | 'tenant' | 'both', face: NavFace): boolean {
  return itemFace === 'both' || itemFace === face
}

/** 这条路由在当前视图下归哪个域（both 面的页面允许在平台视图改归另一个域） */
function domainOf(meta: RouteRecordNormalized['meta'], face: NavFace): NavDomainKey | undefined {
  return (face === 'platform' ? meta.platformDomain : undefined) ?? meta.domain
}

/** 构建当前视图的导航；无可见项的域已被剔除 */
export function buildNav(face: NavFace, perm: PermissionFacade): NavDomain[] {
  const layout = DOMAIN_LAYOUT[face]
  const buckets = new Map<NavDomainKey, Array<{ item: NavItem; order: number }>>()

  for (const route of router.getRoutes()) {
    const meta = route.meta
    if (meta.nav === 'hidden') continue
    const codes = meta.requiresPerm
    if (!codes?.length) continue
    if (!faceMatches(faceOf(codes), face)) continue
    if (!perm.hasAny(codes)) continue
    const key = domainOf(meta, face)
    if (!key || !layout.includes(key)) continue
    const bucket = buckets.get(key) ?? []
    bucket.push({
      order: meta.order ?? 999,
      item: { title: meta.title ?? route.path, path: route.path, icon: meta.icon ?? 'Menu', codes },
    })
    buckets.set(key, bucket)
  }

  return layout
    .map((key) => {
      const items = (buckets.get(key) ?? [])
        .sort((a, b) => a.order - b.order || a.item.title.localeCompare(b.item.title, 'zh-CN'))
        .map((e) => e.item)
      return { key, label: LABELS[key], items }
    })
    .filter((d) => d.items.length > 0)
}

export interface NavLocation {
  domain: NavDomainKey
  /**
   * 这条路由属于**另一个面**（例如管理员在租户视图打开了 `/ops/ippools`）。
   * 两侧的域 key 会撞名（两边都有 `network` / `org`），所以此时绝不能沿用本面同名域的菜单
   * —— 必须改用对方面的那一份，否则侧栏列的是另一套页面、当前页高亮不到。
   */
  otherFace: boolean
}

/**
 * 当前路径落在哪个域、是否来自另一个面。没有 `domain` 元数据的页面（总览）返回 null。
 * 不看 nav 标记 —— 详情/编辑页也要能高亮它所属的域。
 */
export function resolveNavLocation(face: NavFace, path: string): NavLocation | null {
  const meta = router.resolve(path).matched.at(-1)?.meta
  if (!meta) return null
  const domain = domainOf(meta, face)
  if (!domain) return null
  const itemFace = faceOf(meta.requiresPerm)
  return { domain, otherFace: itemFace !== 'both' && itemFace !== face }
}

/**
 * 开发期一致性检查：菜单页必须有合法 `domain` 与 `icon`。返回问题清单，空数组 = 通过。
 * 生产构建里由调用方按 `import.meta.env.DEV` 跳过。
 */
export function navIntegrityProblems(): string[] {
  const problems: string[] = []
  for (const route of router.getRoutes()) {
    const meta = route.meta
    if (!meta.requiresPerm?.length) continue
    if (meta.domain && !VALID_DOMAINS.has(meta.domain)) {
      problems.push(`${route.path}: 未知域 "${meta.domain}"`)
    }
    if (meta.nav === 'hidden') continue
    if (!meta.domain) problems.push(`${route.path}: 菜单页缺 meta.domain`)
    if (!meta.icon) problems.push(`${route.path}: 菜单页缺 meta.icon`)
  }
  return problems
}
