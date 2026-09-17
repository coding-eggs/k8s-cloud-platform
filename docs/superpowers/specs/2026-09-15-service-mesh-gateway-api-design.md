# B6 · 服务网格 Istio / Gateway API（ambient 检测 + 7 类 Gateway API 对象管理）

- **日期**：2026-09-15
- **状态**：草稿，待评审
- **覆盖条目**：原始清单 11
- **范围**：本阶段只交付规格；实现另开
- **CRD/资源**：`gateway.networking.k8s.io/v1` — GatewayClass（cluster-scoped）+ Gateway / HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute（namespaced）。fabric8 通用 CRD API（无代码生成依赖）。
- **外部参考**：Gateway API v1 schema 以 `gateway-api.sigs.k8s.io`（走 7890 代理）为准；实现时逐字段核对。

---

## 1. 背景与目标

平台目前**无任何服务网格 / Gateway API 代码**（仅 `K8sCluster.istioVersion/calicoVersion` 两个版本字段）。本批新增「服务网格」模块：

| 子项 | 目标 |
|---|---|
| 11a | **检测** ambient istio + kubernetes-sigs/gateway-api（决定模块可用性与展示） |
| 11b | **管理 Gateway API 7 类对象**：GatewayClass / Gateway / HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute（列表 + 独立编辑页 + YAML，其余字段建模/UX 本 spec 设计）。**访问分层**：GatewayClass=集群级平台管理（用户不可配）；Gateway+5 Route=**租户域**（资源管理、按命名空间分配） |

## 2. 范围

**In scope（本批交付）**
- 后端：7 个 DTO；k8s-core 7 个 converter + operations（6 namespaced 走 `NamespacedOperations`、GatewayClass 走 `ClusterOperations`，全 fabric8 通用 CRD + SSA/fetch-overlay）；`ResourceType` 加 7 枚举 + factory 注册。**访问分层**：GatewayClass 走 k8s-server 集群域基类（admin CRUD）+ 租户只读引用端点；6 namespaced 复用现有 `AbstractNamespacedResourceController` + `K8sResourceClient`（分配表边界、租户域，同 ServiceMonitor）。mesh-status 检测端点（istio/ambient/gateway-api，admin）。
- 前端：导航「服务网格」跨两上下文——GatewayClass 页=平台管理级；Gateway+5 Route 页=资源管理级（租户、命名空间来自分配上下文）；各列表页 + 独立编辑页（复用 ServiceMonitor/Hpa 范式 + FieldHelp）；mesh-status 横幅（平台侧）；router / api client / types。

**Out of scope（v1 不做）**
- Istio **sidecar 系对象**（VirtualService/DestinationRule/VirtualService 等 `networking.istio.io` CRD）——本批只做 Gateway API（标准 K8s CRD，任意 controller 可实现），Istio 专有对象后续按需。
- **ambient workload/namespace 的 label 注入**（`istio.io/dataplane-mode` / `istio.io/use-waypoint`）——归 **B3 §11**（命名空间编辑「能力开关」+ 工作负载编辑器模块 + 列表快捷开关），不在本批；ztunnel DaemonSet 只**检测**不代管。本批额外承担：**waypoint Gateway per-ns 唯一性校验**（创建/编辑 Gateway 时该 ns 已存在 waypoint GW → 拒绝，供 B3 `use-waypoint` 下拉的「0/1」约束）。
- Route 的跨命名空间 parent/backend 可视化拓扑图——v1 只做对象 CRUD + YAML。
- Gateway API v1beta1/v1alpha2 兼容——只建模 **v1**（GA）。

## 3. 现状盘点（探查结论）

| 项 | 现状 | 对本批的意义 |
|---|---|---|
| **CRD 范式（ServiceMonitor）** | `ServiceMonitorOperations`：fabric8 `GenericKubernetesResource` + `CustomResourceDefinitionContext(group/version/kind/plural/scope)`；`list/get/create/update(SSA)/delete/yaml/checkExist`。`ServiceMonitorConverter`：spec 走 `Map<String,Object>` 读写、`convertForUpdate(dto, live)` **fetch-overlay** 保 atomic list 未建模字段 | **7 类 Gateway API 对象完全套用**此范式；仅 scope（cluster/namespaced）与字段不同 |
| **两种 operations 接口** | `NamespacedOperations<T>`（6 namespaced 用）/ `ClusterOperations<T>`（GatewayClass 用，同 ClusterRole） | 直接复用，无需新接口 |
| **factory** | `KubernetesOperationsFactory.build()` switch on `ResourceType`；`getNamespacedOperation`(tenant) / `getAdminNamespacedOperation`(admin) / `getClusterOperation`(admin) | 加 7 个 case；6 namespaced 走 `getAdminNamespacedOperation`、GatewayClass 走 `getClusterOperation`（平台管理级=全 admin client） |
| **capability 机制** | `k8s_cluster.capability` JSON（group→versions[]），B2 已加读端点 `GET /resource/clusters/{id}/capability` | `hasGatewayApi` = capability 含 `gateway.networking.k8s.io`；`hasIstio` = 含 `istio.io`/`networking.istio.io`。**复用，不新增探测** |
| **访问模型（关键）** | `AbstractNamespacedResourceController` 走 `resolveNamespacedAccess` → `assertNamespacedAccess` **强制分配表三元组**（tenant+cluster+ns），admin 模式亦然 → **不能**做平台级跨 ns 管理。`AbstractClusterResourceController`（集群域）只 `assertClusterAccess` + admin client，无租户边界。`/admin/**` controller（如 `NamespaceAdminController`）= admin client + 显式 namespace、无边界 | **访问分层**：GatewayClass=集群级 → 复用 `AbstractClusterResourceController`（admin CRUD）+ 租户只读引用；6 namespaced（Gateway/Route）=**租户域** → 直接复用现有 `AbstractNamespacedResourceController` + `K8sResourceClient`（分配表边界，同 ServiceMonitor），**无需新基类**。跨 ns 引用（parentRefs/backendRefs）由 Gateway API allowedRoutes+RBAC 管，不由平台 CRUD 边界管 |
| platform-api client | `K8sAdminClient`（/admin/** 生命周期，一方法一端点）/ `K8sResourceClient`（/resources/** DTO 驱动六操作，租户边界） | 7 资源×6 操作 → 新增 **DTO 驱动 admin 资源 client**（形态同 K8sResourceClient、打 `/admin/mesh/**`、无租户边界） |

## 4. 数据模型（platform-common，全 `extends BaseResources`）

> Gateway API v1。字段为「建模核心集」；atomic list（rules/matches/filters/backendRefs/listeners）用 **fetch-overlay** 保未建模子字段，YAML tab 兜底全保真。精确字段名以 gateway-api.sigs.k8s.io 为准。

### 4.1 `GatewayClassDTO`（cluster-scoped）
| 字段 | 类型 | 说明 |
|---|---|---|
| name / labels / creationTime | — | 标准 |
| controllerName | String | `spec.controllerName`（必填），如 `istio.io/gateway-controller` |
| parametersRef { group, kind, name } | obj | `spec.parametersRef`（可选，指向配置 CRD） |
| conditions | List<ConditionDTO>（只读） | `status.conditions` |

全建模（spec 仅 2 字段），无需 fetch-overlay。

### 4.2 `GatewayDTO`（namespaced）
| 字段 | 类型 | 说明 |
|---|---|---|
| name / namespace / labels / creationTime | — | 标准 |
| gatewayClassName | String | `spec.gatewayClassName`（必填，引用 GatewayClass） |
| listeners | List<GatewayListenerDTO> | `{ name, hostname, port:Integer, protocol(HTTP/HTTPS/TLS/TCP/UDP), tls{ mode(Disable/Passthrough/Terminate/CertManager), certificateRefs[{name,kind,group}] }, allowedRoutes{ namespacesFrom(Same/All/Selector), namespaceSelector(Map), kinds[{group,kind}] } }` |
| infrastructureAnnotations | Map | `spec.infrastructure.annotations`（可选） |
| conditions / listenersStatus | （只读） | `status` |

fetch-overlay：listeners 为 atomic list，建模核心字段、保留未建模（advanced allowedRoutes 等）。

### 4.3 `HTTPRouteDTO`（namespaced）★最复杂
| 字段 | 类型 | 说明 |
|---|---|---|
| name / namespace / labels / creationTime | — | 标准 |
| parentRefs | List<ParentRefDTO> | `{ name, namespace, kind, group }`（默认 Gateway） |
| hostnames | List<String> | `spec.hostnames`（DNS 名） |
| rules | List<HTTPRouteRuleDTO> | 见下 |
| conditions / parentStatuses | （只读） | `status` |

**`HTTPRouteRuleDTO`**：
- `matches[] { path{ type(Exact/PathPrefix), value }, method, headers[{name,value,type}], queryParams[{name,value,type}] }`
- `filters[] { type + 对应体 }`：`RequestHeaderModifier{set/add/remove[]}` / `RequestRedirect{scheme,hostName,path,port}` / `URLRewrite{hostname,path{type,value}}` / `RequestMirror{backendRef}` / `ExtensionRef{group,kind,name}`
- `backendRefs[] { name, namespace, port, weight, group, kind }`

fetch-overlay：rules 为 atomic list；建模 path/method/headers + 常用 filter + backendRefs，保留未建模 filter/match 子字段。

### 4.4 `GRPCRouteDTO`（namespaced）
骨架同 HTTPRoute，差异：`matches[] { method{ service, method }, headers[], queryParams[] }`（**无 path**，gRPC 用 service/method）；`filters[] = RequestHeaderModifier / ExtensionRef`。backendRefs 同。fetch-overlay 同。

### 4.5 `TCPRouteDTO` / `TLSRouteDTO` / `UDPRouteDTO`（namespaced，L4 简单）
| 字段 | 类型 | 说明 |
|---|---|---|
| name / namespace / labels / creationTime | — | 标准 |
| parentRefs | List<ParentRefDTO> | `{ name, namespace }` |
| rules | List<L4RouteRuleDTO> | `{ backendRefs[] { name, port, weight } }` |
| （仅 TLSRoute）rules[].matches | List | `{ sniHostname }` |

字段少，基本全建模；fetch-overlay 轻。

## 5. 后端各层改动

### 5.1 k8s-core
- **converter ×7**：`GatewayClassConverter` / `GatewayConverter` / `HttpRouteConverter` / `GrpcRouteConverter` / `TcpRouteConverter` / `TlsRouteConverter` / `UdpRouteConverter`（spec 走 Map；complex 路由的 rules/matches/filters/backendRefs + Gateway listeners 用 fetch-overlay，模式同 `ServiceMonitorConverter.convertForUpdate`）。
- **operations ×7**：6 namespaced 实现 `NamespacedOperations<T>`（CRD context scope=Namespaced、`.inNamespace(ns)`）；`GatewayClassOperations` 实现 `ClusterOperations<T>`（scope=Cluster、无 namespace）。全走 fabric8 `genericKubernetesResources(CRD)` + SSA。
- **factory**：`build()` 加 7 case（单版本，直接 new，无需 capability 分派——Gateway API 只建模 v1）。
- **ResourceType**：加 `GATEWAY_CLASS / GATEWAY / HTTP_ROUTE / GRPC_ROUTE / TCP_ROUTE / TLS_ROUTE / UDP_ROUTE`。

### 5.2 k8s-server（边界，零业务逻辑）
- **GatewayClass**（集群级、平台管理）：`GatewayClassController extends AbstractClusterResourceController<GatewayClassDTO>`，`@RequestMapping("/admin/mesh/gatewayclass")`、`resourceType()=GATEWAY_CLASS`。6 端点免费获得（`assertClusterAccess` + admin client）。
- **6 namespaced**（租户域）：各 `extends AbstractNamespacedResourceController<T>`（同 ServiceMonitor），`@RequestMapping("/resources/gateway"`、`/resources/http-routes` …）+ `resourceType()`。走现有双模访问（租户 token→tenant client 最小权限 / admin 代操作），分配表边界自动生效。**无需新增基类**。
- **mesh-status**：`MeshAdminController` 加 `POST /admin/mesh/status` → `K8sProvisioningService.meshStatus(clusterId)`：capability 列判 hasIstio/hasGatewayApi + admin client 查 ztunnel DaemonSet 判 ambient（见 §5.4）。

### 5.3 platform-api
- **6 namespaced**：走现有 `K8sResourceClient`（DTO 驱动六操作、/resources/**、租户上下文）——加 ResourceType 即通，无需新 client。
- **GatewayClass**：CRUD 走 `K8sAdminClient`（新增方法，打 `/admin/mesh/gatewayclass/*`）；另加**租户只读引用端点** `GET /mesh/gatewayclasses?clusterId=`（经 admin client list，任何登录用户可读，供 Gateway 编辑器选 gatewayClassName）。
- **mesh-status**：`K8sAdminClient.meshStatus(clusterId)`。
- **capability 门禁**：`hasGatewayApi=false` → 租户资源页 create 禁用/提示（同 B2 metrics 门禁）；判定读 capability 列。

### 5.4 capability / ambient 检测机制（回答「怎么搞」）
- **`hasGatewayApi`**：capability 列含 `gateway.networking.k8s.io`（纯 discovery，复用现有列 + B2 读端点）。**模块硬门禁**。
- **`hasIstio`**：capability 列含 `istio.io` 或 `networking.istio.io`。信息性。
- **`istioAmbient`**：**非 CRD group**，靠 **ztunnel DaemonSet 存在性**（ambient 模式每节点一个 ztunnel）。活探测：admin client 查 `istio-system/ztunnel` DaemonSet（命名空间可配/fallback 全 ns 搜 ztunnel）。
- **mesh-status 端点**：页面加载时调，返回 `{ hasIstio, istioAmbient, hasGatewayApi, gatewayApiVersions[] }`。**不污染纯 discovery 的 capability 列**（ambient 是资源 probe，非 discovery）；on-demand、可短 TTL 缓存。

## 6. 前端改动汇总

### 6.1 导航
- 「**服务网格**」跨两上下文：
  - **平台管理级**：GatewayClass（admin CRUD）+ mesh-status 横幅（istio/ambient/gateway-api）。
  - **资源管理级（租户）**：Gateway / HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute（命名空间来自分配上下文，同 ServiceMonitor/Workload 页）。

### 6.2 mesh-status 横幅（11a）
- 模块各页顶部一条状态条：`Istio: ambient ✓ / sidecar / 未安装` · `Gateway API: v1 ✓ / 未安装`。数据来自 `/mesh/status`；`hasGatewayApi=false` 时整组 create 禁用 + 提示「该集群未安装 Gateway API」。

### 6.3 资源页（7 类，统一范式）
- **列表页**：GatewayClass=平台管理级（集群选择，无命名空间）；6 namespaced=租户域（命名空间来自分配上下文，同 ServiceMonitor/Workload）+ 刷新 + 「创建」+ 行操作下拉（查看/编辑/YAML/删除）。列按资源定制（如 Gateway 显示 listeners 摘要、HTTPRoute 显示 hostnames/rules 数、L4 显示 backendRefs）。
- **独立编辑页**（复用 Hpa/ServiceMonitor editor 范式 + FieldHelp 全覆盖）：
  - GatewayClass：controllerName + parametersRef。
  - Gateway：gatewayClassName（下拉选已有 GatewayClass）+ listeners 动态块（name/hostname/port/protocol/tls/allowedRoutes）。
  - HTTPRoute / GRPCRoute：parentRefs（下拉选 Gateway）+ hostnames + rules 动态块（matches/filters/backendRefs，可增删）。
  - TCP/TLS/UDP Route：parentRefs + rules{backendRefs}（TLS 加 sniHostname）。
  - 每字段 FieldHelp（作用 + 用法 + 单位/取值）。
- **YAML tab**：只读全保真（complex 路由的逃生舱）。
- **提交校验**：名称 RFC1123；必填项（controllerName/gatewayClassName/parentRefs/backendRefs）；protocol/port 合法；backendRef weight 0–100。

### 6.4 注册点
- **router**：`/mesh/gatewayclass`(admin) + `/resources/{gateway,http-routes,grpc-routes,tcp-routes,tls-routes,udp-routes}`(tenant) + 各 `/editor`，meta group=服务网格；context 按资源分（GatewayClass=admin、其余=租户 full）。
- **api/index.ts**：`meshApi.status(clusterId)`；7 资源走 admin 资源 client（DTO 驱动六操作）。
- **types.ts**：`K8sGatewayClass / K8sGateway(+Listener) / K8sHttpRoute(+Rule/Match/Filter/BackendRef) / K8sGrpcRoute / K8sTcp|Tls|UdpRoute`；`MeshStatus`。

## 7. 边界与异常

| 场景 | 行为 |
|---|---|
| 集群未装 Gateway API（capability 无该 group） | 模块横幅提示「未安装」，create 禁用；存量对象（若有）照常展示 |
| Istio 未装 / 仅 sidecar | 横幅显示对应状态；Gateway API 对象仍可管理（任意 controller 可实现），ambient flag=false |
| ztunnel 探测失败 / istio-system 不存在 | `istioAmbient=false` + 不报错（信息性，降级） |
| 编辑含未建模 filter/match 的存量路由 | fetch-overlay 原样保留，前端只回显已建模项；YAML tab 看全量 |
| 集群断开 | 列表/详情降级提示，横幅「集群未连接」 |

## 8. 验收标准（怎么算做完）

1. mesh-status 正确判 hasGatewayApi / hasIstio / istioAmbient（ztunnel 探测），多集群不串；未装 Gateway API 时模块门禁生效。
2. 7 类对象列表/创建/编辑/YAML/删除全通；GatewayClass 无命名空间、6 namespaced 按命名空间管理；平台管理级（admin client、无租户边界）。
3. complex 路由（HTTP/GRPC）编辑后未建模 filter/match 子字段不丢失（fetch-overlay）；YAML tab 全保真。
4. 编辑页每字段有 FieldHelp；backendRef/parentRef 下拉正确引用（GatewayClass/Gateway）。
5. context（集群/命名空间）切换刷新正确；集群断开优雅降级。

## 9. 待确认 / 风险

- **Gateway 归属取舍**：现定为 Gateway+Route 都租户域（最一致、全复用现有路径）。若希望平台集中管控入口（listener/TLS 证书），可改 **Gateway=平台管理、Route=租户**——实现前确认。
- **跨 ns 引用**：Route 的 parentRefs/backendRefs 可跨命名空间；由 Gateway API `allowedRoutes` + K8s RBAC 约束，平台分配表边界只限制「可在哪些 ns 建/改对象」，不限制引用目标（与现有资源一致）。
- **ztunnel 探测定位**：istio-system 命名空间可能非默认；v1 先查 `istio-system/ztunnel` + fallback 全 ns 搜 ztunnel DaemonSet；如需精确可后续读 Istio control-plane config。
- **字段核对**：Gateway API v1 各 filter/match/backendRef 精确字段名以 gateway-api.sigs.k8s.io（7890）为准，实现时逐条核对（本 spec 为建模核心集）。
- **platform-api controller 粒度**：7 资源归一个 mesh controller vs 各自 controller——倾向各自（对齐「一页一 controller」），实现时定。
- **模块体量**：7 资源×(列表+编辑) 较大，建议实现分批落地（先 GatewayClass+Gateway+HTTPRoute 核心，再 GRPC/L4）；spec 覆盖全 7。
