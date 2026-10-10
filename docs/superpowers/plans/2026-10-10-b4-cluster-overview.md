# B4 · 集群概览 — 实施计划（新 session 直接可执行）

- **日期**：2026-10-10
- **规格**：[2026-09-14-cluster-overview-design.md](../specs/2026-09-14-cluster-overview-design.md)（骨架设计在它；**本计划已按 2026-10-10 的设计讨论增补健康/异常 + 存储 + Top-N，与旧 spec 不一致处以本文件为准**）
- **当前状态**：**Phase 1 + 2 + 3 全部完成，已验证，待部署**（2026-10-10）。全程未提交。
- **下一步**：部署（先重放 `V2026_10_10_1`，见文末「部署顺序」）。

### 落地记录（2026-10-10）

- **Phase 1**：k8s-core `ClusterAggregationOperations`（固定 9 次 list）+ `POST /cluster/resource-aggregate` + platform-api `overview`/`resource-breakdown`/4 个集群指标端点 + `V2026_10_10_1`（6 行权限，**全复用 `platform:cluster:manage`**）。验证：四模块测试全绿（含 `ClusterAggregationOperationsTest` 24 例）、`npm run build` 通过。
- **Phase 2**：`ClusterView` 行操作收敛为 `el-dropdown`（查看/编辑/重新开通/刷新能力/删除，启用 switch 仍内联）+ 集群名称可点；新增 `ClusterDetailView.vue`（4 tab）+ `components/cluster/ClusterMetricsPanel.vue` + router/api/types 注册点。浏览器实测（假 token + XHR stub）四 tab 全通。
- **Phase 3（降级/性能/门禁）**：
  - **两段分开降级**：`refresh()` 原先用 `Promise.all([get, overview])` 一锅端 —— 概览接口一失败整页变「加载失败」，把「集群断开时基本信息可见」（spec §7）也一起丢了（大集群上 `/cluster/overview` 超时正是最可能的失败）。改为基本信息（锚点，失败=真加载失败）与概览快照（可降级，失败→`overviewError` 提示 + 可刷新重试）分开取。
  - **`aggregateUnavailable` 补上 `overview == null`**：原写法在概览接口失败时算 `false`，首屏会渲染出「无（全部 Ready 且无 pressure）」「无（全部工作负载副本齐）」—— 把「读不到」说成「一切正常」。浏览器实测 Case A（概览 500 + 集群 ERROR/禁用）与 Case B（接口 200 但聚合段全 null）两条路径。
  - **Tab 2 补「聚合不可用」提示**（Tab 1/4 本来就有）：说明 allocatable/allocated 显示「—」而 used/曲线来自 Thanos 不受影响。
  - **性能可观测性**：聚合加慢日志（≥5s 打 warn，带各族对象数）。理由 —— platform-api 侧 45s TTL 缓存把「慢」从接口耗时上抹掉了，没有这条日志就无从判断缓存是否生效、哪一族大到该改窄查询。
  - 门禁：capability 缺失不阻塞基本信息（实测 Case B 里能力全灰 + 提示「可点刷新能力」，基本信息照常）。
- **两处对 Phase 1 契约的增补**（计划里 Tab 4 要「不健康 workload」、首屏要「按 reason 分组计数」，Phase 1 的 DTO 都没给）：
  1. `ClusterAggregateDTO` 加 `unhealthyWorkloads` + `unhealthyWorkloadTotal`（口径 §3，同一个 pass 顺带出，Deploy/Sts 各一行，`replicas` 省略按 1、`readyReplicas` 缺席按 0）。
  2. `ClusterAggregateDTO` 加 `abnormalPodReasonCounts`：**全量**分组计数（在 200 条封顶之前算）—— 否则集群有 500 个崩溃 Pod 时首屏会显示 200，而那是首屏最显眼的数字。
- **另一处计划外的必要修复**：`资源明细` tab 的三态要用 `breakdownLoaded` 区分「还没拉」与「不可用」（原写法首屏会误显示「资源明细不可用」，实测发现）。
- **资源明细表的列（用户 2026-10-10/11 三轮要求，覆盖本计划 Phase 2 Tab 3 的列清单）**：用量列**只留内存**（表头 `内存用量`，值 = Thanos by-namespace 的最近采样点；无指标 → 「—」）。**CPU 不给点值** —— 用户 2026-10-11：CPU 是尖峰型指标，单点没有代表性，而内存（working set）是 gauge、单点稳定可比；**Top-10 也随之从「按 CPU 用量」改成「按内存用量」**；**2026-10-11 用户又要求去掉 Top-N 的「无指标回退 requests」**（口径 §2 原本写着回退）—— 用 requests 顶替会把「申领大、实际小」的命名空间排到前面，正是这个榜要避免的误导；现在只按实测值排，**无实测值的照常列出**（不藏起来）、排末位、值显示「—」。**`/cluster/metrics/cpu/by-namespace` 因此从 Tab 3 的懒加载里移除**（少打一次 Thanos 区间查询；后端端点与 api 方法保留 —— 后续折线看板会用）。也试过「`used / requests` 合并成一格」，结论是把"实际用量"和"申领量"揉一起反而看不清，故分开：requests 不再单独占列（有配额的 ns 可从配额列的记账值间接读到）。排序键只按本列显示的值（无指标记 -1 → 降序沉底）。两列配额的标题从 `hard / used` 改成 `used / hard`（原标题与单元格顺序反了），表底注释点明「用量列 = Thanos 实际用量」vs「配额占用 = 按 requests 记账的配额消耗」。
- **「超卖」的定义与命名修正（用户 2026-10-10 两轮指出，覆盖 spec 里"超额/超卖比"的模糊说法）**：三件事按顺序纠正 ——
  1. `requests / allocatable` **不是超卖**，是**调度水位**（100% = 排满；**>100% 反而是异常** —— 正常调度下调度器只在新 pod 的 requests 放得下时才绑定，故 Σrequests 恒 ≤ allocatable，只有绕过调度器（直接指定 `nodeName`）或节点减少时才可能超）。
  2. **真的超卖 = `limits / allocatable` > 100%**（承诺量超过可分配量，靠"容器很少同时打满 limits"挤进去），这才是 K8s 语境里的 overcommit。
  3. **但那个比值本身必须用中性名**：`limits / allocatable` 在 ≤100% 时是**非常健康**的水平（最坏情况都放得下），所以字段名是 **`limits 占比`**，**「超卖」只作为 >100% 时的判定**出现（此时才显示 `已超卖` 标记）。原实现无条件写「超卖 62%」会把健康值读成告警。
  最终形态：Tab 2 每个资源行头是 `调度水位 40% · limits 占比 160% [已超卖]`（tag 仅在 >100% 时出现），下方两行给出各自公式与阈值判定。两个区间都已在浏览器实测（63% 无标记 / 160% 有标记）。
- **Tab 2 补两张 IO 图（用户 2026-10-10 要求）**：对齐命名空间概览的 4 图布局（CPU / 内存 / 网络 IO / 磁盘 IO）。后端新增 `MetricQuery.CLUSTER_NETWORK_{RECEIVE,TRANSMIT}` + `CLUSTER_DISK_{READ,WRITE}`（照 ns 级那几条去掉 namespace 过滤）、`MetricsService.clusterNetwork/clusterDisk`、端点 `POST /cluster/metrics/{network,disk}`；前端 `clusterMetrics.network/disk` + 面板里两张纯曲线图（无 tile —— 网络/磁盘没有 requests/allocatable 口径可比）。**新增权限行 `V2026_10_10_2`（2 行，仍复用 `platform:cluster:manage`）—— 缺了会被启动交叉校验拒启**。
  - ⚠️ **新代码有意不沿用 ns 级 TRANSMIT 的 `device=~"/dev/dm-.*"`**：`container_network_*` 只有 `interface` 标签、没有 `device`，该匹配器匹配不到任何序列（Pod/Workload/Namespace 三条 TX 很可能恒空，是从磁盘查询抄来的）。既有的三条待一起修 —— 会动到已上线的图，另议。
- **Tab 2 的三口径 tile 只留三格（用户 2026-10-10 要求，覆盖本计划 Tab 2 的「allocatable / allocated / used + 使用率%」写法）**：**去掉「使用率」那一格** —— 它 = `used ÷ allocatable`，而这两个数就在相邻两格里，单占一格是冗余；随时间的使用率由曲线图头的「%」切换承担（`percentable + limit=allocatable`）。现在每资源行 = 三格（`allocatable / requests / used`）+ 行头的 `调度水位 · limits 占比 [已超卖]` + 两行公式说明 + 一张曲线。
- **命名（用户 2026-10-10 要求，覆盖 spec §4.2/§5.4 的措辞）**：UI 上**不再出现「allocated」**这个口径名 —— 它就是 Σ pod `requests`，改用 K8s 原生字段名 **`requests`**；与之成对的 `allocatable` 保留（也是原生字段名）。改名的附带好处：`allocated` / `allocatable` 只差两个字母，摆在一起看必错。涉及：Tab 2 的三口径 tile 标签与超卖比（`requests / allocatable`）、Tab 3 表头 `CPU（used / requests）`、Top-10 的「回退 requests」。DTO/后端字段名（`cpuRequest`/`memRequest`）本来就一致，未动。
- **性能补充**：`资源用量` tab 加 `lazy`（EP 的 `el-tab-pane` 默认不懒，首屏会白打两次 Thanos 区间查询）。
- **未做**：`pod 数 vs kubelet maxPods`（计划标「若易得」，聚合未收集节点 podsLimit，跳过）。
- **部署顺序**：先按序重放 `V2026_10_10_1`、`V2026_10_10_2`，再起新构建（否则 `PermissionCrossCheckRunner` 拒启）。


> ⚠️ **本计划相对旧 spec 的三处新增（2026-10-10 与用户敲定）**：
> 1. **健康/异常视图**（首屏重点）：异常 Pod 按 reason 分组计数 + 可展开看具体 pod；NotReady / 有 pressure 的节点名。数据**搭现有聚合 pass 顺带出**，不多开数据源。
> 2. **存储**：PV 容量（total vs bound）+ PVC 状态计数（bound/pending/lost）；Pending PVC 进健康摘要。
> 3. **资源明细**：顶部 Top-10 命名空间（按用量排）+ 下方完整表。
> 另：页面结构 = 4 tab（spec 原方案）；监控口径 v1 = CPU/内存 + 存储（不含网络/磁盘曲线）。

---

## 0. 落地前必读（约定 & 模板文件）

**B6/B7 是最新落地的范例**（同样 cluster-scoped admin + 聚合 + 前端 ops 页），优先照它们抄。

| 要写什么 | 照抄这个模板 | 关键差异 |
|---|---|---|
| k8s-core 跨命名空间聚合 operation | `k8s-core/.../operations/core/CoreV1NodeOperations.java` 的 `listPodStats()` + `aggregate(List<Pod>)` | **一次 `.inAnyNamespace().list()` pass + 内存聚合 → typed DTO**；`aggregate(List<Pod>)` 包内可见便于单测（照抄这个可测性设计） |
| k8s-server 平台域 controller（自定义端点） | `k8s-server/.../controllers/cluster/ClusterAdminController.java` | `@RequestMapping("/cluster")`（**无前缀**）+ `implements AccessBoundaryAware` → `accessBoundary()=PLATFORM`；照它的 javadoc 写法 |
| platform-api 薄 admin client | `platform-api/.../k8s/K8sLifecycleClient.java` | 一方法一端点，`gateway.exchange(HttpMethod.POST, "/cluster/...", ...)`；`/cluster/**` 归它 |
| platform-api 编排 service | `platform-api/.../services/CalicoService.java`（B7）或 `MeshService` | 降级 + 短 TTL 缓存收这里 |
| platform-api 面向前端 controller | `platform-api/.../controllers/ClusterController.java`（现有 `/cluster`） | 新端点加这里（**一页一 controller**：集群概览页 ↔ ClusterController） |
| Thanos 指标方法 | `MetricsService`（`podXxx/workloadXxx/nodeXxx/namespaceXxx` 那批）+ `MetricQuery` 枚举 | 加 `clusterCpu/clusterMemory(cluster metrics req)` + `byNamespace` 变体；`MetricQuery` 加 `CLUSTER_*` 条目 |
| 指标端点承载 controller | `platform-api/.../controllers/NamespaceController.java` 的 `/metrics/{cpu,...}`（同类先例） | 集群指标挂 `ClusterController`：`POST /cluster/metrics/{cpu,memory}` + `/by-namespace` |
| 前端列表页行操作收敛为下拉 | `platform-web/src/views/RoleView.vue` 或 `TenantView.vue` 的 `el-dropdown`（行操作） | 见 §Phase 2 Step 1 |
| 前端详情页（tab 范式） | `platform-web/src/views/NodeDetailView.vue` | 4 tab；tab 内 stat tile + 表格 |
| 前端指标图表面板 | `platform-web/src/components/workload/MetricsPanel.vue` / `NodeMetricsPanel.vue` + `MetricChart.vue` | 集群级 = 只按 cluster_name 过滤（无 ns）；复用 `MetricChart` |
| 权限行 | `platform-data/.../db/migration/V2026_09_26_1__b3_namespace_permissions.sql`（写法） | 先看 migration 目录现有最大版本号，别撞号 |

### 0.1 现行约定（务必遵守，勿照抄旧 spec 的路径）
- **k8s-server 路径无前缀**：`ClusterAdminController` 在 `/cluster`（不是 `/admin/cluster`）；授权靠 `AccessBoundary` 声明（`ACCESSBoundary.PLATFORM`），不靠前缀。
- **platform-api client 已按域拆分**：`K8sLifecycleClient`(集群生命周期) / `K8sCalicoClient` / `K8sMeshClient` / `K8sNodeClient` / `K8sPodClient`。集群概览的聚合调用归 **`K8sLifecycleClient`**（它已拥有 `/cluster/**`）。
- **面向前端的 controller 顶层前缀**：`/cluster`（现有）。新端点都挂这里。
- **admin client 本就跨命名空间可读**（用户 2026-10-10 确认）→ 聚合 `listPodStats` 已证 `.inAnyNamespace()` 可用，**无需额外 RBAC 讨论**。

---

## Phase 1 · 后端数据平面（聚合 + 端点 + 指标 + RBAC）

### Step 1 — platform-common DTO
新建（`extends BaseResources` 按需；聚合 DTO 是纯计算产物，可不继承）：
- `ClusterAggregateDTO`（聚合原语总输出）：
  - `total: ClusterTotalDTO`
  - `namespaces: List<NamespaceStatDTO>`
  - `abnormalPods: List<AbnormalPodDTO>`（**封顶 200 条**）+ `abnormalPodTotal: Integer`
  - `nodeHealth: List<NodeHealthDTO>`
  - `storage: StorageStatDTO`
- `ClusterTotalDTO`：`cpuRequest, memRequest, cpuLimit, memLimit: BigDecimal`（核/字节）；`podCount, deploymentCount, statefulsetCount, daemonsetCount, serviceCount, namespaceCount, pvcCount, pvCount: Integer`。
- `NamespaceStatDTO`：`namespace`；`podCount, deployCount, stsCount, dsCount, svcCount, pvcCount: Integer`；`cpuRequest, memRequest, cpuLimit, memLimit: BigDecimal`；`quotaCpuHard/MemHard/Used: BigDecimal`（nullable，B3 已落地可填）。
- `AbnormalPodDTO`：`namespace, name, phase, reason, restarts: Integer, node, ageSeconds: Long`。
- `NodeHealthDTO`：`name, ready: Boolean, pressures: List<String>`（MemoryPressure/DiskPressure/PIDPressure）, `version`。
- `StorageStatDTO`：`pvCount, pvcBound, pvcPending, pvcLost: Integer`；`pvCapacityBytes, pvBoundBytes: BigDecimal`。
- `ClusterOverviewDTO`（`POST /cluster/overview` 出参）：`nodeSummary{total,ready,notReady}` + `notReadyNodes: List<NodeHealthDTO>` + `resourceCapacity{cpuAllocatable, memoryAllocatable: BigDecimal}` + `resourceTotal: ClusterTotalDTO` + `capabilitySummary{hasMetricsServer,hasCustomMetrics,hasExternalMetrics,hasMonitoringOperator,hasGatewayApi,hasIstio,hasCalico: Boolean}` + `storage: StorageStatDTO` + `abnormalPods` + `abnormalPodTotal`。
- 单位遵循平台约定：cpu=核、memory=字节、`BigDecimal`；Quantity↔BigDecimal 走 `QuantityUtil`。

### Step 2 — k8s-core 聚合 operation ★架构核心
新建 `ClusterAggregationOperations`（`listPodStats` 的兄弟；构造注入 admin `KubernetesClient`，clusterId 无关——client 由 factory 按集群给）：
- `aggregate()`：
  - `.inAnyNamespace()` 一次列出 **pods** → 累加 per-ns 的 cpu/mem requests+limits、podCount；**同 pass 归出异常 pod**（判定见 §口径）→ `abnormalPods`（封顶 200）+ `abnormalPodTotal`。
  - `.inAnyNamespace()` 列出 **deployments/statefulsets/daemonsets/services** → per-ns 计数。
  - `.inAnyNamespace()` 列出 **resourcequotas** → 填 per-ns `quota*`（B3 已落地）。
  - `.inAnyNamespace()` 列出 **PVCs** → 计数 + bound/pending/lost 归类。
  - **cluster-scoped** 列 **PVs** → `pvCapacityBytes`(Σ spec.capacity) + `pvBoundBytes`(Σ 已 Bound) + pvCount。
  - **cluster-scoped** 列 **nodes** → `nodeHealth`（ready + pressures + version）+ Σ `status.allocatable.{cpu,memory}`。
  - 累加出 **per-ns 行 + total 行** → `ClusterAggregateDTO`。
- **可测性**：内部聚合拆成接受 `List<Pod>`/`List<Deployment>` 等参数的包内方法（照 `listPodStats.aggregate(List<Pod>)`），单测不依赖 client。
- **性能**：多次 `.inAnyNamespace().list()`，大集群偏重 → v1 接受一次性；platform-api 侧加**短 TTL 缓存（30–60s）**（同 B7 CalicoService）。**不逐命名空间循环 list**（会 O(ns) 次 API 调用）。

### Step 3 — k8s-server 薄边界端点
`ClusterAdminController`（`/cluster`，已声明 `PLATFORM`）加：
- `POST /cluster/resource-aggregate`（`@RequestBody AdminClusterKeyRequest`）→ 调 `ClusterAggregationOperations`，原样返回 `ClusterAggregateDTO`。**零业务逻辑**（同 `capability/refresh` 先例）。
- 用 `AccessBoundaryAware` 既有声明即可，无需改边界。

### Step 4 — platform-api
- `K8sLifecycleClient` 加 `resourceAggregate(clusterId)` → `POST /cluster/resource-aggregate`。
- `ClusterService` 加：
  - `overview(clusterId)`：调 `resourceAggregate` 取 `total` + `nodeHealth`/`storage`/`abnormalPods` + 读现有节点 list 算 nodeSummary/allocatable + 读 `k8s_cluster.capability` 派生 flag → `ClusterOverviewDTO`。**降级**：任一段不可用 → 该段 null/空 + 前端「—」，不整体抛。
  - `resourceBreakdown(clusterId)`：同源取 `namespaces` 行（懒加载）。
  - TTL 缓存（30–60s）包住 `resourceAggregate`。
- `ClusterController`（`/cluster`）加：
  - `POST /cluster/overview` → `overview`
  - `POST /cluster/resource-breakdown` → `resourceBreakdown`
  - `POST /cluster/metrics/{cpu,memory}` + `POST /cluster/metrics/{cpu,memory}/by-namespace` → `MetricsService`（见 Step 5）
- **RBAC（必做，否则启动 brick）**：新增 `/cluster/*` 端点会被 `PermissionCrossCheckRunner` 枚举 → 加 Flyway migration 补权限行，code 照现有 `/cluster/get` 那批（应是 `platform:cluster:manage`）。**先读现有 /cluster 权限行确认 code，再照抄写法 + ODKU。**

### Step 5 — 集群级 Thanos 指标
- `MetricQuery` 加：
  - `CLUSTER_CPU_USED` = `sum(rate(container_cpu_usage_seconds_total{cluster_name="%s",container!="POD",pod!=""}[2m]))` → 核
  - `CLUSTER_MEMORY_USED` = `sum(container_memory_working_set_bytes{cluster_name="%s",container!="POD"})` → 字节
  - `CLUSTER_CPU_USED_BY_NS` = `sum by (namespace)(rate(...同上...))`；`CLUSTER_MEMORY_USED_BY_NS` = `sum by (namespace)(...)` → per-ns used
- `MetricsService` 加 `clusterCpu/clusterMemory(ClusterMetricsRequest{clusterId,start,end})` + `clusterCpuByNamespace/clusterMemoryByNamespace(...)`；复用 `clusterName(clusterId)` + `queryRange(sql, start, end, STEP, MetricLabels.class)` + `toSeries`。
- 占位符 `%s` = clusterName（同 namespace 维度写法）。

### Step 6 — 验证 Phase 1
```bash
mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test
```
- 单测：`ClusterAggregationOperationsTest`（喂 mock 的 pods/deployments/nodes/pvs/pvcs，断言 total/per-ns 计数、CPU·内存、**异常 pod 归组**、节点 pressures、存储容量/状态；**含「大集群不逐 ns list」的意图验证——断言只调了固定几次 `inAnyNamespace().list()`**）。异常判定边界用例（Running-but-CrashLoopBackOff 要算异常）。
- 端点冒烟（可选，需活集群）：`/cluster/overview` 返回完整快照；`/cluster/resource-breakdown` 回 per-ns；`/cluster/metrics/cpu` 回曲线。

**Phase 1 做完 → 停一下给用户看（端点通了），再进前端。**

---

## Phase 2 · 前端（ClusterView 收敛 + ClusterDetailView 4 tab）

### Step 1 — ClusterView 行操作收敛（spec §6.1）
- `ClusterView.vue` 行操作列（[ClusterView.vue:228](platform-web/src/views/ClusterView.vue:228) 现在 4 个裸按钮，width 300）→ 改成 `el-dropdown`（**查看** / 编辑 / 重新开通 / 刷新能力 / divided 删除），照 `TenantView.vue`/`RoleView.vue` 的行操作下拉写法（`MoreFilled` 图标 + `el-dropdown-menu`）。**启用 switch 保留内联**。
- **集群名称可点击** → `/clusters/detail?clusterId=<id>`；下拉首项「查看」同跳。

### Step 2 — 新建 `ClusterDetailView.vue`（4 tab，NodeDetailView 范式）
路由 `/clusters/detail?clusterId=`（name `cluster-detail`，meta group='平台管理'，requiresPerm 同 `/clusters` 的 `pageCodes.cluster`）。顶部：集群选择/名称 + 刷新 + 返回。

- **Tab 1「概览」**（首屏）：
  1. **健康/异常摘要**（最上）：
     - 节点：`total / ready / notReady` + **NotReady / 有 pressure 的节点名**（tag 或列表，hover 显 pressure 类型）。
     - **异常 Pod**：按 `reason` 分组计数（`CrashLoopBackOff 3 · Pending 2 · …`）——点一组**展开/弹窗**看具体 pod（名字/ns/reason/重启次数/age），行可跳 Pod 详情页。
     - **Pending PVC** 数（>0 时提示）。
  2. 基本信息（`/cluster/get`：名称/状态/启用/版本/运行时/IP 栈/描述/Prometheus·Grafana/最后心跳/创建时间）。
  3. 组件版本（Istio/Calico）+ capability flags（tag 展示）。
- **Tab 2「资源用量」**：
  - CPU / 内存：**allocatable / allocated / used 三口径 + 使用率%**（allocatable=overview.resourceCapacity、allocated=overview.resourceTotal、used=Thanos 当前值）+ 各一张**实时曲线**（`/cluster/metrics/{cpu,memory}`，复用 `MetricChart` + 时间范围预设 15/30/60min）。
  - **存储块**：PV 容量（total vs bound）+ PVC 状态计数（bound/pending/lost）。
  - ➕ 常识项：超卖比 `allocated/allocatable`、pod 数 vs kubelet `maxPods`（若易得）。
- **Tab 3「资源明细」**：
  - 顶部 **Top-10 命名空间**（按 CPU used 排，无指标回退 allocated；带迷你占比条）。
  - 下方**完整 per-ns 表**（命名空间 / Pod·Deploy·Sts·Ds·Svc 计数 / CPU·内存 allocated / used(by-ns Thanos) / 配额 hard·used），可搜索 + 排序；行可点跳该 ns 详情。**懒加载** `/cluster/resource-breakdown` + `/cluster/metrics/{cpu,memory}/by-namespace`。
- **Tab 4「工作负载」**：对象计数网格（Deploy/Sts/Ds/Pod/Svc/命名空间）+ ➕ **不健康 workload**（`readyReplicas < replicas` 的 Deploy/Sts）。

### Step 3 — 注册点
- **router/index.ts**：加 `/clusters/detail`。
- **api/index.ts**：`clusterApi.overview(clusterId)` / `clusterApi.resourceBreakdown(clusterId)`；`metricsApi.clusterCpu/clusterMemory(/byNamespace)`（沿用现有 metrics api 形态）。
- **types.ts**：`K8sClusterOverview`、`K8sClusterResourceTotal`、`K8sNamespaceResourceStat`、`K8sAbnormalPod`、`K8sNodeHealth`、`K8sStorageStat`；复用 `MetricSeriesResponse`。

### Step 4 — 验证 Phase 2
```bash
npm --prefix platform-web run build   # vue-tsc strict，注意 noUnusedLocals
```
- 手工冒烟（活后端）：列表行操作下拉 + 名称可点；概览页 4 tab 全通；异常 pod 展开；曲线时间范围切换；资源明细 Top-N + 全表；断开集群降级。

**Phase 2 做完 → 停一下给用户看。**

---

## Phase 3 · 降级 / 性能 / 门禁 打磨
- **降级**：集群断开/status=ERROR → 基本信息（DB）照常，聚合段/breakdown/曲线降级「—」+ 顶部「集群未连接，实时数据不可用」；Thanos 无数据 → used/曲线「暂无数据」，allocatable/allocated 不受影响；capability 未探测 → flags 全 false + 提示「可点刷新能力」。
- **性能**：`resourceAggregate` 的 platform-api TTL 缓存（30–60s）；概览页 `overview` 不灌 per-ns 全量（breakdown 懒加载）。
- **门禁**：capability 缺失不阻塞基本信息展示。
- 回归：
```bash
mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test
npm --prefix platform-web run build
```

---

## 口径（已与用户敲定，实现按此）
1. **异常 Pod 判定**：`phase ∈ {Pending, Failed, Unknown}`，**或** Running 但任一容器处于 `waiting`（CrashLoopBackOff / ImagePullBackOff / ErrImagePull）或有 error `terminated`（OOMKilled / Error）→ 归组；`reason` 取最严重的那个（CrashLoopBackOff > OOMKilled > ImagePullBackOff > Pending > …）。
2. **Top-N**：N=10，按 Thanos CPU used 排；无指标回退按 allocated。
3. **不健康 workload**：`readyReplicas < replicas`（Deploy/Sts）。
4. **曲线**：复用 NodeMetricsPanel 的时间范围预设（15/30/60 min）。

## 横切 caveat（实现期处理）
1. **NodeDTO 可能缺 conditions**：节点 health 要 ready + pressures + version。**先查 `NodeDTO` 现有字段**——若没有 `conditions`，在 k8s-core 节点 converter 补（读 `node.status.conditions`），别在 platform-api 侧硬凑。
2. **聚合性能**：多次 `.inAnyNamespace().list()`；**绝不逐命名空间循环**；大集群靠 TTL 缓存 + 懒加载。
3. **RBAC cross-check**：每个新增 platform-api 端点都要权限行或豁免，否则启动 brick（Phase 1 Step 4）。
4. **单位**：cpu=核、memory=字节，`BigDecimal`（`QuantityUtil`）；前端 `formatQuantity` 换算。
5. **多集群不串**：Thanos 查询按 `cluster_name="%s"` 过滤；per-ns used 用 `sum by(namespace)` 且与明细表行对齐。
6. **admin 跨 ns 读无需额外权限**（用户已确认）。

## 验证命令（每 Phase 末）
```bash
mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test
npm --prefix platform-web run build
```

## 验收（对照 spec §8 + 本次新增）
1. 列表行操作收敛为下拉（查看/编辑/重新开通/刷新能力/删除），启用 switch 仍内联；名称可点进概览。
2. 概览页 4 tab 全通；**tab1 首屏有健康/异常摘要**（异常 pod 分组计数 + 可展开具体 pod；NotReady/pressure 节点名；Pending PVC）。
3. `/cluster/overview` 一次返回总量快照（不灌全量对象）+ 健康 + 存储 + 能力；`/cluster/resource-breakdown` 按需回 per-ns；单位正确。
4. 资源用量 tab：CPU/内存 allocatable·allocated·used + 曲线；**存储块**（PV 容量 + PVC 状态）。
5. 资源明细 tab：**Top-10** + 完整表；实时曲线按 cluster_name 取数、多集群不串；时间范围切换生效。
6. 工作负载 tab：计数网格 + 不健康 workload。
7. 集群断开时基本信息可见、聚合段/breakdown/曲线优雅降级「—」。

## 建议推进节奏
Phase 1（后端数据平面）→ 停一下给用户看 → Phase 2（前端 ClusterView 收敛 + ClusterDetailView 4 tab）→ 停一下 → Phase 3（降级/性能/门禁打磨）。每 Phase 末跑验证命令。
