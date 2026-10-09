# platform-api

K8s 云平台的**管理面 BFF（Backend for Frontend）与业务中枢**：面向前端 SPA 提供全部 REST 接口，承载**所有平台业务逻辑与数据库访问**，并把一切 K8s 动作经 HTTP 转发给 `k8s-server`。它同时是 OAuth2 **资源服务器**（校验 `platform-auth` 签发的 JWT）和**唯一的细粒度授权点**。

- **运行端口**：`8081`
- **技术栈**：Java 21 · Spring Boot 4.0.6 · Spring Security（OAuth2 Resource Server）· MyBatis · MySQL · Thanos（HTTP PromQL）

---

## 1. 在整体架构中的位置

`k8s-cloud-platform` 是多模块 Maven 工程：

| 模块 | 职责 |
|------|------|
| `platform-web` | 前端 SPA（Vue 3 + Vite），OAuth2 PKCE 公共客户端 |
| `platform-auth` | 授权服务器：登录、发令牌（`:9527`） |
| **`platform-api`** | **本模块 —— 管理面 BFF：业务规则 + 权限判定 + 数据库，`:8081`** |
| `k8s-server` | K8s 执行面：零业务逻辑，只做边界转换（`:8080`） |
| `k8s-core` | fabric8 client 封装（operations / converter / factory），仅被 `k8s-server` 依赖 |
| `platform-data` | MyBatis Mapper 与实体 |
| `platform-common` | JWT 编解码、异常与响应码、K8s DTO、工具类 |

### 一次典型请求的完整链路

```
浏览器 SPA
  │  Authorization: Bearer <access_token>（platform-auth 签发）
  ▼
platform-api ── ① SecurityFilterChain：验签（JWKS）→ 401
             ── ② PermissionAuthorizationManager：查权限表 → 403 / 放行
             ── ③ Controller（只做参数绑定与 ResponseData 包装）
             ── ④ Service（业务规则、DTO 组装、DB 读写、跨资源编排）
             ── ⑤ K8s*Client / K8sServerGateway（原样透传 Authorization 头）
  ▼
k8s-server ── BoundaryAuthorizationManager（读 controller 的 AccessBoundary 声明）
             ── ResourceAccessResolver（分配表边界 + tenant/admin client 选择）
             ── k8s-core operations（fabric8）
  ▼
Kubernetes API Server ── K8s RBAC（第二道闸）
```

**关键分工**：`platform-api` 是**细粒度授权（能不能做这件事）的唯一判定点**；`k8s-server` 只回答"能碰哪些命名空间、用谁的凭据"。详见 [docs/development/backend-layering.md](../docs/development/backend-layering.md)。

> 本模块**不依赖 `k8s-core` / fabric8**，从不直连 K8s API。所有 K8s 访问都是对 `k8s-server` 的 HTTP 调用。

---

## 2. 核心能力

### 2.1 资源管理（K8s 对象）
覆盖 30+ 种资源的查看与（多数）增删改，全部按"租户 → 集群 → 命名空间"上下文操作：

- **工作负载**：Deployment / StatefulSet / DaemonSet（合并为一个 `/workloads` 视图，含 ReplicaSet 指标）、HPA（v1/v2 按集群能力分派）
- **网络与配置**：Service、ConfigMap、Secret、PVC、Pod（只读 + 流式日志 + exec 终端）
- **存储**：PersistentVolume（跨租户收窄）、StorageClass（只读）、PVC
- **RBAC**：ClusterRole、RoleBinding、ServiceAccount
- **监控**：ServiceMonitor、PodMonitor（Prometheus Operator CRD）
- **Calico 网络**：IPPool、IPReservation、BGPConfiguration / BgpPeer / BgpFilter，以及 IPAM 派生查询（块统计、空闲块、块内 IP 明细）
- **服务网格（Gateway API）**：GatewayClass、Gateway、HTTPRoute、GRPCRoute、TCP/TLS/UDPRoute，以及网格状态总览

### 2.2 集群与租户生命周期
- **集群纳管**：创建（kubeconfig 连通性探测）→ 更新 → 启停 → 删除；运行时刷新并持久化「集群 API 能力快照」（`group → [versions]`），供 HPA / Gateway API 做版本分派
- **租户管理**：租户 CRUD、成员增删与租户内角色授予
- **命名空间分配**：把某集群的某命名空间分配给某租户并绑定 RBAC 模板 —— DB 先行，随后 K8s 侧四步（建 ns → 同步模板 ClusterRole → 确保租户 SA → 建 RoleBinding），任一步失败滚回分配行
- **RBAC 模板**：模板 CRUD，模板规则序列化为 JSON 存储

### 2.3 权限体系（表驱动，本模块独有）
- **权限点管理**：`platform_permission` 表的 CRUD + **热加载**（DB 提交后原子换表，运行时不重启即生效）
- **角色管理**：自定义角色 CRUD + 权限勾选全量重存；可分配权限集合由后端 `/role/permission/assignable` 下发，**前端不得自行按前缀过滤**
- **用户管理**：用户 CRUD + 平台域角色授予
- **当前用户上下文**：`/user/me`（含服务端可信算出的权限闭包）、`/user/my-tenants`

### 2.4 指标（Thanos）
经 Thanos Query 查询所有集群的时序数据（各集群 Prometheus `remote_write` 汇入并统一打 `cluster_name` 标签，故单端点即可跨集群查询）：节点 / 命名空间 / Pod / 工作负载的 CPU、内存、网络、磁盘，含当前值与时间区间序列。

### 2.5 实时通道
Pod exec 终端：浏览器 ⇄ `platform-api` ⇄ `k8s-server` 的 WebSocket 中继；Pod 日志走流式透传（不整体缓冲）。

---

## 3. 分层与目录结构

```
HTTP ──► Controller ──► Service ──► Client ──► K8sServerGateway ──► k8s-server
         参数绑定       业务规则     传输        透传 Authorization
         ResponseData   编排/校验    路径与参数
```

### Controller 的三条硬规则
1. **禁止注入 `K8s*Client` / `K8sServerGateway`**，**禁止出现任何 k8s-server 路径字符串**。
2. **禁止业务判断**：存在性校验、跨资源查询、参数三选一规则、DTO 语义改写。
3. 只保留：`@RequestMapping` 与动词注解、参数绑定、`ResponseData` 包装、OpenAPI 注解、`HttpServletResponse` 输出流这类纯 HTTP 管道动作。

### Service 的职责
业务规则、DTO 组装、跨资源编排（如 `WorkloadService` join Service 算暴露端口）、降级与缓存（如 `CalicoService` 的 45s TTL 缓存）。**每个资源一个 Service**，即使当前是纯透传 —— 它是业务规则的承诺扩展点。

### 目录

```
platform-api/src/main/java/com/coding/platformapi/
├── PlatformApiApplication.java
├── configs/
│   ├── ResourceServerConfig.java          # 资源服务器安全链 + JwtDecoder（JWS/JWE）+ CORS
│   ├── SecurityBeans.java                 # BCrypt
│   ├── NamespaceProtectionProperties.java # 受保护（系统）命名空间名单
│   ├── WebSocketConfig.java               # /ws/pod/exec 注册
│   ├── OAuth2JwtProperties.java           # JWS/JWE 解码配置载体
│   ├── OpenApiConfig.java / GlobalExceptionHandler.java
├── security/
│   ├── PermissionRegistry.java            # URL→code 规则表（不可变快照，可原子换表）
│   ├── PermissionRegistryFactory.java     # 启动时从 platform_permission（API 域）加载
│   ├── PermissionAuthorizationManager.java# 表驱动授权：命中行 → ANY-of PERM；否则豁免；否则拒绝
│   ├── PermissionCrossCheck.java          # 端点 × 权限行 双向核账
│   ├── PermissionCrossCheckRunner.java    # 启动期 fail-closed 校验
│   ├── ExemptPaths.java                   # 豁免清单（登录即过）
│   ├── AuthContext.java                   # 从 JWT data claim 解出当前用户身份
│   └── TenantContextResolver.java         # 生效租户解析（自管 vs 代管）
├── controllers/                           # 28 个：资源域 + 平台域（cluster/tenant/user/role/permission/…）
├── services/                              # 34 个：业务规则在此
├── k8s/
│   ├── K8sServerGateway.java              # HTTP 内核：透传 Authorization、错误码还原、流式
│   ├── K8sClient.java                     # 六操作通用 client（list/listAll/get/yaml/create/update/delete）
│   ├── K8sCalicoClient.java               # /calico/** 全族，一方法一端点
│   ├── K8sNodeClient.java                 # 节点专属动作 + 事件 + 流式日志
│   ├── K8sPodClient.java                  # Pod 流式日志
│   ├── K8sLifecycleClient.java            # /cluster/**、/tenant/**、/namespace/create 等动作
│   ├── K8sMeshClient.java                 # 网格状态 / gatewayClassName 候选
│   └── StreamQueryParams.java
├── metrics/                               # Thanos 查询：MetricsService + MetricQuery(SQL 模板) + labels + dto
├── models/                                # 请求/视图对象
└── components/PodExecRelayHandler.java    # Pod exec WS 中继
```

---

## 4. 安全模型

### 4.1 认证
`SecurityFilterChain` 用 `oauth2ResourceServer` 校验 `platform-auth` 的 JWKS。JWT 是 **self-contained**：`data` claim 里直接带用户信息、平台域角色（`platformRoles`，决定 `PLATFORM_SCOPE`）与租户上下文（`tenantInfo`），本模块**不回查授权服务器**。

- 无 token / 过期 / 签名不对 → **401**
- 过滤器链层的 401/403 不会进 `@ControllerAdvice`，由 `exceptionHandling` 统一返回 `ResponseData` JSON
- WebSocket 握手无法带 `Authorization` 头 → `QueryParameterBearerTokenResolver` 允许 token 走 query `access_token`

### 4.2 授权：表驱动，ANY-of，默认拒绝
`PermissionAuthorizationManager` 的判定顺序：

1. **命中权限行**（`platform_permission` 中 `domain='API'` 的行，`resource` 是 Ant 风格 URL pattern、`action` 是 HTTP 方法）→ 要求 token 持有该行 `code` 对应的 authority。同一 `(方法, 路径)` 的多行是 **ANY-of** 语义，命中任一个即放行；命中行但一个 code 都没有 → **直接拒绝，不回落豁免**。
2. **未命中但路径在 `ExemptPaths`** → 仅要求已登录。
3. **其余一律拒绝**（默认拒绝）。

> 匿名身份被显式排除：`AnonymousAuthenticationToken.isAuthenticated()` 恒为 `true`，不排除会导致未登录命中豁免路径。

### 4.3 启动期交叉校验（fail-closed）
`PermissionCrossCheckRunner` 在 `afterSingletonsInstantiated` 时枚举真实的 `RequestMappingHandlerMapping` 全部端点：

- **正向**：任一端点既无权限行也不在豁免清单 → **抛异常阻断启动**。漏配在部署时暴露，而不是运行时静默 403。
- **反向**：权限行匹配不到任何真实端点（幽灵行）→ **只 WARN**。不影响安全，但会误导审计闭包；v1 有意保留了几条"计划端点"行，其 warn 即是"未交付"标记。

### 4.4 豁免清单的纪律（改动前必读）
`ExemptPaths` 里的前缀是**整族吞掉**的 —— 若把管理域端点挂到已豁免的前缀之下，该端点将永远不需要权限行、也永远不会被授予任何 code，授权语义静默退化为"登录即过"，而交叉校验**检测不到**这种情形（它只兜"非豁免且无行"）。

当前豁免的实际成员（多数是"纯候选值查询"或"登录即需的上下文"）：`/context`、`/user/me`、`/user/my-tenants`、SM/PM 的 `relabel-labels` 与 `metric-names` 四端点、`/namespace/metrics/**`、`/mesh/gatewayclass-refs`、文档与 WS 路径等。**新增 controller 时须人工确认其前缀归属，勿把管理域路径塞进 `ExemptPaths`。**

### 4.5 租户上下文
- **自管**（token 带 `tenantInfo`）：请求里若也传了 `tenantId`，必须与 token 一致，否则 `TENANT_MISMATCH`
- **代管 / 平台视图**（base token，无 `tenantInfo`）：必须显式传 `tenantId`，否则 `TOKEN_TENANT_MISSING`；凭据与边界校验在 `k8s-server` 侧完成
- `/context` 返回「租户 → 集群 → 命名空间」级联（纯 DB，不调 K8s）；**带租户帽子时只输出该租户的节点** —— 端点虽豁免，目录也不该让成员枚举他人租户的布局

---

## 5. 接口概览

### 资源域（六操作，URL 与 `k8s-server` 同名）
`/workloads`、`/pods`、`/services`、`/configmaps`、`/secrets`、`/pvcs`、`/hpas`、`/replicasets`、`/rolebindings`、`/serviceaccounts`、`/storageclasses`、`/persistentvolumes`、`/nodes` 等。

形态：`POST /{resource}/list`（条件在 body）、`GET /{resource}/{name}` + query、`GET /{resource}/{name}/yaml`、`POST /{resource}`、`PUT /{resource}/{name}`、`DELETE /{resource}/{name}`；部分资源多一个 `POST /{resource}/list-all`（跨命名空间列举，**独立权限码**，见下）。

### 跨命名空间列举 `/list-all`
只有实现了 `listAll` 的 ops 才有真实能力，其余返回 `OPERATION_NOT_SUPPORTED`（**绝不静默降级**成单命名空间 list）。授权是**与 `/list` 分开的独立路径 + 独立权限码**（如 `platform:pod:list-all`）—— 因为权限表按 `(方法, 路径)` 定码，想单独授权"全局只读"就必须另开路径。当前落地：Pod、ConfigMap。

> 前端在全局视图下**只读**：行内操作（编辑/删除/日志/Exec/详情）按顶栏上下文的 namespace 解析，在跨命名空间列表里点下去会作用到另一个命名空间的同名对象。

### 平台域
| 前缀 | 内容 |
|------|------|
| `/cluster` | 集群 create / list / options / get / update / toggleEnabled / delete / provision / capability **refresh·get** |
| `/tenant` | 租户 CRUD / provision / namespace **allocate·list·deallocate** / member **add·remove·role/grant·role/revoke·list** |
| `/user` | **me** / **my-tenants** / create / list / get / platformRole **grant·revoke·list** |
| `/role` | list / create / delete / permission **save·list·assignable** |
| `/permission` | list / create / update / delete / reload |
| `/rbacTemplate` | 模板 CRUD |
| `/namespace` | 命名空间合并视图 CRUD + quota / limitrange 增删改查 + **metrics/cpu·memory·network·disk** |
| `/context` | 资源管理上下文集联（租户→集群→命名空间） |
| `/calico` | IPPool / IPReservation / BGP* 全 CRUD + IPAM 派生查询 + form-options |
| `/mesh` | gatewayclasses 六操作 + gatewayclass-refs + **status** 网格总览 |
| `/gateways` `/httproutes` `/grpcroutes` `/tcproutes` `/tlsroutes` `/udproutes` | Gateway API 资源 |
| `/servicemonitors` `/podmonitors` | 监控 CRD + 指标 |
| `/nodes/{name}/metrics/*`、`/{resource}/{name}/metrics/*` | Thanos 指标 |

---

## 6. K8s 访问通道

### client 按**传输形态**划分，不按资源域或权限分
| client | 覆盖 | 形态 |
|--------|------|------|
| `K8sClient` | 六操作（list / get / yaml / create / update / delete）+ 跨命名空间 `listAll` | 路径取 DTO 的 `getApiPath()`，跨集群级与命名空间级 |
| `K8sCalicoClient` | `/calico/**` 全族 | 一方法一端点 |
| `K8sNodeClient` | 节点专属动作 / events / 流式日志 | 一方法一端点 + `OutputStream` 出参 |
| `K8sPodClient` | Pod 流式日志 | 流式 |
| `K8sLifecycleClient` | `/cluster/**`、`/tenant/**`、`/namespace/create` 等动作 | 一方法一端点（动作，非资源 CRUD） |
| `K8sMeshClient` | 网格状态 / 候选值 | 一方法一端点 |

**`K8s*Client` 只是传输层，不参与授权。** 命名上刻意不用 `Admin`：那会暗示安全语义，而 api 侧 client 不承担任何安全职责。新增专用端点时先问它是不是六操作之一；不是，就单开 client 文件，不要塞进 `K8sClient`。

### `K8sServerGateway` 的三件事
1. **认证透传**：把当前请求的 `Authorization` 头原样转发 —— `k8s-server` 看到的是**同一个用户身份**，不是服务账号。
2. **错误还原**：`k8s-server` 返回 `code≠200` 时原样转成 `CloudPlatformException`（共用同一套 `EnumResponseType`）。刻意不用 `retrieve()`：401/403 也带 `ResponseData` JSON，统一按 body 的 `code` 判定。
3. **两套 HTTP 客户端**：常规（连接 10s / 读 120s）与**流式**（读超时 30 分钟，供日志 follow 使用，避免安静容器被掐断）。

另有一个**专用 JsonMapper**（不套用共享配置的 null 值改写），避免 `k8s-server` 的空对象字段被读成"非空的空对象"。

---

## 7. 配置说明（application.yaml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | `8081` | 服务端口 |
| `k8s.server.url` | `${K8S_SERVER_URL:http://127.0.0.1:8080}` | `k8s-server` 地址 |
| `k8s.cloud.kubeconfig.aes-key` | 内置值 | kubeconfig 落库加密密钥（与 `k8s-server` 必须一致，否则解不开） |
| `thanos.query.url` | `${THANOS_QUERY_URL:http://192.168.31.80:19090}` | Thanos Query 全局单端点、无鉴权 |
| `platform.namespace.protected-namespaces` | `kube-system`、`kube-public`、`kube-node-lease`、`calico-system`、`monitoring`、`operators`、`istio-system`、`ingress-nginx`、`cert-manager` | 受保护命名空间：**精确匹配**，命中者拒绝一切写操作（编辑/删除/配额/限制范围），**读仍开放**。须与集群内真实 ns 名一致，按部署环境增删 |
| `spring.datasource.*` | MySQL `192.168.31.80:1234/k8s_cloud_platform` | 平台库（可用 `MYSQL_URL/USERNAME/PASSWORD` 覆盖） |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `${OAUTH2_SERVER:http://127.0.0.1:9527/oauth2/jwks}` | 验签密钥源 |
| `…jwt.type` / `jws` / `jwe` | `JWS` | 本模块**有意不校验 audience**（接受 auth server 签发的任意客户端 token）；对称签名/加密另有分支 |
| `spring.security.ignore-urls` | 见 yaml | 免登录放行（actuator、文档、静态资源等） |
| `jwt.data-key` | `data` | JWT 中自定义用户 claim 的键名（与 `platform-auth` 一致） |
| Knife4j | `/doc.html`、`/v3/api-docs` | 接口文档（中文） |

> ⚠️ **Redis 未参与运行时逻辑**：`spring-boot-starter-data-redis` 在 pom 中已声明，但当前代码未使用 Redis（无 `RedisTemplate` 注入，yaml 也无 `spring.data.redis.*` 配置）。会话与令牌状态全在 `platform-auth` 侧。

---

## 8. 数据库

本模块是平台库的**主要读写方**（经 `platform-data` 的 MyBatis Mapper）：

- `platform_user` / `platform_role` / `platform_permission` / `platform_role_permission` / 用户-角色关联
- `platform_tenant` / 用户-租户-角色关联
- `platform_tenant_namespace`（**命名空间分配表** —— `k8s-server` 的边界校验依据，三元组 `(tenantId, clusterId, namespace)`）
- `k8s_cluster`（集群注册表 + capability 快照 + 加密 kubeconfig）
- `platform_rbac_template`（RBAC 模板 + 规则 JSON）

**迁移用 Flyway**，脚本在 `platform-data/src/main/resources/db/migration/`（`V2026_*`）。权限行也写在迁移里。

---

## 9. 构建与运行

**前置**：JDK 21 · Maven · 可达的 MySQL；**`platform-auth` 必须先起**（否则验签拿不到 JWKS）；提供 K8s 动作时需 `k8s-server` 在线。

```bash
# 在仓库根目录，构建本模块及其依赖
mvn -pl platform-api -am package
```

运行 `com.coding.platformapi.PlatformApiApplication`，或：

```bash
java -jar platform-api/target/platform-api-0.0.1-SNAPSHOT.jar
```

访问：`http://127.0.0.1:8081/doc.html`（Knife4j）。

### 单测
`platform-api/src/test/java/` 下有 19 个测试类，覆盖权限表语义（`PermissionAuthorizationManagerTest` / `PermissionCrossCheckTest` / `PermissionRegistryTest`）、各 Service 的业务规则（含 `NodeServiceTest` 用断言钉住"podstats 只调一次"的 N+1 修复）。

```bash
mvn -pl platform-api test
```

---

## 10. 不变式与注意事项

1. **两跳 URL 同名**：controller 的 `@RequestMapping` 与 DTO 的 `getApiPath()` **必须一致**（`/configmaps`，不是 `/resource/configmaps` 也不是 `/resources/configmaps`）。三族前缀已统一去掉，路径此后**只用于寻址，不表达任何授权含义**。
2. **新增端点的顺序**：DTO → `k8s-server` controller → 本模块 Service → Controller → **权限行迁移**。权限行漏写会导致**启动失败**（`PermissionCrossCheckRunner` fail-closed），且**迁移必须先于新构建重放**。
3. **别在 Controller 里碰 client 或 k8s-server 路径**：这是本模块最重要的分层纪律，`docs/development/backend-layering.md §1.4` 有越层信号自查清单。
4. **接口该放 api 还是 k8s-server**：K8s API 能直接回答的问题（含原生 fieldSelector/labelSelector）→ `k8s-server`；涉及数据库、跨对象聚合/join/派生计算、需要业务规则 → 本模块。冲突时以"`k8s-server` 只暴露 K8s 能直接回答的问题"为准。
5. **PersistentVolume 的跨租户收窄在本模块**（`k8s-server` 侧声明为 `UPSTREAM`，不做边界判定）：`PersistentVolumeService` 按 `claimRef.namespace` 过滤，让租户只看到属于自己的 PV。**改这一族时务必保留该过滤。**
6. **角色权限不变量是单向的**：`TENANT` 角色只能持 `tenant:` 族 code；`PLATFORM` 角色**两族都可持**（`code=admin` 的硬编码豁免已删除）。可分配集合一律由 `/role/permission/assignable` 下发。
7. **权限 code 不唯一**：同一 `code` 可对应多个 URL 行（ANY-of）。因此勾选一个 code 必须把该 code 的**全部行**都落 `platform_role_permission` 关联，否则运行时按行 id 查闭包会漏掉未关联的 URL。
8. **权限表热加载的并发边界**：写操作是"DB 提交后重读整表并原子换表"，双管理员并发编辑的竞态在本平台规模下**显式接受**（已在 `PermissionService` 注释中记录）。
9. **`/list-all` 的隐藏语义**：不要给 `/list` 加"namespace 可空 = 全部"的隐式分支 —— 那会把危险语义藏进"漏传参数"里（一个拼错参数的调用就从"看不见"变成"看见全集群"），而且写操作会跟着一起放开。
10. **CORS 已 `allowCredentials=true`**，`allowedOrigins` 用 pattern 回显请求 Origin（字面量 `*` 与 credentials 不能共存）。
