# 路由策略（Route 五合一）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute 五种 route 收敛成一个前端功能「路由策略」：一个列表页（类型列 + 类型筛选）+ 一个编辑器（外壳 + 5 个 kind 子组件），菜单 5 项并为 1 项，旧的 10 条路径与 10 个旧视图文件全部删除。

**Architecture:** 用仓库已有的 `WorkloadView` / `WorkloadEditorView`（一个页面管 3 种 kind）作形态先例。列表页按"用户有权限的 kind"逐个拉、`Promise.allSettled` 隔离失败；编辑器外壳只持有真正同形的部分（类型选择器 / 路由名 / 父资源块 / hostnames / 校验编排），5 种 kind 的表单体**整体搬迁**进 `components/route/*RouteForm.vue`，靠 `v-if` 分支切换（切 kind 即销毁重建，专属字段自然清空）。**后端零改动**。

**Tech Stack:** Vue 3 + TypeScript + Element Plus + Vite（`vue-tsc` 类型检查）；无前端单测框架，功能面靠浏览器实测（用同源非 `/login` 宿主页，见 Global Constraints）。

**Spec:** `docs/superpowers/specs/2026-10-14-route-policy-design.md`（实施前先读；本计划每个任务都对应它的某一节）

## Global Constraints

- **无网络**：一律 `npm --prefix platform-web run build`（= vue-tsc + vite build）。不要装依赖。
- **中文**：注释、FieldHelp、提示文案、commit message 全部中文（技术名词保留英文）。
- **后端零改动**：不碰 `platform-api` / `k8s-server` / `k8s-core` / `platform-common` / SQL 迁移 / 权限行。
- **页面码与能力码分工**（`docs/development/frontend-permission-conventions.md` §1）：页面可见性（`requiresPerm`、菜单 `v-if`）用 `pageCodes`；能力短路（发不发请求、按钮灰不灰）用 `apiCodes`。**不要混用**。
- **`silent403` 不得用于主数据**：`api/http.ts` 的注释明确写了"主数据（列表/详情）一律不允许"。本批的 list 调用**不带** `silent403`。
- **同类资源不抽公共基类**：5 个 kind 子组件各写各的（搬迁各自旧代码），只共享真正同形的外壳与静态目录；不要抽 mixin / 基类组件。
- **切 kind 必须清掉旧 kind 的字段**：靠 `v-if` 分支切换销毁重建（不要改成 `v-show`，也不要手写"清字段"的 watcher）。
- **浏览器实测的宿主页**：dev server 的 `/login` 会在挂载后自动跳 OAuth（`LoginView` onMounted → `login()`），导致 `preview_eval` 目标频繁"navigated or closed"。**先建一个同源空白宿主页**（如 `platform-web/__scratch.html`，内容仅 `<!doctype html><html><body><div id="scratch-root"></div></body></html>`），把 preview 导航到 `/__scratch.html` 再直挂 SFC；**收尾时删除该文件**（不得提交）。Element Plus 的下拉是 teleport 到 body 的 `.el-popper.el-select__popper`：断言前先清尽残留 popper，再取**可见的那个**。
- 子组件的 Vite deps 版本查询：直挂 SFC 时用 `import('/node_modules/.vite/deps/vue.js?v=<hash>')`，hash 从 `fetch('/src/views/resource/<某个SFC>.vue')` 的转换产物里取（否则 `useRoute()` 注入 key 不同、返回 undefined）。

## Review Focus

spec 没写、但最可能咬到用户的六类输入/条件，逐条钉到任务上：

1. **只装了部分 CRD 的集群**（如标准通道没有 L4 三类）：缺失的 kind 必须不拖累其它 kind（其余照常显示），且**不反复报错**。→ Task 4 `unavailable` 会话内不重试 + Task 5 断言 3。
2. **只拥有部分 kind 权限的自定义角色**：无 list 权限的 kind 不出现、**不发请求**；无 create 权限的 kind 不出现在创建下拉；无 update 权限时保存置灰。→ Task 1 码表 + Task 4 `permitted`/`creatable` + Task 2 保存按钮 + Task 5 断言 2。
3. **非法/缺失的 `?kind=`**（手输 URL、旧书签）：列表回落「全部」；编辑器回落 `httproute`，且**只有合法参数才锁定**类型选择器。→ Task 4 `parseRouteKind` + Task 2 `kindLocked` + Task 5 断言 6。
4. **切 kind 后旧 kind 的字段残留**（HTTP 的 matches/filters 混进 TCP 的 body）：必须清空。→ Task 2/3 的 `v-if` 分支 + Task 5 断言 4。
5. **编辑既有对象时的"未建模字段透传"**（filter 里的 CORS/兄弟键、parentRefs 的 group/port）：搬迁时**一条都不能丢**。→ Task 2/3 的搬迁清单 + Task 5 断言 5。
6. **父引用块的四行联动**（上一批刚做完的 kind/命名空间/名称/段名 + 只读提示 + 段名软校验）：搬迁到外壳时必须逐字保留（含 `@change` 而不是 `watch` 清值这条警戒）。→ Task 2 Step 4 + Task 5 断言 7。

---

## 文件结构

**新增**

| 文件 | 职责 |
|---|---|
| `platform-web/src/utils/routeKinds.ts` | 5 种 kind 的静态目录：路由参数值 / 显示名 / 能力码 / 是否有 hostnames / api 函数 / 摘要文本 / 删除提示 + `parseRouteKind` |
| `platform-web/src/views/resource/RouteListView.vue` | 「路由策略」列表页（类型筛选 / 表格 / 抽屉（概览 + YAML）/ 创建下拉 / 失败隔离） |
| `platform-web/src/views/resource/RouteEditorView.vue` | 编辑器外壳（类型选择器 / 路由名 / 父资源块 / hostnames / 校验编排 / 回填 / 提交 / 删除） |
| `platform-web/src/components/route/HttpRouteForm.vue` | HTTPRoute 规则表单（从 `HttpRouteEditorView.vue` 搬迁） |
| `platform-web/src/components/route/GrpcRouteForm.vue` | GRPCRoute 规则表单（从 `GrpcRouteEditorView.vue` 搬迁） |
| `platform-web/src/components/route/TcpRouteForm.vue` | TCPRoute 规则表单（从 `TcpRouteEditorView.vue` 搬迁） |
| `platform-web/src/components/route/TlsRouteForm.vue` | TLSRoute 规则表单（从 `TlsRouteEditorView.vue` 搬迁） |
| `platform-web/src/components/route/UdpRouteForm.vue` | UDPRoute 规则表单（从 `UdpRouteEditorView.vue` 搬迁） |

**修改**

| 文件 | 改动 |
|---|---|
| `platform-web/src/apiCodes.ts` | +15 个常量（5 kind × list/create/update） |
| `platform-web/src/router/index.ts` | +2 条（`/routes`、`/routes/editor`）；删 10 条旧记录（列表 5 条在 Task 4 删，编辑器 5 条在 Task 3 删） |
| `platform-web/src/layouts/MainLayout.vue` | 5 个菜单项 → 1 项「路由策略」；组标题 `v-if` 同步 |

**删除**（Task 3 删编辑器 5 个；Task 4 删列表 5 个）

`views/resource/{Http,Grpc,Tcp,Tls,Udp}RouteEditorView.vue`、`views/resource/{Http,Grpc,Tcp,Tls,Udp}RouteView.vue`

**任务顺序说明（与 spec §11 的差别）**：spec 建议 T1 就删旧列表页；本计划把「删旧文件 + 切路由/菜单」挪到**各自的替换物完成之后**，让每个任务结束时应用都可用（T1 只加基础设施；T2/T3 加编辑器且旧编辑器仍在；T4 一次切换列表侧）。

---

### Task 1: 静态目录 + 能力码

**Files:**
- Create: `platform-web/src/utils/routeKinds.ts`
- Modify: `platform-web/src/apiCodes.ts`

**Interfaces:**
- Produces（Task 2/4 依赖，逐字一致）：
  ```ts
  type RouteKind = 'httproute' | 'grpcroute' | 'tcproute' | 'tlsroute' | 'udproute'
  type RouteRow = (K8sHttpRoute | K8sGrpcRoute | K8sTcpRoute | K8sTlsRoute | K8sUdpRoute) & { _kind: RouteKind }
  type RouteCtx = { tenantId: string; clusterId: string; namespace: string }
  interface RouteKindMeta {
    key: RouteKind; label: string; usesHostnames: boolean
    listCode: string; createCode: string; updateCode: string
    list: (ctx: RouteCtx) => Promise<unknown[]>
    yaml: (name: string, ctx: RouteCtx) => Promise<string>
    remove: (name: string, ctx: RouteCtx) => Promise<void>
    summary: (row: RouteRow) => string
    deleteHint: string
  }
  const ROUTE_KINDS: RouteKindMeta[]
  function kindMeta(key: RouteKind): RouteKindMeta
  function parseRouteKind(raw: unknown): RouteKind | null
  ```
- Consumes: `@/api` 的 5 个客户端（已存在）、`@/apiCodes`（本任务新增常量）。

- [ ] **Step 1: `apiCodes.ts` 加 15 个常量**

在 `apiCodes` 对象里 `gatewayList` 那一行**之后**插入（保持扁平风格，与既有 `gatewayList` 一致）：

```ts
  // ---- 路由策略（Route 五合一）：逐 kind 的拉取短路 / 创建与保存的按钮置灰 ----
  // 页面可见性用 pageCodes.mesh.<x>Route.{list,edit}（本批不加页面码）；这里只放"发不发请求/按不按得动"的判断。
  httpRouteList: 'tenant:httproute:list',
  httpRouteCreate: 'tenant:httproute:create',
  httpRouteUpdate: 'tenant:httproute:update',
  grpcRouteList: 'tenant:grpcroute:list',
  grpcRouteCreate: 'tenant:grpcroute:create',
  grpcRouteUpdate: 'tenant:grpcroute:update',
  tcpRouteList: 'tenant:tcproute:list',
  tcpRouteCreate: 'tenant:tcproute:create',
  tcpRouteUpdate: 'tenant:tcproute:update',
  tlsRouteList: 'tenant:tlsroute:list',
  tlsRouteCreate: 'tenant:tlsroute:create',
  tlsRouteUpdate: 'tenant:tlsroute:update',
  udpRouteList: 'tenant:udproute:list',
  udpRouteCreate: 'tenant:udproute:create',
  udpRouteUpdate: 'tenant:udproute:update',
```

（这 15 个码本就存在于权限表 —— `V2026_10_08_4` / `V2026_10_08_5` 的 `tenant:<kind>route:{list,create,update}`，本步只是把它们登记进前端常量表。）

- [ ] **Step 2: 建 `utils/routeKinds.ts`（完整内容）**

```ts
import { grpcRouteApi, httpRouteApi, tcpRouteApi, tlsRouteApi, udpRouteApi } from '@/api'
import { apiCodes } from '@/apiCodes'
import type { K8sGrpcRoute, K8sHttpRoute, K8sL4RouteRule, K8sRouteBackendRef, K8sTcpRoute, K8sTlsRoute, K8sUdpRoute } from '@/types'

/**
 * 「路由策略」的 5 种 kind 静态目录（列表页与编辑器共用）。
 *
 * <p>放这里而不是各自的页面里：kind 的**身份信息**（叫什么、有没有 hostnames、用哪几个码、怎么拉/怎么删、
 * 列表摘要怎么写）是同一份事实，页面只负责渲染。**注意**：这里只装"种类"的事实，不装表单 ——
 * 5 种 kind 的规则表单仍在各自的 components/route/*RouteForm.vue 里各写各的。
 */
export type RouteKind = 'httproute' | 'grpcroute' | 'tcproute' | 'tlsroute' | 'udproute'

/** 列表行：5 种 wire 类型 + 前端打上的判别字段（不依赖后端 DTO 是否回带 kind） */
export type RouteRow = (K8sHttpRoute | K8sGrpcRoute | K8sTcpRoute | K8sTlsRoute | K8sUdpRoute) & { _kind: RouteKind }

/** 三个既有上下文字段（与各处 ctx3 同形） */
export type RouteCtx = { tenantId: string; clusterId: string; namespace: string }

export interface RouteKindMeta {
  key: RouteKind
  /** 展示名，如 HTTPRoute */
  label: string
  /** 该 kind 有没有 hostnames 字段（编辑器外壳据此决定渲染与否；TCP/UDP 没有） */
  usesHostnames: boolean
  /** 逐 kind 拉取的短路码（apiCodes，不是 pageCodes） */
  listCode: string
  createCode: string
  updateCode: string
  list: (ctx: RouteCtx) => Promise<unknown[]>
  yaml: (name: string, ctx: RouteCtx) => Promise<string>
  remove: (name: string, ctx: RouteCtx) => Promise<void>
  /** 列表「摘要」列的 kind 相关文本 */
  summary: (row: RouteRow) => string
  /** 删除确认框里的后果说明（各 kind 不同） */
  deleteHint: string
}

/** backendRefs → `ns/name:port, …`；空则 `—` */
function backendsText(refs: K8sRouteBackendRef[] | null | undefined): string {
  const list = (refs ?? []).map((b) => `${b.namespace ? `${b.namespace}/` : ''}${b.name}:${b.port ?? '?'}`)
  return list.join(', ') || '—'
}

/** hostnames → `a.com, b.com`；空则 `全部主机名` */
function hostsText(hostnames: string[] | null | undefined): string {
  return (hostnames ?? []).length ? (hostnames ?? []).join(', ') : '全部主机名'
}

/** TCP/UDP 只有一条 rule，摘要取它的 backendRefs */
function l4Backends(rules: K8sL4RouteRule[] | null | undefined): string {
  return backendsText((rules ?? [])[0]?.backendRefs)
}

export const ROUTE_KINDS: RouteKindMeta[] = [
  {
    key: 'httproute',
    label: 'HTTPRoute',
    usesHostnames: true,
    listCode: apiCodes.httpRouteList,
    createCode: apiCodes.httpRouteCreate,
    updateCode: apiCodes.httpRouteUpdate,
    list: (ctx) => httpRouteApi.list(ctx),
    yaml: (name, ctx) => httpRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => httpRouteApi.delete(name, ctx),
    summary: (row) => {
      const r = row as K8sHttpRoute
      return `${(r.rules ?? []).length} 条规则 · ${hostsText(r.hostnames)}`
    },
    deleteHint: '其匹配的流量将回落到该 Gateway 的其他路由或 404。',
  },
  {
    key: 'grpcroute',
    label: 'GRPCRoute',
    usesHostnames: true,
    listCode: apiCodes.grpcRouteList,
    createCode: apiCodes.grpcRouteCreate,
    updateCode: apiCodes.grpcRouteUpdate,
    list: (ctx) => grpcRouteApi.list(ctx),
    yaml: (name, ctx) => grpcRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => grpcRouteApi.delete(name, ctx),
    summary: (row) => {
      const r = row as K8sGrpcRoute
      return `${(r.rules ?? []).length} 条规则 · ${hostsText(r.hostnames)}`
    },
    deleteHint: '其匹配的 gRPC 流量将回落到该 Gateway 的其他路由或失败。',
  },
  {
    key: 'tcproute',
    label: 'TCPRoute',
    usesHostnames: false,
    listCode: apiCodes.tcpRouteList,
    createCode: apiCodes.tcpRouteCreate,
    updateCode: apiCodes.tcpRouteUpdate,
    list: (ctx) => tcpRouteApi.list(ctx),
    yaml: (name, ctx) => tcpRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => tcpRouteApi.delete(name, ctx),
    summary: (row) => l4Backends((row as K8sTcpRoute).rules),
    deleteHint: '该 TCP 端口将不再转发到任何后端。',
  },
  {
    key: 'tlsroute',
    label: 'TLSRoute',
    usesHostnames: true,
    listCode: apiCodes.tlsRouteList,
    createCode: apiCodes.tlsRouteCreate,
    updateCode: apiCodes.tlsRouteUpdate,
    list: (ctx) => tlsRouteApi.list(ctx),
    yaml: (name, ctx) => tlsRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => tlsRouteApi.delete(name, ctx),
    summary: (row) => {
      const r = row as K8sTlsRoute
      return `SNI ${(r.hostnames ?? []).length ? (r.hostnames ?? []).join(', ') : '全部'}`
    },
    deleteHint: '按 SNI 命中的 TLS 连接将不再转发到任何后端。',
  },
  {
    key: 'udproute',
    label: 'UDPRoute',
    usesHostnames: false,
    listCode: apiCodes.udpRouteList,
    createCode: apiCodes.udpRouteCreate,
    updateCode: apiCodes.udpRouteUpdate,
    list: (ctx) => udpRouteApi.list(ctx),
    yaml: (name, ctx) => udpRouteApi.getYaml(name, ctx),
    remove: (name, ctx) => udpRouteApi.delete(name, ctx),
    summary: (row) => l4Backends((row as K8sUdpRoute).rules),
    deleteHint: '该 UDP 端口将不再转发到任何后端。',
  },
]

/** 取 kind 的身份信息；未知 key 抛错（调用方保证 key 来自 parseRouteKind / 目录本身） */
export function kindMeta(key: RouteKind): RouteKindMeta {
  const m = ROUTE_KINDS.find((k) => k.key === key)
  if (!m) throw new Error(`未知的 route 类型：${key}`)
  return m
}

/** 解析 `?kind=`：合法值返回它，非法/缺失返回 null（调用方回落默认） */
export function parseRouteKind(raw: unknown): RouteKind | null {
  return typeof raw === 'string' && ROUTE_KINDS.some((k) => k.key === raw) ? (raw as RouteKind) : null
}
```

- [ ] **Step 3: 类型检查**

```bash
npm --prefix platform-web run build
```

预期：`✓ built in …`。若报 `K8sTcpRoute`/`K8sUdpRoute` 没有 `rules`、或 `K8sL4RouteRule` 字段名不符，读 `platform-web/src/types.ts` 里对应 interface 后按实际字段名修（**不要**改成 `any`）。

- [ ] **Step 4: 提交**

```bash
git add platform-web/src/apiCodes.ts platform-web/src/utils/routeKinds.ts
git commit -m "feat(web): 路由策略静态目录 + 15 个能力码（5 kind × list/create/update）"
```

---

### Task 2: 编辑器外壳 + HTTPRoute / GRPCRoute 两个子组件

**Files:**
- Create: `platform-web/src/components/route/HttpRouteForm.vue`、`platform-web/src/components/route/GrpcRouteForm.vue`
- Create: `platform-web/src/views/resource/RouteEditorView.vue`
- Modify: `platform-web/src/router/index.ts`（新增 `/routes/editor`；**旧 5 条编辑器记录先留着**）
- Read（搬迁来源，不要删）：`views/resource/HttpRouteEditorView.vue`、`GrpcRouteEditorView.vue`

**Interfaces:**
- Consumes（Task 1）：`ROUTE_KINDS`、`kindMeta`、`parseRouteKind`、`RouteKind`、`RouteCtx`。
- Produces（Task 3 依赖）：
  - 子组件契约（两种）：
    - `HttpRouteForm` / `GrpcRouteForm` / `TlsRouteForm`：`defineModel<K8sHrRule[] | K8sGrpcRule[] | K8sL4RouteRule[]>('rules', { required: true })` + `props: { namespace: string }` + `defineExpose({ validate })`，其中 `validate(): string | null`。
    - `TcpRouteForm` / `UdpRouteForm`：`defineModel<K8sL4RouteRule>('rule', { required: true })`（**单条**，与旧编辑器内部模型一致）+ 同上 `namespace` / `validate`。
  - 外壳：`RouteEditorView.vue` 的 5 个 `v-if` 分支与单例模板 ref 名字 `formEl`。
  - 契约为什么不做成统一的 `rules: unknown[]`：5 个分支是静态 `v-if`（不用 `:is`），各自类型独立最省事；TCP/UDP 保持单条模型可让搬迁改动最小。

- [ ] **Step 1: 建 `HttpRouteForm.vue` —— 从 `HttpRouteEditorView.vue` 搬迁**

**搬进子组件的脚本块**（照抄，逐个改名）：

| 来源（HttpRouteEditorView.vue） | 改名 |
|---|---|
| `interface BackendRow` / `MatchRow` / `FilterRow` / `RuleRow` | 不改 |
| 常量 `METHODS` / `PATH_TYPES` / `MATCH_TYPES` / `PATH_MOD_TYPES` / `FILTER_TYPES` | 不改 |
| `newBackend` / `newMatch` / `newFilter` / `newRule` | 不改 |
| `addRule` / `removeRule` / `addMatch` / `removeMatch` / `addFilter` / `removeFilter` / `addBackend` / `removeBackend` | 内部 `form.rules` → `rules`（`defineModel` 的 ref） |
| `fromMatch` / `fromFilter` / `fromBackend` / `fromRule` | 不改（它们只吃 DTO 参数） |
| `unsupportedFilterTypes` 的计算（`computed`） | `form.rules` → `rules` |
| `validate()` 里**关于 rules 的那两个循环**（`至少需要一条规则` 与每条规则的 matches/backends 校验） | 抽成子组件的 `validate()`，文案保持不变（去掉前缀 `规则 #N` 之外的部分不动） |

**搬进子组件的模板块**：从 `<el-divider content-position="left">规则（rules）</el-divider>` 到该块结束（`<el-button size="small" @click="addRule">添加规则</el-button>` 之后）、**以及**页面顶部那个 `unsupportedFilterTypes` 的告警块（`<el-alert v-if="unsupportedFilterTypes.length" …>`，它是关于 filter 的，归子组件）。

**壳**：

```vue
<script setup lang="ts">
import { computed, defineModel, ref } from 'vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import type { K8sHrRule } from '@/types'

const props = defineProps<{ namespace: string }>()
const rules = defineModel<K8sHrRule[]>('rules', { required: true })

// …搬迁来的接口/常量/newXxx/addXxx/removeXxx/fromXxx/unsupportedFilterTypes…

/** 本 kind 的规则校验：null = 通过 */
function validate(): string | null {
  // …搬迁来的两个循环…
  return null
}

defineExpose({ validate })
</script>

<template>
  <!-- …搬迁来的告警块 + 规则块（form.rules → rules）… -->
</template>
```

搬迁时**必须逐条保留**（Review Focus 5）：`Group`/`Kind`/`Port` 的"未建模兄弟键原样回传"逻辑（filter 体里未在表单出现的键、backendRef 的 group/kind/port），以及 `RequestMirror` / `ExtensionRef` 等各 filter 的字段。

- [ ] **Step 2: 建 `GrpcRouteForm.vue`**

同 Step 1 的搬法，来源换成 `GrpcRouteEditorView.vue`：接口 `BackendRow`/`MatchRow`/`FilterRow`/`RuleRow`、`newBackend`/`newMatch`/`newFilter`/`newRule`、`addRule`/`removeRule`/`addMatch`/`removeMatch`/`addFilter`/`removeFilter`/`addBackend`/`removeBackend`、`fromMatch`/`fromFilter`/`fromBackend`/`fromRule`；模板从 `<el-divider content-position="left">规则（rules）</el-divider>` 到规则块结束 + 顶部 `unsupportedFilterTypes` 告警块。GRPC 的匹配是 `methodType`/`methodService`/`methodMethod`，原样保留。

- [ ] **Step 3: 建 `RouteEditorView.vue` 外壳**

外壳需求（逐条对应 spec §5.1）：

1. `kind`：`const kind = ref<RouteKind>(parseRouteKind(route.query.kind) ?? 'httproute')`；
   `const kindLocked = computed(() => !!editing.value || parseRouteKind(route.query.kind) !== null)`（**合法参数才锁定**；非法/缺失 → 默认 httproute 且可选，Review Focus 3）。
2. `editing = ref<string | null>((route.query.name as string) || null)`。
3. 路由名 / 父资源块 / hostnames / 保存 / 删除 / 校验编排。
4. **父资源块整体搬自 `HttpRouteEditorView.vue`**（脚本：`useParentCandidates` 那一整块 + `effectiveNs` / `bucketOf` / `hasUnmodeledKind` / `onParentKindChange` / `onParentNamespaceChange` / `noDataText` / `namePlaceholder` / `sectionOptions` / `sectionNoDataText` / `nameRowLabel` / `sectionRowLabel`，以及 `ParentRow` 接口与 `SECTION_NAME_RE`；模板：从 `<el-divider content-position="left">父资源（parentRefs）</el-divider>` 到 `<el-button size="small" @click="addParent">添加父引用</el-button>`）。**逐字保留 `@change` 清值（不要改成 watch）**。
5. 5 个分支 + 单例模板 ref：

```vue
<HttpRouteForm v-if="kind === 'httproute'" ref="formEl" v-model:rules="httpRules" :namespace="state.namespace!" />
<GrpcRouteForm v-else-if="kind === 'grpcroute'" ref="formEl" v-model:rules="grpcRules" :namespace="state.namespace!" />
<TcpRouteForm v-else-if="kind === 'tcproute'" ref="formEl" v-model:rule="tcpRule" :namespace="state.namespace!" />
<TlsRouteForm v-else-if="kind === 'tlsroute'" ref="formEl" v-model:rules="tlsRules" :namespace="state.namespace!" />
<UdpRouteForm v-else-if="kind === 'udproute'" ref="formEl" v-model:rule="udpRule" :namespace="state.namespace!" />
```

```ts
/** 当前挂着的那个子组件实例：v-if 保证同一时刻只有一个，故 5 个分支共用同一个 ref 名是安全的 */
const formEl = ref<{ validate: () => string | null } | null>(null)
```

6. 数据 ref（切换 kind 时整组重置）：

```ts
const httpRules = ref<K8sHrRule[]>([])
const grpcRules = ref<K8sGrpcRule[]>([])
const tlsRules = ref<K8sL4RouteRule[]>([])
const tcpRule = ref<K8sL4RouteRule>({ name: '', backendRefs: [] })
const udpRule = ref<K8sL4RouteRule>({ name: '', backendRefs: [] })

/** 切 kind：把 5 组模型一起清空（新 kind 的表单从空白开始）+ 清 hostnames */
function resetKindModels(next: RouteKind): void {
  httpRules.value = []
  grpcRules.value = []
  tlsRules.value = []
  tcpRule.value = { name: '', backendRefs: [] }
  udpRule.value = { name: '', backendRefs: [] }
  form.hostnames = []
  kind.value = next
}
```

7. 校验编排（先外壳后子组件）：

```ts
const RFC1123_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/
const HOSTNAME_RE = /^(\*\.)?[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/
/** SectionName 的 CRD pattern（≤253，见 Gateway API shared_types） */
const SECTION_NAME_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/

function validate(): string | null {
  const name = (editing.value ?? form.name).trim()
  if (!name) return '请输入名称'
  if (!RFC1123_RE.test(name)) return '名称须为 RFC-1123（小写字母/数字/-，字母或数字开头结尾）'
  if (!form.parentRefs.some((p) => p.name.trim())) return '至少需要一个父资源（parentRefs）'
  for (const [pi, p] of form.parentRefs.entries()) {
    const sec = p.sectionName.trim()
    if (!sec) continue
    // 填错监听器名/端口名是"静默不挂载"，故提交前拦一道（与 HttpRouteEditorView 逐字相同）
    if (sec.length > 253 || !SECTION_NAME_RE.test(sec)) {
      return `父引用 #${pi + 1}：sectionName 须为合法段名（小写字母/数字/'-'/'.'，≤253）：「${sec}」`
    }
  }
  if (kindMeta(kind.value).usesHostnames) {
    for (const h of form.hostnames) {
      if (h.trim() && !HOSTNAME_RE.test(h.trim())) return `主机名格式不合法：「${h}」`
    }
  }
  return formEl.value?.validate() ?? null
}
```

8. 提交：按 kind 分支组装 body 调对应 api（`create(ctx2, body)` / `update(editing, ctx2, body)`），并**按 kind 的 `createCode`/`updateCode` 置灰保存**：

```ts
const saving = ref(false)
const canSave = computed(() => {
  const m = kindMeta(kind.value)
  return perm.has(editing.value ? m.updateCode : m.createCode)
})
```

9. 回填 `loadDetail()`：按 kind 分支 `get`，把 `name` / `parentRefs` / `hostnames` 填进外壳的 `form`，把 rules 交给子组件的 `setRules`（**外壳不解析 rules**）。5 个分支全写出来：

```ts
async function loadDetail(): Promise<void> {
  if (!editing.value) return
  detailState.value = 'loading'
  try {
    if (kind.value === 'httproute') {
      const d = await httpRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      form.hostnames = [...(d.hostnames ?? [])]
      formEl.value?.setRules?.(d.rules ?? [])
    } else if (kind.value === 'grpcroute') {
      const d = await grpcRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      form.hostnames = [...(d.hostnames ?? [])]
      formEl.value?.setRules?.(d.rules ?? [])
    } else if (kind.value === 'tcproute') {
      const d = await tcpRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      form.hostnames = []
      formEl.value?.setRules?.(d.rules ?? [])
    } else if (kind.value === 'tlsroute') {
      const d = await tlsRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      form.hostnames = [...(d.hostnames ?? [])]
      formEl.value?.setRules?.(d.rules ?? [])
    } else {
      const d = await udpRouteApi.get(editing.value, ctx3.value)
      form.name = d.name
      form.parentRefs = (d.parentRefs ?? []).map(fromParent)
      form.hostnames = []
      formEl.value?.setRules?.(d.rules ?? [])
    }
    detailState.value = 'loaded'
  } catch {
    detailState.value = 'error'
  }
}
```

`formEl` 的类型声明因此是 `ref<{ validate: () => string | null; setRules?: (raw: unknown) => void } | null>(null)`（`setRules` 可选：子组件挂载后才有）。
若某 kind 的 DTO 字段名与上面不符（例如 TCP/UDP 的规则字段不是 `rules`），读该 DTO 在 `platform-web/src/types.ts` 的定义后按实际字段名改这一分支。

- [ ] **Step 4: 路由**

在 `router/index.ts` 的 mesh 分组里**新增**（旧的 5 条编辑器记录**本任务不删**）：

```ts
{ path: 'routes/editor', name: 'route-editor', component: () => import('@/views/resource/RouteEditorView.vue'), meta: { title: '路由策略 编辑', group: '服务网格', context: 'full', requiresPerm: [pageCodes.mesh.httpRoute.edit, pageCodes.mesh.grpcRoute.edit, pageCodes.mesh.tcpRoute.edit, pageCodes.mesh.tlsRoute.edit, pageCodes.mesh.udpRoute.edit] } },
```

- [ ] **Step 5: 类型检查 + 冒烟**

```bash
npm --prefix platform-web run build
```

预期 `✓ built`。然后（宿主页办法，见 Global Constraints）直挂 `RouteEditorView`，断言：
① `?kind=tcproute` 落到 TCP 表单（**无 hostnames 行、无 matches**）；
② `?kind=httproute&name=<一条已有 httproute>` 回填出 name + parentRefs + rules；
③ 手动把 kind 改成 `grpcroute`：出现 hostnames 行、rules 清空为一条空白规则。

- [ ] **Step 6: 提交**

```bash
git add platform-web/src/components/route/HttpRouteForm.vue platform-web/src/components/route/GrpcRouteForm.vue platform-web/src/views/resource/RouteEditorView.vue platform-web/src/router/index.ts
git commit -m "feat(web): 路由策略编辑器外壳 + HTTP/GRPC 规则子组件"
```

---

### Task 3: 其余三个子组件 + 删旧编辑器

**Files:**
- Create: `platform-web/src/components/route/TcpRouteForm.vue`、`TlsRouteForm.vue`、`UdpRouteForm.vue`
- Modify: `platform-web/src/router/index.ts`（删 5 条旧编辑器记录）
- Delete: `views/resource/{Http,Grpc,Tcp,Tls,Udp}RouteEditorView.vue`

**Interfaces:**
- Consumes：Task 2 的契约（`v-model:rules` / `v-model:rule`、`props.namespace`、`defineExpose({ validate, setRules })`）。
- Produces：5 个 kind 的编辑器能力齐全，旧编辑器文件可以安全删除。

- [ ] **Step 1: 建 `TcpRouteForm.vue`（单条模型）**

从 `TcpRouteEditorView.vue` 搬迁：`interface BackendRow`、`newBackend`、`addBackend` / `removeBackend`、`BACKEND` 相关常量、`fromBackend`；模板从 `<el-divider content-position="left">规则（rules；TCPRoute 只允许一条）</el-divider>` 到规则块结束。

```vue
<script setup lang="ts">
import { defineModel } from 'vue'
import FieldHelp from '@/components/workload/FieldHelp.vue'
import type { K8sL4RouteRule } from '@/types'

const props = defineProps<{ namespace: string }>()
/** TCPRoute 只允许一条规则 —— 模型就是那一条（与旧编辑器内部模型一致，搬迁改动最小） */
const rule = defineModel<K8sL4RouteRule>('rule', { required: true })

// …搬迁来的 BackendRow/newBackend/addBackend/removeBackend/fromBackend…

function validate(): string | null {
  // …旧编辑器 validate() 里关于 backendRefs 的那段（"至少需要一个后端"等），文案不变…
  return null
}

/** 回填：外壳把 DTO 的 rules 原样交过来，这里归一成一条 */
function setRules(raw: unknown): void {
  const list = (raw as K8sL4RouteRule[] | null) ?? []
  const first = list[0]
  rule.value = { name: first?.name ?? '', backendRefs: (first?.backendRefs ?? []).map(fromBackend) }
  if (!rule.value.backendRefs.length) rule.value.backendRefs = [newBackend()]
}

defineExpose({ validate, setRules })
</script>
```

模板里 `rule.backendRefs` 原样（旧编辑器用的是 `form.backendRefs` → 改名 `rule.backendRefs`）。

- [ ] **Step 2: 建 `UdpRouteForm.vue`**

与 Step 1 同形，来源 `UdpRouteEditorView.vue`（divider 文案是 `<el-divider content-position="left">规则（rules；UDPRoute 只允许一条）</el-divider>`）。

- [ ] **Step 3: 建 `TlsRouteForm.vue`（数组模型，最多 16 条）**

来源 `TlsRouteEditorView.vue`：`interface RuleRow`、`newRule`、`addRule` / `removeRule`、`MAX_RULES`、`fromRule`；模板从 `<el-divider content-position="left">规则（rules；最多 16 条）</el-divider>` 到规则块结束。契约与 HTTP/GRPC 相同（`v-model:rules` + `setRules`）。

- [ ] **Step 4: 删旧编辑器与旧编辑器路由**

```bash
git rm platform-web/src/views/resource/HttpRouteEditorView.vue \
       platform-web/src/views/resource/GrpcRouteEditorView.vue \
       platform-web/src/views/resource/TcpRouteEditorView.vue \
       platform-web/src/views/resource/TlsRouteEditorView.vue \
       platform-web/src/views/resource/UdpRouteEditorView.vue
```

并在 `router/index.ts` 删掉 5 条 `…route-editor`（`httproute-editor` / `grpcroute-editor` / `tcproute-editor` / `tlsroute-editor` / `udproute-editor`）记录。**注意**：`WorkloadView.vue` 里没有对这些名字的跳转，但先全仓 grep 一次确认没有别处 `name: 'httproute-editor'` 之类的引用：

```bash
grep -rn "httproute-editor\|grpcroute-editor\|tcproute-editor\|tlsroute-editor\|udproute-editor" platform-web/src || echo "无引用"
```

- [ ] **Step 5: 类型检查 + 冒烟**

```bash
npm --prefix platform-web run build
```

预期 `✓ built`。直挂 `RouteEditorView` 验：`?kind=tlsroute` 出 SNI 主机名行 + 规则块（最多 16 条）；`?kind=udproute` 单条规则、无 hostnames。

- [ ] **Step 6: 提交**

```bash
git add -A platform-web/src/components/route platform-web/src/router/index.ts
git commit -m "feat(web): TCP/TLS/UDP 规则子组件；删除 5 个旧编辑器页与路由"
```

---

### Task 4: 列表页 + 路由/菜单收敛 + 删旧列表

**Files:**
- Create: `platform-web/src/views/resource/RouteListView.vue`
- Modify: `platform-web/src/router/index.ts`（+`/routes`，删 5 条旧列表记录）、`platform-web/src/layouts/MainLayout.vue`
- Delete: `views/resource/{Http,Grpc,Tcp,Tls,Udp}RouteView.vue`

**Interfaces:**
- Consumes：Task 1 的 `ROUTE_KINDS` / `kindMeta` / `parseRouteKind`；Task 2/3 的 `route-editor` 路由。
- Produces：「路由策略」入口齐全。

- [ ] **Step 1: 建 `RouteListView.vue`**

```vue
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import MeshStatusBanner from '@/components/mesh/MeshStatusBanner.vue'
import YamlBlock from '@/components/YamlBlock.vue'
import { useMeshStatus } from '@/composables/useMeshStatus'
import { usePermission } from '@/stores/permission'
import { useResourceContext } from '@/stores/context'
import { kindMeta, parseRouteKind, ROUTE_KINDS, type RouteCtx, type RouteKind, type RouteRow } from '@/utils/routeKinds'
import type { K8sRouteParentRef } from '@/types'
import { fmtDate } from '@/utils/format'

/**
 * 「路由策略」列表：5 种 route 一个页面（形态沿用 WorkloadView 的多 kind 列表）。
 *
 * <p><b>按权限逐 kind 拉</b>：没有该 kind 的 list 能力位就不发请求、不进筛选器 —— 权限表在服务端，
 * 前端短路只为"不发注定被拒的请求"（frontend-permission-conventions §1 的码表分工）。
 *
 * <p><b>失败隔离 + 不反复报错</b>：某个 kind 拉失败（典型：该 CRD 未安装）只在会话内标记一次并
 * 从后续刷新里排除；它不拖累其它 kind，也不会每刷新一次就炸一串提示（横幅里给一行说明）。
 */
const route = useRoute()
const router = useRouter()
const { state, ready, currentTenant, currentCluster, load } = useResourceContext()
const perm = usePermission()
const mesh = useMeshStatus(computed(() => state.clusterId ?? null))

const ctxParams = computed<RouteCtx>(() => ({
  tenantId: state.tenantId!,
  clusterId: state.clusterId!,
  namespace: state.namespace!,
}))

/** 有 list 能力位的 kind（无权限的不出现、不发请求） */
const permitted = computed(() => ROUTE_KINDS.filter((k) => perm.has(k.listCode)))
/** 拉取失败的 kind：会话内不再重试（避免反复报错）；切集群/命名空间时清空重试 */
const unavailable = ref<RouteKind[]>([])

const loading = ref(false)
const rows = ref<RouteRow[]>([])
const kindFilter = ref<'all' | RouteKind>(parseRouteKind(route.query.kind) ?? 'all')

const filtered = computed(() =>
  kindFilter.value === 'all' ? rows.value : rows.value.filter((r) => r._kind === kindFilter.value),
)
const canCreate = computed(() => mesh.hasGatewayApi.value)
/** 创建下拉：有 create 能力位、且该 kind 当前可用 */
const creatable = computed(() =>
  permitted.value.filter((k) => perm.has(k.createCode) && !unavailable.value.includes(k.key)),
)

async function refresh(): Promise<void> {
  if (!ready.value) return
  loading.value = true
  try {
    const targets = permitted.value.filter((k) => !unavailable.value.includes(k.key))
    const settled = await Promise.allSettled(
      targets.map(async (k) => (await k.list(ctxParams.value)).map((r) => ({ ...(r as object), _kind: k.key }) as RouteRow)),
    )
    const out: RouteRow[] = []
    settled.forEach((s, i) => {
      if (s.status === 'fulfilled') out.push(...s.value)
      else {
        const key = targets[i].key
        unavailable.value = [...unavailable.value, key]
        if (kindFilter.value === key) kindFilter.value = 'all' // 该 kind 掉了 → 筛选回落「全部」
      }
    })
    rows.value = out
  } finally {
    loading.value = false
  }
}

async function refreshAll(): Promise<void> {
  unavailable.value = [] // 用户显式刷新：失败的 kind 再试一次
  await mesh.refresh()
  await refresh()
}

watch([() => ready.value, () => state.clusterId, () => state.namespace], ([r]) => {
  if (!r) return
  unavailable.value = [] // 换了集群/命名空间：CRD 与可见范围都可能变
  void refresh()
}, { immediate: true })

// ---------- 导航 ----------
function goCreate(key: RouteKind): void { router.push({ name: 'route-editor', query: { kind: key } }) }
function goEdit(row: RouteRow): void { router.push({ name: 'route-editor', query: { kind: row._kind, name: row.name } }) }

async function onDelete(row: RouteRow): Promise<void> {
  const meta = kindMeta(row._kind)
  try {
    await ElMessageBox.confirm(`确认删除 ${meta.label}「${row.name}」？${meta.deleteHint}`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await meta.remove(row.name, ctxParams.value)
    ElMessage.success('已删除')
    await refresh()
  } catch { /* 拦截器已提示 */ }
}

// ---------- 详情抽屉 ----------
const drawerVisible = ref(false)
const detail = ref<RouteRow | null>(null)
const detailTab = ref<'info' | 'yaml'>('info')
const yamlText = ref('')

async function openDetail(row: RouteRow, tab: 'info' | 'yaml' = 'info'): Promise<void> {
  detail.value = row
  detailTab.value = tab
  yamlText.value = ''
  drawerVisible.value = true
  if (tab === 'yaml') await loadYaml(row)
}

async function loadYaml(row: RouteRow): Promise<void> {
  try {
    yamlText.value = await kindMeta(row._kind).yaml(row.name, ctxParams.value)
  } catch { /* 拦截器已提示 */ }
}

// ---------- 展示辅助（与旧 5 个列表页逐字相同的口径） ----------
function parentText(p: K8sRouteParentRef): string {
  const kind = p.kind || 'Gateway'
  const ns = p.namespace ? `${p.namespace}/` : ''
  const qual = p.sectionName || (p.port != null ? String(p.port) : '')
  return `${kind} ${ns}${p.name ?? '?'}${qual ? `:${qual}` : ''}`
}

/** 行内摘要（各 kind 的规则/后端/SNI 摘要） */
function summaryText(row: RouteRow): string {
  return kindMeta(row._kind).summary(row)
}

/** hostnames 只有 HTTP/GRPC/TLS 有 —— 联合类型上必须按"可能没有"读（否则 vue-tsc 报错） */
function rowHostnames(row: RouteRow | null): string[] {
  return ((row ?? {}) as { hostnames?: string[] }).hostnames ?? []
}

/** 抽屉「概览」里的规则行文本：HTTP/GRPC 有 matches，L4 只有后端 */
function ruleText(row: RouteRow, index: number): string {
  const meta = kindMeta(row._kind)
  const rules = ((row as { rules?: unknown[] }).rules ?? []) as Record<string, unknown>[]
  const r = rules[index]
  if (!r) return '—'
  const backends = ((r.backendRefs ?? []) as Record<string, unknown>[])
    .map((b) => `${b.namespace ? `${b.namespace}/` : ''}${b.name}:${b.port ?? '?'}${b.weight != null ? `(w=${b.weight})` : ''}`)
    .join(', ')
  const matches = (r.matches ?? []) as Record<string, unknown>[]
  const matchText = matches.length ? `${matches.length} 条匹配` : ''
  return [meta.label, matchText, backends || '—'].filter(Boolean).join(' · ')
}

const contextDesc = computed(() => {
  if (!ready.value) return '请在顶栏选择租户 / 集群 / 命名空间'
  return `${currentTenant.value?.name ?? ''} · ${currentCluster.value?.clusterName ?? ''} / ${state.namespace}`
})
</script>
```

模板（要点，逐条对应 spec §4.3/§4.5/§4.6）：

```vue
<template>
  <div>
    <PageHeader title="路由策略" :description="contextDesc">
      <el-button @click="refreshAll" :disabled="!ready">刷新</el-button>
      <!-- 筛选 = 全部：下拉选类型；筛选 = 某 kind：直接创建该 kind -->
      <el-dropdown v-if="kindFilter === 'all' && creatable.length" @command="goCreate">
        <el-button type="primary" :disabled="!ready || !canCreate">创建<el-icon><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="k in creatable" :key="k.key" :command="k.key">创建 {{ k.label }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button v-else-if="kindFilter !== 'all'" type="primary" :disabled="!ready || !canCreate" @click="goCreate(kindFilter)">
        创建 {{ kindMeta(kindFilter).label }}
      </el-button>
    </PageHeader>

    <MeshStatusBanner v-if="ready && state.clusterId" :has-gateway-api="mesh.hasGatewayApi.value"
      :gateway-api-versions="mesh.gatewayApiVersions.value" :has-istio="mesh.hasIstio.value"
      :istio-ambient="mesh.istioAmbient.value" :ambient-known="mesh.ambientKnown.value"
      :probed="mesh.probed.value" :can-refresh="mesh.canRefresh.value" @refresh="refreshAll" />

    <!-- 不可用 kind 的一行说明（不反复报错，但用户得知道缺了什么） -->
    <el-alert v-if="unavailable.length" type="info" :closable="false" class="kind-alert"
      :title="`以下类型当前不可用（CRD 未安装或读取失败）：${unavailable.map((k) => kindMeta(k).label).join('、')}`" />

    <EmptyState v-if="ready && !filtered.length && !loading"
      title="该命名空间下暂无路由策略"
      description="路由策略含 HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute 五种（gateway.networking.k8s.io CRD）。" />

    <div v-else class="panel table-panel">
      <div class="filter-row">
        <span class="muted">类型</span>
        <el-select v-model="kindFilter" style="width: 180px">
          <el-option label="全部" value="all" />
          <el-option v-for="k in permitted" :key="k.key" :label="k.label" :value="k.key" />
        </el-select>
      </div>
      <el-table v-loading="loading || !ready" :data="filtered" stripe>
        <el-table-column label="类型" width="130">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ kindMeta(row._kind).label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><code class="res-name name-link" @click="openDetail(row)">{{ row.name }}</code></template>
        </el-table-column>
        <el-table-column label="父资源" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="(p, i) in (row.parentRefs ?? [])" :key="i" size="small" type="info" effect="plain" class="tag-inline">{{ parentText(p) }}</el-tag>
            <span v-if="!(row.parentRefs ?? []).length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="240">
          <template #default="{ row }"><span class="mono">{{ summaryText(row) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ fmtDate(row.creationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click">
              <el-button link><el-icon><MoreFilled /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="openDetail(row, 'info')">查看</el-dropdown-item>
                  <el-dropdown-item @click="openDetail(row, 'yaml')">YAML</el-dropdown-item>
                  <el-dropdown-item @click="goEdit(row)">编辑</el-dropdown-item>
                  <el-dropdown-item divided style="color: var(--el-color-danger)" @click="onDelete(row)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 详情抽屉：一份，按 kind 渲染 -->
    <el-drawer v-model="drawerVisible" :title="detail ? `路由策略 · ${kindMeta(detail._kind).label} · ${detail.name}` : ''" size="720px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="概览" name="info">
          <el-descriptions :column="1" border>
            <el-descriptions-item v-if="detail && kindMeta(detail._kind).usesHostnames" label="主机名 / SNI">
              {{ rowHostnames(detail).join(', ') || '全部主机名' }}
            </el-descriptions-item>
            <el-descriptions-item label="父资源">{{ (detail?.parentRefs ?? []).map(parentText).join(', ') || '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtDate(detail?.creationTime) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-title">规则</div>
          <el-table :data="(detail?.rules ?? []) as unknown[]" stripe size="small">
            <el-table-column label="#" type="index" width="50" />
            <el-table-column label="摘要" min-width="480">
              <template #default="{ $index }"><span class="mono">{{ detail ? ruleText(detail, $index) : '' }}</span></template>
            </el-table-column>
          </el-table>

          <div class="section-title">父资源状态</div>
          <el-table :data="detail?.parentStatuses ?? []" stripe size="small">
            <el-table-column label="父资源" min-width="160">
              <template #default="{ row }">{{ row.parentRef ? parentText(row.parentRef) : '—' }}</template>
            </el-table-column>
            <el-table-column prop="controllerName" label="控制器" min-width="200" />
            <el-table-column label="条件" min-width="180">
              <template #default="{ row }">
                <el-tag v-for="c in (row.conditions ?? [])" :key="c.type" size="small"
                  :type="c.status === 'True' ? 'success' : 'danger'" class="tag-inline">{{ c.type }}: {{ c.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="YAML（只读）" name="yaml">
          <YamlBlock v-if="yamlText" :yaml="yamlText" />
          <div v-else class="muted">加载中…</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>
```

样式块：把旧列表页的 `<style scoped>` 整体搬过来（`.table-panel` / `.panel` / `.res-name` / `.name-link` / `.mono` / `.muted` / `.tag-inline` / `.section-title`），另加：

```css
.filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.kind-alert { margin-bottom: 12px; }
```

- [ ] **Step 2: 路由 + 菜单**

`router/index.ts`：
- **新增**：`{ path: 'routes', name: 'routes', component: () => import('@/views/resource/RouteListView.vue'), meta: { title: '路由策略', group: '服务网格', context: 'full', requiresPerm: [pageCodes.mesh.httpRoute.list, pageCodes.mesh.grpcRoute.list, pageCodes.mesh.tcpRoute.list, pageCodes.mesh.tlsRoute.list, pageCodes.mesh.udpRoute.list] } }`
- **删除** 5 条旧列表记录（`httproutes` / `grpcroutes` / `tcproutes` / `tlsroutes` / `udproutes`）。

`layouts/MainLayout.vue`：把 mesh 组里那 5 个 `el-menu-item`（`/resources/httproutes` … `/resources/udproutes`）换成 1 项：

```vue
<el-menu-item v-if="can(pageCodes.mesh.httpRoute.list) || can(pageCodes.mesh.grpcRoute.list) || can(pageCodes.mesh.tcpRoute.list) || can(pageCodes.mesh.tlsRoute.list) || can(pageCodes.mesh.udpRoute.list)" index="/routes">
  <el-icon><Share /></el-icon>
  <template #title>路由策略</template>
</el-menu-item>
```

组标题那行的 `v-if` 已经含这 5 个码（`MainLayout.vue` 第 284-285 行），保持不动。

- [ ] **Step 3: 删旧列表页**

```bash
git rm platform-web/src/views/resource/HttpRouteView.vue \
       platform-web/src/views/resource/GrpcRouteView.vue \
       platform-web/src/views/resource/TcpRouteView.vue \
       platform-web/src/views/resource/TlsRouteView.vue \
       platform-web/src/views/resource/UdpRouteView.vue
grep -rn "resources/httproutes\|resources/grpcroutes\|resources/tcproutes\|resources/tlsroutes\|resources/udproutes" platform-web/src || echo "无引用"
```

预期：`无引用`（若有，逐条改到 `/routes`）。

- [ ] **Step 4: 类型检查**

```bash
npm --prefix platform-web run build
```

预期 `✓ built`。

- [ ] **Step 5: 提交**

```bash
git add -A platform-web/src/views/resource platform-web/src/router/index.ts platform-web/src/layouts/MainLayout.vue
git commit -m "feat(web): 路由策略列表页；菜单 5 项并 1 项；删除 5 个旧列表页与路由"
```

---

### Task 5: 端到端浏览器实测 + 落地记录

**Files:**
- Modify: 本计划文件（追加「落地记录」一节）
- Create（临时，收尾删除）: `platform-web/__scratch.html`

**Interfaces:**
- Consumes：Task 1–4 的全部产物。

- [ ] **Step 1: 起宿主页**

```bash
printf '<!doctype html>\n<html><head><meta charset="utf-8"><title>scratch</title></head><body><div id="scratch-root"></div></body></html>\n' > platform-web/__scratch.html
```

`preview_start` 起 dev server（`.claude/launch.json` 已有 `main-dev-check`，端口 5199），`preview_eval` 里 `window.location.href = '/__scratch.html'`。

- [ ] **Step 2: 逐条跑断言并记录证据**

每条记「怎么造的数据 / 看到什么 / 在哪看到的」；不允许只写"通过"。

| # | 断言 | 造数方式 |
|---|---|---|
| 1 | 列表出现 5 种 kind 的行；筛选切到单 kind 只剩该 kind | 桩掉 5 个 api 各回 1 条 |
| 2 | **权限短路**：只给 `tenant:tcproute:list` → 筛选器只有 TCPRoute，且只发出 1 个 list 请求 | 桩掉 `usePermission().has`（或桩 permission store）后数调用 |
| 3 | **失败隔离**：让 httproute 的 list 抛错 → 该 kind 进"不可用"提示条、其余照常显示；再点刷新不会重复炸提示（不可用集合已生效） | 桩 api 抛错 |
| 4 | 编辑器：`?kind=tcproute` 无 hostnames 行、无 matches；切到 `grpcroute` → 出现 hostnames 行与 matches/filters、rules 清空为一条空白规则 | 直挂 `RouteEditorView` |
| 5 | 保存载荷按 kind 正确：HTTP 带 `rules[].matches[].path`、TCP 只有 `rules[].backendRefs`（且 `rules` 长度为 1） | 桩 `create/update` 抓 body |
| 6 | 非法 `?kind=xxx`：列表回落「全部」；编辑器回落 `httproute` 且类型选择器**可改**；合法 `?kind=tcproute` 时选择器**锁定** | 手输 URL |
| 7 | 父引用块四行联动仍在（kind/命名空间/名称/段名 + Service 时 `group: null` + 未建模 kind 只读提示） | 复用上一批的夹具 |
| 8 | 菜单只剩 1 项「路由策略」；无任何 list 权限时不出现该项 | 桩 permission store |

- [ ] **Step 3: 无 CRD 的降级说明**

断言 3 用桩造的 404 已覆盖；若环境里恰好有只装了部分 CRD 的集群，补一次真机确认（并在记录里写明是桩还是真机）。

- [ ] **Step 4: 删宿主页 + 写落地记录 + 提交**

```bash
rm -f platform-web/__scratch.html
```

落地记录追加到本文件末尾：实际改动文件、与计划的偏差（含原因）、8 条断言的实跑结果、未验证项（如实列出）。

```bash
git add docs/superpowers/plans/2026-10-14-route-policy.md
git commit -m "docs(plan): 路由策略落地记录"
```

---

## 自查（作者已核）

- **spec 覆盖**：§3 入口→Task 4 Step 2；§4.1 拉取与短路→Task 1 码表 + Task 4 Step 1；§4.2 失败隔离→Task 4 `unavailable`；§4.3 表格/筛选→Task 4 模板；§4.4 抽屉/ YAML→Task 4 模板；§4.5 创建入口→Task 4 `creatable`；§4.6 类型与判别字段→Task 1；§5 编辑器→Task 2/3；§6 权限→Task 1 + Task 2 Step 3（`canSave`）；§7 数据流→Task 2/4；§8 降级→Task 4 + Task 5 断言 6；§9 验收→Task 5；§10 部署→无迁移，随前端构建。
- **无占位**：每个代码步都给了可粘贴内容或精确搬迁清单（搬迁对象是仓库里已存在的块，点名到可定位的锚点/函数名）。
- **命名一致**：`ROUTE_KINDS` / `kindMeta` / `parseRouteKind` / `RouteKind` / `RouteRow` / `RouteCtx` / `RouteKindMeta` / `_kind` / `permitted` / `unavailable` / `creatable` / `filtered` / `kindFilter` / `formEl` / `setRules` / `canSave` / `kindLocked` / `resetKindModels` / `summaryText` / `parentText` / `ruleText` / `openDetail` / `loadYaml` / `refreshAll` 全文一致。
- **Review Focus 逐条有落点**：见文首六条各自的任务指向。
- **已知偏离 spec 的两处（理由已写在各处）**：①任务顺序（删旧文件挪到替换物完成之后，保持每个任务结束时应用可用）；②子组件契约不统一为 `rules: unknown[]`（HTTP/GRPC/TLS 用 `v-model:rules`、TCP/UDP 用 `v-model:rule`，理由是各分支静态 `v-if`、类型独立、搬迁改动最小），并为此新增 `setRules` 让外壳零 rules 知识。

---

## 落地记录（2026-10-14）

**提交**（main 上顺序落地）：`93a7b82` 静态目录+能力码 → `15f95ff` 编辑器外壳+HTTP/GRPC 子组件 → `891fb57` TCP/TLS/UDP 子组件+删旧编辑器 → `df80f9b` 列表页+菜单/路由收敛+删旧列表 → `b297ee8` 回填修复（回归验证发现）。

**实际改动**

| 文件 | 改动 |
|---|---|
| `platform-web/src/utils/routeKinds.ts` | 新增：5 种 kind 的静态目录（码 / usesHostnames / api 函数 / 摘要 / 删除提示）+ `parseRouteKind` |
| `platform-web/src/apiCodes.ts` | +15 个常量（5 kind × list/create/update） |
| `platform-web/src/components/route/{Http,Grpc,Tcp,Tls,Udp}RouteForm.vue` | 新增：从 5 个旧编辑器**整体搬迁**的规则表单（脚本抽取，不重写） |
| `platform-web/src/views/resource/RouteEditorView.vue` | 新增：编辑器外壳（类型选择器/路由名/父资源块/hostnames/校验编排/回填/提交） |
| `platform-web/src/views/resource/RouteListView.vue` | 新增：路由策略列表（按权限逐 kind 拉 / 失败隔离 / 筛选 / 抽屉（概览+YAML）/ 创建下拉） |
| `platform-web/src/router/index.ts` | +`/routes`、`/routes/editor`；删 10 条旧记录 |
| `platform-web/src/layouts/MainLayout.vue` | 5 个菜单项 → 1 项「路由策略」 |
| 删除 | 10 个旧页面：`views/resource/{Http,Grpc,Tcp,Tls,Udp}Route{View,EditorView}.vue` |

**执行中的裁决（与计划的偏差，逐条含原因）**

1. **T2 的计划缺陷**：计划的外壳写了 5 个 `v-if` 分支但 T2 只造 2 个子组件（build 必红）→ 改为 T2 只接 2 个分支并用局部 `IMPLEMENTED` 驱动选择器，T3 补齐后删除该常量。
2. **子组件契约**：计划写 `v-model:rules` + `props.namespace`；实现改为**只有三个方法**（`setRules` / `toDto` / `validate`），外壳**不持有任何 rules 状态** —— 模型类型是各 kind 自己的 `Row` 类型，让它跨边界会逼外壳认识每种 kind 的模型；`namespace` 在规则里用不到。切 kind 靠 `v-if` 销毁重建，状态自然重置。
3. **T3 过渡**：删掉旧编辑器后，5 个旧列表页（T4 才删）的创建/编辑会指向不存在的路由 → 同一任务里先把它们指到 `route-editor?kind=<kind>`，保证每个任务结束时应用可用。
4. **TLS 的 hostnames 校验**：原用 `SNI_RE`（文案「SNI 名格式不合法」），收敛后 hostnames 归外壳统一用 `HOSTNAME_RE`（文案「主机名格式不合法」）—— 两个正则逐字相同，只是文案统一。
5. **规则回填的时序 bug（本批自造、由回归验证逮到）**：规则表单在 `<el-form v-if="formVisible">` 里，而 `formVisible` 依赖 `detailState==='loaded'`；置 loaded 之前子组件还没挂上，`formEl` 是 null → `setRules` 被 `?.` 静默跳过 → **打开既有路由时规则整条为空**（父资源与主机名照常回填，肉眼不易察觉；T2/T3 的冒烟只读标签与选择器显示值，选择器为空时显示的是 placeholder，把问题掩盖了）。修法：先置 loaded，`await nextTick()` 让表单挂上，再回填（`b297ee8`）。

**验证证据**

`npm --prefix platform-web run build`：每步后均通过（最终 2586 modules，exit 0，vue-tsc 无错）。
浏览器（dev server 5199，宿主页 `/__scratch.html?t=<ts>` 直挂 SFC + 桩掉 api/store，逐条实跑）：

| # | 结果 |
|---|---|
| 1 | ✅ 列表页 5 个 kind 的 list 全被调用、4 行渲染（TCPRoute 桩抛错被隔离）、摘要按 kind 不同（`1 条规则 · a.com` / `SNI t.com` / `svc:80`）、类型筛选切到单 kind 只剩该 kind |
| 2 | ✅ **权限短路**：me 只给 `tenant:tcproute:list` → `calls: ["tcproute"]`（只 1 个请求）、筛选器只有「全部/TCPRoute」、仅 1 行 |
| 3 | ✅ **失败隔离**：TCPRoute 的 list 抛错 → 该 kind 进「以下类型当前不可用…」提示条，其余 4 种照常显示 |
| 4 | ✅ **切 kind 清值**：新建态在 HTTP 的路径值填 `/tmp` → 切 TCP 后无残留 → 切回 HTTP 显示默认 `/` |
| 5 | ✅ **保存载荷按 kind**：HTTP `rules[0].matches[0].path={PathPrefix,"/api"}` + backendRefs + hostnames；TCP `rules[0]={name,backendRefs}` 且**无 `matches` 键** |
| 6 | ✅ **非法 kind**：`?kind=bogus` → 列表回落「全部」、编辑器回落 HTTPRoute 且类型选择器**不禁用**；`?kind=tcproute&name=tr` → 选择器**禁用**（`is-disabled` 探针命中） |
| 7 | ✅ **父引用块**：Service 父引用（名称行「Service」/段名行「端口名（sectionName）」/端口候选项 `http（80/TCP）`）与未建模 `ServiceImport`（原 kind + 「段名（sectionName）」+ port 与未建模两条只读提示）；保存载荷 `group:""`+`kind:Service` / `group:"multicluster.x-k8s.io"`+`kind:ServiceImport`+`port:80` 全部保真 |
| 8 | ⚠️ **源级核对**（未渲染）：菜单 `index="/routes"` 恰好 1 项、标题「路由策略」、旧 5 项 0 项；router 只剩 `routes`/`routes/editor`，旧 10 条 0 条 |

**验证方法上的经验（写下来免得下次再踩）**

- 必须先在 `localStorage` 写 `platform_access_token`（假的即可），否则 `permission.load()` 走 `refreshPermission()` 把权限置空 → 列表页不发请求、创建按钮消失，表现为"页面空但没报错"。
- `usePermission()` **每次返回新对象**，改它身上的 `has` 无效；要桩 `userApi.me` 再走真实 `load()`（`has` 读的是模块级 `state.permissions`）。
- `/context` 的桩必须返回真实租户树，否则 store 的级联校验会清空上下文（`ready=false`）。
- **模块实例**：有 HMR 时 SFC 的 import 带 `?t=`，`fetch` 拿到的旧产物对不上 → 要么从 SFC 的转换产物里取 URL，要么重启 dev server 后用 plain import。
- EP 的 `.el-select__selected-item` 在**未选中时显示的是 placeholder** —— 只读它会把"空的"看成"回填成功"（本批就是这么漏过一次）。

**未验证**

- 断言 8 是**源级**核对，没有真的渲染菜单（菜单需要完整 app 外壳 + 权限引导；本批 5 个旧页面已删、入口唯一）。
- 列表/编辑器的数据全部来自桩：**没有对真实后端 + 真实集群**跑过一遍（本批纯前端改动，后端零改动；真实环境下的 5 种 CRD 是否齐全、YAML tab 的取数等仍需上线后抽查）。
- 未做：真实渲染下的 `?kind=` 深链（如从浏览器地址栏直接进 `/routes/editor?kind=tlsroute`）——已验的是同一路径通过直挂 + query 进入。

**留待后续（未修）**

- `MainLayout.vue:283` 的注释仍写「Gateway / HTTPRoute = 租户资源面」（本批已收敛为「路由策略」）。
- 未建模 kind 的段名行 placeholder 仍是 Gateway 措辞（「留空 = 该 Gateway 上全部匹配的监听器」），标签已正确显示「段名（sectionName）」。

## 整支复审与修复轮（2026-10-14，fresh reviewer / opus）

复审范围＝`fd8de2f..ca0eb1d` 的 6 个提交（`platform-web/src`）。结论：**需修复后合并** —— 2 个 Important + 8 个 Minor。Review Focus 六条：3（非法 `?kind=`）、5（未建模字段透传，搬迁后逐区域 diff 为空）、6（父引用四行，归一化后与旧文件 diff 为空）**查过没问题**；1/2/4 的主判据通过但各有一处不达标。

**已修（提交 `c49f686`，7 处；每条都先拿到 RED 再验 GREEN）**

| # | 问题 | RED 证据 | 修法 |
|---|---|---|---|
| F1 | 筛到「该类型 0 行」时空态顶掉筛选行 → 回不到「全部」，且文案误导 | `?kind=grpcroute` 且该 kind 0 行 → `.filter-row` 消失 | 筛选行移出空态；空态文案分三种（无读权限 / 该类型无行 / 该命名空间真没有） |
| F2 | 抽屉里点 YAML 标签永不加载（合并时丢了旧 5 页都有的 `watch(detailTab)`） | `getYaml` 调用数 0、面板停在「加载中…」 | 补回该 watch（带 `!yamlText` 幂等守卫） |
| F3 | 切 kind 不清 `hostnames`（spec §5.3 明文；rules 由子组件重建已清，hostnames 归外壳会跨 kind 存活） | 新建 HTTP 填 `a.com` → 切 TLSRoute 后 SNI 框仍是 `a.com` | 类型选择器挂 `@change` 清 `form.hostnames`（与父引用块同样只挂 `@change`） |
| F4 | 类型选择器未按 create 码过滤（spec §5.1） | 只给 `tcproute:create` 时下拉仍 5 项 | 只列有 create 码的 kind；新建态默认落到第一个可创建的 |
| F5 | 筛选态下的「创建 X」按钮不看 create 码 | 只给 `tcproute:list` 仍出现「创建 TCPRoute」 | 按钮与下拉同口径（无码则不出现） |
| F6 | 失败 kind 在筛选器里仍可选、无标注（spec §4.2） | 提示条说不可用，但下拉里正常可选 | 标「（未安装）」+ `disabled` |
| F7 | 换命名空间重置「不可用」→ 反复炸提示（spec §4.2「不反复报错」） | 切命名空间后重新发起失败的 kind 的 list | CRD 是否安装是**集群**属性：只在换集群/点刷新时重置 |

**留待后续（未修）**：F8 抽屉规则表只剩摘要（旧页有「匹配 → 后端 / 过滤器数」明细，想看明细要去 YAML tab）；F9 `pageTitle` 成了死代码（页头用静态标题）；Cosmetic：未建模 filter 的告警块从表单顶部挪到了中部、`MainLayout.vue:283` 的注释仍写「Gateway / HTTPRoute = 租户资源面」。

**验证方法上的补充经验**：手动 `remove()` EP 的 popper 节点会破坏其内部引用（之后该 select 打不开）；取下拉必须按"可见 + 项集合匹配目标下拉"来挑（本批用"项都在 5 个 kind 标签内"把类型下拉与列表页遗留的筛选下拉区分开）。修复后复跑 `npm --prefix platform-web run build` → 2586 modules、exit 0。
