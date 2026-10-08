# 后端分层与 client 规范（platform-api / k8s-server）

> 适用范围：`platform-api`（对前端的管理面 BFF）与 `k8s-server`（对 K8s 的执行面）。
> 起因：2026-10 排查发现 13 个资源 controller 里 12 个**越层直连 K8s client**，业务规则、DTO 组装、
> k8s-server 路径字符串散落在 controller 里；`K8sAdminClient` 被当成"安全边界"使用，而它其实只是
> 一个按 wire 形态划分的传输 client。本文是防复发的判据，不是事后描述。

---

## 1. 分层职责（platform-api）

```
HTTP ──► Controller ──► Service ──► Client ──► K8sServerGateway ──► k8s-server
         参数绑定      业务规则      传输       透传 Authorization
         ResponseData  编排/校验     路径与参数
```

### 1.1 Controller 的三条硬规则

1. **禁止出现 `K8s*Client` / `K8sServerGateway` 的注入**，**禁止出现任何 k8s-server 路径字符串**
   （`"/resources/nodes/cordon"` 这类字面量）。
2. **禁止业务判断**：存在性校验、跨资源查询、参数三选一规则、DTO 字段改写（如 `body.setName(name)`
   之外的语义改写）、降级/缓存。
3. 只保留：`@RequestMapping` 与动词注解、路径/query 参数绑定、`<code>ResponseData</code>` 包装、OpenAPI 注解、
   `HttpServletResponse` 输出流这类**纯 HTTP 管道**动作。

### 1.2 Service 的职责

业务规则、DTO 组装（`dto(name, tenantId, clusterId, namespace)` 这类工厂方法一律在本层）、
跨资源编排（如 `WorkloadService` join Service 算暴露端口）、降级与缓存（如 `CalicoService` 的 TTL 缓存）。
**每个资源一个 Service**，即使当前是纯透传 —— 它是业务规则的承诺扩展点；
"现在没有规则所以 controller 直接调 client"正是本批要消除的形态。

### 1.3 Service 里不该出现的东西

Servlet 类型（`HttpServletResponse`）与 HTTP 管道调用。流式日志的做法是：
Service 收一个 `OutputStream`（由 controller 从 `HttpServletResponse` 取出传进来），
Service 负责 query 组装与转发，controller 负责响应流。

### 1.4 越层信号（自查清单）

看到下面任一形态，就是这个文件该改：

- controller 里出现 `private final K8s*Client` 或 `private final K8sServerGateway`
- controller 里出现以 `/` 开头的 k8s-server 路径字符串
- controller 里有 `new XxxDTO()` 并逐个 setter 填身份字段
- controller 里有 `Map<String,String>` 参数拼装
- controller 里出现"先查一次判断是否存在，再调用"的两步编排

---

## 2. client 分层

### 2.1 真实形态：按**传输形态**分，不是按资源域或权限分

| client | 覆盖 | 形态 |
|---|---|---|
| `K8sClient` | 全部 DTO 驱动六操作（list/get/yaml/create/update/delete） | 路径取 `BaseResources#getApiPath()`，跨集群级与命名空间级资源 |
| `K8sCalicoClient` | `/calico/**` 全族 | 一方法一端点；list 走 body、get/yaml 的 clusterId 走 query |
| `K8sNodeClient` | `/nodes/**` 中非六端点部分（cordon/…/events/流式日志） | 一方法一端点 + `OutputStream` 出参 |
| `K8sPodClient` | `/pods/{name}/logs` | 流式 |
| `K8sLifecycleClient` | `/cluster/**`、`/tenant/**`、`/namespace/create` | 一方法一端点（动作，非资源 CRUD） |

**`K8sClient` 为什么既能收 `/namespaces` 又能收 `/pods`**：它收的是"是不是六端点标准形态"，
与资源是集群级还是命名空间级无关。所以**按 namespace/cluster 拆 api 侧 client 是不成立的** ——
那条轴落在 k8s-server 侧（见 §4），api 侧只是哑代理。

> **2026-10-08 变更**：`/resource/**`（api 侧）、`/resources/**` 与 `/admin/**`（k8s-server 侧）三条前缀族
> 已**统一去掉**，两跳的 URL 空间因此重合（api `/configmaps` ≡ k8s-server `/configmaps`）。路径此后
> 只用于寻址。见 §3 与 `superpowers/plans/2026-10-08-prefix-and-boundary.md`。

### 2.2 拆分规则

- 一个后端路由族 = 一个 client 文件（Calico 一族、Node 专属动作一族、生命周期一族）。
- 新增专用端点时：**先问它是不是六操作之一**。不是，就不要塞进 `K8sClient`，单开文件。
- 方法体只做"参数 → 请求 → 响应"的映射，零业务。任何"先查再算再写"都属于 Service。

### 2.3 命名

- `K8sClient`（公共行为）—— 不叫 `K8sResourceClient`，因为"Resource"会让人以为它只管 `/resources/**`。
- 专用 client 用 `<后端路由域>Client`：`K8sCalicoClient` / `K8sNodeClient` / `K8sPodClient` / `K8sLifecycleClient`。
- **不要**用 `Admin` 命名：它暗示安全语义，而 api 侧 client 不承担任何安全职责（见 §3）。

---

## 3. 安全边界分四层（`K8s*Client` 不是边界）

```
① platform-api 权限表        platform_permission(API 域) → PermissionRegistry → PermissionAuthorizationManager
                             URL+method → code（ANY-of），默认拒绝，启动期交叉校验 fail-closed
                             ← 细粒度"能不能做这件事"只在这一处判
        │  (JWT 原样透传到 k8s-server)
        ▼
② k8s-server 端点边界         BoundaryAuthorizationManager：读目标 handler 声明的 AccessBoundary
                             · PLATFORM → 要求 PLATFORM_SCOPE（= data.platformRoles 非空）
                             · TENANT / UPSTREAM → 放行，边界交给 ③ 或上游服务层
                             · 读不到声明 → 拒绝（fail-closed）
                             与路径前缀无关；这就是"哪个端点是平台侧"的唯一判据
        │
        ▼
③ k8s-server 资源边界         ResourceAccessResolver：回答"能碰哪些 namespace、用谁的凭据"
                             · 命名空间域(租户边界)：分配表三元组 (tenantId, clusterId, namespace) 必须存在
                             · 命名空间域(平台边界)：只校验集群已登记，一律 admin client
                             · admin 分支（token 无 tenantInfo）：额外要求调用方是平台侧身份
                             · 集群域：只校验集群已登记
        │
        ▼
④ K8s RBAC（第二道闸）
                             tenant client = 租户 SA 的 token，受 RoleBinding→模板 ClusterRole 限制
                             admin  client = 集群 kubeconfig（cluster-admin）
```

结论：**`K8s*Client` 只是传输层，不参与授权**。想收紧一个端点的访问面，改的是 ①（权限表）；
想收紧数据范围，改的是 ③ 或 Service 层的归属过滤（如 `PersistentVolumeService` 按
`claimRef.namespace` 收窄）。

### 3.1 信任边界

先分清两件事：k8s-server 的 `ResourceServerConfig` **始终要求认证**（`oauth2ResourceServer` 的 JWT 校验；
无 token / 过期 / 签名不对 = 401，这部分从未放松）。2026-10-08 批次换掉的只是**授权判据**。

- **曾经**：`/admin/** → PLATFORM:admin` 一条路径规则。它把传输寻址当授权概念，硬编码 `admin` 这个
  角色名（与 ① 的权限表不同源），且只覆盖 14 个路由 —— 挂在 `/resources/**` 下的集群级端点
  （nodes / persistentvolumes / storageclasses / clusterroles）因此**任何已认证 token 都能直接调用**。
- **现在**：边界由 controller 声明（`AccessBoundary`，见 §4），集群级默认 `PLATFORM`。租户成员直连
  `/nodes/**` 同样被 403。
- **仍然成立的前提**：k8s-server 假定"内网可达、主要被 platform-api 调用"。它**不是**纵深防御的第二道
  细粒度授权 —— 若 platform-api 自身有漏洞（漏配权限行、SSRF），k8s-server 不会拦住。
- **已知残留（显式记录，勿当新问题上报）**：`/persistentvolumes/**` 声明 `UPSTREAM`（集群级但租户必须
  能看），持租户 token 直连 k8s-server 可读全集群 PV（含他人 `claimRef`）。闭合它需要"只接受 platform-api
  调用"的内部凭证（gateway 侧签名 + k8s-server filter），属下一步。
- **本批有意没做**：k8s-server 侧的启动期交叉校验。它的失败模式是"授权不足"（未分类即 403，连平台
  管理员一起 403，不显眼但**不静默放行**），与 ① 那个 fail-closed 校验（失败模式是**静默放行**）性质相反，
  故不需要。

---

## 4. k8s-server 侧：controller 声明自己的访问边界

两个基类各自给出默认边界，无基类的 controller 直接实现 `AccessBoundaryAware`；消费方是
`BoundaryAuthorizationManager`（§3 的 ②）。

| 声明 | 用途 | 边界 |
|---|---|---|
| `AbstractClusterResourceController<T>` | 集群级资源（无 namespace） | `PLATFORM`（默认） |
| `AbstractNamespacedResourceController<T>` | 命名空间级资源（有 namespace） | `TENANT`（默认） |
| `ResourceQuotaController` / `LimitRangeController` | 命名空间级，但平台级开通流程用 | 覆写为 `PLATFORM` |
| 无基类者：`NodeController`、Calico 派生查询、生命周期动作、`PodExecWebSocketHandler` | 非六端点形态 | 各自显式实现（HTTP 全 `PLATFORM`，WS 为 `TENANT`） |

`AccessBoundary` 三个取值：

- **`PLATFORM`**：只允许平台侧。要求 token 持有 `PLATFORM_SCOPE`（= `data.platformRoles` 非空）。
  **与 token 有无 `tenantInfo` 无关** —— 这一点是必须的：集群级 controller 不调用
  `ResourceAccessResolver`，而租户成员的 token **是带** `tenantInfo` 的，"有 tenantInfo 就走租户逻辑"
  对它们等于没有校验。
- **`TENANT`**：租户边界。租户 token 的 tenantId 以 JWT claim 为唯一来源；无 tenantInfo 的 token
  必须显式传 tenantId 代操作**且必须是平台侧身份**；两者都过分配表三元组校验。client 按模式选
  tenant/admin。
- **`UPSTREAM`**：本层不做边界判定，收窄在上游服务层。**当前仅 `PersistentVolumeController`** 用它。
  选它等于显式承认"这一族对象的隔离不归本层"，便于一眼搜出全部例外 —— 不要靠"挂在某个前缀下"隐式豁免。

**跨命名空间列举（`/list-all`）：能力由 ops 实现声明，没有第二个闸门**

命名空间级基类提供 `POST /list-all`（对**所有**子类都存在）。闸门只有两个，都不在 controller 上：

- **支不支持**：`NamespacedOperations#listAll` 有没有被实现 —— 默认实现抛业务异常 `OPERATION_NOT_SUPPORTED`，
  绝不静默降级成单命名空间 list（那会把"不支持"伪装成"支持"，返回一份看起来正常却不完整的数据）。
- **暴不暴露**：platform-api 的权限表有没有配码（没配就没有可达路径，且漏配会被启动期交叉校验拒启）。
- **租户调用方到不了**：`/list-all` 走 `resolvePlatformNamespacedAccess`，其中的平台侧身份校验会拒。
- 归属判据见 §5.2。

> 曾经给这一格加过一个 `platformCrossNamespaceList()` 开关（默认 false）。它是**同一个决定的第三个闸门**；
> 买到的只有"挡住 ops 已实现但平台不想暴露时的直连路径"这一条薄边，不值得在每个资源上多维护一个概念，已删除。

**为什么不再用前缀断言**：原先的不变式是"声明 PLATFORM 边界的 controller，其 `@RequestMapping` 必须以
`/admin/` 开头"，即靠挂载位置保证"免分配表校验的路径租户碰不到"。前缀去掉后该断言恒假；这条不变式现在
由 ② 直接承担（声明 PLATFORM ⇒ 必须持有 PLATFORM_SCOPE），比间接引用路径更强 —— 它不再依赖任何人记得
把 controller 挂在正确的前缀下。

**非资源 controller**（生命周期动作、派生查询、流式端点）不套基类，各自写 —— 它们不是资源 CRUD，
但**必须**实现 `AccessBoundaryAware`，否则被 ② 拒绝。

---

## 5. 接口该放 api 还是 k8s-server

按顺序问：

1. **是不是 K8s API 能直接回答的问题**（list/get/yaml/CRUD + 原生 fieldSelector/labelSelector）？
   → k8s-server。
2. **是否需要跨命名空间的视野**（kube-system 等非租户命名空间、按 `spec.nodeName` 全局统计）？
   → k8s-server 提供能力（它有 admin client），api 侧做业务过滤。
3. **是否涉及数据库**（分配表、模板、集群注册表）？ → 一律 platform-api。
4. **是不是多对象聚合 / join / 派生计算**？ → platform-api。
5. 冲突时以 **"k8s-server 只暴露 K8s 能直接回答的问题"** 为准。

### 5.1 两个实例裁定

- `/nodes/{name}/pods`（按 `spec.nodeName` 列 Pod，跨命名空间）：
  是 K8s 用 fieldSelector 能直接回答的（§5.1）→ 留在 k8s-server。api 侧原样透传，
  **不因为"api 是唯一鉴权点"就把它下沉** —— 下沉反而要多一跳。
- `/nodes/podstats`（按节点聚合 Pod 数与 requests）：
  形式上属 §5.4（聚合计算），但它需要"跨全部命名空间 list pods"，而 platform-api 侧只有命名空间域
  的 `/pods/list`（namespace 必填）。放 api 侧会退化成 N×M 次请求。
  → **明示例外**：留在 k8s-server 作为"K8s 对象聚合"，api 侧的 `NodeService` 只做 join。

### 5.2 跨命名空间列举（`/list-all`）为什么这么切

需求形态："看全部 Pod"、"运维一个不属于任何租户的命名空间"。这不是服务边界问题，而是原模型里缺的一格 ——
"作用域"（单命名空间 / 跨命名空间）与"边界"（租户 / 平台）是两个正交轴，而 `list(namespace, ...)` 把命名空间
做成了必填参数，于是这一格无处安放：

| | 单个命名空间 | 跨命名空间 |
|---|---|---|
| 租户边界 | ✅ `/list`（分配表三元组） | ❌ 不该有 —— "我的全部命名空间"是业务，属 platform-api 扇出 |
| 平台边界 | ✅ `/list` + 显式 tenantId 代操作 | ✅ **`/list-all`**（本批补的；含无租户的命名空间） |

三条归属判据：

1. **能力放 k8s-core**（`NamespacedOperations#listAll`）：`inAnyNamespace()` 是 K8s 能直接回答的问题，
   而且 k8s-core 里早就手写过三处（Calico form-options / Calico IPAM / Node 的 podstats·pods·events），
   本批只是把它形式化、收进接口。
2. **形态放 k8s-server 的基类**：`/list-all` 由 `AbstractNamespacedResourceController` 提供给**所有**命名空间级
   资源，支不支持由 ops 的 `listAll` 实现决定。**不给 `list()` 加"namespace 可空 = 全部"的隐式分支** —— 那会把
   危险语义藏进"漏传参数"里（一个拼错参数的调用就从"看不见"变成"看见全集群"），而且写操作会跟着一起放开。
   也**没给 `/list-all` 加 controller 级开关**：暴露与否本来就在 api 侧权限表里定，加开关等于同一个决定配三个闸门。
3. **授权放 platform-api，且必须是独立路径**：权限表按 `(方法, 路径)` 定码，同一路径同一方法只能是一组 code。
   想给"全命名空间列举"单独授权（例如只给审计角色 Pod 全局只读、不给 ConfigMap —— ConfigMap 常含带凭据的
   配置），就只能另开一个路径。`/configmaps/list-all` 的码是 `platform:configmap:list-all`，与租户的
   `tenant:configmap:list` 互不影响。

**前端配套：全局视图是只读的。** 在那一模式下整列隐藏行内操作（编辑/删除/日志/Exec/详情）—— 它们都按**顶栏上下文**
的 namespace 解析，在跨命名空间列表里点下去会作用到**另一个命名空间里的同名对象**。要操作请先切上下文。

---

## 6. 已修的历史问题（不要再退回去）

| 问题 | 表现 | 修法 |
|---|---|---|
| `NodeService.list` N+1 + 死代码 | 先查一次 podstats 建 map 却不用，再在循环里逐节点重查 → 10 节点 11 次 HTTP | podstats 只取一次，按 nodeName 建索引后内存回填；`NodeServiceTest` 断言调用次数 |
| controller 直连 client | 12/13 个资源 controller | 全部下沉 Service，controller 只做绑定与包装 |
| `/user/logout` 死配置 | `application.yaml` 的 ignore-urls 里登记了一个不存在的端点，掩盖了"根本没有登出端点" | 改为真实存在的 `/session/logout` |
| 错误的类注释 | `AbstractClusterResourceController` 声称"仅 PLATFORM:admin 可访问（SecurityFilterChain 对 /admin/** 统一要求）"，而 PV/SC/ClusterRole 实际挂在 `/resources/**` | 注释如实改写；该错误正是 PV 跨租户可读、SC 全员可读两个越权面的认知根因 |
| **路径前缀当授权判据** | `/admin/** → PLATFORM:admin`：把传输寻址当授权概念、硬编码 `admin` 角色名（与权限表不同源），且只覆盖 14 个路由 → `nodes`/`persistentvolumes`/`storageclasses`/`clusterroles` **任何已认证 token 都能读写** | 边界改由 controller 声明（`AccessBoundary`）+ `BoundaryAuthorizationManager`；判据换成 `PLATFORM_SCOPE`（= 持有任意 PLATFORM-scope 角色）；三族前缀一并去掉。见 `superpowers/plans/2026-10-08-prefix-and-boundary.md` |
| **admin 代操作不校验调用方身份** | `ResourceAccessResolver` 的 admin 分支（token 无 tenantInfo → tenantId 由调用方提供 + admin client）只校验分配表三元组，不校验调用方是不是平台侧 → 传任意租户的三元组即可拿 cluster-admin 凭据代操作 | `assertPlatformSide()`：进入 admin 模式前要求 `PLATFORM_SCOPE`；`resolvePlatformNamespacedAccess` 同样加 |
| **WS 握手绕过边界判定** | `WebSocketHttpRequestHandler` 里那层 handler 被 `WebSocketHandlerDecoratorFactory` 包着，按最外层读声明读不到 → 握手 403（补测试时才暴露） | `WebSocketHandlerDecorator.unwrap(...)` 剥装饰器链；`PodExecWebSocketHandler` 实现 `AccessBoundaryAware`，`BoundaryAuthorizationManagerTest` 用"装饰后的真实形态"钉住 |

---

## 7. 新增一个资源页/端点的 checklist

1. DTO 放在 `platform-common`（`BaseResources` 子类，覆写 `getApiPath()`）。
   **路径不带前缀族**（`/configmaps`，不是 `/resources/configmaps` 也不是 `/resource/configmaps`）。
2. k8s-server：套 `AbstractClusterResourceController` 或 `AbstractNamespacedResourceController`
   （后者按需覆写 `accessBoundary()`），只声明 `@RequestMapping` + `resourceType()`。
   **无基类的 controller 必须实现 `AccessBoundaryAware`** —— 不实现会被 `BoundaryAuthorizationManager`
   拒绝（fail-closed，对所有人 403，含平台管理员）。
3. platform-api：`XxxService`（业务 + DTO 组装）→ `XxxController`（绑定 + 包装）。
   controller 的 `@RequestMapping` 与 DTO 的 `getApiPath()` **必须一致**（两跳 URL 现在同名）。
4. **权限行**：API 域行写进 Flyway 迁移（`resource` 与 api 侧路由一致，无 `/resource` 前缀）；
   `PermissionCrossCheckRunner` 是 fail-closed，漏行即拒绝启动。迁移必须先于新构建重放。
5. client：六操作走 `K8sClient`（无需改）；专用端点单开 client 文件。
6. 前端页面：见 `frontend-permission-conventions.md` 的 checklist。
