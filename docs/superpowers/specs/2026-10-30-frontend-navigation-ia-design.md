# 前端导航信息架构重构 · 设计

- 日期：2026-10-30
- 状态：形态已与用户逐轮确认，待评审
- 范围：`platform-web` 的顶栏、侧边栏、总览页，以及菜单的数据来源
- 不动：任何 URL、任何页面组件（`OverviewView` 除外）、任何后端接口

## 1. 背景

菜单是 `MainLayout.vue` 模板里手写的：**一层平铺、42 个菜单项、12 个分组**，全部同级。
问题不是"项太多"，是三条叠在一起：

1. **一层且不可折叠** —— 管理员最长的滚动要掠过全部 12 组才到底。
2. **同一层混用四套切分维度** —— 按对象（工作负载/配置管理/存储）、按生命周期（持续集成/应用交付）、
   按人（用户与权限）、按平台职责（平台管理/集群运维）、按能力（服务网格/可观测/监控告警）。
   用户找东西时无法预判"这事归哪个组"。
3. **分组重量与内容不匹配** —— 「服务发现」只有 Service 一项却占一个分组标题。

另外两个结构性问题：

- **菜单不跟随视图**。权限闭包已经按帽子收窄了（平台视图下租户页自动消失），但**分组顺序和标题是写死的**。
  管理员切进租户视图后仍能看到平台运维页（平台族码是无条件并入的），42 项原样铺开 ——
  这正是"重点凸显不出来"的来源。
- **加一个页面要改三处**：`router/index.ts`、`pageCodes.ts`、`MainLayout.vue` 的 `v-if` 模板。
  漏改会静默漂移（菜单有、路由无，或反之）。

总览页 `OverviewView.vue` 只服务管理员：3 张统计卡（集群 / 租户 / 命名空间分配）对租户成员全是 0，
4 个快捷入口有一半是管理员专属。租户成员进来看到的是一个空页。

## 2. 目标与非目标

**目标**

- 任意身份打开系统，侧边栏同时不超过 6 项，不滚动。
- 菜单结构跟随当前视图（平台视图 / 租户视图），而不是把两套权限拼成一张表。
- 找页面不靠"记住分组"：提供跨域的页面搜索（Ctrl/Cmd+K）。
- 总览回答"现在有什么需要我管"，而不是只数个数；两种视图各有一个版本。
- 菜单只有一个数据来源。加页面只改路由一处。

**非目标**

- 不改 URL、不加快捷键之外的键盘交互。
- 不做菜单自定义排序 / 收藏（最近访问已覆盖该需求）。
- 不做面包屑（顶栏域高亮 + 侧栏当前项已回答"我在哪"）。
- 不合并页面本体（如"中间件"与"中间件实例"仍为两页，见 §11）。

## 3. 规则

三条规则贯穿全文，后续所有判断都由它们推出：

**R1 域 = 对象领域，可见性 = 权限。**
一个页面只属于一个域，归域看"它在管什么东西"，不看"谁能进"。存储类归「存储」、
GatewayClass 归「网络」，只是因为非管理员持不到对应的页面码才看不见。

**R2 域内无可见项 → 整个域从顶栏消失。**
不显示禁用态的空白 tab。

**R3 两个视图各有一套域。**
视图由租户上下文决定（`perm.currentTenant == null` = 平台视图），与
`docs/development/frontend-permission-conventions.md` §1.5 一致。页面归属于哪一套（"面"）由
`meta.requiresPerm` 里页面码的族决定：

| `requiresPerm` 里的码 | 面 |
|---|---|
| 全部 `platform:` | 平台视图可见 |
| 全部 `tenant:` | 租户视图可见 |
| 两族都有 | 两个视图都可见（`/git-credentials`、`/mesh/traces`） |

**"面"不是新写死的清单，是从现有页面码推出来的**，所以它不会与权限漂移。

## 4. 域划分

### 4.1 平台视图（仅管理员，4 域 / 19 项）

| 域 key | 域 | 页面 |
|---|---|---|
| `cluster` | **集群** | 集群管理 · 节点管理 · 命名空间管理 · 存储类 |
| `network` | **网络** | GatewayClass · 地址池 · 保留 IP · BGP 配置 · BGP 对等体 · BGP 过滤器 |
| `org` | **组织** | 租户管理 · 用户管理 · 角色与权限 · 权限点 · RBAC 模板 |
| `ops` | **运维** | 审计日志 · 通知方式 · Git 凭据 · 链路追踪 |

### 4.2 租户视图（7 域 / 25 项）

| 域 key | 域 | 页面 |
|---|---|---|
| `app` | **应用** | 应用编排 · 中间件 · 中间件实例 |
| `build` | **交付** | 流水线 · Git 凭据 · 镜像仓库 |
| `resource` | **资源** | 工作负载 · Pod · HPA · ConfigMap · Secret |
| `storage` | **存储** | PVC · 持久卷 · 对象存储 |
| `network` | **网络** | Service · Gateway · 路由策略 · 网格拓扑 |
| `observe` | **观测** | 告警 · ServiceMonitor · PodMonitor · PrometheusRule · 日志查询 · 链路追踪 |
| `org` | **组织** | 我的租户 |

两套域合计覆盖全部 42 个菜单页，无遗漏、无重复归属。两个面都为 `both` 的页面
（Git 凭据、链路追踪）在平台视图里落到 `ops`，在租户视图里落到各自的域 —— 见 §8.1 的
`meta.platformDomain`。

### 4.3 几处刻意的处置

- **「服务发现」组解散**：只有 Service 一项，并入「网络」。Service 本来就是流量入口。
- **IPAM / BGP 归「网络」不归「集群」**：它们管的是集群的地址平面；且只在平台视图出现，
  不会与租户视图里的 Service / Gateway 撞车（同名域、不同内容，是 R3 的直接结果）。
- **ServiceMonitor / PodMonitor / PrometheusRule 留在「观测」**：它们是采集规则，不是资源本身。
  租户的心智是"我要看监控"，不是"我要建 CR"。
- **「我的租户」归「组织」**：与「租户管理」同域。租户用户在该域只看到 1 项，
  管理员看到 6 项（`tenant:page:tenant.self` 只有租户侧角色持有）。
- **「运维」域是平台视图的横切工具区**：审计、通知方式、Git 凭据、链路追踪四者都以
  "平台管理员单独使用"为主，不属于任何租户资源域。不单开「观测」域是因为平台视图下它只剩链路追踪一项（违反 R2 的精神）。

## 5. 页面归属总表

`nav` 列为最终是否出现在侧边栏；`hidden` 的页面通过列表页的行内操作或 URL 进入。

| 路由 | 菜单标题 | 面 | 域（租户视图 / 平台视图） | 页面码 | nav |
|---|---|---|---|---|---|
| `/overview` | 总览 | both | —（固定首位） | 无 | ✓ |
| `/clusters` | 集群管理 | platform | — / `cluster` | `pageCodes.cluster` | ✓ |
| `/clusters/detail` | 集群概览 | platform | — / `cluster` | `pageCodes.cluster` | hidden |
| `/nodes` | 节点管理 | platform | — / `cluster` | `pageCodes.node.list` | ✓ |
| `/nodes/detail` | 节点详情 | platform | — / `cluster` | `pageCodes.node.detail` | hidden |
| `/namespaces` | 命名空间管理 | platform | — / `cluster` | `pageCodes.namespace.list` | ✓ |
| `/namespaces/editor` | 命名空间编辑 | platform | — / `cluster` | `pageCodes.namespace.edit` | hidden |
| `/namespaces/detail` | 命名空间概览 | platform | — / `cluster` | `pageCodes.namespace.detail` | hidden |
| `/resources/storageclasses` | 存储类 | platform | — / `cluster` | `pageCodes.storageClass.list` | ✓ |
| `/mesh/gatewayclasses` | GatewayClass | platform | — / `network` | `pageCodes.mesh.gatewayClass.list` | ✓ |
| `/mesh/gatewayclasses/editor` | GatewayClass 编辑 | platform | — / `network` | `pageCodes.mesh.gatewayClass.edit` | hidden |
| `/ops/ippools` | 地址池 | platform | — / `network` | `pageCodes.ops.ippool.list` | ✓ |
| `/ops/ippools/detail` | 地址池详情 | platform | — / `network` | `pageCodes.ops.ippool.detail` | hidden |
| `/ops/ippools/editor` | 地址池编辑 | platform | — / `network` | `pageCodes.ops.ippool.edit` | hidden |
| `/ops/ipreservations` | 保留 IP | platform | — / `network` | `pageCodes.ops.ipReservation.list` | ✓ |
| `/ops/ipreservations/editor` | 保留 IP 编辑 | platform | — / `network` | `pageCodes.ops.ipReservation.edit` | hidden |
| `/ops/bgpconfigurations` | BGP 配置 | platform | — / `network` | `pageCodes.ops.bgpConfiguration.list` | ✓ |
| `/ops/bgpconfigurations/detail` | BGP 配置详情 | platform | — / `network` | `pageCodes.ops.bgpConfiguration.detail` | hidden |
| `/ops/bgpconfigurations/editor` | BGP 配置编辑 | platform | — / `network` | `pageCodes.ops.bgpConfiguration.edit` | hidden |
| `/ops/bgppeers` | BGP 对等体 | platform | — / `network` | `pageCodes.ops.bgpPeer.list` | ✓ |
| `/ops/bgppeers/detail` | BGP 对等体详情 | platform | — / `network` | `pageCodes.ops.bgpPeer.detail` | hidden |
| `/ops/bgppeers/editor` | BGP 对等体编辑 | platform | — / `network` | `pageCodes.ops.bgpPeer.edit` | hidden |
| `/ops/bgpfilters` | BGP 过滤器 | platform | — / `network` | `pageCodes.ops.bgpFilter.list` | ✓ |
| `/ops/bgpfilters/detail` | BGP 过滤器详情 | platform | — / `network` | `pageCodes.ops.bgpFilter.detail` | hidden |
| `/ops/bgpfilters/editor` | BGP 过滤器编辑 | platform | — / `network` | `pageCodes.ops.bgpFilter.edit` | hidden |
| `/tenants` | 租户管理 | platform | — / `org` | `pageCodes.tenant.list` | ✓ |
| `/users` | 用户管理 | platform | — / `org` | `pageCodes.user` | ✓ |
| `/roles` | 角色与权限 | platform | — / `org` | `pageCodes.role` | ✓ |
| `/permissions` | 权限点 | platform | — / `org` | `pageCodes.permission` | ✓ |
| `/templates` | RBAC 模板 | platform | — / `org` | `pageCodes.template` | ✓ |
| `/audit` | 审计日志 | platform | — / `ops` | `pageCodes.audit` | ✓ |
| `/notification-channels` | 通知方式 | platform | — / `ops` | `pageCodes.notifyChannel` | ✓ |
| `/git-credentials` | Git 凭据 | both | `build` / `ops` | `pageCodes.credential`, `pageCodes.credentialTenant` | ✓ |
| `/mesh/traces` | 链路追踪 | both | `observe` / `ops` | `pageCodes.mesh.trace`, `pageCodes.mesh.traceTenant` | ✓ |
| `/applications` | 应用编排 | tenant | `app` | `pageCodes.application` | ✓ |
| `/applications/:id` | 应用画布 | tenant | `app` | `pageCodes.application` | hidden |
| `/apps` | 中间件 | tenant | `app` | `pageCodes.app` | ✓ |
| `/apps/instances` | 中间件实例 | tenant | `app` | `pageCodes.app` | ✓ |
| `/apps/:id` | 中间件详情 | tenant | `app` | `pageCodes.app` | hidden |
| `/pipelines` | 流水线 | tenant | `build` | `pageCodes.pipeline` | ✓ |
| `/pipelines/editor` | 流水线编辑 | tenant | `build` | `pageCodes.pipeline` | hidden |
| `/pipelines/runs` | 流水线运行 | tenant | `build` | `pageCodes.pipeline` | hidden |
| `/pipelines/run` | 运行详情 | tenant | `build` | `pageCodes.pipeline` | hidden |
| `/registries` | 镜像仓库 | tenant | `build` | `pageCodes.registry` | ✓ |
| `/resources/workloads` | 工作负载 | tenant | `resource` | `pageCodes.workload.list` | ✓ |
| `/resources/workloads/detail` | 工作负载详情 | tenant | `resource` | `pageCodes.workload.detail` | hidden |
| `/resources/workloads/editor` | 工作负载编辑 | tenant | `resource` | `pageCodes.workload.edit` | hidden |
| `/resources/pods` | Pod | tenant | `resource` | `pageCodes.pod.list` | ✓ |
| `/resources/pods/detail` | Pod 详情 | tenant | `resource` | `pageCodes.pod.detail` | hidden |
| `/resources/pods/container` | 容器详情 | tenant | `resource` | `pageCodes.pod.container` | hidden |
| `/resources/hpas` | HPA | tenant | `resource` | `pageCodes.hpa.list` | ✓ |
| `/resources/hpas/editor` | HPA 编辑 | tenant | `resource` | `pageCodes.hpa.edit` | hidden |
| `/resources/configmaps` | ConfigMap | tenant | `resource` | `pageCodes.configmap.list` | ✓ |
| `/resources/configmaps/editor` | ConfigMap 编辑 | tenant | `resource` | `pageCodes.configmap.edit` | hidden |
| `/resources/secrets` | Secret | tenant | `resource` | `pageCodes.secret.list` | ✓ |
| `/resources/secrets/editor` | Secret 编辑 | tenant | `resource` | `pageCodes.secret.edit` | hidden |
| `/resources/pvcs` | PVC | tenant | `storage` | `pageCodes.pvc.list` | ✓ |
| `/resources/persistentvolumes` | 持久卷 | tenant | `storage` | `pageCodes.persistentVolume.list` | ✓ |
| `/object-storage` | 对象存储 | tenant | `storage` | `pageCodes.objectStorage` | ✓ |
| `/resources/services` | Service | tenant | `network` | `pageCodes.service.list` | ✓ |
| `/resources/services/editor` | Service 编辑 | tenant | `network` | `pageCodes.service.edit` | hidden |
| `/resources/gateways` | Gateway | tenant | `network` | `pageCodes.mesh.gateway.list` | ✓ |
| `/resources/gateways/editor` | Gateway 编辑 | tenant | `network` | `pageCodes.mesh.gateway.edit` | hidden |
| `/routes` | 路由策略 | tenant | `network` | `pageCodes.mesh.*Route.list`（5 个，ANY-of） | ✓ |
| `/routes/editor` | 路由策略编辑 | tenant | `network` | `pageCodes.mesh.*Route.edit`（5 个） | hidden |
| `/mesh/topology` | 网格拓扑 | tenant | `network` | `pageCodes.mesh.gateway.list` | ✓ |
| `/alerts` | 告警 | tenant | `observe` | `pageCodes.alert` | ✓ |
| `/resources/servicemonitors` | ServiceMonitor | tenant | `observe` | `pageCodes.serviceMonitor.list` | ✓ |
| `/resources/servicemonitors/editor` | ServiceMonitor 编辑 | tenant | `observe` | `pageCodes.serviceMonitor.edit` | hidden |
| `/resources/podmonitors` | PodMonitor | tenant | `observe` | `pageCodes.podMonitor.list` | ✓ |
| `/resources/podmonitors/editor` | PodMonitor 编辑 | tenant | `observe` | `pageCodes.podMonitor.edit` | hidden |
| `/resources/prometheusrules` | PrometheusRule | tenant | `observe` | `pageCodes.prometheusRule.list` | ✓ |
| `/resources/prometheusrules/detail` | PrometheusRule 详情 | tenant | `observe` | `pageCodes.prometheusRule.list` | hidden |
| `/resources/prometheusrules/editor` | PrometheusRule 编辑 | tenant | `observe` | `pageCodes.prometheusRule.edit` | hidden |
| `/observability/logs` | 日志查询 | tenant | `observe` | `pageCodes.observability.logs` | ✓ |
| `/tenants/detail` | 我的租户 | tenant | `org` | `pageCodes.tenant.self` | ✓ |
| `/profile` | 个人中心 | — | —（头像下拉） | 无 | hidden |

## 6. 交互设计

### 6.1 顶栏

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ ◆ K8s 云平台 │ 总览 │ 应用  交付  资源  存储  网络  观测  组织 │ 租户 acme ▾ 集群 ▾ │ ☀ │ (头像) │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

- **域 tab**：按当前视图的域顺序渲染（§4），当前域高亮。高亮用底部 2px 强调色下划线，
  不用胶囊填充 —— 胶囊会与右侧的上下文级联抢视觉。
- **点域 tab** 跳到该域**上次停留的页面**：`localStorage['platform_nav_last:<face>:<domain>']`，
  无记录则取该域第一项。
- **总览**固定在最左，与域 tab 之间有 1px 分隔线。
- **上下文级联**（`ContextSelector`）行为不变：只在 `meta.context === 'full'` 的页面渲染。
- **视图切换**（`TenantSwitcher`）行为不变。切换视图后落到**该视图总览**，
  而不是继承上一视图的路径（两个视图的页面集不相交，继承会落到无权限页）。

### 6.2 侧边栏

```
┌ 应用 ─────────┐
│  应用编排        │
│  中间件          │
│  中间件实例       │
│                  │
│  ──────────────  │
│  🔍 搜索  Ctrl K  │
└────────────────┘
```

- 只渲染当前域的项目，1–6 项，不滚动（管理员视角通常 3–6 项；租户非管理员的「组织」域只有「我的租户」一项）。
- 域名在顶部以小号灰字显示；折叠态（64px 图标轨）隐藏域名，只留当前页的激活色。
- 搜索入口固定在底部；折叠态退化为一个放大镜图标。
- 折叠 / 窄屏抽屉 / 遮罩的行为全部沿用现状，不改断点（1280px）。

### 6.3 命令面板（Ctrl / Cmd + K）

- 覆盖层，输入即过滤**当前视图可见**的全部页面（跨域）。数据来自 `nav.ts`，纯前端。
- 每行：图标 + 页面名 + 所属域（小字，右侧）。
- 键盘：↑↓ 选择、Enter 跳转、Esc 关闭；点击也可。
- 输入为空时展示**最近访问**（最多 5 条）。
- 匹配规则：标题包含（不区分大小写）+ 拼音首字母不做（引入依赖不值得），
  额外匹配域名的中文前缀。

### 6.4 最近访问

- `localStorage['platform_nav_recent']`：JSON 数组，环形缓冲，最多 8 条，
  记录 `{ path, title, domain, face, ts }`。
- 写入点：`router.afterEach`，跳过 `nav: 'hidden'` 的页面（详情页进最近访问会让列表变噪声）
  和 `/login`、`/callback`、`/overview`。
- 读取点：总览页「最近访问」区块、命令面板空态。
- 只在本机本浏览器，不落后端，不跨设备。

### 6.5 窄屏

- 域 tab 横向可滚动（`overflow-x: auto`，隐藏滚动条），保证 7 个 tab 在 1024px 下仍可点。
- 侧边栏沿用现状：窄屏折叠为图标轨，点 logo 打开浮层 drawer。

## 7. 总览页

两个版本由视图决定，互不复用数据。

### 7.1 平台视图（首屏 1 次 `/cluster/list`，可选 +2 次）

```
平台总览                                              [刷新]
┌ 集群 3 · 启用 2 ┐ ┌ 租户 5 · 启用 4 ┐ ┌ 命名空间分配 12 ┐
── 集群状态 ────────────────────────────────────────────
 名称        状态       版本    节点   操作
 coding      ● 已连接   1.31    6      概览
 test-work   ○ 已停用   —       —      —
── 快捷操作 ────────────────────────────────────────────
 [+ 新增集群]  [+ 分配命名空间]  [+ 新建租户]  [查审计]
── 最近访问 ────────────────────────────────────────────
 集群管理 · 命名空间管理 · 审计日志 · 节点管理
```

- 统计三卡与现状同源（`/cluster/list`、`/tenant/list`、`/tenant/namespace/list`），
  无对应 API 权限时跳过请求并显示「—」，不显示 0（沿用现有门控写法）。
- 集群状态表来自 `/cluster/list` 已有字段，**不逐集群调 `/cluster/overview`** ——
  那是 9 次 list 的聚合，首屏 N 次调用会明显拖慢。健康明细留给集群详情页。
- 快捷操作按页面码过滤，与菜单同源（§8.2）。

### 7.2 租户视图（首屏 1 次 `/tenant/overview`）

```
acme 工作台                                            [刷新]
┌ CPU 12.4 / 40 核 ┐ ┌ 内存 48 / 160 GiB ┐ ┌ 异常 Pod 3 ┐ ┌ 不健康 1 ┐
── 需要关注 ─────────────────────────────────────────────
 异常 Pod        CrashLoopBackOff ×2 · ImagePullBackOff ×1
 不健康工作负载   api-server（Deployment，就绪 1/3）
 配额缺口         prod、staging 未配 ResourceQuota
── 常用操作 ─────────────────────────────────────────────
 [+ 新建应用]  [+ 新建中间件]  [跑流水线]  [看日志]  [查链路]
── 最近访问 ─────────────────────────────────────────────
 应用编排 · 中间件实例 · 流水线 · 告警
```

- 数据一次到手：`tenantApi.overview({ tenantId: perm.currentTenant.tenantId })` ——
  显式传 tenantId，自管轨与代管轨都成立（`TenantOverviewPanel` 已是这个写法）。
- **四张卡**：CPU（`quotaCpuUsed / quotaCpuHard`）、内存（同）、异常 Pod（`abnormalPodTotal`）、
  不健康工作负载（`unhealthyWorkloadTotal`）。配额硬上限为 `null` 时显示「未设配额」而不是 0（沿用 B4 口径）。
- **需要关注**三行：异常 Pod（按 `abnormalPodReasonCounts` 取前 3 个 reason）、
  不健康工作负载（`unhealthyWorkloads` 前 3）、配额缺口
  （`namespacesWithoutQuota` + `namespacesPartialQuota`）。三行都空才显示"一切正常"空态。
- **常用操作**按页面码过滤：新建应用 / 新建中间件 / 跑流水线 / 看日志 / 查链路。
- 管理员进入租户视图时，页头显式标注「当前代管 <租户名>」，避免把租户数据当平台数据看。

## 8. 实现

### 8.1 路由元数据（`src/router/index.ts`）

`RouteMeta` 增加四个可选字段：

```ts
interface RouteMeta {
  // ... 既有
  /** 所属导航域（租户视图用的那个）。nav:'hidden' 的页面也填，供面包屑/高亮回落 */
  domain?: NavDomainKey
  /** 平台视图下改归哪个域；缺省沿用 domain。仅 both 面的页面需要（Git 凭据、链路追踪） */
  platformDomain?: NavDomainKey
  /** 菜单图标（Element Plus 图标组件名，字符串形式，靠全局注册） */
  icon?: string
  /** 'hidden' = 不进菜单（详情/编辑页）；缺省 = 进菜单 */
  nav?: 'hidden'
  /** 域内排序，小在前；缺省按路由声明顺序 */
  order?: number
}
```

「面」不新增字段：由 `requiresPerm` 中页面码的前缀推导（§3 R3）。

### 8.2 `src/nav.ts`（新增）

单一数据来源，被顶栏、侧栏、总览、命令面板共用：

```ts
export type NavFace = 'platform' | 'tenant'
export type NavDomainKey = 'cluster'|'network'|'org'|'ops'|'app'|'build'|'resource'|'storage'|'observe'

export interface NavItem { title: string; path: string; icon: string; codes: string[] }
export interface NavDomain { key: NavDomainKey; label: string; items: NavItem[] }

/** 两个视图的域顺序与显示名 */
const DOMAIN_LAYOUT: Record<NavFace, Array<{ key: NavDomainKey; label: string }>>

/** 由路由表 + 权限 + 视图，构建当前视图的导航；空域已剔除 */
export function buildNav(face: NavFace, perm: PermissionFacade): NavDomain[]

/** 当前路由落在哪个域（考虑 platformDomain 回落）。
 *  只看 meta.domain，**不看 nav 标记** —— 详情/编辑页也要能高亮它所属的域。
 *  仅当路由没有 domain（总览、个人中心）时返回 null。 */
export function resolveDomain(face: NavFace, path: string): NavDomainKey | null

/** 页面码 → 面；总览等无码页面返回 'both' */
export function faceOf(codes: string[] | undefined): 'platform' | 'tenant' | 'both'
```

- 数据来源是 `router.getRoutes()`，按 `meta.nav !== 'hidden'` 过滤，按
  `DOMAIN_LAYOUT[face]` 的域顺序 + `meta.order` 排序。
- `PermissionFacade` 就是 `perm.has` / `perm.hasAny` 两个函数，便于在非组件环境（测试、断言）里调用。

**开发期一致性断言**（`import.meta.env.DEV` 下执行，生产构建剔除）：
对每条 `meta.nav !== 'hidden'` 且带 `requiresPerm` 的路由，检查
`meta.domain` 已填且属于 `NavDomainKey` 闭集；不满足则在控制台 `console.error` 一次性列出。
这替代了本仓库目前没有的前端测试框架 —— 不为一个断言引入 vitest。

### 8.3 `MainLayout.vue`

- 删除全部手写的 `el-menu-item` / `.menu-group` 模板（约 200 行）。
- 新增：
  - `face = computed(() => perm.currentTenant ? 'tenant' : 'platform')`
  - `domains = computed(() => buildNav(face.value, perm))`
  - `activeDomain = computed(() => resolveDomain(face.value, route.path))`
  - 顶栏渲染 `domains` 为 tab；侧栏渲染 `domains.find(d => d.key === activeDomain)?.items ?? []`
- **回落**：当前路由解析出的域不在本视图的域集合里（例如管理员在租户视图直接打开收藏的
  `/clusters`）时，不重定向、不报错；把该域临时追加到 tab 末尾并高亮，侧栏正常渲染。
  这样深链不会把人扔进一个空侧栏。
- 顶栏原被注释掉的面包屑代码块一并删除。

### 8.4 `OverviewView.vue`

按 §7 重写。视图由 `perm.currentTenant` 决定，两部分为独立子组件
（`views/overview/PlatformOverview.vue`、`views/overview/TenantOverview.vue`），
`OverviewView.vue` 只做分发与页头。

### 8.5 新增组件

| 文件 | 职责 |
|---|---|
| `src/nav.ts` | 导航数据源（§8.2） |
| `src/components/CommandPalette.vue` | Ctrl/Cmd+K 面板 |
| `src/components/RecentVisits.vue` | 最近访问区块（总览与面板空态共用） |
| `src/composables/useRecentVisits.ts` | localStorage 读写 + 路由钩子 |
| `src/views/overview/PlatformOverview.vue` | 平台视图总览 |
| `src/views/overview/TenantOverview.vue` | 租户视图总览 |

## 9. 行为变化与兼容

| 变化 | 影响 | 处置 |
|---|---|---|
| 租户视图不再出现平台运维页 | 管理员切到租户视图后看不到「集群」域 | 顶栏切换器常驻；切回平台视图落到平台总览。**这是本次最显眼的行为变化，需在验收时确认** |
| Git 凭据、链路追踪在两个视图都在，但归属域不同 | 平台视图在「运维」，租户视图在「交付」/「观测」 | 由 `meta.platformDomain` 表达 |
| 菜单不再手写 | 新增页面只需在路由上加 `domain` / `icon` / `order` | 开发期断言兜底 |
| URL 全部不变 | 收藏、外部链接、审计日志里的 path 全部继续有效 | — |
| `/overview` 仍无页面码 | 守卫回落目标不变 | 不动 |

localStorage 新增两个 key：`platform_nav_last:<face>:<domain>`、`platform_nav_recent`。
与既有的 `platform_sidebar_collapsed` 互不影响。

## 10. 验收

1. **构建**：`npm run build`（含 `vue-tsc`）通过。
2. **平台视图**：顶栏恰好 4 个域 tab（集群/网络/组织/运维）+ 总览；每个域的侧栏项与 §4.1 一致；
   总览显示集群状态表与三种快捷操作。
3. **租户视图**：顶栏恰好 7 个域 tab；无「集群」域；总览四张卡有值（用真集群数据），
   「需要关注」在存在异常 Pod 时列出 reason。
4. **按帽子分家**：管理员在租户视图手输 `/clusters` 能进（不重定向），侧栏临时出现「集群」域并高亮。
5. **命令面板**：Ctrl/Cmd+K 打开，输入「日志」能跨域命中「日志查询」；Enter 跳转；Esc 关闭。
6. **最近访问**：连点三个页面后回总览，三者在列表中；点详情页不进入最近访问。
7. **域记忆**：在「资源」域点进 Pod，切到「存储」再切回「资源」，落回 Pod 列表页。
8. **权限过滤**：用一个只有 `tenant:page:workload.list` 的租户角色登录，顶栏只剩「资源」域，
   且该域只有工作负载一项。
9. **一致性断言**：故意给一条菜单路由删掉 `meta.domain`，dev 控制台应报错。

## 11. 风险与已否决的方案

**风险**

- 平台视图的「运维」域语义偏松散（审计 / 通知 / 凭据 / 链路）。若后续该域超过 6 项，应拆出「凭据」或「集成」域。
- 租户视图的「组织」域对非管理员只有 1 项。若后续加入租户级设置（配额查看、成员邀请），这里会自然长出内容。
- 域 tab 在 1024–1280px 区间可能与上下文级联抢宽度，靠横向滚动兜底；验收时需实际量一次。

**已否决**

- **手写 `nav.ts` 显式清单**：可读性更好，但仍是路由 + 清单两处维护，且漏改时静默不一致。选了从路由派生。
- **继续手写菜单模板只重排分组**：改动最小，但 8 个域、按帽子分家后模板会继续膨胀到 300 行以上，
  且解决不了"加页面改三处"。
- **两级可折叠树**：用户已明确选择"顶栏切域 + 侧栏单层"。
- **合并「中间件」与「中间件实例」**、**合并 SM/PM/PrometheusRule 为一页**：都要动页面本体，
  属于二批可选，不在本次 IA 范围内。
- **在总览里逐集群拉 `/cluster/overview`**：首屏 N 次 9-list 聚合，收益不抵成本。
