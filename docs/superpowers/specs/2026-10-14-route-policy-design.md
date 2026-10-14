# 路由策略（Route 五合一）· 设计（2026-10-14）

- **目标读者**：本批实施者（本文件是 spec；实施计划见后续 `writing-plans` 产出的 plan）
- **上游讨论**：2026-10-14 会话内评审（列表形态 / 编辑器形态 / 权限码 / 旧入口，四项逐项确认）
- **相关**：B6 服务网格（`docs/superpowers/specs/2026-09-15-service-mesh-gateway-api-design.md`）、父引用跨 kind 支持（`docs/superpowers/specs/2026-10-14-route-parentrefs-design.md`）、`docs/development/frontend-permission-conventions.md`（§1 码表分工 / §8 新增页面 checklist）、`docs/development/backend-layering.md`

## 0. 一句话

前端把 HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute 五种 route 收敛成**一个功能「路由策略」**：一个列表页（类型列 + 类型筛选）与一个编辑器（外壳 + 5 个 kind 子组件），菜单 5 项并为 1 项，**旧的 10 条路径与 10 个旧视图文件全部删除**（不保留重定向）。**后端零改动**（端点 / 权限行 / DTO 都不动）。

## 1. 目标 / 非目标

**目标**

1. 一个列表页看全部 5 种 route：类型列 + 类型筛选（默认「全部」），按 kind 隔离失败。
2. 一个编辑器按 kind 编辑/新建 5 种 route：同形部分（路由名、父资源、hostnames 开关、保存编排）只写一份。
3. 入口收敛：菜单「服务网格」组内 5 项 → 1 项；**旧的 10 条路由记录与 10 个旧视图文件直接删除**（不做兼容重定向）。
4. 权限语义不退化：能进页面 ≠ 能操作每个 kind —— 无权限的 kind 不出现、不发请求、保存按钮置灰。
5. 零迁移、零角色绑定变更、零后端改动（存量自定义角色不受影响）。

**非目标（本批不做）**

- 后端改动：无新端点、无新权限行、无新 DTO 字段、无 Flyway 迁移。
- **不合并 5 个 wire 类型**（`K8sHttpRoute`/`K8sGrpcRoute`/… 原样保留；列表内部靠外层判别字段）。
- 不抽 5 个 kind 子组件的公共基类 / mixin（差异留在各自文件里；外壳只共享真正同形的部分）。
- Gateway / GatewayClass 页面不动（它们是另一类资源，`allowedRoutes` / `gatewayClassName` 的语义与 route 无关）。
- 不加跨 kind 的聚合端点（前端并发拉 5 个既有 list 即可）。
- 不改任何列表列 / 抽屉内容的**语义**（只改承载方式）。

## 2. 现状与先例

| 项 | 现状 |
|---|---|
| 列表页 | 5 个近乎同形，各 ~260 行：`PageHeader`（刷新 / 创建）→ `MeshStatusBanner` → `EmptyState` 或 `el-table` → `el-drawer`(720px，tabs：概览 + YAML（只读 `YamlBlock`）） |
| 编辑器 | 5 个共 3235 行（Http 943 / Grpc 806 / Tls 528 / Tcp 479 / Udp 479），骨架相同（名称 + 父资源 + [hostnames] + 规则），差异在规则内部；**编辑器页没有 YAML tab**（YAML 只在列表页抽屉里） |
| 路由 | `router/index.ts` 10 条（5 列表 + 5 编辑器），均 `group: '服务网格'`、`context: 'full'`、`requiresPerm: [pageCodes.mesh.<x>Route.{list,edit}]` |
| 菜单 | `layouts/MainLayout.vue` 「服务网格」组内 5 项（+ GatewayClass / Gateway），组标题 `v-if` 用 5 个 list 码相或 |
| 权限 | 页面码 10 个（`tenant:page:<kind>.list/.edit`），各有 DB Page 行（`V2026_10_08_4` / `V2026_10_08_5`）+ 内置角色绑定；API 码每 kind 6 个：`tenant:<kind>route:{list,get,yaml,create,update,delete}` |
| 列表列 | HTTP/GRPC：名称 主机名 父资源 规则数 创建时间；TLS：名称 父资源 SNI 主机名 后端 状态 创建时间；TCP/UDP：名称 父资源 后端 状态 创建时间 |

**先例（本批照它做）**：`WorkloadView` + `WorkloadEditorView` 已经就是"一个页面管 3 种 kind" —— 列表用 `kindFilter`（默认 `'all'`）+ 类型列 + 按 kind 分支的行操作；编辑器用类型下拉（编辑时禁用）+ 按 kind 条件渲染 + **切 kind 清专属字段**。本批把这套形态从 3 种扩到 5 种。

## 3. 入口：路由与菜单

### 3.1 新增两条

| path | name | title | meta |
|---|---|---|---|
| `/routes` | `routes` | 路由策略 | `group: '服务网格'`、`context: 'full'`、`requiresPerm: [pageCodes.mesh.httpRoute.list, …grpcRoute.list, …tcpRoute.list, …tlsRoute.list, …udpRoute.list]` |
| `/routes/editor` | `route-editor` | 路由策略 编辑 | 同上但用 5 个 `…edit` 码 |

`requiresPerm` 的判定是**任一命中**（`perm.hasAny`，见 `router/index.ts` 的 guard 与 `docs/development/frontend-permission-conventions.md` §1），所以"5 个码传进一个数组"就是"能看其中任意一种 route 就能进这一页"。

### 3.2 旧路径与旧视图：**全删**

删除 `router/index.ts` 里这 10 条记录与对应的 10 个视图文件：

| 删除的 path | 删除的文件 |
|---|---|
| `/resources/httproutes`、`/resources/httproutes/editor` | `views/resource/HttpRouteView.vue`、`HttpRouteEditorView.vue` |
| `/resources/grpcroutes[/editor]` | `GrpcRouteView.vue`、`GrpcRouteEditorView.vue` |
| `/resources/tcproutes[/editor]` | `TcpRouteView.vue`、`TcpRouteEditorView.vue` |
| `/resources/tlsroutes[/editor]` | `TlsRouteView.vue`、`TlsRouteEditorView.vue` |
| `/resources/udproutes[/editor]` | `UdpRouteView.vue`、`UdpRouteEditorView.vue` |

**不做重定向**：访问旧路径会落到仓库既有的 catch-all（`{ path: '/:pathMatch(.*)*', redirect: '/' }` → 总览）。理由：这批改动之后旧路径没有任何独有内容，重定向只是把一份死代码留在 router 里。

**但 10 个 Page 码必须保留**（`tenant:page:<kind>.list/.edit` 的 DB 行、内置角色绑定、`pageCodes.ts` 常量）：它们是**新页面的门票** —— §3.1 的 `requiresPerm` 与菜单 `v-if` 正是用这 5 个 list 码（任一）判定准入，删掉行或绑定，新页面就会对所有人关闭（权限闭包取不到这些码）。同理 5 个 edit 码是编辑器页的门票。它们指向旧路径的文字描述（Page 行的 `resource` / `name` 列）本批**不改**（改它要迁移，且这属于"码还在用、只是描述过时"，见 §12）。

`?kind=` 的取值是 5 个 kind 的小写名（`httproute` / `grpcroute` / `tcproute` / `tlsroute` / `udproute`），是本批新增的**前端路由参数约定**（类型定义见 §4.6）；非法值一律回落（§8）。

### 3.3 菜单

- 「服务网格」组内 5 项 → **1 项**：`index="/routes"`、标题「路由策略」、图标沿用现 HTTPRoute 那枚（`Share`）。
- 该项与组标题的 `v-if` 都用"5 个 list 码相或"，与 §3.1 的 `requiresPerm` 同源（约定：菜单与路由必须同码，否则会出现"有标题没条目"的空分组）。

## 4. 列表页 `RouteListView.vue`

### 4.1 拉取与权限短路

```ts
/** 5 种 kind 的静态目录（utils/routeKinds.ts）：路由参数值 / 显示名 / 能力码 / api 客户端 / 是否有 hostnames */
const KINDS = [
  { key: 'httproute', label: 'HTTPRoute', usesHostnames: true,
    listCode: apiCodes.httpRouteList, createCode: apiCodes.httpRouteCreate, updateCode: apiCodes.httpRouteUpdate },
  // grpcroute(usesHostnames: true) / tcproute(false) / tlsroute(true) / udproute(false) 同形
] as const
```

**码表分工**（`docs/development/frontend-permission-conventions.md` §1）：**页面可见性用 `pageCodes`**（§3.1 的 `requiresPerm`、菜单 `v-if`），**能力短路用 `apiCodes`** —— 本页的"这个 kind 的拉取会不会被后端拒"属于后者，所以 §4.1 的短路、§4.5 的创建下拉、§5.1 的保存置灰一律用 `apiCodes`（本批新增，见 §6）。

- 只对 **`perm.has(k.listCode)` 为真**的 kind 发请求；无权限的 kind **不进筛选器、不进表格**（`usePermission().has` 是现成的同步判定，见 `stores/permission.ts`）。
- 并发用 **`Promise.allSettled`**，每个 kind 单独记录 `loading / error`。
- 页面级 loading 与"一个 kind 都没有"分开表达（无任何 list 权限时给一句话说明，而不是空表格）。

### 4.2 失败隔离（本页最重要的一条降级）

单个 kind 失败**不得**影响其他 kind，且要说清是哪一类失败：

| 情形 | 表现 |
|---|---|
| 集群没装该 CRD（后端透传 404） | 该 kind 在筛选器中标为「未安装」且不可选；表格不显示其行；给一条可关闭的提示条（例如"TCPRoute CRD 未安装，已跳过"） |
| 无该 kind 读权限（403） | §4.1 已短路，不会发请求 |
| 集群整体不可达 | 所有 kind 一起失败 → 页级错误提示 + 刷新按钮（与现有 5 页同样的兜底） |

### 4.3 表格与筛选

- 筛选：`el-select`（默认「全部」），选项 = 有权限的 kind（+「全部」）；与 `WorkloadView` 的 `kindFilter` 同形。
- 列：**类型 | 名称 | 父资源 | 摘要 | 创建时间 | 操作**。
  - 类型：`el-tag`（各 kind 一个色/文案）。
  - 名称：可点（打开抽屉），沿用 `.name-link`。
  - 父资源：沿用父引用跨 kind 那批做好的 `parentText(p)`（`kind ns/name[:sectionName|:port]`）。
  - **摘要（单列，kind 相关文本）**：HTTP/GRPC = `N 条规则 · <hostname 或"全部主机名">`；TLS = `SNI <sniHostname 或"全部">`；TCP/UDP = `→ <backend:port 列表>`。**理由**：合并后的表若按 kind 条件渲染各自列，切换筛选时列会跳动；一句话摘要更稳。
  - 操作：编辑 / YAML（打开抽屉的 YAML tab）/ 详情（打开抽屉的概览 tab）/ 删除（按 kind 的 delete 码置灰，二次确认文案带类型）。
- 排序：不新增（沿用后端返回顺序）。

### 4.4 详情抽屉（一份，按 kind 渲染）

`el-drawer`（720px，标题 `路由策略 · <KIND> · <name>`）+ `el-tabs`：

- **概览**：`el-descriptions`（各 kind 共有的：父资源、创建时间；kind 相关的：主机名 / SNI 主机名 / 后端数）→ 「规则」表（HTTP/GRPC 显示 匹配→后端 + 过滤器数；TLS/TCP/UDP 显示 后端）→ 「父资源状态」表（5 kind 同形：父资源 / 控制器 / 条件）。
- **YAML（只读）**：`YamlBlock`，内容按 kind 走对应 `getYaml`。

这两块现在是 5 份复制，收敛成 1 份是本批的主要收益。

### 4.5 创建入口

`PageHeader` 右侧：

- 筛选 = 「全部」→ 一个 `el-dropdown`「创建」，菜单项 = **`apiCodes.<kind>Create` 为真**的 kind（无权限的不列）。
- 筛选 = 某一 kind → 按钮文案变「创建 HTTPRoute」并直接进编辑器（`/routes/editor?kind=<key>`）。

### 4.6 类型与判别字段

```ts
export type RouteKind = 'httproute' | 'grpcroute' | 'tcproute' | 'tlsroute' | 'udproute'
export type RouteRow = (K8sHttpRoute | K8sGrpcRoute | K8sTcpRoute | K8sTlsRoute | K8sUdpRoute) & { _kind: RouteKind }
```

`_kind` 由**前端在拉取时打标**（`rows.push(...list.map((r) => ({ ...r, _kind: k.key })))`），**不依赖后端 DTO 是否回带 `kind`** —— 5 个 list 端点是各自独立的，前端本就知道每行来自哪个 kind，比信任一个可能不填的字段更可靠。
`RouteKind` 与 `KINDS` 目录是本批唯一的"新抽象"，放在 `platform-web/src/utils/routeKinds.ts`（纯数据 + 类型，无副作用，供列表页与编辑器共享）。

## 5. 编辑器：`RouteEditorView.vue`（外壳）+ 5 个子组件

### 5.1 外壳职责（只放真正同形的部分）

- **类型选择器**：`el-select`（5 项，按 create 权限过滤）；**编辑既有对象时禁用**（`kind` 不可改，与 `WorkloadEditorView` 同）。
- **路由名**：新建可填；编辑禁用（沿用现有 5 个编辑器的"名称创建后不可改"）。
- **父资源块**：父引用跨 kind 那批做的四行联动（类型 / 命名空间 / 名称 / 段名 + 只读提示 + 段名软校验）从 5 份**收成 1 份**放在外壳。
- **hostnames**：外壳渲染，但**是否显示取自 `routeKinds.ts` 的 `usesHostnames`**（HTTP/GRPC/TLS 是 `true`，TCP/UDP 是 `false`）—— 这个开关是**静态目录的一部分**，不是子组件的 props/expose（避免"外壳要读子组件实例才能决定渲染什么"这种反向依赖）。
- **保存 / 删除**按钮与提交编排、加载回填、错误提示。
- 外壳**不认识** rules 的内部结构。

### 5.2 子组件契约（`components/route/`）

```
components/route/HttpRouteForm.vue
components/route/GrpcRouteForm.vue
components/route/TcpRouteForm.vue
components/route/TlsRouteForm.vue
components/route/UdpRouteForm.vue
```

| 方向 | 内容 |
|---|---|
| props | `rules`（该 kind 的规则数组，类型各自定义）、`namespace: string`（候选/校验可能要用） |
| emits | `update:rules`（用 `defineModel<Rule[]>('rules')`） |
| expose | `validate(): string \| null`（返回错误文案，null = 通过） |

hostnames **不在契约里**：它由外壳持有并渲染（是否显示看 `routeKinds.ts` 的 `usesHostnames`），子组件不碰它。

外壳提交时组装 DTO：`{ name, namespace, kind, parentRefs, ...(usesHostnames ? { hostnames } : {}), rules }`（各 kind 的 DTO 字段名一致，仅 rules 的内部结构不同）。

### 5.3 切 kind

子组件用 **`:key="kind"`** 强制重建 —— 专属字段（HTTP 的 matches/filters、TLS 的 sniHostname…）随之自然清空，**不需要**手写"切 kind 清字段"的 watcher（那正是 `WorkloadEditorView` 里那段容易漏字段的代码）。切 kind 时一并清空 `rules` 与 `hostnames`（新 kind 的表单从空白开始）。

### 5.4 校验编排

`validate()` 顺序：外壳（路由名 RFC1123 → 至少一个父资源 → 父引用的 sectionName pattern）→ 子组件（该 kind 的 rules 校验，文案前缀带上类型，如「HTTPRoute：规则 #1 …」）。

### 5.5 回填与提交

- 回填：`route-editor?kind=X&name=Y` → 外壳按 X 选 api `get`，把 `rules` 交给子组件、`hidden` 字段透传（沿用现有 5 个编辑器各自的"UI 不编辑、原样回传"清单）。
- **透传字段的所有权**：现有 5 个编辑器里都有一份"未建模字段原样回传"的清单（filter 兄弟键、parentRefs 的 group/port 等）。收敛后由子组件各自维护自己的那份（HTTP/GRPC 在 rules/filters 里；TLS 在 matches 里），外壳只维护 parentRefs 那份。
- 提交：与现有 5 个编辑器逐字相同的 `create`/`update` 调用（`xxxRouteApi.create(ctx2, body)` / `update(name, ctx2, body)`），只是 kind 由选择器决定。

### 5.6 旧文件的处置

- 5 个 `*RouteEditorView.vue` 与 5 个 `*RouteView.vue` **删除**（内容分别并入外壳/子组件与列表页；git 历史留有原样）。它们的表单一节整体搬进 `components/route/*RouteForm.vue`，因此"搬"不是重写：字段、FieldHelp、校验规则、未建模字段的透传清单一并保留。
- `pageCodes.ts` 的 10 个页面码**保留不动**（理由见 §3.2：它们是新页面的门票）。

## 6. 权限与前端能力位

`apiCodes.ts` 增补 **15 个常量**（扁平风格，与既有 `gatewayList` 一致；只收"前端真用来判断"的）：

| 常量名（× 5 kind） | 值（示例，httproute） | 用途 |
|---|---|---|
| `httpRouteList` / `grpcRouteList` / `tcpRouteList` / `tlsRouteList` / `udpRouteList` | `tenant:httproute:list` | 逐 kind 拉取短路（§4.1） |
| `httpRouteCreate` / … | `tenant:httproute:create` | 创建下拉过滤（§4.5）、保存按钮置灰（新建，§5.1） |
| `httpRouteUpdate` / … | `tenant:httproute:update` | 编辑既有对象时保存按钮置灰（§5.1） |

**不加页面码**：页面可见性继续用 `pageCodes.mesh.<x>Route.{list,edit}`（§3.1），因此 §8 checklist 的第 1/2 步（建 Page 行、绑内置角色）本批**不需要**。

**不变量**：

1. 无 list 码的 kind：不进筛选器、不发请求、列表里没有它的行。
2. 无 create 码的 kind：创建下拉里不出现。
3. 无 update 码的 kind：编辑既有对象时保存按钮置灰 + tooltip 说明（**后端仍是权威**，前端只是不发注定被拒的请求）。
4. `tenant:xxxroute:yaml` / `get` / `delete` 本批**不短路**（沿用现状：由后端拒、拦截器提示）—— 若要一并做，属另一批。

## 7. 数据流

```
列表页  ── 按权限逐 kind list（allSettled）→ 打 _kind 标 → 合并 → 筛选 → 表格
   │
   ├─ 编辑 → /routes/editor?kind=<key>&name=<n>
   ├─ 创建 → /routes/editor?kind=<key>
   ├─ YAML → getYaml(kind, name, ctx3) → YamlBlock
   └─ 详情 → get(kind, name, ctx3) → 抽屉概览（规则表 + 父资源状态）

编辑器  ── 外壳：kind（新建可选/编辑锁定）→ 按 kind 选 api
   ├─ 回填：get → { name, parentRefs, hostnames?, rules }
   ├─ 子组件：v-model:rules（自己解析/序列化 rules）
   └─ 提交：validate(外壳) → validate(子组件) → create|update(kind)
```

## 8. 降级与失败模式

| 条件 | 期望行为 |
|---|---|
| 5 个 kind 全无 list 权限 | 页面给"无可用路由类型"说明（不是空表）；不发任何请求 |
| 集群只装了 HTTPRoute CRD | 筛选器只有 HTTPRoute（其余标"未安装"）；页面正常可用 |
| 某 kind `list` 突然 403（权限回收） | 该 kind 置空 + 提示；其他 kind 不受影响 |
| 编辑既有对象但无 update 码 | 表单可看（get 有权限时）、保存按钮置灰 + tooltip；提交被后端拒也不会发生 |
| 切 kind 后旧 kind 的表单残留 | 不允许（`:key` 重建，见 §5.3） |
| `?kind=` 非法值（手输 URL） | 回到「全部」（列表）/ 默认 kind 并禁用选择器（编辑器），不报错 |

## 9. 测试与验收

**构建**：`npm --prefix platform-web run build`（vue-tsc + vite）必须通过；本仓无前端单测框架，功能面靠浏览器实测。

**浏览器实测**（用**同源非 `/login` 宿主页**的办法跑直挂，避开 `LoginView` 挂载即跳 OAuth 的问题；上一次批次的教训）：

1. 列表页出现 5 个 kind 的行；类型筛选切到单 kind 只剩该 kind 的行。
2. **权限短路**：只给 `tenant:tcproute:list` → 筛选器只有 TCPRoute，且只发出 1 个 list 请求（桩住 api 数调用）。
3. **失败隔离**：让其中一个 kind 的 list 抛 404 → 该 kind 标"未安装"，其余 kind 正常显示。
4. 编辑器：`?kind=tcproute` 落到 TCP 表单（无 hostnames 行、无 matches）；切到 `grpcroute` 后出现 hostnames 行与 matches/filters、rules 清空为空白规则。
5. 保存载荷按 kind 正确（HTTP 带 `rules[].matches[].path`、TCP 只有 `rules[].backendRefs`；父引用块与父引用跨 kind 那批一致，含 `kind=Service` 时 `group: null`）。
6. **旧路径已不存在**：访问 `/resources/httproutes` 落到 catch-all → 总览（不报错、不白屏）；`/routes?kind=非法值` 回落「全部」。
7. 菜单「服务网格」组内只剩 1 个路由条目；无任何 list 权限时不出现该条目。
8. 抽屉概览与 YAML 按 kind 取数正确（HTTP 显示规则表、TCP 不显示过滤器列）。

## 10. 部署与回滚

- **无迁移、无权限行、无后端改动**：只发前端构建。
- 回滚 = 回滚前端静态资源：旧构建把 10 条旧路径带回来（旧页面恢复可用）；新路径 `/routes` 在旧构建里不存在 → 落 catch-all 到总览（不会白屏）。**注意**：回滚期间用户若把 `/routes` 存成书签，回滚后会失效 —— 这是"不保留重定向"的代价，已确认接受。
- 风险点：菜单入口消失（5 → 1）会让用户一时找不到 —— 上线时在「路由策略」页里保留类型筛选，并在页面描述里写明含 5 种类型。

## 11. 实施分期（建议，供 plan 采用）

1. **T1** `utils/routeKinds.ts` + `apiCodes.ts` 15 个常量 + 列表页 `RouteListView.vue`（权限短路 / 失败隔离 / 筛选 / 表格 / 抽屉 / YAML / 创建入口）+ 路由与菜单收敛（加新路径、**删 10 条旧路径**）+ 删 5 个旧列表页。
2. **T2** 编辑器外壳 `RouteEditorView.vue`（类型选择器 / 路由名 / 父资源块 / 校验编排 / 回填 / 提交）+ HTTP、GRPC 两个子组件（最复杂），路由指向外壳。
3. **T3** TCP、TLS、UDP 三个子组件；删 5 个旧编辑器页。
4. **T4** 端到端浏览器实测（§9）+ 落地记录。

分期理由：T1 独立可验（列表页自成一体）；T2 之后 3 个简单 kind 是机械复制，一次做完；T4 与上一批一样单独成任务（浏览器断言历史上是最容易漏的一环）。

## 12. 待确认（评审时否决即可，否则按此实施）

1. 列表**摘要列**用单列 kind 相关文本（§4.3），而不是按 kind 条件渲染各自列。
2. 类型筛选用 `el-select`（§4.3），不是顶部 `el-radio-group`。
3. 页面与菜单项名就叫**「路由策略」**；页面描述里点明含 5 种类型。
4. 旧 10 条路径**直接删除**（已确认；访问落 catch-all → 总览）；随之 10 个 Page 码的 `resource`/`name` 文字仍写着旧路径 —— 本批**不改**（改要迁移；码本身还在用作新页面的门票），若要一并改文字，另开一条迁移。
5. `parentRefs` 的 `parentText` 摘要继续用"完整 kind 前缀"（`Gateway ns/name:sec` / `Service ns/name:port`）—— 与上一批 spec §10 的默认一致。
