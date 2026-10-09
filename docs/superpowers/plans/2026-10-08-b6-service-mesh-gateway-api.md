# B6 · 服务网格 Istio / Gateway API — 实施计划（新 session 直接可执行）

- **日期**：2026-10-08
- **规格**：[2026-09-15-service-mesh-gateway-api-design.md](../specs/2026-09-15-service-mesh-gateway-api-design.md)（完整设计/字段/边界在那里；本文件是「怎么落地」的执行清单）
- **当前状态**：**Phase 1 + 2 完成，Phase 3 已被取代（2026-10-09 撤销「每 ns 至多一个」）**，未提交。7 类 Gateway API 对象全栈落地（列表/创建/编辑/YAML/删除）+ mesh-status 横幅；waypoint 数量改为**不设上限**，改用「按 `waypoint-for` 过滤候选 + 类型写进选择器」保障不选错（见 Phase 3 节的修订说明）。测试与 `npm run build` 全绿。**未覆盖项见下方「未覆盖（有意不做 · 记档）」**。
- **下一步**：无（本批收尾）。部署前先重放 `V2026_10_08_4` + `V2026_10_08_5` 两条 Flyway 迁移再起构建。

> ⚠️ **spec 写于 2026-09-15，之后 k8s-server 做了一次「鉴权收敛 + 路径去前缀」重构（2026-10-08，见 `plans/2026-10-08-prefix-and-boundary.md`）。spec 里的 `/admin/mesh/**`、`/resources/**` 路径全部作废。本计划按重构后的现行约定写——以 B7 Calico 的落地代码为准（它就是重构后落的最新范例）。三处关键修正：**
> 1. **路径无前缀、两跳同名**：k8s-server 侧 `/servicemonitors`、`/calico/ippool`（不再是 `/resources/**`、`/admin/**`）；platform-api 侧对应顶层前缀（如 `/calico/**`）。见 §0.3。
> 2. **鉴权靠 `AccessBoundary` 声明**，不靠路径前缀：集群级 controller 继承 `AbstractClusterResourceController`（默认 `PLATFORM`），命名空间级继承 `AbstractNamespacedResourceController`（默认 `TENANT`），非基类 controller 实现 `AccessBoundaryAware` 显式声明。见 §0.3。
> 3. **platform-api 侧 B7 实际用了专用 client `K8sCalicoClient`**（不是 spec 说的往 `K8sAdminClient` 加方法）。B6 照此建 `K8sMeshClient`。见 §0.3。

---

## 0. 落地前必读（约定 & 模板文件）

新 session 先读这几处，全程照抄范式。**B7 Calico 是本次的黄金模板**（同样「7 个 CRD + 集群级/命名空间级混合 + capability 门禁」），优先照它。

| 要写什么 | 照抄这个模板 | 关键差异 |
|---|---|---|
| CRD operations（namespaced） | `k8s-core/.../operations/monitoring/ServiceMonitorOperations.java` | Gateway/HTTP/GRPC/TCP/TLS/UDP Route：scope=Namespaced，保留 `.inNamespace(ns)` |
| CRD operations（cluster-scoped） | `k8s-core/.../operations/calico/IppoolOperations.java`（B7） | GatewayClass：scope=Cluster，去掉 namespace |
| CRD converter（含 atomic list fetch-overlay） | `k8s-core/.../converter/impl/monitoring/ServiceMonitorConverter.java` | rules/listeners/matches/filters/backendRefs 是 atomic list → `convertForUpdate(dto, live)` fetch-overlay 保未建模子字段（Gateway 的 listeners、HTTP/GRPC 的 rules 尤其重要） |
| k8s-server 集群级 controller（6 端点免费） | `k8s-server/.../controllers/calico/IppoolController.java`（B7） | `@RequestMapping("/gatewayclasses")`（**无前缀**）；继承 `AbstractClusterResourceController` → 自动 `PLATFORM` 边界 |
| k8s-server 命名空间级 controller（6 端点免费） | `k8s-server/.../controllers/namespace/ServiceMonitorController.java` | `@RequestMapping("/gateways")` 等（**无前缀**）；继承 `AbstractNamespacedResourceController` → 自动 `TENANT` 边界 |
| k8s-server 非标准端点 controller | `k8s-server/.../controllers/calico/CalicoIpamAdminController.java`（B7） | 实现 `AccessBoundaryAware` 显式声明边界（mesh-status 用 `PLATFORM`） |
| platform-api 专用 admin client | `platform-api/.../k8s/K8sCalicoClient.java`（B7） | 一方法一端点，打 k8s-server 的无前缀路径；纯传输零业务 |
| platform-api 编排 service | `platform-api/.../services/CalicoService.java`（B7） | 降级 + 短 TTL 缓存等业务逻辑收这里 |
| platform-api 面向前端的 controller | `platform-api/.../controllers/CalicoController.java`（B7） | `@RequestMapping("/mesh")` 顶层前缀，委托 service/client |
| platform-api 命名空间级资源 controller | 现有 `ServiceMonitorController`（platform-api 侧，`/servicemonitors`） | 6 namespaced 走现有 `K8sResourceClient`（DTO 驱动），**加 ResourceType 即通，无需新 client** |
| 前端列表页（命名空间级） | `platform-web/src/views/resource/ServiceMonitorView.vue` | 命名空间来自分配上下文，同 ServiceMonitor |
| 前端列表页（平台级） | `platform-web/src/views/ops/IppoolView.vue`（B7） | 集群级、无命名空间；admin-gated |
| 前端独立编辑页 | `platform-web/src/views/resource/ServiceMonitorEditorView.vue`（或 B7 `IppoolEditorView.vue`） | FieldHelp 全覆盖；name 仅创建可填 |
| 前端 detail（tab + YAML tab） | `platform-web/src/views/resource/ServiceMonitorView.vue` 的 detail，或 B7 `IppoolDetailView.vue` | YAML tab 全保真（complex 路由的逃生舱） |
| 导航菜单 | `platform-web/src/layouts/MainLayout.vue`（menu-group 模式，见「集群运维」组） | **B6 新建「服务网格」组**（当前不存在） |
| 路由 | `platform-web/src/router/index.ts` | 新建 mesh 路由，meta.group='服务网格' |
| api client | `platform-web/src/api/index.ts` | 6 namespaced 用 `makeResourceApi<K8sXxx>('plural')`；GatewayClass/mesh-status 用专用 `meshApi` |
| types | `platform-web/src/types.ts` | 见 §0.4 |

### 0.1 CRD 上下文（group/version/kind/plural/scope）

| 资源 | group | kind | plural | scope | 归属 |
|---|---|---|---|---|---|
| GatewayClass | `gateway.networking.k8s.io` | GatewayClass | gatewayclasses | **Cluster** | 集群级（平台管理，admin CRUD + 租户只读引用） |
| Gateway | `gateway.networking.k8s.io` | Gateway | gateways | Namespaced | 租户域 |
| HTTPRoute | `gateway.networking.k8s.io` | HTTPRoute | httproutes | Namespaced | 租户域 |
| GRPCRoute | `gateway.networking.k8s.io` | GRPCRoute | grpcroutes | Namespaced | 租户域 |
| TCPRoute | `gateway.networking.k8s.io` | TCPRoute | tcproutes | Namespaced | 租户域 |
| TLSRoute | `gateway.networking.k8s.io` | TLSRoute | tlsroutes | Namespaced | 租户域 |
| UDPRoute | `gateway.networking.k8s.io` | UDPRoute | udproutes | Namespaced | 租户域 |

> **版本 caveat（实现时按集群实际 served version 核对）**：Gateway/GatewayClass/HTTPRoute 自 Gateway API v1 起 GA；**GRPCRoute 自 v1.1 GA；TCP/TLS/UDPRoute 在标准里仍是 experimental（v1alpha2）**。spec 说「只建模 v1」，但 L4 三个路由在多数集群只有 `v1alpha2`。**实现时读集群 capability 里 `gateway.networking.k8s.io` 的 versions 决定 CRD context 的 version**（可对每个资源写死「优先 v1，缺则 v1alpha2」，或先按集群 capability 分派——同 HPA 的 `buildHpa` 思路，见 `KubernetesOperationsFactory`）。**这条务必先确认，否则 L4 路由 404。**
> fabric8 通用 CRD：`new CustomResourceDefinitionContext.Builder().withGroup(...).withVersion(...).withKind(...).withPlural(...).withScope("Cluster"|"Namespaced").build()`；操作走 `client.genericKubernetesResources(CRD)`。SSA：`.fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply()`。

### 0.2 导航 & 路由（B6 新建「服务网格」组）
- 当前 MainLayout 有：平台管理 / 用户与权限 / 工作负载 / 服务发现 / 配置管理 / 存储 / 监控告警 / 集群运维。**没有「服务网格」组、router 里也没有任何 mesh 占位**。
- **做法**：新建 `<div class="menu-group">服务网格</div>`，跨两上下文：
  - **平台管理级**（`v-if="perm.isAdmin"` 或按 `platform:cluster:manage`）：GatewayClass 入口 + mesh-status 横幅。
  - **资源管理级（租户）**：Gateway / HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute（命名空间来自分配上下文，同 ServiceMonitor/Workload 页）。这些按资源域权限码显隐（见 §0.5）。
- router meta.group='服务网格'；平台级路由 context=admin、租户级 context=租户 full（对齐 ServiceMonitor）。

### 0.3 后端路径 & client（定死，按重构后约定）
- **k8s-server**（无前缀、两跳同名）：
  - GatewayClass → `GatewayClassController extends AbstractClusterResourceController<GatewayClassDTO>`，`@RequestMapping("/gatewayclasses")`（自动 `PLATFORM`）。6 端点免费。
  - 6 namespaced → `GatewayController`/`HttpRouteController`/… `extends AbstractNamespacedResourceController<T>`，`@RequestMapping("/gateways")`、`/httproutes`、`/grpcroutes`、`/tcproutes`、`/tlsroutes`、`/udproutes`（自动 `TENANT`）。6 端点免费。
  - mesh-status → `MeshController`（或并入现有 provisioning controller）实现 `AccessBoundaryAware` → `PLATFORM`，`@RequestMapping("/mesh")`，`POST /status?clusterId=`（或 GET）。**B7 里要查 ztunnel DaemonSet，需 admin client。**
- **platform-api**：
  - 6 namespaced：走现有 `K8sResourceClient`（DTO 驱动六操作）——**加 ResourceType 即通，无需新 client**（同 ServiceMonitor）。面向前端的 controller 路径 = `/gateways` 等（对齐现有 `/servicemonitors`）。
  - GatewayClass CRUD：新建 **`K8sMeshClient`**（照 `K8sCalicoClient`），打 k8s-server `/gatewayclasses/**`。
  - GatewayClass **租户只读引用端点**：`POST /mesh/gatewayclasses`（admin client list，任何登录用户可读，供 Gateway 编辑器选 gatewayClassName）。
  - mesh-status：`K8sMeshClient.meshStatus(clusterId)` → `POST /mesh/status`。
  - 面向前端的 controller：`MeshController`（platform-api 侧）`@RequestMapping("/mesh")`，委托 service/client。
- **RBAC（必做，否则启动 brick）**：platform-api 新增端点会被 `PermissionCrossCheckRunner` 枚举——每个都要「有权限行 or 豁免」。参照：
  - 平台级（GatewayClass CRUD / mesh-status）：补权限行，code 建议 `platform:cluster:manage`（admin 已持有）。
  - 租户级（6 namespaced 的 list/get/yaml/create/update/delete）：补资源域权限码，参照 `V2026_09_29_1__resource_fine_grained.sql` + `V2026_10_07_1__page_permissions.sql` 的既有写法（ServiceMonitor 那批码照抄一份换成 gateway/route）。**实现时先读这两个迁移，照现有 code 命名与角色绑定模式来。**
  - 迁移文件名沿用当前最大版本号之后（先看 `platform-data/.../db/migration/` 现有最大版本，避免撞号）。

### 0.4 platform-common DTO（全 `extends BaseResources`）
字段见 spec §4.1–4.5。要点：
- `GatewayClassDTO`：controllerName（必填）、parametersRef{group,kind,name}、conditions（只读）。**全建模，无 fetch-overlay**。
- `GatewayDTO`：gatewayClassName（必填）、listeners[]{name,hostname,port,protocol,tls{mode,certificateRefs[]},allowedRoutes{namespacesFrom,namespaceSelector,kinds[]}}、infrastructureAnnotations。listeners 是 atomic list → fetch-overlay。
- `HTTPRouteDTO`：parentRefs[]{name,namespace,kind,group}（默认 Gateway）、hostnames[]、rules[]{matches[]{path{type,value},method,headers[],queryParams[]},filters[]{RequestHeaderModifier/RequestRedirect/URLRewrite/RequestMirror/ExtensionRef},backendRefs[]{name,namespace,port,weight,group,kind}}。rules atomic → fetch-overlay。**最复杂。**
- `GRPCRouteDTO`：骨同 HTTPRoute，差异 matches{method{service,method},headers[],queryParams[]}（**无 path**）、filters 仅 RequestHeaderModifier/ExtensionRef。
- `TCPRouteDTO`/`TLSRouteDTO`/`UDPRouteDTO`：parentRefs、rules[]{backendRefs[]}（TLS 加 rules[].matches[]{sniHostname}）。字段少，基本全建模。
- `MeshStatusDTO`：{hasIstio, istioAmbient, hasGatewayApi, gatewayApiVersions[]}。
> 精确字段名以 gateway-api.sigs.k8s.io 为准（走 7890 代理）；实现时逐条对。**建议先查一遍 Gateway API v1 的 `HTTPRouteRule`/`HTTPRouteFilter`/`GatewayListener` schema 再建模**（可用 Apifox MCP 或代理抓 spec）。

### 0.5 前端注册点
- **router**：`/mesh/gatewayclass`(+`/editor`)（平台级）+ `/gateways`、`/httproutes`、`/grpcroutes`、`/tcproutes`、`/tlsroutes`、`/udproutes`(+各自 `/editor`)（租户级），meta.group='服务网格'。**注意**：现有资源页浏览器路由是 `/resources/xxx`（如 `/resources/workloads`）——B6 的租户级路由要跟现有资源页前缀一致（读 router 现有写法照抄），别自造。
- **api/index.ts**：`meshApi.gatewayClasses*`、`meshApi.status(clusterId)`；6 namespaced 用 `makeResourceApi<K8sGateway>('gateways')` 等（照 `serviceMonitorApi`）。
- **types.ts**：`K8sGatewayClass / K8sGateway(+Listener) / K8sHttpRoute(+Rule/Match/Filter/BackendRef) / K8sGrpcRoute / K8sTcp|Tls|UdpRoute`；`MeshStatus`。

---

## Phase 1 · mesh-status 检测 + GatewayClass + Gateway + HTTPRoute（核心）

> 依 spec §9 建议「先 GatewayClass+Gateway+HTTPRoute 核心，再 GRPC/L4」。这是最常用、也最复杂的三类（含 atomic list fetch-overlay）。

### Step 1 — platform-common DTO
`GatewayClassDTO`、`GatewayDTO`(+GatewayListenerDTO)、`HttpRouteDTO`(+Rule/Match/Filter/BackendRef 子 DTO)、`MeshStatusDTO`（§0.4）。

### Step 2 — k8s-core
- converters ×3：`GatewayClassConverter`（全建模）、`GatewayConverter`、`HttpRouteConverter`（后两者 listeners/rules 走 fetch-overlay，同 ServiceMonitorConverter.convertForUpdate）。
- operations ×3：`GatewayClassOperations implements ClusterOperations<GatewayClassDTO>`（scope=Cluster）；`GatewayOperations`/`HttpRouteOperations implements NamespacedOperations<T>`（scope=Namespaced）。
- `MeshOperations`（或复用现有 provisioning 服务）：`meshStatus(clusterId)` —— capability 判 hasIstio/hasGatewayApi；admin client 查 ztunnel DaemonSet 判 ambient（先查 `istio-system/ztunnel`，fallback 全 ns 搜 ztunnel DaemonSet；查不到 → false 不报错）。
- factory：`KubernetesOperationsFactory.build()` 加 3 case。**L4 版本分派见 §0.1 caveat。**
- ResourceType：加 `GATEWAY_CLASS / GATEWAY / HTTP_ROUTE`（Phase 2 再加 GRPC/TCP/TLS/UDP）。

### Step 3 — k8s-server
- `GatewayClassController` `@RequestMapping("/gatewayclasses")` extends `AbstractClusterResourceController<GatewayClassDTO>`。
- `GatewayController` `/gateways`、`HttpRouteController` `/httproutes` extends `AbstractNamespacedResourceController`。
- mesh-status controller：`@RequestMapping("/mesh")`，实现 `AccessBoundaryAware`→`PLATFORM`，`POST /status`。

### Step 4 — platform-api
- `K8sMeshClient`（照 `K8sCalicoClient`）：GatewayClass×6 + `meshStatus`。
- `MeshService`：编排 + 降级 + mesh-status 短 TTL 缓存。
- `MeshController` `@RequestMapping("/mesh")`：GatewayClass CRUD（platform:/cluster:manage）+ `POST /mesh/gatewayclasses`（租户只读引用）+ `POST /mesh/status`。
- 6 namespaced 走现有 `K8sResourceClient`：加 ResourceType 后，platform-api 侧再补 gateway/httproutes 两个 controller（照 ServiceMonitorController platform-api 侧，委托 `K8sResourceClient`）。
- **capability 门禁**：`hasGatewayApi=false` → 租户资源页 create 禁用/提示。
- **RBAC**：补权限行（§0.3）。

### Step 5 — 前端
- MainLayout 新建「服务网格」组：平台级 GatewayClass 入口 + 租户级 Gateway/HTTPRoute 入口（Phase 2 再补 4 个）。
- mesh-status 横幅组件：各页顶部一条 `Istio: ambient ✓ / sidecar / 未安装 · Gateway API: v1 ✓ / 未安装`；`hasGatewayApi=false` 整组 create 禁用 + 提示。
- 列表页 ×3（GatewayClass 平台级；Gateway/HTTPRoute 租户级）+ 独立编辑页 ×3（FieldHelp 全覆盖；Gateway 的 listeners 动态块、HTTPRoute 的 parentRefs（下拉选 Gateway）+ hostnames + rules 动态块（matches/filters/backendRefs 可增删））+ YAML tab（只读全保真）。
- 提交校验：名称 RFC1123；必填（controllerName/gatewayClassName/parentRefs/backendRefs）；protocol/port 合法；backendRef weight 0–100。
- router/api/types（§0.5）。

### Step 6 — 验证 Phase 1
```bash
mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test
npm --prefix platform-web run build
```
- 单测：`GatewayClassConverterTest`/`GatewayConverterTest`/`HttpRouteConverterTest`（spec↔DTO 双向 + **fetch-overlay 保未建模 filter/match 子字段**）、`MeshOperations` 的 meshStatus 判定（mock capability + ztunnel 存在/不存在）。
- **Phase 1 做完 → 停下来给用户看，确认方向再进 Phase 2。**

---

## Phase 2 · GRPCRoute + L4 路由（TCP / TLS / UDP）
- platform-common：`GrpcRouteDTO`、`TcpRouteDTO`、`TlsRouteDTO`、`UdpRouteDTO`。
- k8s-core：converters ×4 + operations ×4（全 namespaced）；factory + ResourceType（`GRPC_ROUTE/TCP_ROUTE/TLS_ROUTE/UDP_ROUTE`）。**版本按 §0.1 caveat 分派。**
- k8s-server：4 个 controller，`/grpcroutes`、`/tcproutes`、`/tlsroutes`、`/udproutes`，extends `AbstractNamespacedResourceController`。
- platform-api：4 个 controller（照 ServiceMonitor platform-api 侧，`K8sResourceClient`）+ RBAC 行。
- 前端：列表页 ×4 + 编辑页 ×4（TCP/TLS/UDP 简单：parentRefs + rules{backendRefs}，TLS 加 sniHostname；GRPC 同 HTTPRoute 无 path）+ YAML tab；MainLayout 补 4 个入口；router/api/types。

## Phase 3 · waypoint Gateway per-ns 唯一性校验 —— ⚠️ 本条已于 2026-10-09 撤销，见下方修订
- 背景（spec §2/§9）：B3 §11 的 ambient `use-waypoint` 下拉约束「每 ns 至多一个 waypoint Gateway」。本批承担该校验。
- **实现**：创建/编辑 Gateway 时，若该 ns 已存在 waypoint Gateway（判定：Gateway 带 waypoint 语义标识，如 `gateway.istio.io/...` 注解/label——**实现前先与用户确认判定口径**），再建第二个 → 拒绝（中文错）。
- 落点：`GatewayService`（platform-api）在 create/update 前查同 ns Gateway 列表做校验；gateway-api 侧无此约束，是平台附加规则。
- 单测：`GatewayServiceTest` 覆盖「已有一个 waypoint → 拒绝第二个」。

### Phase 3 落地实况（2026-10-08/09 已完成）
### Phase 3 修订（2026-10-09）：撤销数量上限，改用「按类型过滤候选」

**为什么撤销**：加这条规则的理由是「B3 的 `use-waypoint` 下拉只能表达 0/1」，是**拿数据约束将就 UI**。
现在两处选择器都按**名字**列候选（N 个也表达得出），前提不成立；istio 本身也允许同 ns 多个 waypoint
（按名字寻址，且按「流量的原始目标类型」分流以避免双重处理）。硬上限还挡住三类合法用法：
按工作负载划分安全边界的多个同类型 waypoint、waypoint 自身的版本灰度、控制面 revision 并存。

**改成了什么**：
- `GatewayService` 删掉 `assertWaypointUniqueInNamespace`，create/update 变纯透传（顺带去掉"list 失败 → 保守拒绝"那条拖累可用性的路径）
- 新增 `WaypointRefDTO`（名字 + 类型）与 `waypointForOf` / `canHandleService` / `canHandleWorkload` 投影：
  **候选一律带类型**，`/mesh/gateways` 与租户 `/gateways/list` 两侧共用同一份投影
- 选择器按类型过滤：命名空间级只给 `service`/`all`，Pod 级（pod template）只给 `workload`/`all`；
  **不匹配的候选不进下拉**（用户 2026-10-09 明确：不可选的就不该出现在选项里）。两处例外，都是"说明"而非"候选"：
  ①当前值本身不可用（类型不匹配 / 已被删）时为**回显**保留一条 disabled 项，否则选择器显示空白、看起来像从未设置过；
  ②候选存在但一个都不能用时给一句 disabled 说明（避免分组空着让人以为坏了）
- 后端兜底：`WorkloadService.meshToggle` 在"waypoint 存在性"之外，再校验类型必须是 `workload`/`all` —— 这是**修死配置**的关键一条（见下）
- Gateway 编辑器：原来的"已有 waypoint → 阻断创建"改成"列出已有的（名字+类型）"，同类型重复只**警告**不阻断

**顺带修掉的一个真问题**：平台建 waypoint 时默认写 `istio.io/waypoint-for: service`（与 `istioctl` 一致），
而 Pod 上的 `use-waypoint` 只对「最初目标是 Pod/VM IP」的流量有效 —— 官方原文：
"when you label a pod to use a specific waypoint … the waypoint should be labeled istio.io/waypoint-for with
the value workload or all"。也就是说改之前，**工作负载级的 L7 在平台默认配置下是个死配置**
（标签写进去了、istio 静默放行、L7 策略不生效且无任何报错）。现在：候选过滤 + 后端校验 + 列表/编辑器把类型写进状态文案。

- **判定口径定案**：`Gateway.spec.gatewayClassName` **包含** `-waypoint`（不是 `istio.io/waypoint-for` label，也不是 `gateway.istio.io/*` 注解）。用 `contains` 而非 `endsWith`——`istio-waypoint-1-20-0` 这类带版本后缀的类名也要算，宁可多拦。
- 落点 `GatewayService.create/update` → `assertWaypointUniqueInNamespace`（update 传自身名排除自己）；**列表查询失败时保守拒绝**（无法确认即拒绝，不放过）。
- **waypoint 固定形状**（前端 `GatewayEditorView.vue` 的 `waypointMode`）：listener 由代码生成，固定 `{name: mesh, port: 15008, protocol: HBONE}`，不可增删改；可编辑项只有三项——**名称**、**`istio.io/waypoint-for`**（`service`/`workload`/`all`/`none`，默认 `service`）、**`allowedRoutes.namespaces`**（`from` + 可选 `selector.matchLabels`，跨 ns waypoint 用，Istio 1.23+）。
  - `allowedRoutes` **不是**无用字段：Route 本就是靠 `parentRefs` 挂到 waypoint Gateway 上的（HTTPRoute Beta / TCPRoute、TLSRoute Alpha），跨命名空间 waypoint 必须靠它放行。
  - 提交 `labels` 时**只提交 `istio.io/waypoint-for` 这一个 key**（SSA 对 `metadata.labels` 按 key 细粒度合并），不接管 istiod 加的其它 label；非 waypoint 路径仍传 `null`。
  - 入口：Gateway 列表页「创建 Waypoint」按钮 → `gateway-editor?waypoint=1`（waypoint 不是独立资源类型，就是一张预填表单）。

## 未覆盖（有意不做 · 记档）

> 结论日期 2026-10-09，用户确认。以下均为**已知缺口**，不是遗漏。

1. **金丝雀 waypoint —— 暂不做**（Istio 1.31 Alpha）。`istio.io/use-waypoint-canary`（label，金丝雀 Gateway 名）+ `istio.io/use-waypoint-canary-namespace`（label）+ `istio.io/use-waypoint-canary-weight`（annotation，0–100，默认 0），用于在升级期间把服务流量按比例分流到**第二个** waypoint；支持对象为 Service / ServiceEntry / Namespace。
   - **与 Phase 3 直接冲突**：金丝雀的官方做法就是「在主 waypoint **旁边**同名空间再起一个」，而 Phase 3 用户明确要求「一个命名空间只能有一个 waypoint」——该校验会把金丝雀挡掉。
   - **决定**：金丝雀不做（Istio 1.31 Alpha）。~~唯一性规则需放宽~~ —— 该冲突已随 2026-10-09 撤销数量上限而消失，将来要做不必再动这条规则。
2. **消费方侧标签注入 → 归 B3 §11**（本 spec §2 已明确排除在 B6 之外）：`istio.io/use-waypoint`（Namespace / Service / Pod）、`istio.io/use-waypoint-namespace`（跨 ns 消费方）、`istio.io/ingress-use-waypoint`（Istio 1.25+，Service / Namespace，且需 istiod 开 `ENABLE_INGRESS_WAYPOINT_ROUTING`，默认 false）。
   - ⚠️ **后果**：B3 §11 落地前，本批建出来的 waypoint **不会被任何流量使用**（istiod 会照常给它起 Deployment/Service，但没有消费方引用它）。用户已确认属「先做 B6、回头补 B3 §11」的排列，不是缺陷。
3. **命名空间 `istio.io/dataplane-mode: ambient` 前置条件**：编辑器仅文案提示，不做活校验（读 Namespace 对象属 B3 域）。
4. **强制流量经 waypoint**（AuthorizationPolicy 只放行 waypoint 的 SA；**SA 名 = Gateway 名**）属 Istio 专有 CRD `security.istio.io`，spec 明确 out of scope。

---

## 横切 caveat（实现期必须处理）
1. **spec 路径已过时**：一切按 §0.3 的现行约定（无前缀 + `AccessBoundary`）；**别照抄 spec 的 `/admin/mesh/**`、`/resources/**`**。
2. **L4 路由版本**：TCP/TLS/UDP 多为 `v1alpha2`，GRPCRoute 自 v1.1 GA——按集群 capability 分派，否则 404（§0.1）。
3. **atomic list fetch-overlay（已按实况修正）**：实测 `spec.listeners` 是 `listType=map, listMapKey=name`，**不是 atomic**；**真正必须 fetch-overlay 的只有 HTTPRoute / GRPCRoute**（`spec.rules` 无 listType → 默认 atomic，`matches`/`filters`/`backendRefs`/`parentRefs` 亦然）。Gateway 侧仍按 name 做了 overlay（可测 + 版本稳健），但不是 SSA 正确性所必需。YAML tab 兜底全保真。
4. **Gateway 归属（已定）**：**Gateway + 5 类 Route 都租户域**（同 ServiceMonitor，分配表边界），2026-10-08 用户确认；GatewayClass 为集群级平台管理（`AbstractClusterResourceController`，PLATFORM 边界）。
5. **ambient 探测**：istio-system 可能非默认；v1 先查 `istio-system/ztunnel` + fallback 全 ns 搜 ztunnel DaemonSet；失败 → false 不报错（信息性降级）。
6. **RBAC cross-check**：每个新增 platform-api 端点都要权限行或豁免，否则启动 brick（§0.3）。
7. **跨 ns 引用**：Route 的 parentRefs/backendRefs 可跨 ns，由 Gateway API `allowedRoutes` + K8s RBAC 约束；平台分配表边界只限「能在哪些 ns 建/改」，不限引用目标（与现有资源一致）。
8. **降级**：集群未装 Gateway API / 集群断开 → 各段优雅降级 + 横幅提示，不整页崩。

## 验证命令（每 Phase 末）
```bash
mvn -pl platform-common,k8s-core,k8s-server,platform-api -am test
npm --prefix platform-web run build
```

## 验收（对照 spec §10）
1. mesh-status 正确判 hasGatewayApi/hasIstio/istioAmbient（ztunnel 探测），多集群不串；未装 Gateway API 时模块门禁生效。
2. 7 类对象列表/创建/编辑/YAML/删除全通；GatewayClass 无命名空间、6 namespaced 按命名空间管理。
3. complex 路由（HTTP/GRPC）编辑后未建模 filter/match 子字段不丢失（fetch-overlay）；YAML tab 全保真。
4. 编辑页每字段有 FieldHelp；backendRef/parentRef 下拉正确引用（GatewayClass/Gateway）。
5. context（集群/命名空间）切换刷新正确；集群断开优雅降级。
6. ~~waypoint per-ns 唯一性校验生效（Phase 3）~~ → 改为：**waypoint 数量不设上限**；候选按 `waypoint-for` 过滤（ns 级 service/all、Pod 级 workload/all），不匹配的以禁用项+原因列出；Pod 级指向非 workload/all 型时后端拒绝（2026-10-09 修订）。

## 建议推进节奏（已执行完毕）
Phase 1（mesh-status + GatewayClass + Gateway + HTTPRoute）→ Phase 2（GRPC + L4 四路由）→ Phase 3（waypoint 唯一性），三段均已落地，每段末跑上面验证命令。**开工前需确认的两条**：(a) Gateway 归属 = **租户域** ；(b) L4 路由版本 = **按集群 capability 分派** —— 均已于 2026-10-08 确认并落地。
