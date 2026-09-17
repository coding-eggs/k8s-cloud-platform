# B4 · 集群概览（操作收敛 + 概览页 + Thanos 实时曲线 + 跨命名空间聚合）

- **日期**：2026-09-14
- **状态**：草稿，待评审
- **覆盖条目**：原始清单 9
- **范围**：本阶段只交付规格；实现另开
- **范式参照**：`NodeDetailView`（tab + stat tile）+ `NodeMetricsPanel`（Thanos 曲线渲染）+ `CoreV1NodeOperations.listPodStats()`（k8s-core 跨命名空间聚合先例）

---

## 1. 背景与目标

当前集群管理（`ClusterView.vue`，「平台管理」组）**行操作是 4 个裸按钮**（编辑/重新开通/刷新能力/删除），未收敛成下拉；无「查看」、无概览页。本批：

| 子项 | 目标 |
|---|---|
| 9a | **其他操作收敛**成行操作下拉（像 Workload/Hpa 等页面）；集群名称可点击 + 新增「查看」进入概览页 |
| 9b | **新增集群概览页**：完整概览 + **后端跨命名空间聚合**（总量 + per-namespace 明细，为配额铺路）+ **Thanos 实时曲线** |

## 2. 范围

**In scope（本批交付）**
- 后端：**k8s-core 跨命名空间聚合 operation**（`.inAnyNamespace()` 一次 pass → per-ns 行 + 集群总量行，typed DTO）；k8s-server admin 薄端点；platform-api `ClusterService` + `POST /cluster/overview`（总量快照）+ `POST /cluster/resource-breakdown`（per-ns 明细）。
- **Thanos 集群级实时指标**：新增 cluster 级 PromQL（by `cluster_name`，含 by-namespace 变体）+ `MetricsService` 方法 + 指标端点。
- 前端：`ClusterView.vue` 行操作收敛为下拉 + 名称可点击；新增 `ClusterDetailView.vue` 概览页（基本信息 / **资源用量：allocatable·allocated·used 三口径 + Thanos 曲线** / **资源明细 per-ns 表** / 工作负载计数）；router / api client / types。

**Out of scope（v1 不做）**
- **配额列填充**：`NamespaceStat` 的 `quota*` 字段**结构预留、v1 留空**，待 B3（ResourceQuota）落地后由同一 operation 顺手填（见 §5.4）。B4 不依赖 B3。
- overview 内 per-pod 明细：概览只到 namespace 粒度；单 Pod 看工作负载页。
- 集群 YAML：K8s 无「集群对象」YAML，不设 YAML tab。

## 3. 现状盘点（探查结论）

| 项 | 现状 | 对本批的意义 |
|---|---|---|
| `ClusterView.vue` | 4 裸操作按钮；启用=内联 switch；无查看/概览 | 收敛为下拉 + 名称可点 + 新增查看 |
| `K8sCluster` 模型 | name/version/containerRuntime/istio/calico/ipStack/description/prometheusUrl/grafanaUrl/status/enabled/lastHeartbeatTime/capability(JSON) | 概览「基本信息」取自 `/cluster/get`（已有） |
| **跨命名空间聚合先例** | `CoreV1NodeOperations.listPodStats()`：`client.pods().inAnyNamespace().list()` → 内存 group-by + sum → typed DTO；类注释「所有 K8s 语义都收在这里，k8s-server 只做边界透传」 | **本批聚合 operation 的范式**：`.inAnyNamespace()` + 一次 pass 出 rows+total，K8s 语义归 k8s-core |
| **节点（cluster-scoped）** | `CoreV1NodeOperations.list()` = `client.nodes().list()`；`status.allocatable.{cpu,memory}` 可读 | 节点健康 + allocatable 直接取，无跨 ns 问题 |
| **Thanos 栈（已存在）** | `ThanosQueryClient`：全局单端点、按 **`cluster_name`** 标签区分多集群、`query`/`queryRange`。`MetricsService`：Pod/Workload/Node 各维度 CPU/内存，STEP=15s → `MetricSeriesResponse{unit, series[]}`。`MetricQuery`：PromQL 模板（Pod/Workload 按 `cluster_name="%s"`[+`namespace="%s"`] 过滤）。前端 `NodeMetricsPanel` 已渲染曲线 | **实时 used 完全复用**：cluster 级 = 去 ns 选择器；per-ns = `sum by(namespace)`。used 维度不碰 K8s 列表 |
| k8s-server 访问模型 | namespaced 资源 client（`AbstractNamespacedResourceController.list`）走 `resolveNamespacedAccess` **分配表边界、按 namespace**——租户域，**不能**做集群级全量；admin 路径（`K8sAdminClient`→`/admin/*`，PLATFORM:admin、无租户上下文）为集群级 | 集群级聚合走 **admin 路径 + `.inAnyNamespace()`**（见 §5.4） |
| capability 列 | B2 已规划读同一列 | 概览「API 能力摘要」复用同列派生 flag |

## 4. 数据模型与端点

### 4.1 k8s-core 聚合原语（`ClusterResourceAggregateDTO`）★架构核心
一次 `.inAnyNamespace()` pass 产出 **per-ns 行 + 集群总量行**：
```
ClusterResourceAggregateDTO {
  total:      ClusterResourceTotalDTO     // ← 「总量」，Σ 出来的那一行
  namespaces: List<NamespaceStatDTO>       // ← 「汇总命名空间」明细行
}
ClusterResourceTotalDTO {
  cpuRequest, memRequest, cpuLimit, memLimit: BigDecimal   // allocated（核/字节）
  podCount, deploymentCount, statefulsetCount, daemonsetCount, serviceCount, namespaceCount: Integer
}
NamespaceStatDTO {
  namespace: String
  podCount, deploymentCount, statefulsetCount, daemonsetCount, serviceCount: Integer
  cpuRequest, memRequest, cpuLimit, memLimit: BigDecimal   // allocated
  quotaCpuHard, quotaMemHard, quotaCpuUsed, quotaMemUsed: BigDecimal   // nullable，B3 落地后填
}
```
- 单位遵循平台约定：cpu=核、memory=字节，`BigDecimal`；Quantity ↔ BigDecimal 走 `QuantityUtil`。
- **加配额 = operation 里多填一个字段**，DTO/端点结构不变 → 「以后要总量/配额」不返工。

### 4.2 `POST /cluster/overview`（轻量快照）
`ClusterOverviewDTO`：
| 字段 | 类型 | 说明 |
|---|---|---|
| `nodeSummary` | { total, ready, notReady: Integer } | 节点健康（cluster-scoped node list） |
| `resourceCapacity` | { cpuAllocatable, memoryAllocatable: BigDecimal } | Σ `node.status.allocatable.{cpu,memory}` |
| `resourceTotal` | ClusterResourceTotalDTO | allocated 总量 + 对象计数（取聚合原语的 `total` 行） |
| `capabilitySummary` | CapabilitySummaryDTO | flag：`hasMetricsServer/hasCustomMetrics/hasExternalMetrics/hasMonitoringOperator/hasGatewayApi/hasIstio/hasCalico`（为 B6/B7 铺路） |

### 4.3 `POST /cluster/resource-breakdown`（per-ns 明细，按需）
入参 `{ clusterId }`；出参 `List<NamespaceStatDTO>`（取聚合原语的 `namespaces` 行）。**只在打开「资源明细」tab 时拉**（懒加载），不灌进 overview。

### 4.4 Thanos 集群级实时指标（复用现有栈）
- **新增 `MetricQuery` 条目**：
  - `CLUSTER_CPU_USED` = `sum(rate(container_cpu_usage_seconds_total{cluster_name="%s",container!="POD",pod!=""}[2m]))` → **核**
  - `CLUSTER_MEMORY_USED` = `sum(container_memory_working_set_bytes{cluster_name="%s",container!="POD"})` → **字节**
  - `CLUSTER_CPU_USED_BY_NS` = `sum by (namespace)(rate(...同上...))`；`CLUSTER_MEMORY_USED_BY_NS` = `sum by (namespace)(...)` → per-ns used（喂 §4.3 明细表的 used 列）
- **`MetricsService` 新增**：`clusterCpu/clusterMemory(ClusterMetricsRequest{clusterId,start,end})` + `clusterCpuByNamespace/clusterMemoryByNamespace(...)`；复用 `clusterName(clusterId)` + `queryRange(sql, start, end, STEP, MetricLabels.class)` + `toSeries`。
- **指标端点**：加到现有承载 node/workload 指标的 controller（同形态）：`POST /metrics/cluster/{cpu,memory}`、`POST /metrics/cluster/{cpu,memory}/by-namespace`。返回 `MetricSeriesResponse`，前端图表直接吃。

## 5. 后端各层改动

### 5.1 k8s-core（聚合的家）
- **新增 `ClusterResourceAggregationOperations`**（`listPodStats` 的兄弟，构造注入 admin `KubernetesClient` + converter）：
  - `.inAnyNamespace()` 列 pods（requests/limits/podCount）、deployments/statefulsets/daemonsets/services（计数）。
  - **一次 pass** 累加出 per-ns 行 + total 行 → `ClusterResourceAggregateDTO`。
  - （B3 后）同 pass 顺手 `.inAnyNamespace()` 列 resourcequotas，把 hard/used 挂到对应 ns 的 `quota*` 字段。
- **节点**：复用现有 `CoreV1NodeOperations.list()`（cluster-scoped），platform-api 侧算 nodeSummary + Σ allocatable。

### 5.2 k8s-server（薄 admin 边界，零业务逻辑）
- 在 `ClusterAdminController`（`/admin/cluster`，PLATFORM:admin、无租户上下文）加：
  - `POST /admin/cluster/resource-aggregate` → 调 `ClusterResourceAggregationOperations`，原样返回 `ClusterResourceAggregateDTO`。
  - （节点若需 admin 端点则复用/新增；否则 platform-api 走既有 node list 路径。）
- **数据整形、不含业务规则**（同 `capability/refresh` 回 discovery 快照先例）→ 不违反「k8s-server 零逻辑」。

### 5.3 platform-api（编排 + 展示规则）
- `ClusterService.overview(clusterId)`：调 admin `resource-aggregate` 取 `total` + node list 算 nodeSummary/allocatable + capability 列派生 flag → `ClusterOverviewDTO`。
- `ClusterService.resourceBreakdown(clusterId)`：调同一 admin 端点，返回 `namespaces` 行（+ per-ns used 由前端走 Thanos by-namespace 拼）。
- **降级**：某源不可用 → 该段 null + 前端「—」，不整体报错。

## 6. 前端改动汇总

### 6.1 `ClusterView.vue`（列表重构）
- **行操作收敛为下拉**（`el-dropdown`，同 Workload/Hpa）：查看、编辑、重新开通、刷新能力、分隔、删除。**启用 switch 保留内联**。
- **集群名称可点击** → `/clusters/detail?clusterId=<id>`；下拉首项「查看」同跳。

### 6.2 `ClusterDetailView.vue`（新增概览页）★
路由 `/clusters/detail?clusterId=<id>`，复用 NodeDetailView tab 范式：
1. **概览**：基本信息（`/cluster/get`：名称/状态/启用/版本/容器运行时/IP栈/描述/Prometheus/Grafana/最后心跳/创建时间）+ 组件版本（Istio/Calico，呼应 B6/B7）+ stat tile（节点 ready/total、CPU·内存 allocatable）+ API 能力摘要（capabilitySummary flag 以 tag 展示）。
2. **资源用量**：CPU / Memory 各一条「**allocatable / allocated / used + 使用率%**」三口径（allocatable=overview、allocated=overview.resourceTotal、used=Thanos 当前值），下方各一张 **实时曲线图**（`/metrics/cluster/{cpu,memory}`，复用 NodeMetricsPanel 图表组件 + 时间范围预设）。
3. **资源明细**：per-namespace 表（懒加载 `/cluster/resource-breakdown`）——列：命名空间 / Pod·Deployment·Sts·Ds·Svc 计数 / CPU·内存 allocated(request) / used(Thanos by-ns) / [配额 hard·used，B3 后]。行内可点跳该 ns。
4. **工作负载**：resourceTotal 的对象计数网格（Deployment/StatefulSet/DaemonSet/Pod/Service/命名空间）。
- 顶部「返回」+ 刷新；集群禁用/断开时提示。

### 6.3 注册点
- **router**：`/clusters/detail`(name `cluster-detail`)，meta group=平台管理。
- **api/index.ts**：`clusterApi.overview(clusterId)` / `clusterApi.resourceBreakdown(clusterId)`；`metricsApi.clusterCpu/clusterMemory(/byNamespace)`（沿用现有 metrics api 形态）。
- **types.ts**：`K8sClusterOverview`、`K8sClusterResourceTotal`、`K8sNamespaceResourceStat`；复用 `MetricSeriesResponse`。

## 5.4 跨命名空间聚合机制（回答「怎么搞」）★
- **为什么不用现有 namespaced client**：`AbstractNamespacedResourceController.list` 走 `resolveNamespacedAccess`（分配表边界、按 namespace），租户域设计，无法列全集群。
- **三层分工**（§5.1–5.3）：聚合逻辑归 **k8s-core operation**（`.inAnyNamespace()` + 一次 pass → rows+total，同 `listPodStats` 先例）；k8s-server 只 admin 薄透传；platform-api 编排 + 展示规则。
- **双数据平面**：结构/静态口径（allocated·计数·allocatable·配额）走 K8s 聚合 op；实时 used 走 Thanos（per-ns = `sum by(namespace)`，总量 = 去 ns）。两平面拼出 allocatable/allocated/used/quota 四口径。
- **为配额铺路**：`NamespaceStatDTO.quota*` 结构预留、v1 留空；B3 落地后 operation 同 pass 填 resourcequota hard/used，端点/DTO 不变。

## 7. 边界与异常

| 场景 | 行为 |
|---|---|
| 集群断开 / status=ERROR | 基本信息照常（DB）；overview 聚合段 + breakdown + Thanos 曲线降级「—」+ 顶部提示「集群未连接，实时数据不可用」 |
| Thanos 无该集群数据 / 查询失败 | 资源用量 used 列/曲线显示「暂无数据」，allocated/allocatable 不受影响 |
| capability 列未探测（空） | capabilitySummary 全 false + 提示「能力未探测，可点『刷新能力』」 |
| 跨命名空间聚合不可用 | resourceTotal / breakdown 段降级「—」，节点健康与基本信息不受影响 |
| B3 未落地 | `quota*` 列显示「—」（结构在、值空），不报错 |

## 8. 验收标准（怎么算做完）

1. 列表行操作收敛为下拉（查看/编辑/重新开通/刷新能力/删除），启用 switch 仍内联；集群名称可点击进概览。
2. 概览页四 tab 全通：基本信息+节点健康+能力摘要 / **资源用量 allocatable·allocated·used + Thanos 曲线** / **资源明细 per-ns 表** / 工作负载计数。
3. `POST /cluster/overview` 一次返回总量快照（不灌全量对象）；`/cluster/resource-breakdown` 按需回 per-ns 行；单位正确（cpu 核/memory 字节）。
4. 实时曲线按 `cluster_name` 正确取数、多集群不串；per-ns used 用 `sum by(namespace)` 对齐明细表行；时间范围切换生效。
5. 集群断开时基本信息可见、聚合段/breakdown/曲线优雅降级为「—」。

## 9. 待确认 / 风险

- **admin client 权限**：`resource-aggregate` 依赖 admin client 覆盖全集群（`listPodStats` 已证 `.inAnyNamespace()` 可用）；实现时确认。
- **聚合性能**：per-ns breakdown 需多路 `.inAnyNamespace().list()`（pods + 各 workload），大集群偏重 → v1 接受一次性 + platform-api 侧可选短 TTL 缓存（30–60s）；overview 的 total 同源。
- **实时曲线范围**：v1 CPU/内存两条（+by-ns 变体喂明细表）；网络/磁盘可选后加。
- **used 口径**：容器级 `container_*`（cAdvisor），与现有 Pod/Workload 曲线同源一致；非 node_exporter 主机口径。
- **配额依赖 B3**：`quota*` 列 B3 落地前恒空，属预期（结构已预留）。
