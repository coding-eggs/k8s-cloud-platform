# 租户概览 · 设计（2026-10-12）

- **目标读者**：本批实施者（本文件是 spec，实施计划见后续 `writing-plans` 产出的 plan）
- **上游讨论**：2026-10-12 会话内评审（入口 / 范围 / 内容块 / 配额口径 / 缓存形态 / 一致性 / 分层落点逐节确认）
- **相关**：B4 集群概览（`docs/superpowers/plans/2026-10-10-b4-cluster-overview.md`）、`docs/development/backend-layering.md` §5、`「每个命名空间必须有配额」的产品意图`

## 0. 一句话

给"某个租户"一个概览：跨它**全部集群**的命名空间看**配额、健康、用量**；数据不新采一套，而是把已有的集群聚合快照**按作用域拆成两层缓存**，让租户、集群、命名空间三种视角各自只读自己那一份。

## 1. 目标 / 非目标

**目标**
1. 租户视角首屏：合计用量 + 需要关注的异常（含配额异常）+ 该租户全部命名空间的用量与配额占用。
2. 三种视角（租户 / 集群 / 命名空间）的同一事实**只有一个缓存键、一套口径**。
3. 落地「每个命名空间必须有配额」的产品口径（`missing` / `partial` 判为异常）。
4. 租户概览的成本与**该租户自己的对象量**相关，不与集群里别人的对象量相关。

**非目标（本批不做）**
- 租户**写**配额（改 hard）——那是权限面的另一批活。
- 按命名空间的用量曲线（命名空间详情页已有它自己的 4 条曲线；本批只给"租户合计"两条）。
- 节点健康 / 集群容量不进租户概览（集群管理面的数据）。
- 告警/通知（阈值、订阅、webhook）。
- 集群概览的"总量与明细相加对不上"以外的其它 B4 口径调整。

## 2. 作用域模型：两层缓存键

| key（`RedisJsonCache` 再加全局前缀 `platform:cache:`） | 值 | 谁读 | 谁填 |
|---|---|---|---|
| `cluster:agg:<clusterId>` | **只放集群级事实**：节点健康/容量、PV/存储、命名空间名索引、`sampledAt`。**不含任何 per-ns 数值、不含合计** | 集群概览的节点/存储/能力块；索引用来知道 MGET 哪些 ns 键 | 集群概览那次聚合（`/cluster/resource-aggregate` 的响应切片后） |
| `ns:<clusterId>:<namespace>` | **这一个命名空间的一切**（`NamespaceSnapshotDTO`）：`NamespaceStatDTO` 行 + 该 ns 的异常 Pod（计数 + reason 分组计数 + 封顶名单）+ 该 ns 的不健康工作负载（计数 + 封顶名单）+ `clusterId` + `sampledAt` | ① 租户概览（读它的子集）② 命名空间页/详情（读它自己那一行）③ 集群概览的"资源明细/异常/不健康"块（读全部） | ① 集群页那次聚合的**切片**（一次采样填 N 个键，共享同一个 `sampledAt`）② 窄读未命中时按需补自己那几个（单 ns 采样） |

**为什么异常 Pod / 不健康工作负载在 ns 键而不是集群键**：它们**带 namespace，就是命名空间级事实**；只有这样才能让租户只读自己那几行就拿到完整名单（不会被别人的对象把 200 条封顶挤掉）。代价是**集群页的合计与那两个名单改为由各行合并现算**（含 reason 分组），排序/封顶逻辑沿用既有实现 —— 见 §6 规则 1。

**这是与"集群键里塞 ns 行"（今天的形态）的**唯一**差别**，理由：一个事实只存一个键，才不会出现"两个读路径读到同一个事实的两个副本"。代价见 §6（集群明细页要 MGET + 也要受页内一致性阈值约束）。

**注意**：`namespace` 可能被多个租户共享吗？不会——分配表的语义是命名空间归属一个租户（多租户共享命名空间不在产品模型里）。若日后要支持，`ns:` 键的键位不变，只是读取方多一个。

集群页在 `cluster:agg` **命中**时：拿索引里的命名空间名 → MGET 全部 `ns:` 键 → 由这些切片现算合计、异常 Pod 名单与不健康工作负载名单（§6 规则 1）。

**键值细节**：`sampledAt` 用 **epoch 毫秒（Long）**，前端自己格式化（避免时区解析分歧）；租户的命名空间集合取自分配表**未删除**的行；分配表里有、但集群里已不存在的命名空间，该行标为"命名空间不存在"（不静默当成 0 计入合计）。

## 3. 分层落点

判据：**这段代码引用了"平台侧才有的概念"吗？**（`backend-layering.md` §5）

| 逻辑 | 落点 | 依据 |
|---|---|---|
| 集群采样（现有 9 次全量 list） | k8s-core `ClusterAggregationOperations`（已有） | K8s API 能直接回答 |
| 单命名空间采样（该 ns 内 7 次 `inNamespace().list()`：pods / 3 种 workload / services / resourcequotas / PVC） | k8s-core **新增** | 同上；不认识租户 |
| 装配 per-ns 行（由对象列表算计数、requests/limits、配额 hard/used） | k8s-core：抽成**纯函数** `NamespaceStatAssembler`，两个采样器共用 | 防两套实现漂移的唯一手段（不是抽基类） |
| 统计范围（租户的 (集群, 命名空间) 集合） | platform-api（分配表） | 平台侧独有概念 |
| 过滤 / 跨集群合并 / 求和 | platform-api | 输入是**已算好的行**（几十 KB），不触发 §5.1 规模例外；跨集群合并只有 api 能做 |
| 缓存读写、TTL、`sampledAt`、页内阈值 | platform-api | Redis 是 api 侧基础设施；k8s-server 不碰缓存/业务库 |
| 权限行、自管轨 tenantId 强制 | platform-api（`TenantContextResolver`） | k8s-server 侧只有边界 |
| 曲线：PromQL 组装、Thanos 查询、按租户 ns 过滤求和 | platform-api（`MetricsService`） | k8s-server 不碰监控后端 |
| 产品阈值（配额三档、TTL、一致性阈值） | platform-api | §5 点名的"产品阈值" |

**k8s-server 必须不知道**：租户、分配表（除边界校验这一处既定用途）、缓存。新端点只回答"**这一个**命名空间的行是什么"。

**明确不做**：不做"传一批命名空间"的批量端点 —— 边界校验是"一个三元组一次"的，批量端点要么在适配器里发明一层范围判定，要么留洞（租户传别人家的 ns）。租户的命名空间是个位数，逐个调即可。

## 4. 采样器与装配

```
k8s-core（既有类的既有函数复用，不新造装配器）:
  ClusterAggregationOperations.aggregate(ClusterObjects, now)      ← 对象→行 的唯一口径，改成 static
  ClusterAggregationOperations.namespaceSnapshots(ClusterObjects, now) → Map<ns, NamespaceSnapshotDTO>   ← 新增：按 ns 切片（aggregate 的全局结果由它派生，既有 24 例单测是回归底线）
  ClusterAggregationOperations.aggregate() : list×9 → 集群事实 + namespaceSnapshots()      （集群路径）
  NamespaceStatOperations.row(ns)          : list×7(inNamespace) → namespaceSnapshots() 取那一片 （单 ns 路径，新增）
```

**不变式（单测钉住）**：同一个 ns 的同一批对象，经单 ns 路径与经集群路径（把该 ns 混进集群对象集里）得到的 `NamespaceSnapshotDTO` **逐字段相同**。这条测试是"不漂移"的可执行定义。

## 5. 数据流

```
① 租户概览  POST /tenant/overview
    分配表 → (clusterId → [ns]) → 逐集群 MGET ns 键（未命中/过旧 → 单 ns 采样补齐）
    → 按租户过滤/求和 → TenantOverviewDTO（含 sampledAt = 各行中最旧）

② 集群概览  POST /cluster/overview + /resource-breakdown
    cluster:agg 命中 → MGET 全部 ns 键 → 明细与合计都由这些行现算
    未命中 → /cluster/resource-aggregate（9 次 list）→ 剥掉 namespaces[] 写 cluster 键 + 切片写 N 个 ns 键（同一 sampledAt）→ 本页直接用这次响应

③ 命名空间页（顺带统一）  读它自己那一个 ns 键；未命中 → 单 ns 采样
    配额列从此与租户页/集群页同源（不再另走 /namespace/quota/get 一条口径）
```

## 6. 一致性口径

1. **合计由行现算**：任何页面显示的总计（资源合计、Pod/工作负载计数、异常 Pod 总数与 reason 分组、不健康工作负载总数）= 该页面本次拿到的那些 ns 切片合并而来（不另存总计值）→ **同一屏上"合计 = Σ明细"恒成立**。排序/封顶沿用既有实现（`sortAndCap`），只是输入从"集群全量"换成"本页合并结果"。
2. **页内阈值自洽**：行值里带 `sampledAt`。组装方（只有"多行拼一个数"的页面需要：租户概览、集群明细）读到各行 `sampledAt` 相差超过 **15s** 时，把过旧的那些在**本次请求内重采**，补完即自愈（后续 15s 内的读取不再触发）。阈值 = 0 退化为"每次重采"，= TTL 退化为"不设限"。
   - 规则属于**组装方**，不进 `RedisJsonCache`（它仍是纯 TTL 缓存）。
   - 单行消费者（命名空间页）不需要这条。
3. **"数据截至"**：页面显示本页各行中**最旧**的 `sampledAt`。
4. 不同页面之间仍可能有最多一个 TTL（45s）的漂移——这是按作用域缓存的固有代价，页面各自标注自己的截止时间。

## 7. 配额口径（本批落地产品意图）

每个命名空间分三档（**在 platform-api 一处判定**，前端只展示）：

| 档 | 条件 | 展示 |
|---|---|---|
| `ok` | 有 ResourceQuota，且含 `requests.cpu` + `requests.memory` 两个 key | 正常，显示占用条 |
| `missing` | 没有任何 ResourceQuota | 首屏"需要关注"里计数 + 明细行标黄 |
| `partial` | 有 quota 但缺 `requests.*` 中的任一 key | 同上，文案区分（"配额未覆盖 requests"） |

- 首屏给 `namespacesWithoutQuota` / `namespacesPartialQuota` 计数 + 命名空间名清单（可点进命名空间）。
- **本批三处一起统一**（用户 2026-10-12 决定）：
  1. 租户概览（新页）：首屏"需要关注"计数 + 明细表状态列
  2. 集群概览「资源明细」tab：它读的就是同一批 `ns:` 行，不额外取数，只把 `—` 换成 `⚠ 无配额 / ⚠ 配额未覆盖 requests`
  3. 命名空间**详情页**的「概览」tab：占用展示（used/hard）与"有没有配额 / 缺不缺 requests"的判定改读同一行
- **统一的是"判定与展示口径"，不是编辑器**：详情页的「配额」tab 是**配额编辑器**（要读完整 `ResourceQuota` 对象、且展示 count 系列如 pods/services 配额），这一份**保持原样**走 `/namespace/quota/get`。行里只有 `requests.cpu` / `requests.memory` 两个 key —— 少的那部分（count 系列）本来就不在概览口径里，不是这次丢的。
- 命名空间**列表页**不涉及（它今天没有配额列）。
- 一个 ns 有多份 ResourceQuota 时求和（沿用 `NamespaceStatDTO` 既有口径）。

## 8. 接口与权限

**k8s-server（1 个新端点）**

| 方法 | 路径 | 边界 | 说明 |
|---|---|---|---|
| GET | `/namespacestats/{namespace}?clusterId[&tenantId]` | `TENANT` | 单命名空间的行；走 `ResourceAccessResolver.resolveNamespacedAccess`（分配表三元组 + tenant/admin client 选择）。**租户/代管两轨用它**（tenantId 非空） |
| POST | `/namespacestats/platform/get` | `PLATFORM` | **平台模式下同一个事实的采样入口**（body `{clusterId, namespace}`，独立 controller 类 —— 边界按类读）：走 `resolvePlatformNamespacedAccess` + admin client。**必须存在**：单命名空间的行本身不含租户维度（`kube-system`/`default` 这类未分配命名空间没有租户），而集群概览明细页与命名空间详情页都是平台侧页面（`TENANT` 边界会以"管理员代操作需显式指定 tenantId"拒掉它们）。两个入口写的是**同一个 `ns:` 键** —— 一个事实一个键不因入口变多而破 |

> **2026-10-13 修正（最终整支复审的根因）**：初版只有 TENANT 那一条，而它的两个平台侧消费者传 `tenantId = null` → 冷缓存路径必然被拒（T13 页面不可用、集群概览 500）。上面的 PLATFORM 入口是修复；`NamespaceStatService` 按 `tenantId == null` 选"平台模式"，语义写在 `K8sNamespaceStatClient` 的 javadoc 里。

新类（**不套资源基类**，它不是资源 CRUD；参照非资源 controller 的写法）：`NamespaceStatController`；k8s-core 经 `KubernetesOperationsFactory.getNamespaceStatOperation(clusterId, tenantId, adminMode)` 取（非六端点 CRUD，独立返回具体类型，同 `getNodeOperation`）。platform-api 侧新 client：`K8sNamespaceStatClient`（一方法一端点）。

**platform-api（4 个新端点，**零新权限码**）**

| 方法 | 路径 | 权限码（ANY-of 两行） |
|---|---|---|
| POST | `/tenant/overview` | `tenant:overview:view`（自管轨） **或** `platform:allocation:list`（代管轨） |
| POST | `/tenant/overview/breakdown` | 同上 |
| POST | `/tenant/overview/metrics/cpu` | 同上 |
| POST | `/tenant/overview/metrics/memory` | 同上 |

- 两轨沿用 `/tenant/namespace/list` 的既有范式：同一端点两行权限（ANY-of），服务层用 `TenantContextResolver.requireContext(tokenTenantId, requestTenantId)`：**自管轨强制 tenantId = 自己**（传别人的也只看到自己），代管轨才认入参。
- **"读一行"收敛成一个入口**（本次统一的关键）：新 service `NamespaceStatService` 提供
  `row(clusterId, namespace)`（缓存优先，未命中单 ns 采样）与 `rows(clusterId, namespaces)`（含 §6 的页内阈值规则）。
  三个消费者共用它：租户概览（M 行）、集群概览明细（该集群全部行）、命名空间详情页（1 行）。
- 为第三个消费者新增 1 个 platform-api 端点（**唯一新增的权限行**）：

  | 方法 | 路径 | 权限码 |
  |---|---|---|
  | POST | `/namespace/stat/get` | `platform:allocation:list` |

  请求 `{clusterId, namespace}` → 返回 `NamespaceStatEntry`（行 + 三档状态）。**刻意不让 `/namespace/get` 顺带回带**：那个端点是分配页/编辑器的读取面，保持它便宜且语义稳定，不要因为一个展示需求让它变得会失败在聚合上。
- 新 controller `TenantOverviewController`（`@RequestMapping("/tenant/overview")`）+ 新 service `TenantOverviewService`；`ClusterService.aggregateCached` 开成 public（供跨域只读复用，注释写明"只读候选/快照借用"，同 `WorkloadService` 借 `GatewayService` 的先例）。
- 请求体：`{tenantId?, clusterId?, rangeMinutes?}`（`clusterId` 仅代管轨有意义；自管轨忽略）。
- `TenantOverviewDTO` 字段：`tenantId`、`sampledAt`、`total`（cpu/mem requests+limits、pod/deploy/sts/ds/svc/pvc 计数、namespaceCount）、`quotaTotals`（Σ hard/used，只累加有配额的 ns）、`clusters[]`（clusterId/clusterName/namespaceCount/可用性/小计）、`unavailableClusters[]`、`abnormalPodTotal` + `abnormalPodReasonCounts` + `abnormalPods[]`（封顶 50，`namespace` 字段已有）、`unhealthyWorkloadTotal` + `unhealthyWorkloads[]`、`namespacesWithoutQuota`/`namespacesPartialQuota` 计数与清单。
- 明细接口返回 `List<NamespaceStatEntry>`（= `NamespaceStatDTO` + `clusterId`；**`clusterId` 由 platform-api 合并时回填**，与 k8s-server 侧 `stamp()` 的既有做法同形）。

## 9. 前端

- 新 `components/tenant/TenantOverviewPanel.vue`（自包含加载），挂成 `TenantDetailPanel` 的**第一个 tab**「概览」（`lazy`），两个入口自动同时获得：admin `/tenants` 抽屉、自管 `/tenants/detail` 页。
- admin 抽屉 `size` 760px → **1040px**（现有两个 tab 也偏挤）。
- 复用 `components/workload/MetricChart.vue`（纯展示，props: title/unit/series/loading/percentable/limit/ranges）。
- 曲线：租户合计 CPU / 内存各一条；`percentable` 打开时上限取 `quotaTotals` 的 hard 合计（无配额则不给百分比）。
- 明细表列：命名空间（可点进命名空间页）· 集群 · requests(cpu/内存) · limits · 配额 hard · 占用条 · **状态（ok/missing/partial）**。
- "需要关注"区：未配置/部分配额、异常 Pod（按 reason 分组计数，可展开）、不健康工作负载；各计数可点进对应页面。
- 空态：租户没有任何命名空间 → "请联系平台管理员分配命名空间"。
- **命名空间详情页**（本次统一第三处）：`概览` tab 的配额占用展示与状态改读 `/namespace/stat/get`；`配额` 编辑 tab 不动。

## 10. 降级与失败模式

| 情况 | 行为 |
|---|---|
| 某集群聚合不可用（集群断开/超时） | **该集群标"不可用"**（计入 `unavailableClusters`，首屏显式一条提示 + 可重试），其余集群照常出数；不整页失败 |
| Redis 不可用 | 退化为"每次都直采"（`RedisJsonCache` 既有语义），功能不失效 |
| 单 ns 采样失败 | **其所属集群标记不可用**（本批粒度），其余集群照常。逐行"不可用"需要给切片 DTO 加标记位、并在合并时区分"0"与"缺数"——收益不抵复杂度，本批不做 |
| 租户无命名空间 | 空态 |
| 曲线查询失败 | 图表区显示失败态，不影响上方快照（两段分开降级——B4 的 `Promise.all` 一锅端踩过） |

## 11. 测试与验收

**单测（TDD）**
- k8s-core：`NamespaceStatAssembler` 的纯函数断言；**两条采样路径同输入 → 同行**（防漂移的可执行定义）。
- platform-api：`TenantOverviewService` 的过滤（只统计本租户 ns）/ 跨集群求和 / 配额三档分档 / `unavailableClusters` 降级 / 页内阈值选行（构造不同 `sampledAt` 的行集，断言"过旧的被重采"与"不再触发"）。
- platform-api：`TenantContextResolver` 的自管轨越权用例（传别人的 tenantId → 仍为本人）。
- k8s-server：新端点的边界与路由（MockMvc，参照本批 `NodeAddressRouteTest` 的写法）。

**验收（浏览器实测，假 token + XHR stub）**
1. 租户概览首屏：合计 / 需要关注 / 配额表 / 两条曲线全出；`quotaTotals` 与明细逐行相加一致。
2. 未配置配额的命名空间被计入"需要关注"并在明细里标黄。
3. 断开一个集群（stub 该集群聚合 500）→ 首屏标"集群 X 不可用"，其余数字照常。
4. `ns:` 键被命名空间页访问后，租户概览读到的是同一行（同一 `sampledAt`）——三个视角同一事实同一值。

## 12. 部署顺序

1. 重放迁移 `V2026_10_12_2__tenant_overview_permissions.sql`：**9 行 API 行** = 4 个 `/tenant/overview*` 端点 × **2 行**（同一 `(method, path)` 两行 = ANY-of：`tenant:overview:view` + `platform:allocation:list`，`platform_permission` 一行只能挂一个码）+ 1 行 `/namespace/stat/get`（`platform:allocation:list`）；角色绑定：admin 全 9 行，tenant-admin/tenant-member 只绑 4 行 `tenant:overview:view`。**零新码、零模板变更、零新页面码**（tab 挂在既有页面里）。
2. k8s-server 与 platform-api **同批**上线（新 k8s-server 端点不被 api 调用前不影响任何既有路径）。
3. 无 Redis / 无 Thanos 的特殊要求。

## 13. 实施分期（建议，供 plan 采用）

| 期 | 内容 | 完成判据 |
|---|---|---|
| P1 | k8s-core：`NamespaceStatAssembler` 抽取 + 单 ns 采样 + 工厂入口；k8s-server：`NamespaceStatController`；platform-api：`K8sNamespaceStatClient` | 两条采样路径"同输入同行"单测绿；新端点可被 api 调通 |
| P2 | platform-api：`RedisJsonCache` 加批量读/写（MGET/pipeline SET）→ `cluster:agg` 剥离 ns 行 + `ns:` 键切片写入 → `ClusterService.overview/resourceBreakdown` 改读 ns 键 | 集群概览三个 tab 数字与改造前一致（回归基线）；`ns:` 键落地 |
| P3 | platform-api：`TenantOverviewService`/`Controller` + DTO + 迁移（4 行权限）+ 曲线端点 | 单测（过滤/求和/分档/降级/阈值）绿；`/tenant/overview` 可调 |
| P4 | 前端：`TenantOverviewPanel` + tab + 抽屉加宽 + api/types | 浏览器实测四项验收（§11）通过 |
| P5 | 三处口径统一下沉：集群概览明细 tab 状态列、命名空间详情页「概览」tab 改读 `/namespace/stat/get` | 三个页面同一命名空间的"配额状态 + used/hard"逐字一致 |

## 14. 待确认（评审时否决即可，否则按此实施）

1. **集群键剥掉 ns 行**（§2）：换来"一个事实一个键"，代价是集群明细页改用 MGET + 受页内阈值约束。若倾向"集群键保留 ns 行、ns 键只是它的切片"（今天形态的延伸，集群页零改动、存储冗余一倍），则在实施前改回。
2. ~~配额三档同步范围~~ **已定：三处一起统一（租户概览 / 集群概览明细 tab / 命名空间详情页概览 tab），编辑器不动**（§7）。
3. **新端点放 `TenantOverviewController`**（§8），而非并入 `TenantController`。
