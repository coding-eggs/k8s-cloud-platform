# 前端导航信息架构重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `platform-web` 的一层平铺菜单（42 项 / 12 组）改成「顶栏切域 + 侧栏单层」，菜单由路由元数据派生，并补齐命令面板、最近访问与两版总览。

**Architecture:** 在路由 `meta` 上补 `domain` / `icon` / `order` / `nav`，由新增的 `src/nav.ts` 把 `router.getRoutes()` 折叠成「域 → 菜单项」；`MainLayout` 的顶栏与侧栏只消费这份结果，模板从约 200 行降到约 20 行。总览页拆成平台版与租户版两个子组件，由 `perm.currentTenant` 分发。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript、vue-router 4、Element Plus 2（图标已在 `main.ts` 全局注册，故导航里用字符串图标名）、Vite。

**Spec:** `docs/superpowers/specs/2026-10-30-frontend-navigation-ia-design.md`

## Global Constraints

- **URL 零变更**：只改分组与入口，不改任何 `path`。
- **页面码唯一来源仍是 `src/pageCodes.ts`**：路由 `meta.requiresPerm` 只能引用它，禁止字面量，禁止复用 API 码。
- **不引入前端测试框架**：本仓 `platform-web` 目前零测试、`package.json` 无测试依赖。本计划的自动化门禁是 `npm run build`（`vue-tsc --build` + `vite build`），逻辑正确性由开发期断言 + 浏览器验收清单兜底。
- **`npm run build` 必须在 `platform-web/` 目录下执行。**
- 中文文案与现有页面保持一致（「命名空间」「工作负载」「中间件」等），不新造术语。
- 样式变量只用既有 CSS 变量：`--bg` `--bg-elevated` `--panel` `--border` `--text-1/2/3` `--accent` `--accent-grad` `--accent-soft` `--panel-hover`。
- 不改断点（1280px），不改侧栏折叠 / 窄屏 drawer 的既有行为。

## Review Focus

- **面不匹配的深链**：管理员在租户视图直接打开收藏的 `/clusters` 时，期望侧栏临时出现「集群」域并高亮，而不是空侧栏或跳回总览。
- **权限闭包为空**（`/user/me` 失败、或最小权限角色）：期望顶栏只剩能看的域，一个都没有时不渲染空 tab、不崩溃、不触发重定向循环。
- **域记忆指向已失去权限的页面**：期望回落到该域第一项，而不是跳到无权页再被守卫弹回总览。
- **localStorage 不可用**（隐私模式 / 配额满，`setItem` 抛异常）：期望菜单、总览、命令面板照常工作，只是不记忆、不显示最近访问。
- **详情/编辑页的面归属**：`/resources/pods/detail` 这类不进菜单的页面仍须能高亮「资源」域，否则侧栏在详情页会空掉。

---

### Task 1: 路由元数据 + `nav.ts` 数据源

**Files:**
- Create: `platform-web/src/nav.ts`
- Modify: `platform-web/src/router/index.ts`（整文件重写）
- Modify: `platform-web/src/main.ts:18-27`（加开发期断言）
- Modify: `platform-web/src/layouts/MainLayout.vue`（删掉 `pageGroup` computed 与 `.crumb*` 样式）

**Interfaces:**
- Consumes: `src/pageCodes.ts` 的 `pageCodes`；`vue-router` 的 `router.getRoutes()`。
- Produces:
  - `type NavFace = 'platform' | 'tenant'`
  - `type NavDomainKey = 'cluster'|'network'|'org'|'ops'|'app'|'build'|'resource'|'storage'|'observe'`
  - `interface NavItem { title: string; path: string; icon: string; codes: string[] }`
  - `interface NavDomain { key: NavDomainKey; label: string; items: NavItem[] }`
  - `interface PermissionFacade { has(code: string): boolean; hasAny(codes: string[]): boolean }`
  - `buildNav(face: NavFace, perm: PermissionFacade): NavDomain[]`
  - `resolveDomain(face: NavFace, path: string): NavDomainKey | null`
  - `labelOf(key: NavDomainKey): string`
  - `faceOf(codes: string[] | undefined): 'platform' | 'tenant' | 'both'`
  - `navIntegrityProblems(): string[]`
  - `RouteMeta` 新增 `domain?` `platformDomain?` `icon?` `nav?: 'hidden'` `order?`，删除 `group?`

- [ ] **Step 1: 新建 `platform-web/src/nav.ts`**

```ts
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
  | 'build'
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
  app: '应用',
  build: '交付',
  resource: '资源',
  storage: '存储',
  observe: '观测',
}

/** 两个视图各自的域顺序（租户视图 7 域、平台视图 4 域） */
const DOMAIN_LAYOUT: Record<NavFace, NavDomainKey[]> = {
  platform: ['cluster', 'network', 'org', 'ops'],
  tenant: ['app', 'build', 'resource', 'storage', 'network', 'observe', 'org'],
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

/** 当前路径落在哪个域；没有 `domain` 元数据的页面（总览）返回 null。不看 nav 标记 —— 详情页也要能高亮。 */
export function resolveDomain(face: NavFace, path: string): NavDomainKey | null {
  const meta = router.resolve(path).matched.at(-1)?.meta
  return meta ? (domainOf(meta, face) ?? null) : null
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
```

- [ ] **Step 2: 整文件重写 `platform-web/src/router/index.ts`**

保留原文件的守卫逻辑与注释，替换 `RouteMeta` 声明与 `children` 数组。完整内容：

```ts
import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/auth/oauth'
import { usePermission } from '@/stores/permission'
import MainLayout from '@/layouts/MainLayout.vue'
import { pageCodes } from '@/pageCodes'
import type { NavDomainKey } from '@/nav'

declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录页 */
    public?: boolean
    title?: string
    /** 顶栏上下文控件：'full' = 租户→集群→命名空间级联 */
    context?: string
    /** 进入该页所需的权限点 code，ANY-of（命中任一即放行）；缺则守卫跳总览、菜单隐藏。
     *
     *  ⚠️ 只放 **Page 域** 码（pageCodes.ts）。页面可见性与接口授权是两件事：这里的码决定"能不能进这个页面"，
     * 能不能调某个接口由后端权限表按 API 域码判定。历史上这里复用的是 API code，导致收一个 API code
     * 会连带页面消失、也无法单独把页面发出去 —— V2026_10_07_1 起改为 Page 域独立成码。
     *
     *  /overview 有意不设本字段：它是本守卫的回落目标，给它加码会形成重定向死循环。 */
    requiresPerm?: string[]
    /** 所属导航域（租户视图下的归属）。详情/编辑页也要填 —— 侧栏高亮要用。 */
    domain?: NavDomainKey
    /** 平台视图下改归哪个域。只有「两个视图都可见」的页面需要（Git 凭据、链路追踪）。 */
    platformDomain?: NavDomainKey
    /** 菜单图标（Element Plus 图标组件名；main.ts 全局注册，故此处用字符串）。 */
    icon?: string
    /** 'hidden' = 不进菜单（详情/编辑页）。缺省 = 进菜单。 */
    nav?: 'hidden'
    /** 域内排序，小在前；缺省 999。 */
    order?: number
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    { path: '/callback', name: 'callback', component: () => import('@/views/CallbackView.vue'), meta: { public: true } },
    {
      path: '/',
      component: MainLayout,
      redirect: '/overview',
      children: [
        // ============ 总览 / 个人中心 ============
        // 总览：无 requiresPerm（登录即可见）—— 路由守卫的回落目标，加码会死循环
        { path: 'overview', name: 'overview', component: () => import('@/views/OverviewView.vue'), meta: { title: '总览' } },
        // 入口在右上角头像下拉里，侧边菜单不露
        { path: 'profile', name: 'profile', component: () => import('@/views/ProfileView.vue'), meta: { title: '个人中心', nav: 'hidden' } },

        // ============ 平台视图 · 集群 ============
        { path: 'clusters', name: 'clusters', component: () => import('@/views/ClusterView.vue'), meta: { title: '集群管理', domain: 'cluster', icon: 'Monitor', order: 1, requiresPerm: [pageCodes.cluster] } },
        { path: 'clusters/detail', name: 'cluster-detail', component: () => import('@/views/ClusterDetailView.vue'), meta: { title: '集群概览', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.cluster] } },
        { path: 'nodes', name: 'nodes', component: () => import('@/views/NodeView.vue'), meta: { title: '节点管理', domain: 'cluster', icon: 'Cpu', order: 2, requiresPerm: [pageCodes.node.list] } },
        { path: 'nodes/detail', name: 'node-detail', component: () => import('@/views/NodeDetailView.vue'), meta: { title: '节点详情', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.node.detail] } },
        { path: 'namespaces', name: 'namespaces', component: () => import('@/views/NamespaceView.vue'), meta: { title: '命名空间管理', domain: 'cluster', icon: 'FolderOpened', order: 3, requiresPerm: [pageCodes.namespace.list] } },
        { path: 'namespaces/editor', name: 'namespace-editor', component: () => import('@/views/NamespaceEditorView.vue'), meta: { title: '命名空间编辑', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.namespace.edit] } },
        { path: 'namespaces/detail', name: 'namespace-detail', component: () => import('@/views/NamespaceDetailView.vue'), meta: { title: '命名空间概览', domain: 'cluster', nav: 'hidden', requiresPerm: [pageCodes.namespace.detail] } },
        { path: 'resources/storageclasses', name: 'storageclasses', component: () => import('@/views/resource/StorageClassView.vue'), meta: { title: '存储类', domain: 'cluster', icon: 'Files', order: 4, requiresPerm: [pageCodes.storageClass.list] } },

        // ============ 平台视图 · 网络 ============
        { path: 'mesh/gatewayclasses', name: 'gatewayclasses', component: () => import('@/views/mesh/GatewayClassView.vue'), meta: { title: 'GatewayClass', domain: 'network', icon: 'Guide', order: 1, requiresPerm: [pageCodes.mesh.gatewayClass.list] } },
        { path: 'mesh/gatewayclasses/editor', name: 'gatewayclass-editor', component: () => import('@/views/mesh/GatewayClassEditorView.vue'), meta: { title: 'GatewayClass 编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.mesh.gatewayClass.edit] } },
        { path: 'ops/ippools', name: 'ippools', component: () => import('@/views/ops/IppoolView.vue'), meta: { title: '地址池', domain: 'network', icon: 'Grid', order: 2, requiresPerm: [pageCodes.ops.ippool.list] } },
        { path: 'ops/ippools/detail', name: 'ippool-detail', component: () => import('@/views/ops/IppoolDetailView.vue'), meta: { title: '地址池详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.ippool.detail] } },
        { path: 'ops/ippools/editor', name: 'ippool-editor', component: () => import('@/views/ops/IppoolEditorView.vue'), meta: { title: '地址池编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.ippool.edit] } },
        { path: 'ops/ipreservations', name: 'ipreservations', component: () => import('@/views/ops/IpReservationView.vue'), meta: { title: '保留 IP', domain: 'network', icon: 'Lock', order: 3, requiresPerm: [pageCodes.ops.ipReservation.list] } },
        { path: 'ops/ipreservations/editor', name: 'ipreservation-editor', component: () => import('@/views/ops/IpReservationEditorView.vue'), meta: { title: '保留 IP 编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.ipReservation.edit] } },
        { path: 'ops/bgpconfigurations', name: 'bgpconfigurations', component: () => import('@/views/ops/BgpConfigurationView.vue'), meta: { title: 'BGP 配置', domain: 'network', icon: 'Connection', order: 4, requiresPerm: [pageCodes.ops.bgpConfiguration.list] } },
        { path: 'ops/bgpconfigurations/detail', name: 'bgpconfiguration-detail', component: () => import('@/views/ops/BgpConfigurationDetailView.vue'), meta: { title: 'BGP 配置详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpConfiguration.detail] } },
        { path: 'ops/bgpconfigurations/editor', name: 'bgpconfiguration-editor', component: () => import('@/views/ops/BgpConfigurationEditorView.vue'), meta: { title: 'BGP 配置编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpConfiguration.edit] } },
        { path: 'ops/bgppeers', name: 'bgppeers', component: () => import('@/views/ops/BgpPeerView.vue'), meta: { title: 'BGP 对等体', domain: 'network', icon: 'Share', order: 5, requiresPerm: [pageCodes.ops.bgpPeer.list] } },
        { path: 'ops/bgppeers/detail', name: 'bgppeer-detail', component: () => import('@/views/ops/BgpPeerDetailView.vue'), meta: { title: 'BGP 对等体详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpPeer.detail] } },
        { path: 'ops/bgppeers/editor', name: 'bgppeer-editor', component: () => import('@/views/ops/BgpPeerEditorView.vue'), meta: { title: 'BGP 对等体编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpPeer.edit] } },
        { path: 'ops/bgpfilters', name: 'bgpfilters', component: () => import('@/views/ops/BgpFilterView.vue'), meta: { title: 'BGP 过滤器', domain: 'network', icon: 'Filter', order: 6, requiresPerm: [pageCodes.ops.bgpFilter.list] } },
        { path: 'ops/bgpfilters/detail', name: 'bgpfilter-detail', component: () => import('@/views/ops/BgpFilterDetailView.vue'), meta: { title: 'BGP 过滤器详情', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpFilter.detail] } },
        { path: 'ops/bgpfilters/editor', name: 'bgpfilter-editor', component: () => import('@/views/ops/BgpFilterEditorView.vue'), meta: { title: 'BGP 过滤器编辑', domain: 'network', nav: 'hidden', requiresPerm: [pageCodes.ops.bgpFilter.edit] } },

        // ============ 平台视图 · 组织 ============
        { path: 'tenants', name: 'tenants', component: () => import('@/views/TenantView.vue'), meta: { title: '租户管理', domain: 'org', icon: 'OfficeBuilding', order: 1, requiresPerm: [pageCodes.tenant.list] } },
        { path: 'users', name: 'users', component: () => import('@/views/UserView.vue'), meta: { title: '用户管理', domain: 'org', icon: 'User', order: 2, requiresPerm: [pageCodes.user] } },
        { path: 'roles', name: 'roles', component: () => import('@/views/RoleView.vue'), meta: { title: '角色与权限', domain: 'org', icon: 'Lock', order: 3, requiresPerm: [pageCodes.role] } },
        { path: 'permissions', name: 'permissions', component: () => import('@/views/PermissionView.vue'), meta: { title: '权限点', domain: 'org', icon: 'Key', order: 4, requiresPerm: [pageCodes.permission] } },
        { path: 'templates', name: 'templates', component: () => import('@/views/TemplateView.vue'), meta: { title: 'RBAC 模板', domain: 'org', icon: 'CollectionTag', order: 5, requiresPerm: [pageCodes.template] } },

        // ============ 平台视图 · 运维（也是 both 面页面的平台落点） ============
        { path: 'audit', name: 'audit', component: () => import('@/views/AuditView.vue'), meta: { title: '审计日志', domain: 'ops', icon: 'Document', order: 1, requiresPerm: [pageCodes.audit] } },
        { path: 'git-credentials', name: 'git-credentials', component: () => import('@/views/GitCredentialView.vue'), meta: { title: 'Git 凭据', domain: 'build', platformDomain: 'ops', icon: 'Tickets', order: 2, requiresPerm: [pageCodes.credential, pageCodes.credentialTenant] } },
        { path: 'notification-channels', name: 'notification-channels', component: () => import('@/views/NotificationChannelView.vue'), meta: { title: '通知方式', domain: 'ops', icon: 'Message', order: 3, requiresPerm: [pageCodes.notifyChannel] } },
        { path: 'mesh/traces', name: 'mesh-traces', component: () => import('@/views/mesh/TraceView.vue'), meta: { title: '链路追踪', domain: 'observe', platformDomain: 'ops', icon: 'Timer', order: 6, context: 'full', requiresPerm: [pageCodes.mesh.trace, pageCodes.mesh.traceTenant] } },

        // ============ 租户视图 · 应用 ============
        { path: 'applications', name: 'applications', component: () => import('@/views/ApplicationListView.vue'), meta: { title: '应用编排', domain: 'app', icon: 'Connection', order: 1, context: 'full', requiresPerm: [pageCodes.application] } },
        { path: 'applications/:id', name: 'application-canvas', component: () => import('@/views/ApplicationCanvasView.vue'), meta: { title: '应用画布', domain: 'app', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.application] } },
        { path: 'apps', name: 'apps', component: () => import('@/views/MiddlewareStoreView.vue'), meta: { title: '中间件', domain: 'app', icon: 'Grid', order: 2, context: 'full', requiresPerm: [pageCodes.app] } },
        { path: 'apps/instances', name: 'middleware-instances', component: () => import('@/views/MiddlewareInstanceView.vue'), meta: { title: '中间件实例', domain: 'app', icon: 'Files', order: 3, context: 'full', requiresPerm: [pageCodes.app] } },
        { path: 'apps/:id', name: 'middleware-detail', component: () => import('@/views/MiddlewareDetailView.vue'), meta: { title: '中间件详情', domain: 'app', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.app] } },

        // ============ 租户视图 · 交付 ============
        { path: 'pipelines', name: 'pipelines', component: () => import('@/views/PipelineListView.vue'), meta: { title: '流水线', domain: 'build', icon: 'Operation', order: 1, context: 'full', requiresPerm: [pageCodes.pipeline] } },
        { path: 'pipelines/editor', name: 'pipeline-editor', component: () => import('@/views/PipelineEditorView.vue'), meta: { title: '流水线编辑', domain: 'build', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pipeline] } },
        { path: 'pipelines/runs', name: 'pipeline-runs', component: () => import('@/views/PipelineRunView.vue'), meta: { title: '流水线运行', domain: 'build', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pipeline] } },
        { path: 'pipelines/run', name: 'pipeline-run-detail', component: () => import('@/views/PipelineRunDetailView.vue'), meta: { title: '运行详情', domain: 'build', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pipeline] } },
        { path: 'registries', name: 'registries', component: () => import('@/views/RegistryView.vue'), meta: { title: '镜像仓库', domain: 'build', icon: 'Collection', order: 3, requiresPerm: [pageCodes.registry] } },

        // ============ 租户视图 · 资源 ============
        { path: 'resources/workloads', name: 'workloads', component: () => import('@/views/resource/WorkloadView.vue'), meta: { title: '工作负载', domain: 'resource', icon: 'Box', order: 1, context: 'full', requiresPerm: [pageCodes.workload.list] } },
        { path: 'resources/workloads/detail', name: 'workload-detail', component: () => import('@/views/resource/WorkloadDetailView.vue'), meta: { title: '工作负载详情', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.workload.detail] } },
        { path: 'resources/workloads/editor', name: 'workload-editor', component: () => import('@/views/resource/WorkloadEditorView.vue'), meta: { title: '工作负载编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.workload.edit] } },
        { path: 'resources/pods', name: 'pods', component: () => import('@/views/resource/PodView.vue'), meta: { title: 'Pod', domain: 'resource', icon: 'Cpu', order: 2, context: 'full', requiresPerm: [pageCodes.pod.list] } },
        { path: 'resources/pods/detail', name: 'pod-detail', component: () => import('@/views/resource/PodDetailView.vue'), meta: { title: 'Pod 详情', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pod.detail] } },
        { path: 'resources/pods/container', name: 'container-detail', component: () => import('@/views/resource/ContainerDetailView.vue'), meta: { title: '容器详情', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.pod.container] } },
        { path: 'resources/hpas', name: 'hpas', component: () => import('@/views/resource/HpaView.vue'), meta: { title: 'HPA', domain: 'resource', icon: 'TrendCharts', order: 3, context: 'full', requiresPerm: [pageCodes.hpa.list] } },
        { path: 'resources/hpas/editor', name: 'hpa-editor', component: () => import('@/views/resource/HpaEditorView.vue'), meta: { title: 'HPA 编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.hpa.edit] } },
        { path: 'resources/configmaps', name: 'configmaps', component: () => import('@/views/resource/ConfigMapView.vue'), meta: { title: 'ConfigMap', domain: 'resource', icon: 'Document', order: 4, context: 'full', requiresPerm: [pageCodes.configmap.list] } },
        { path: 'resources/configmaps/editor', name: 'configmap-editor', component: () => import('@/views/resource/ConfigMapEditorView.vue'), meta: { title: 'ConfigMap 编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.configmap.edit] } },
        { path: 'resources/secrets', name: 'secrets', component: () => import('@/views/resource/SecretView.vue'), meta: { title: 'Secret', domain: 'resource', icon: 'Key', order: 5, context: 'full', requiresPerm: [pageCodes.secret.list] } },
        { path: 'resources/secrets/editor', name: 'secret-editor', component: () => import('@/views/resource/SecretEditorView.vue'), meta: { title: 'Secret 编辑', domain: 'resource', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.secret.edit] } },

        // ============ 租户视图 · 存储 ============
        { path: 'resources/pvcs', name: 'pvcs', component: () => import('@/views/resource/PvcView.vue'), meta: { title: 'PVC', domain: 'storage', icon: 'Coin', order: 1, context: 'full', requiresPerm: [pageCodes.pvc.list] } },
        { path: 'resources/persistentvolumes', name: 'persistentvolumes', component: () => import('@/views/resource/PersistentVolumeView.vue'), meta: { title: '持久卷', domain: 'storage', icon: 'Box', order: 2, context: 'full', requiresPerm: [pageCodes.persistentVolume.list] } },
        { path: 'object-storage', name: 'object-storage', component: () => import('@/views/ObjectStorageView.vue'), meta: { title: '对象存储', domain: 'storage', icon: 'Cloudy', order: 3, requiresPerm: [pageCodes.objectStorage] } },

        // ============ 租户视图 · 网络 ============
        { path: 'resources/services', name: 'services', component: () => import('@/views/resource/ServiceView.vue'), meta: { title: 'Service', domain: 'network', icon: 'Link', order: 1, context: 'full', requiresPerm: [pageCodes.service.list] } },
        { path: 'resources/services/editor', name: 'service-editor', component: () => import('@/views/resource/ServiceEditorView.vue'), meta: { title: 'Service 编辑', domain: 'network', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.service.edit] } },
        { path: 'resources/gateways', name: 'gateways', component: () => import('@/views/resource/GatewayView.vue'), meta: { title: 'Gateway', domain: 'network', icon: 'Promotion', order: 2, context: 'full', requiresPerm: [pageCodes.mesh.gateway.list] } },
        { path: 'resources/gateways/editor', name: 'gateway-editor', component: () => import('@/views/resource/GatewayEditorView.vue'), meta: { title: 'Gateway 编辑', domain: 'network', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.mesh.gateway.edit] } },
        // 路由策略（Route 五合一）：一个页面管 5 种 route；requiresPerm 传 5 个码 = 任一命中即可进
        { path: 'routes', name: 'routes', component: () => import('@/views/resource/RouteListView.vue'), meta: { title: '路由策略', domain: 'network', icon: 'Share', order: 3, context: 'full', requiresPerm: [pageCodes.mesh.httpRoute.list, pageCodes.mesh.grpcRoute.list, pageCodes.mesh.tcpRoute.list, pageCodes.mesh.tlsRoute.list, pageCodes.mesh.udpRoute.list] } },
        { path: 'routes/editor', name: 'route-editor', component: () => import('@/views/resource/RouteEditorView.vue'), meta: { title: '路由策略编辑', domain: 'network', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.mesh.httpRoute.edit, pageCodes.mesh.grpcRoute.edit, pageCodes.mesh.tcpRoute.edit, pageCodes.mesh.tlsRoute.edit, pageCodes.mesh.udpRoute.edit] } },
        // 网格拓扑（S2.4）：与 Gateway 共用一个码 —— 能看 Gateway 的人就能看拓扑
        { path: 'mesh/topology', name: 'mesh-topology', component: () => import('@/views/mesh/MeshTopologyView.vue'), meta: { title: '网格拓扑', domain: 'network', icon: 'DataLine', order: 4, context: 'full', requiresPerm: [pageCodes.mesh.gateway.list] } },

        // ============ 租户视图 · 观测 ============
        { path: 'alerts', name: 'alerts', component: () => import('@/views/AlertView.vue'), meta: { title: '告警', domain: 'observe', icon: 'Bell', order: 1, context: 'full', requiresPerm: [pageCodes.alert] } },
        { path: 'resources/servicemonitors', name: 'servicemonitors', component: () => import('@/views/resource/ServiceMonitorView.vue'), meta: { title: 'ServiceMonitor', domain: 'observe', icon: 'DataLine', order: 2, context: 'full', requiresPerm: [pageCodes.serviceMonitor.list] } },
        { path: 'resources/servicemonitors/editor', name: 'servicemonitor-editor', component: () => import('@/views/resource/ServiceMonitorEditorView.vue'), meta: { title: 'ServiceMonitor 编辑', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.serviceMonitor.edit] } },
        { path: 'resources/podmonitors', name: 'podmonitors', component: () => import('@/views/resource/PodMonitorView.vue'), meta: { title: 'PodMonitor', domain: 'observe', icon: 'DataLine', order: 3, context: 'full', requiresPerm: [pageCodes.podMonitor.list] } },
        { path: 'resources/podmonitors/editor', name: 'podmonitor-editor', component: () => import('@/views/resource/PodMonitorEditorView.vue'), meta: { title: 'PodMonitor 编辑', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.podMonitor.edit] } },
        { path: 'resources/prometheusrules', name: 'prometheusrules', component: () => import('@/views/resource/PrometheusRuleView.vue'), meta: { title: 'PrometheusRule', domain: 'observe', icon: 'Tickets', order: 4, context: 'full', requiresPerm: [pageCodes.prometheusRule.list] } },
        // 详情用 list 码：能看列表就能看详情
        { path: 'resources/prometheusrules/detail', name: 'prometheusrule-detail', component: () => import('@/views/resource/PrometheusRuleDetailView.vue'), meta: { title: 'PrometheusRule 详情', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.prometheusRule.list] } },
        { path: 'resources/prometheusrules/editor', name: 'prometheusrule-editor', component: () => import('@/views/resource/PrometheusRuleEditorView.vue'), meta: { title: 'PrometheusRule 编辑', domain: 'observe', nav: 'hidden', context: 'full', requiresPerm: [pageCodes.prometheusRule.edit] } },
        { path: 'observability/logs', name: 'observability-logs', component: () => import('@/views/observability/LogsView.vue'), meta: { title: '日志查询', domain: 'observe', icon: 'Document', order: 5, context: 'full', requiresPerm: [pageCodes.observability.logs] } },

        // ============ 租户视图 · 组织 ============
        // 自管轨「我的租户」：租户 hat 内、无代管权的成员可达；tenantId 不进 path，取当前租户上下文
        { path: 'tenants/detail', name: 'tenant-detail', component: () => import('@/views/TenantDetailView.vue'), meta: { title: '我的租户', domain: 'org', icon: 'OfficeBuilding', order: 1, requiresPerm: [pageCodes.tenant.self] } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 未登录一律去 /login（/callback 由页面自行处理换票）；已登录按 meta.requiresPerm 过滤管理页
router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!getAccessToken()) return { name: 'login' }
  const perm = usePermission()
  // 首次进 shell：引导权限 + 租户自动进入（spec §4.5）。await 在页面 mount 之前完成，
  // 自动切换后各页 onMounted 首拉即用新 token；后续导航为 O(1) 空转。
  await perm.bootstrap()
  if (!perm.ready) await perm.load() // JWE/损坏 token：等 /user/me 兜底再判定
  const need = to.meta.requiresPerm
  if (need && !perm.hasAny(need)) {
    // 无权直达管理页 → 回落总览（菜单本已隐藏，此处防手输 URL / 权限回收后的旧页停留）。
    // to 本身就是 /overview 时放行，避免重定向死循环 —— 这也是 /overview 不设 requiresPerm 的原因。
    if (to.name === 'overview') return true
    return { name: 'overview' }
  }
  return true
})

export default router
```

- [ ] **Step 3: 删掉 `MainLayout.vue` 里对 `meta.group` 的引用**

在 `MainLayout.vue` 的 `<script setup>` 中删除这一段：

```ts
const pageTitle = computed(() => (route.meta.title as string) ?? '')
const pageGroup = computed(() => (route.meta.group as string) ?? '')
```

替换为：

```ts
// 顶栏不再显示面包屑：域 tab + 侧栏当前项已经回答了「我在哪」（spec §6.1）
```

在 `<template>` 中删除这段已被注释掉的面包屑块：

```html
<!--        <div class="crumb">-->
<!--          <span v-if="pageGroup" class="crumb-group">{{ pageGroup }}</span>-->
<!--          <span v-if="pageGroup" class="crumb-sep">/</span>-->
<!--          <span class="crumb-title">{{ pageTitle }}</span>-->
<!--        </div>-->
```

在 `<style scoped>` 中删除 `.crumb`、`.crumb-group`、`.crumb-sep`、`.crumb-title` 四条规则。

- [ ] **Step 4: 在 `main.ts` 加开发期一致性断言**

在 `app.mount('#app')` 之前插入：

```ts
// 开发期导航一致性检查：菜单页缺 domain/icon 或域 key 拼错时当场报出来。
// 生产构建里 import.meta.env.DEV 为 false，整段被 tree-shake 掉。
if (import.meta.env.DEV) {
  import('@/nav').then(({ navIntegrityProblems }) => {
    const problems = navIntegrityProblems()
    if (problems.length) console.error('[nav] 路由元数据不一致：\n' + problems.join('\n'))
  })
}
```

用动态 `import('@/nav')` 是为了不在生产包的首屏路径上拖入整张路由表。

- [ ] **Step 5: 构建验证**

```bash
cd platform-web && npm run build
```

预期：`vue-tsc` 与 `vite build` 均无错误通过。

若报 `Property 'group' does not exist on type 'RouteMeta'`，说明 Step 3 漏删；若报 `Unknown domain` 类错误，对照 §5 归属总表检查 `domain` 拼写。

- [ ] **Step 6: 提交**

```bash
cd /d/coding/k8s-cloud-platform
git add platform-web/src/nav.ts platform-web/src/router/index.ts platform-web/src/main.ts platform-web/src/layouts/MainLayout.vue
git commit -m "feat(web): 路由元数据承载导航归属，新增 nav.ts 数据源"
```

---

### Task 2: 顶栏域 tab + 侧栏数据化

**Files:**
- Modify: `platform-web/src/layouts/MainLayout.vue`（`<script setup>`、`<template>` 的 aside 与 header、`<style scoped>`）

**Interfaces:**
- Consumes: `buildNav`、`resolveDomain`、`NavDomain`、`NavDomainKey`、`NavFace`（Task 1）
- Produces: 无新导出；`MainLayout` 内部新增 `face` / `domains` / `activeDomain` / `activeItems` / `goDomain`

- [ ] **Step 1: 替换 `<script setup>` 的导航部分**

在 `MainLayout.vue` 的 `<script setup>` 顶部，把 import 区改为：

```ts
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAccessToken, getUserInfo, logout, startSessionMaintenance } from '@/auth/oauth'
import { getTheme, toggleTheme } from '@/utils/theme'
import ContextSelector from '@/components/ContextSelector.vue'
import TenantSwitcher from '@/components/TenantSwitcher.vue'
import { usePermission } from '@/stores/permission'
import { buildNav, resolveDomain, type NavDomain, type NavDomainKey, type NavFace } from '@/nav'
```

删除 `import { pageCodes } from '@/pageCodes'` 与整个 `can()` / `canAny()` 函数（菜单不再按码手写，改由 `buildNav` 统一过滤）。

在 `const perm = usePermission()` 之后插入：

```ts
// ---------- 导航：域 → 菜单项，全部来自 nav.ts ----------
const face = computed<NavFace>(() => (perm.currentTenant ? 'tenant' : 'platform'))

/**
 * 当前视图的域列表。
 * 当前页所属域若不在本视图的域集合里（管理员在租户视图直接打开收藏的平台页），
 * 把它的域补到末尾并高亮 —— 深链不该把人扔进一个空侧栏。
 */
const domains = computed<NavDomain[]>(() => {
  const list = buildNav(face.value, perm)
  const cur = resolveDomain(face.value, route.path)
  if (cur && !list.some((d) => d.key === cur)) {
    const other: NavFace = face.value === 'tenant' ? 'platform' : 'tenant'
    const extra = buildNav(other, perm).find((d) => d.key === cur)
    if (extra) list.push(extra)
  }
  return list
})

const activeDomain = computed<NavDomain | null>(() => {
  const key = resolveDomain(face.value, route.path)
  return key ? (domains.value.find((d) => d.key === key) ?? null) : null
})

const activeItems = computed(() => activeDomain.value?.items ?? [])

/** 域 tab：回到该域上次停留的页面；记录已失效（权限被收）则回落到第一项 */
function goDomain(key: NavDomainKey): void {
  const domain = domains.value.find((d) => d.key === key)
  if (!domain) return
  let stored: string | null = null
  try {
    stored = localStorage.getItem(`platform_nav_last:${face.value}:${key}`)
  } catch {
    /* localStorage 不可用（隐私模式）：忽略记忆，直接取第一项 */
  }
  const target = stored && domain.items.some((i) => i.path === stored) ? stored : domain.items[0]?.path
  if (target && target !== route.path) void router.push(target)
}

/** 记住每个域最后停留的菜单页。详情页不记：它通常依赖 query，回到裸路径是坏页。 */
watch(
  () => route.path,
  (path) => {
    if (route.meta.nav === 'hidden') return
    const key = resolveDomain(face.value, path)
    if (!key) return
    try {
      localStorage.setItem(`platform_nav_last:${face.value}:${key}`, path)
    } catch {
      /* 隐私模式 / 配额满：不记忆即可，不影响导航 */
    }
  },
  { immediate: true },
)
```

- [ ] **Step 2: 替换 `<template>` 的侧边栏菜单**

把 `<el-menu>…</el-menu>` 整块（从 `<el-menu :default-active=...>` 到 `</el-menu>`）替换为：

```html
<el-menu :default-active="route.path" router class="menu" :collapse="collapsed" @select="onMenuSelect">
  <el-menu-item index="/overview">
    <el-icon><Odometer /></el-icon>
    <template #title>总览</template>
  </el-menu-item>

  <div v-if="activeDomain" class="menu-domain">{{ activeDomain.label }}</div>
  <el-menu-item v-for="item in activeItems" :key="item.path" :index="item.path">
    <el-icon><component :is="item.icon" /></el-icon>
    <template #title>{{ item.title }}</template>
  </el-menu-item>
</el-menu>
```

- [ ] **Step 3: 在顶栏加域 tab**

把 `<header class="topbar">` 的开头（`.topbar-right` 之前）改为：

```html
<header class="topbar">
  <nav v-if="domains.length" class="domain-tabs">
    <button
      v-for="d in domains"
      :key="d.key"
      type="button"
      class="domain-tab"
      :class="{ 'is-active': activeDomain?.key === d.key }"
      @click="goDomain(d.key)"
    >
      {{ d.label }}
    </button>
  </nav>

  <ContextSelector v-if="showContextSelector" />
```

`.topbar-right` 那一块保持原样不动。

- [ ] **Step 4: 调整样式**

在 `<style scoped>` 中：

(a) 删除 `.menu-group` 与 `.sidebar.is-collapsed .menu-group` 两条规则，替换为：

```css
/* 侧栏当前域名（折叠态退化为一条分隔线） */
.menu-domain {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: .12em;
  padding: 14px 12px 6px;
  user-select: none;
}
.sidebar.is-collapsed .menu-domain {
  font-size: 0;
  letter-spacing: 0;
  padding: 8px 0 2px;
  margin: 4px 12px 0;
  border-top: 1px solid var(--border);
}
```

(b) 在 `.topbar` 规则后追加：

```css
/* ---------- 域 tab ---------- */
.domain-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1 1 auto;
  min-width: 0;
  overflow-x: auto;   /* 7 个域 + 360px 级联在 1280px 下放不下，横向滚动兜底 */
  scrollbar-width: none;
}
.domain-tabs::-webkit-scrollbar { display: none; }
.domain-tab {
  flex-shrink: 0;
  height: 64px;
  padding: 0 14px;
  border: none;
  background: transparent;
  color: var(--text-2);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  position: relative;
  transition: color .15s;
}
.domain-tab:hover { color: var(--text-1); }
.domain-tab.is-active { color: var(--accent); font-weight: 600; }
.domain-tab.is-active::after {
  content: '';
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 0;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--accent-grad);
}
```

(c) 把 `.topbar` 的 `padding: 0 24px;` 改为 `padding: 0 16px 0 6px;`（让第一个域 tab 贴近侧栏边界），`gap: 20px;` 改为 `gap: 16px;`。

- [ ] **Step 5: 构建验证**

```bash
cd platform-web && npm run build
```

预期：通过。若报 `property 'pageCodes' is not used` 之类的告警可忽略；若 `pageCodes` 仍有残留引用导致 `is declared but its value is never read`，删除该 import。

- [ ] **Step 6: 浏览器冒烟**

启动 dev server（需后端可登录）：

```bash
cd platform-web && npm run dev
```

逐项确认：

1. 管理员登录后落在 `/overview`：顶栏 4 个域 tab（集群 / 网络 / 组织 / 运维），侧栏只有「总览」一项。
2. 点「集群」→ 进入 `/clusters`，侧栏出现「集群管理 / 节点管理 / 命名空间管理 / 存储类」四项。
3. 点「网络」再点回「集群」→ 回到 `/clusters`（域记忆生效）。
4. 切到租户视图：顶栏变为 7 个域 tab（应用 / 交付 / 资源 / 存储 / 网络 / 观测 / 组织），无「集群」。
5. 在租户视图地址栏手输 `/clusters`：页面能进，侧栏临时多出「集群」域并高亮，不跳回总览。
6. 把窗口缩到 1280px 以下：域 tab 可横向滚动，上下文级联不被压缩变形。

- [ ] **Step 7: 提交**

```bash
cd /d/coding/k8s-cloud-platform
git add platform-web/src/layouts/MainLayout.vue
git commit -m "feat(web): 顶栏切域、侧栏按域渲染，菜单改由路由元数据驱动"
```

---

### Task 3: 最近访问

**Files:**
- Create: `platform-web/src/composables/useRecentVisits.ts`
- Create: `platform-web/src/components/RecentVisits.vue`
- Modify: `platform-web/src/main.ts`（注册路由钩子）

**Interfaces:**
- Consumes: `resolveDomain`、`labelOf`、`NavDomainKey`、`NavFace`（Task 1）；`usePermission`
- Produces:
  - `interface RecentVisit { path: string; title: string; domain: NavDomainKey; domainLabel: string; face: NavFace; ts: number }`
  - `readRecentVisits(): RecentVisit[]`
  - `clearRecentVisits(): void`
  - `installRecentVisitsTracking(router: Router): void`
  - `<RecentVisits :face="NavFace" :limit?="number" />` 组件

- [ ] **Step 1: 新建 `platform-web/src/composables/useRecentVisits.ts`**

```ts
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
```

- [ ] **Step 2: 新建 `platform-web/src/components/RecentVisits.vue`**

```vue
<script setup lang="ts">
/**
 * 最近访问列表（总览页与命令面板空态共用）。
 * 只读快照：每次挂载时读一次 localStorage —— 总览每次导航都会重新挂载，够用。
 */
import { computed } from 'vue'
import { readRecentVisits } from '@/composables/useRecentVisits'
import type { NavFace } from '@/nav'

const props = defineProps<{
  face: NavFace
  limit?: number
}>()

const visits = computed(() => readRecentVisits().filter((v) => v.face === props.face).slice(0, props.limit ?? 8))
</script>

<template>
  <div v-if="visits.length" class="recent">
    <router-link v-for="v in visits" :key="v.path" :to="v.path" class="recent-item">
      <span class="recent-title">{{ v.title }}</span>
      <span class="recent-domain">{{ v.domainLabel }}</span>
    </router-link>
  </div>
  <p v-else class="recent-empty">还没有访问记录 —— 用过几次后这里会出现你最近去过的页面。</p>
</template>

<style scoped>
.recent {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.recent-item {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  text-decoration: none;
  transition: all .15s;
}
.recent-item:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.recent-title {
  font-size: 13px;
  color: var(--text-1);
}
.recent-item:hover .recent-title {
  color: var(--accent);
}
.recent-domain {
  font-size: 11px;
  color: var(--text-3);
}
.recent-empty {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
```

- [ ] **Step 3: 在 `main.ts` 注册路由钩子**

在 `app.use(router)` 之后、`app.mount('#app')` 之前插入：

```ts
// 最近访问记录（localStorage，纯前端）。必须在 app.use(router) 之后安装，
// 否则首次导航的 afterEach 钩子还没挂上。
installRecentVisitsTracking(router)
```

并在文件顶部 import 区加：

```ts
import { installRecentVisitsTracking } from './composables/useRecentVisits'
```

- [ ] **Step 4: 构建验证**

```bash
cd platform-web && npm run build
```

预期：通过。

- [ ] **Step 5: 浏览器验证**

在 dev server 下依次访问 `/clusters`、`/nodes`、`/namespaces`，然后在控制台执行：

```js
JSON.parse(localStorage.getItem('platform_nav_recent'))
```

预期：数组长度为 3，`[0].title` 为「命名空间管理」。再访问 `/nodes/detail`（详情页），数组长度仍为 3（详情页不记录）。

- [ ] **Step 6: 提交**

```bash
cd /d/coding/k8s-cloud-platform
git add platform-web/src/composables/useRecentVisits.ts platform-web/src/components/RecentVisits.vue platform-web/src/main.ts
git commit -m "feat(web): 最近访问记录（localStorage）"
```

---

### Task 4: 命令面板（Ctrl/Cmd + K）

**Files:**
- Create: `platform-web/src/components/CommandPalette.vue`
- Modify: `platform-web/src/layouts/MainLayout.vue`（挂载面板、全局快捷键、侧栏搜索入口）

**Interfaces:**
- Consumes: `buildNav`（Task 1）、`readRecentVisits`（Task 3）、`usePermission`
- Produces: `<CommandPalette v-model="open" />` 组件

- [ ] **Step 1: 新建 `platform-web/src/components/CommandPalette.vue`**

```vue
<script setup lang="ts">
/**
 * 页面搜索面板（Ctrl / Cmd + K）。
 *
 * 纯前端：数据来自 nav.ts 的当前视图导航，不请求后端。
 * 只搜「当前视图可见」的页面 —— 与侧栏同源，因此不会搜到正在显示的菜单里没有的东西。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { buildNav, type NavDomainKey } from '@/nav'
import { usePermission } from '@/stores/permission'
import { readRecentVisits } from '@/composables/useRecentVisits'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const router = useRouter()
const perm = usePermission()

interface Entry {
  title: string
  path: string
  icon: string
  domain: NavDomainKey
  domainLabel: string
}

const keyword = ref('')
const cursor = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

const face = computed(() => (perm.currentTenant ? 'tenant' : 'platform'))

const entries = computed<Entry[]>(() =>
  buildNav(face.value, perm).flatMap((d) =>
    d.items.map((i) => ({ title: i.title, path: i.path, icon: i.icon, domain: d.key, domainLabel: d.label })),
  ),
)

const results = computed<Entry[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return []
  return entries.value
    .filter((e) => e.title.toLowerCase().includes(k) || e.domainLabel.includes(k))
    .slice(0, 12)
})

const recent = computed(() => readRecentVisits().filter((v) => v.face === face.value).slice(0, 5))

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    keyword.value = ''
    cursor.value = 0
    await nextTick()
    inputEl.value?.focus()
  },
)

watch(results, () => {
  cursor.value = 0
})

function close(): void {
  emit('update:modelValue', false)
}

function goPath(path: string): void {
  close()
  void router.push(path)
}

function choose(entry?: Entry): void {
  const target = entry ?? results.value[cursor.value]
  if (target) goPath(target.path)
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    close()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    cursor.value = Math.min(cursor.value + 1, Math.max(results.value.length - 1, 0))
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    cursor.value = Math.max(cursor.value - 1, 0)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    choose()
  }
}
</script>

<template>
  <div v-if="modelValue" class="cp-mask" @click.self="close">
    <div class="cp-panel" role="dialog" aria-label="页面搜索">
      <div class="cp-input-row">
        <el-icon class="cp-search-icon"><Search /></el-icon>
        <input
          ref="inputEl"
          v-model="keyword"
          class="cp-input"
          placeholder="搜索页面…"
          @keydown="onKeydown"
        />
        <kbd class="cp-kbd">Esc</kbd>
      </div>

      <div class="cp-list">
        <template v-if="keyword.trim()">
          <button
            v-for="(e, i) in results"
            :key="e.path"
            type="button"
            class="cp-item"
            :class="{ 'is-active': i === cursor }"
            @mousemove="cursor = i"
            @click="choose(e)"
          >
            <el-icon class="cp-item-icon"><component :is="e.icon" /></el-icon>
            <span class="cp-item-title">{{ e.title }}</span>
            <span class="cp-item-domain">{{ e.domainLabel }}</span>
          </button>
          <p v-if="!results.length" class="cp-empty">没有匹配的页面</p>
        </template>

        <template v-else>
          <p class="cp-group-title">最近访问</p>
          <button
            v-for="v in recent"
            :key="v.path"
            type="button"
            class="cp-item"
            @click="goPath(v.path)"
          >
            <el-icon class="cp-item-icon"><Clock /></el-icon>
            <span class="cp-item-title">{{ v.title }}</span>
            <span class="cp-item-domain">{{ v.domainLabel }}</span>
          </button>
          <p v-if="!recent.length" class="cp-empty">输入页面名开始搜索</p>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cp-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(2, 6, 17, .55);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 14vh;
}
.cp-panel {
  width: 560px;
  max-width: 92vw;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 14px;
  box-shadow: 0 24px 64px -12px rgba(0, 0, 0, .6);
  overflow: hidden;
}
.cp-input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
}
.cp-search-icon {
  font-size: 16px;
  color: var(--text-3);
}
.cp-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-1);
  font: inherit;
  font-size: 15px;
}
.cp-input::placeholder {
  color: var(--text-3);
}
.cp-kbd {
  font-size: 11px;
  color: var(--text-3);
  border: 1px solid var(--border);
  border-radius: 5px;
  padding: 1px 6px;
}
.cp-list {
  max-height: 46vh;
  overflow-y: auto;
  padding: 6px;
}
.cp-group-title {
  margin: 6px 10px 4px;
  font-size: 11px;
  letter-spacing: .1em;
  color: var(--text-3);
}
.cp-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-1);
  font: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
}
.cp-item.is-active {
  background: var(--accent-soft);
  color: var(--accent);
}
.cp-item-icon {
  font-size: 15px;
  color: var(--text-3);
}
.cp-item.is-active .cp-item-icon {
  color: var(--accent);
}
.cp-item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cp-item-domain {
  font-size: 11px;
  color: var(--text-3);
}
.cp-empty {
  margin: 16px 12px;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
```

- [ ] **Step 2: 在 `MainLayout.vue` 挂载面板并加全局快捷键**

import 区加：

```ts
import CommandPalette from '@/components/CommandPalette.vue'
```

在 `const activeItems = computed(...)` 之后插入：

```ts
// ---------- 命令面板 ----------
const paletteOpen = ref(false)

function onGlobalKeydown(e: KeyboardEvent): void {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = !paletteOpen.value
  }
}
```

在已有的 `onMounted`（那个启动 `startSessionMaintenance` 的）里追加一行：

```ts
window.addEventListener('keydown', onGlobalKeydown)
```

在已有的 `onBeforeUnmount` 里追加一行：

```ts
window.removeEventListener('keydown', onGlobalKeydown)
```

> 注意：`onMounted` / `onBeforeUnmount` 已存在，不要重复定义钩子，只在函数体内追加。

在 `<template>` 的 `</el-container>`（最外层那个）之前插入：

```html
<CommandPalette v-model="paletteOpen" />
```

- [ ] **Step 3: 侧栏加搜索入口**

在 `<el-menu>…</el-menu>` 之后、`</aside>` 之前插入：

```html
<button type="button" class="menu-search" :title="collapsed ? '搜索页面（Ctrl K）' : undefined" @click="paletteOpen = true">
  <el-icon><Search /></el-icon>
  <span v-if="!collapsed" class="menu-search-text">搜索页面</span>
  <kbd v-if="!collapsed" class="menu-search-kbd">Ctrl K</kbd>
</button>
```

样式追加到 `<style scoped>`：

```css
/* 侧栏搜索入口（折叠态只留图标） */
.menu-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  color: var(--text-2);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all .15s;
}
.menu-search:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.sidebar.is-collapsed .menu-search {
  justify-content: center;
  padding: 9px 0;
  margin: 8px 6px;
}
.menu-search-text {
  flex: 1;
  text-align: left;
}
.menu-search-kbd {
  font-size: 10.5px;
  color: var(--text-3);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
}
```

- [ ] **Step 4: 构建验证**

```bash
cd platform-web && npm run build
```

预期：通过。

- [ ] **Step 5: 浏览器验证**

1. 任意页面按 Ctrl+K（macOS 用 Cmd+K）：面板打开，输入框自动聚焦。
2. 输入「日志」：命中「日志查询」，右侧标注「观测」。
3. 按 ↓ 再按 Enter：跳到 `/observability/logs`，面板关闭。
4. 清空输入：显示「最近访问」最多 5 条；没有记录时显示「输入页面名开始搜索」。
5. Esc 关闭；点遮罩关闭。
6. 在平台视图按 Ctrl+K 输入「集群」：不出现「日志查询」（租户资源页不在当前视图）。

- [ ] **Step 6: 提交**

```bash
cd /d/coding/k8s-cloud-platform
git add platform-web/src/components/CommandPalette.vue platform-web/src/layouts/MainLayout.vue
git commit -m "feat(web): Ctrl/Cmd+K 页面搜索面板"
```

---

### Task 5: 总览页拆分 —— 平台视图版

**Files:**
- Rewrite: `platform-web/src/views/OverviewView.vue`（变成分发壳）
- Create: `platform-web/src/views/overview/PlatformOverview.vue`

**Interfaces:**
- Consumes: `clusterApi.list`、`tenantApi.list`、`tenantApi.namespaceList`、`apiCodes`、`pageCodes`、`usePermission`、`RecentVisits`（Task 3）
- Produces: `<PlatformOverview />`

- [ ] **Step 1: 把 `OverviewView.vue` 改成分发壳**

```vue
<script setup lang="ts">
/**
 * 总览：按当前视图分发到两个互不复用的实现。
 * 平台视图 = 平台管理员的健康与入口；租户视图 = 租户成员的工作台。
 * 视图判定口径与 MainLayout / nav.ts 完全一致（`perm.currentTenant`）。
 */
import { computed } from 'vue'
import { usePermission } from '@/stores/permission'
import PlatformOverview from '@/views/overview/PlatformOverview.vue'
import TenantOverview from '@/views/overview/TenantOverview.vue'

const perm = usePermission()
const isPlatformView = computed(() => perm.currentTenant == null)
</script>

<template>
  <PlatformOverview v-if="isPlatformView" />
  <TenantOverview v-else />
</template>
```

> `TenantOverview.vue` 在 Task 6 创建。本步结束后构建会因缺少该文件失败 —— 先创建一个最小占位，Task 6 再补全：

`platform-web/src/views/overview/TenantOverview.vue`（占位，Task 6 会整文件替换）：

```vue
<template>
  <div />
</template>
```

- [ ] **Step 2: 新建 `platform-web/src/views/overview/PlatformOverview.vue`**

```vue
<script setup lang="ts">
/**
 * 平台视图总览：平台管理员的健康摘要 + 快捷操作 + 最近访问。
 *
 * 首屏只打 `/cluster/list` 一次。**不逐集群调 `/cluster/overview`** ——
 * 那是 9 次 list 的聚合，N 个集群就是 N 次重活，健康明细留给集群详情页。
 */
import { computed, onMounted, ref } from 'vue'
import { clusterApi, tenantApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import RecentVisits from '@/components/RecentVisits.vue'
import type { K8sCluster, NamespaceAllocation, PlatformTenant } from '@/types'

const perm = usePermission()

const loading = ref(false)
const clusters = ref<K8sCluster[]>([])
const tenants = ref<PlatformTenant[]>([])
const allocations = ref<NamespaceAllocation[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    // 三块统计各对应一个管理端点：无权限点则跳过，避免 403 连弹。
    // 门控用的是 **API 域** 码（拉数据的能力），不是页面码。
    ;[clusters.value, tenants.value, allocations.value] = await Promise.all([
      perm.has(apiCodes.clusterManage) ? clusterApi.list() : Promise.resolve([] as K8sCluster[]),
      perm.has(apiCodes.tenantRead) ? tenantApi.list() : Promise.resolve([] as PlatformTenant[]),
      perm.has(apiCodes.allocationList) ? tenantApi.namespaceList() : Promise.resolve([] as NamespaceAllocation[]),
    ])
  } finally {
    loading.value = false
  }
}

const enabledClusters = computed(() => clusters.value.filter((c) => c.enabled === 1).length)
const enabledTenants = computed(() => tenants.value.filter((t) => t.status === 1).length)

const stats = computed(() => [
  { label: '集群', value: clusters.value.length, sub: `启用 ${enabledClusters.value}` },
  { label: '租户', value: tenants.value.length, sub: `启用 ${enabledTenants.value}` },
  { label: '命名空间分配', value: allocations.value.length, sub: '跨集群总计' },
])

function statusTag(status?: K8sCluster['status']): { type: 'success' | 'danger' | 'info' | 'warning'; text: string } {
  switch (status) {
    case 'CONNECTED': return { type: 'success', text: '已连接' }
    case 'ERROR': return { type: 'danger', text: '错误' }
    case 'CONNECTING': return { type: 'warning', text: '连接中' }
    default: return { type: 'info', text: status ?? '未知' }
  }
}

const quickActions = computed(() =>
  [
    { title: '新增集群', path: '/clusters', icon: 'Monitor', perm: pageCodes.cluster },
    { title: '分配命名空间', path: '/namespaces', icon: 'FolderOpened', perm: pageCodes.namespace.list },
    { title: '新建租户', path: '/tenants', icon: 'OfficeBuilding', perm: pageCodes.tenant.list },
    { title: '查审计', path: '/audit', icon: 'Document', perm: pageCodes.audit },
  ].filter((a) => perm.has(a.perm)),
)

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">平台总览</h2>
        <p class="ph-desc">集群健康、平台对象与最近去过的地方</p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <!-- 统计 -->
    <div class="stat-grid">
      <div v-for="s in stats" :key="s.label" class="panel stat-card">
        <div class="stat-value">{{ s.value }}</div>
        <div class="stat-label">{{ s.label }}<span class="stat-sub">{{ s.sub }}</span></div>
      </div>
    </div>

    <!-- 集群状态 -->
    <h3 class="section-title">集群状态</h3>
    <el-table :data="clusters" class="panel" empty-text="还没有接入集群">
      <el-table-column prop="clusterName" label="名称" min-width="180" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status).type" size="small" effect="plain">{{ statusTag(row.status).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="120">
        <template #default="{ row }">{{ row.version || '—' }}</template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">{{ row.enabled === 1 ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push({ name: 'cluster-detail', query: { clusterId: row.clusterId } })">
            概览
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 快捷操作 -->
    <h3 class="section-title">快捷操作</h3>
    <div class="shortcut-grid">
      <router-link v-for="a in quickActions" :key="a.path" :to="a.path" class="panel shortcut-card">
        <el-icon class="sc-icon"><component :is="a.icon" /></el-icon>
        <span class="sc-title">{{ a.title }}</span>
      </router-link>
    </div>

    <!-- 最近访问 -->
    <h3 class="section-title">最近访问</h3>
    <RecentVisits face="platform" />
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
.stat-card {
  padding: 18px;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-1);
  line-height: 1.1;
}
.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-2);
}
.stat-sub {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3);
}
.section-title {
  margin: 26px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-2);
}
.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.shortcut-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  text-decoration: none;
  transition: all .15s;
}
.shortcut-card:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px color-mix(in srgb, var(--accent) 18%, transparent);
}
.sc-icon {
  font-size: 20px;
  color: var(--accent);
  flex-shrink: 0;
}
.sc-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
@media (max-width: 1200px) {
  .shortcut-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
```

> `cluster-detail` 路由读取的是 `route.query.clusterId`（见 `ClusterDetailView.vue:39`），上面的按钮参数名与之对应。

- [ ] **Step 3: 构建验证**

```bash
cd platform-web && npm run build
```

预期：通过（`TenantOverview.vue` 此时是空壳，类型上没问题）。

- [ ] **Step 4: 浏览器验证**

管理员登录、确保处于平台视图，打开 `/overview`：三张统计卡有值；集群状态表列出集群与状态标签；快捷操作只出现其有权限的项；最近访问显示此前访问过的页面。

- [ ] **Step 5: 提交**

```bash
cd /d/coding/k8s-cloud-platform
git add platform-web/src/views/OverviewView.vue platform-web/src/views/overview/PlatformOverview.vue platform-web/src/views/overview/TenantOverview.vue
git commit -m "feat(web): 总览拆成两版，平台视图版落地"
```

---

### Task 6: 总览页 —— 租户视图版

**Files:**
- Rewrite: `platform-web/src/views/overview/TenantOverview.vue`

**Interfaces:**
- Consumes: `tenantApi.overview`、`usePermission`、`RecentVisits`（Task 3）、`formatMetricValue`（`@/utils/metrics`）
- Produces: `<TenantOverview />`

- [ ] **Step 1: 整文件写入 `TenantOverview.vue`**

```vue
<script setup lang="ts">
/**
 * 租户视图总览：当前租户的工作台。
 *
 * 首屏只打 `/tenant/overview` 一次 —— 后端一次返回合计、逐集群小计、
 * 异常 Pod 名单、不健康工作负载名单与配额三档，够回答「现在有什么需要我管」。
 *
 * 单位口径沿用 TenantOverviewPanel：CPU=核、内存=字节，格式化走 formatMetricValue。
 * null 一律显示「—」，不显示 0（B4 口径）。
 */
import { computed, onMounted, ref } from 'vue'
import { tenantApi } from '@/api'
import { pageCodes } from '@/pageCodes'
import { usePermission } from '@/stores/permission'
import RecentVisits from '@/components/RecentVisits.vue'
import { formatMetricValue } from '@/utils/metrics'
import type { K8sTenantOverview } from '@/types'

const perm = usePermission()

const loading = ref(false)
const loadError = ref(false)
const overview = ref<K8sTenantOverview | null>(null)

const tenantId = computed(() => perm.currentTenant?.tenantId ?? null)
const tenantName = computed(() => perm.currentTenant?.tenantName ?? perm.currentTenant?.tenantId ?? '当前租户')

async function load(): Promise<void> {
  const target = tenantId.value
  if (!target) return
  loading.value = true
  loadError.value = false
  try {
    const data = await tenantApi.overview({ tenantId: target })
    if (target !== tenantId.value) return // 期间切了租户：丢弃这次结果
    overview.value = data
  } catch {
    if (target === tenantId.value) {
      overview.value = null
      loadError.value = true
    }
  } finally {
    if (target === tenantId.value) loading.value = false
  }
}

function fmtCpu(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '核', false)
}
function fmtMem(v?: number | null): string {
  return v == null ? '—' : formatMetricValue(v, '字节', false)
}

const cards = computed(() => {
  const o = overview.value
  return [
    { label: 'CPU 配额占用', value: `${fmtCpu(o?.quotaCpuUsed)} / ${fmtCpu(o?.quotaCpuHard)}` },
    { label: '内存配额占用', value: `${fmtMem(o?.quotaMemUsed)} / ${fmtMem(o?.quotaMemHard)}` },
    { label: '异常 Pod', value: o ? String(o.abnormalPodTotal) : '—' },
    { label: '不健康工作负载', value: o ? String(o.unhealthyWorkloadTotal) : '—' },
  ]
})

/** 异常 reason 取前 3 个（后端已按严重度降序） */
const podReasons = computed(() => {
  const counts = overview.value?.abnormalPodReasonCounts ?? {}
  return Object.entries(counts)
    .slice(0, 3)
    .map(([reason, count]) => `${reason || '未知'} ×${count}`)
})

const unhealthy = computed(() =>
  (overview.value?.unhealthyWorkloads ?? [])
    .slice(0, 3)
    .map((w) => `${w.name ?? '—'}（${w.kind ?? '—'}，就绪 ${w.readyReplicas}/${w.replicas}）`),
)

const quotaGaps = computed(() => {
  const missing = overview.value?.namespacesWithoutQuota ?? []
  const partial = overview.value?.namespacesPartialQuota ?? []
  const parts: string[] = []
  if (missing.length) parts.push(`未配配额：${missing.slice(0, 3).join('、')}${missing.length > 3 ? ` 等 ${missing.length} 个` : ''}`)
  if (partial.length) parts.push(`配额不完整：${partial.slice(0, 3).join('、')}${partial.length > 3 ? ` 等 ${partial.length} 个` : ''}`)
  return parts
})

const quickActions = computed(() =>
  [
    { title: '新建应用', path: '/applications', icon: 'Connection', perm: pageCodes.application },
    { title: '新建中间件', path: '/apps', icon: 'Grid', perm: pageCodes.app },
    { title: '跑流水线', path: '/pipelines', icon: 'Operation', perm: pageCodes.pipeline },
    { title: '看日志', path: '/observability/logs', icon: 'Document', perm: pageCodes.observability.logs },
    { title: '查链路', path: '/mesh/traces', icon: 'Timer', perm: pageCodes.mesh.traceTenant },
  ].filter((a) => perm.has(a.perm)),
)

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h2 class="ph-title">{{ tenantName }} 工作台</h2>
        <p class="ph-desc">
          <template v-if="perm.isAdmin">当前代管 {{ tenantName }} —— 这里的数字只统计该租户</template>
          <template v-else>你的资源概况、需要关注的问题与常用入口</template>
        </p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="warning"
      :closable="false"
      title="概览数据暂时取不到"
      description="可能是集群不可用或聚合未就绪。其余页面不受影响，可稍后重试。"
    />

    <div class="stat-grid">
      <div v-for="c in cards" :key="c.label" class="panel stat-card">
        <div class="stat-value">{{ c.value }}</div>
        <div class="stat-label">{{ c.label }}</div>
      </div>
    </div>

    <h3 class="section-title">需要关注</h3>
    <div class="panel watch-panel">
      <template v-if="podReasons.length || unhealthy.length || quotaGaps.length">
        <div v-if="podReasons.length" class="watch-row">
          <span class="watch-label">异常 Pod</span>
          <span class="watch-value">{{ podReasons.join(' · ') }}</span>
        </div>
        <div v-if="unhealthy.length" class="watch-row">
          <span class="watch-label">不健康工作负载</span>
          <span class="watch-value">{{ unhealthy.join(' · ') }}</span>
        </div>
        <div v-if="quotaGaps.length" class="watch-row">
          <span class="watch-label">配额缺口</span>
          <span class="watch-value">{{ quotaGaps.join(' · ') }}</span>
        </div>
      </template>
      <p v-else-if="overview" class="watch-ok">一切正常 —— 没有异常 Pod、不健康工作负载或配额缺口。</p>
      <p v-else-if="!loading" class="watch-ok">暂无数据。</p>
    </div>

    <h3 class="section-title">常用操作</h3>
    <div class="shortcut-grid">
      <router-link v-for="a in quickActions" :key="a.path" :to="a.path" class="panel shortcut-card">
        <el-icon class="sc-icon"><component :is="a.icon" /></el-icon>
        <span class="sc-title">{{ a.title }}</span>
      </router-link>
    </div>

    <h3 class="section-title">最近访问</h3>
    <RecentVisits face="tenant" />
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.ph-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.load-error {
  margin-bottom: 14px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.stat-card {
  padding: 18px;
}
.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-1);
  line-height: 1.2;
}
.stat-label {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-3);
}
.section-title {
  margin: 26px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-2);
}
.watch-panel {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.watch-row {
  display: flex;
  gap: 14px;
  font-size: 13px;
}
.watch-label {
  width: 96px;
  flex-shrink: 0;
  color: var(--text-3);
}
.watch-value {
  color: var(--text-1);
  line-height: 1.6;
}
.watch-ok {
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
}
.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
}
.shortcut-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  text-decoration: none;
  transition: all .15s;
}
.shortcut-card:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px color-mix(in srgb, var(--accent) 18%, transparent);
}
.sc-icon {
  font-size: 20px;
  color: var(--accent);
  flex-shrink: 0;
}
.sc-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
@media (max-width: 1200px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .shortcut-grid { grid-template-columns: repeat(3, 1fr); }
}
</style>
```

- [ ] **Step 2: 构建验证**

```bash
cd platform-web && npm run build
```

预期：通过。若 `formatMetricValue` 的签名与这里不一致（第二个参数不是单位字符串），打开 `src/utils/metrics.ts` 对照调整，不要改 `formatMetricValue` 本身。

- [ ] **Step 3: 浏览器验证（租户视图）**

切到任一个租户视图，打开 `/overview`：

1. 四张卡有值（CPU/内存配额占用、异常 Pod、不健康工作负载）。
2. 若该租户有异常 Pod，「需要关注 → 异常 Pod」列出 reason 与计数。
3. 若无任何问题，显示「一切正常」。
4. 常用操作按权限出现（缺 `tenant:page:pipeline` 时不应出现「跑流水线」）。
5. 管理员视角下页头显示「当前代管 <租户名>」。

- [ ] **Step 4: 提交**

```bash
cd /d/coding/k8s-cloud-platform
git add platform-web/src/views/overview/TenantOverview.vue
git commit -m "feat(web): 租户视图总览落地（用量/异常/配额/常用操作）"
```

---

### Task 7: 全量验收

**Files:** 无新增；只跑验证，必要时修。

- [ ] **Step 1: 构建**

```bash
cd platform-web && npm run build
```

预期：`vue-tsc` 与 `vite build` 全部通过，无 error。

- [ ] **Step 2: 逐条走 spec §10 验收**

启动 `npm run dev`（需后端可登录），按下表确认，任一不通过就回到对应 Task 修：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 管理员平台视图 | 顶栏 4 个域 tab（集群/网络/组织/运维）；每域侧栏项与 spec §4.1 一致；总览有集群状态表 |
| 2 | 管理员租户视图 | 顶栏 7 个域 tab；无「集群」域；总览四张卡有值 |
| 3 | 租户视图手输 `/clusters` | 能进页面；侧栏临时出现「集群」域并高亮；不跳回总览 |
| 4 | Ctrl/Cmd+K 输入「日志」 | 跨域命中「日志查询」；Enter 跳转；Esc 关闭 |
| 5 | 连访三页后回总览 | 三者在最近访问；访问详情页不进入列表 |
| 6 | 域记忆 | 「资源」域进 Pod 列表 → 切「存储」→ 切回「资源」，落回 Pod 列表 |
| 7 | 最小权限角色 | 只有 `tenant:page:workload.list` 时顶栏只剩「资源」，其下只有「工作负载」 |
| 8 | 开发期断言 | 临时给 `/clusters` 删掉 `meta.domain`，dev 控制台应报「菜单页缺 meta.domain」；确认后还原 |
| 9 | localStorage 不可用 | 浏览器隐私模式（或控制台 `Object.defineProperty(window,'localStorage',{get(){throw new Error()}})`）下菜单与总览照常，不白屏 |

- [ ] **Step 3: 清理**

删除任何临时改动（Step 2 第 8 条里删掉的 `meta.domain`），确认 `git status` 干净。

- [ ] **Step 4: 提交（若有修复）**

```bash
cd /d/coding/k8s-cloud-platform
git add -A platform-web
git commit -m "fix(web): 导航重构验收问题修复"
```

若 Step 2 全过、Step 3 无改动，跳过本步。

---

## 附：不做的事（避免执行时自作主张）

- 不改任何 URL、不改 `pageCodes.ts`、不动后端。
- 不合并「中间件」与「中间件实例」，不合并 SM/PM/PrometheusRule。
- 不引入 vitest / jest。
- 不做菜单收藏、自定义排序、面包屑。
- 不在总览里逐集群调 `/cluster/overview`。
- 不修改 `ContextSelector.vue` 的 `flex-shrink: 0`。
