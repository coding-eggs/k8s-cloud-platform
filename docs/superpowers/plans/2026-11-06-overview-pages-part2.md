# 平台总览与各维度概览 · 第 2 册（platform-api 服务、接口、权限）

> 全局约束、Review Focus 见 [第 1 册](2026-11-06-overview-pages.md)。本册依赖 Task 1–4 的产出。

### Task 5: 中间件实时状态（按集群缓存）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/services/MiddlewareService.java`
- Test: `platform-api/src/test/java/com/coding/platformapi/services/MiddlewareServiceTest.java`（已有则加用例）

**Interfaces — Produces:**
- `public record LiveStatus(String namespace, String tenantId, String status) {}`（`MiddlewareService` 内的嵌套 record）
- `public List<LiveStatus> liveStatuses(String clusterId)`：取该集群全部未删除实例，按 `(code, namespace)` 分组走现有 `refreshGroup` 覆盖实时状态；结果用 `RedisJsonCache.get("overview:mw:" + clusterId, Duration.ofSeconds(45), …)` 缓存。k8s-server 读失败时保留库值（与列表页同语义）。
- 不在这里做租户 / 命名空间过滤：调用方按 `namespace`、`tenantId` 过滤。

- [ ] Step 1: 测试 `liveStatusesOverridesStoredStatus`：库里一条 READY，mock `client.status` 返回 DEGRADED → 结果 status 为 DEGRADED；`liveStatusesKeepsStoredWhenProbeFails`：`client.status` 抛异常 → 保留 READY。
- [ ] Step 2: FAIL → Step 3: 实现（缓存 loader 内调用，测试里用直通的 cache mock）→ Step 4: PASS；提交。

### Task 6: 平台服务健康探测

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/services/overview/PlatformHealthProbe.java`
- Modify: `platform-api/src/main/resources/application.yaml`（新增 `platform.overview.health-targets`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/overview/PlatformHealthProbeTest.java`

**Interfaces — Produces:**
- 配置：`platform.overview.health-targets`（`Map<String,String>`，服务名 → health URL）。默认：
  `platform-auth: ${OAUTH2_SERVER:http://127.0.0.1:9000/auth}/actuator/health`、
  `k8s-server: ${K8S_SERVER_URL:http://127.0.0.1:8080}/actuator/health`、
  `platform-gateway: ${PLATFORM_GATEWAY_HEALTH_URL:}`（**空 = 不探测、不计入分母**）。platform-api 自身固定算 UP。
- `public record ServiceHealth(int up, int total, List<String> down) {}`
- `public ServiceHealth probe()`：并行 GET，每个超时 2s；HTTP 2xx 且 body `status == "UP"` 记 UP，其余（含超时、401）记 down。
- 前置核对（Step 0）：分别确认 auth / k8s-server 的 `/actuator/health` 匿名可访问；若被安全配置拦（401），在该服务的安全配置里 permitAll 这一个路径（只开 health，不开 metrics），并在本任务提交里一并改。

- [ ] Step 1: 测试用 JDK `com.sun.net.httpserver.HttpServer` 起两个本地端点（一个回 `{"status":"UP"}`、一个回 503）+ 一个空地址：断言 `up=2`（含自身）、`total=3`、`down=["bad"]`。
- [ ] Step 2: FAIL → Step 3: 实现（`@ConfigurationProperties` 或 `@Value` 注入 map；`RestClient` 或 `HttpClient` 均可）→ Step 4: PASS；提交。

### Task 7: 平台总览接口

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/overview/PlatformOverviewView.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/overview/PlatformOverviewService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/controllers/PlatformOverviewController.java`（`@RequestMapping("/overview")`，`POST /platform`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/overview/PlatformOverviewServiceTest.java`

**Interfaces — Produces**（`PlatformOverviewView` 为 record，嵌套 record 同文件）：
```
services: ServiceHealth
clusters: { int connected; int unavailable; List<String> unavailableNames }
alertsFiring: CountBreakdown
applications: CountBreakdown
middleware: CountBreakdown
workloads: { long total; long unhealthy }            // total = deploy+sts+ds
pods: { long total; long running; long pending; long succeeded; long abnormal }
tenants: { long total; long enabled; long disabled; long members; long allocatedNamespaces }
capacity: { BigDecimal cpuRequest, cpuAllocatable, memRequest, memAllocatable }   // 可用集群合计，全不可用则 null
nodes: { long ready; long notReady }
namespaces: { long allocated; long unallocated; long system }
quota: { BigDecimal cpuUsed, cpuHard, memUsed, memHard }   // 只算配了 hard 的 ns；都没配则 null
```
- 数据来源：集群列表（启用的）→ 每个集群 `ClusterService.snapshot(id)`（null = 不可用）+ `NamespaceStatService.rows(null, id, snap.getNamespaceNames())` 求和（`NamespaceStatMerge`）；应用 / 告警计数用 Task 3 SQL（clusterId=null）；中间件 = 各可用集群 `liveStatuses` 合并；租户 = `PlatformTenantMapper.listAll()` + `countDistinctUsers(null)` + `PlatformTenantNamespaceMapper.listAll().size()`；命名空间三类 = 各集群 `namespaceNames` 用 `NamespaceCategory.of`（allocated 集合取该集群的分配行）。
- 集群「已连接 / 不可用」以 snapshot 是否为 null 为准（不是 `k8s_cluster.status` 字段）。

- [ ] Step 1: 测试 `unavailableClusterExcludedFromTotals`：两个集群，A 快照正常（2 节点 Ready、podRunning 5），B 快照 null → `clusters.connected=1, unavailable=1, unavailableNames=["B"]`，`pods.running=5`，`nodes.ready=2`；`capacity` 只含 A。
- [ ] Step 2: 测试 `quotaNullWhenNoNamespaceHasHard`、`namespacesClassifiedPerCluster`（A 有 `kube-system`、`demo`(已分配)、`x`(未分配) → system1/allocated1/unallocated1）。
- [ ] Step 3: FAIL → Step 4: 实现 service + controller → Step 5: PASS；提交。

### Task 8: 范围摘要（集群 / 命名空间 / 租户）

**Files:**
- Create: `platform-api/src/main/java/com/coding/platformapi/models/overview/ScopeSummaryView.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/overview/ScopeSummaryService.java`
- Create: `platform-api/src/main/java/com/coding/platformapi/services/overview/OverviewPermissions.java`
- Modify（各加一个端点，一页一 controller）：`ClusterController` → `POST /cluster/summary {clusterId}`；`NamespaceController` → `POST /namespace/summary {clusterId, namespace}`；`TenantOverviewController` → `POST /tenant/overview/summary {tenantId}`（租户解析同该类现有 `overview`：`ctx.requireContext(tokenTenantId(), req.getTenantId())`）
- Test: `platform-api/src/test/java/com/coding/platformapi/services/overview/ScopeSummaryServiceTest.java`

**Interfaces — Produces:**
- `ScopeSummaryView` record：`CountBreakdown applications, middleware, pipelines, alerts`（无读权限时为 **null**）；`PodsView pods; WorkloadsView workloads; QuotaView quota`（复用 Task 7 的嵌套类型；集群范围为 null，前端用现有集群概览数据）；`NodesView nodes; NamespacesView namespaces`（仅集群范围有值）；`Long namespaceCount, memberCount`（仅租户范围）。
- `ScopeSummaryService`：`cluster(String clusterId)`、`namespace(String clusterId, String namespace)`、`tenant(String tenantId)`。租户范围 = `TenantOverviewService.allocationsByCluster(tenantId)` 逐集群调用 Task 3 SQL 后合并；中间件按 `namespace` 集合过滤 `liveStatuses`。
- `OverviewPermissions.has(String code)`：`JwtPermissionResolver.resolve(auth.current().getUsername(), auth.hatTenantId()).contains(code)`。逐块所需码（任一即可）：应用 `tenant:application:read`；中间件 `tenant:middleware:read`；CI `tenant:pipeline:read`；告警 `tenant:alert:list` 或 `platform:alert:read`。
- 命名空间范围边界：沿用 `NamespaceController` 现有 `/get` 的校验方式（平台侧 `platform:allocation:list`）。

- [ ] Step 1: 测试 `memberWithoutAlertPermissionGetsNullAlerts`：permissions 不含两个告警码 → `alerts == null`，`applications != null`。
- [ ] Step 2: 测试 `tenantScopeSumsAcrossClusters`：分配 {c1:[a], c2:[b]}，c1 应用 READY 2、c2 READY 1 → `applications.total == 3`；`namespaceCount == 2`。
- [ ] Step 3: 测试 `namespaceScopeFiltersMiddlewareByNamespace`：liveStatuses 含 ns a、b 各一条 → namespace(c1,"a") 的 middleware.total == 1。
- [ ] Step 4: FAIL → Step 5: 实现 → Step 6: PASS；提交。

### Task 9: 权限迁移与测试

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_11_06_4__overview_permissions.sql`
- Create: `platform-api/src/test/java/com/coding/platformapi/security/OverviewPermissionMigrationTest.java`

**行**（`ON DUPLICATE KEY UPDATE`，显式 id，风格照 `V2026_11_06_2`）：
| id | resource | action | code | 绑定 |
|---|---|---|---|---|
| perm_overview_platform | /overview/platform | POST | platform:overview:view（新码） | builtin_role_admin |
| perm_cluster_summary | /cluster/summary | POST | platform:cluster:manage | builtin_role_admin |
| perm_ns_summary | /namespace/summary | POST | platform:allocation:list | builtin_role_admin |
| perm_tenant_summary_self | /tenant/overview/summary | POST | tenant:overview:view | admin、tenant_admin、tenant_member |
| perm_tenant_summary_proxy | /tenant/overview/summary | POST | platform:allocation:list | builtin_role_admin |
| perm_node_podstat | /nodes/{name}/podstat | GET | platform:cluster:manage | builtin_role_admin |

- [ ] Step 1: 测试读 SQL 文件，断言上表每个 resource+code 出现、每行都有对应 `platform_role_permission` 绑定。
- [ ] Step 2: 写迁移 → 测试 PASS。
- [ ] Step 3: 全量 `mvn -q -pl platform-api,k8s-server -am test`（后台跑）→ 全绿；提交。
