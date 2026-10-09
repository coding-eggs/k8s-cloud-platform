# k8s-server

K8s 云平台的**执行面（K8s 适配层）**：平台内**唯一**接触 Kubernetes API 的进程。它把多集群 K8s 操作包装成一组**薄、稳定、与 K8s 同形**的 HTTP 端点，供 `platform-api` 调用。

**设计原则（用户钦定）：本模块零业务逻辑。** 它不做业务判断、不查业务表（只读边界所需的分配表/集群注册表）、不装配前端 DTO —— 只回答两件事：*"这个调用方能不能碰这个端点"* 和 *"他能碰哪些命名空间、该用谁的凭据"*。其余一律拒绝承担。

- **运行端口**：`8080`
- **技术栈**：Java 21 · Spring Boot 4.0.6 · Spring Security（OAuth2 Resource Server）· fabric8 Kubernetes Client 7.9.0 · MyBatis · Spring WebSocket

---

## 1. 在整体架构中的位置

| 模块 | 职责 |
|------|------|
| `platform-web` | 前端 SPA（Vue 3 + Vite） |
| `platform-auth` | 授权服务器：登录、发令牌（`:9527`） |
| `platform-api` | 管理面 BFF：业务规则 + 权限判定 + 数据库（`:8081`） |
| **`k8s-server`** | **本模块 —— K8s 执行面（`:8080`）** |
| `k8s-core` | fabric8 client 封装（operations / converter / factory），仅本模块依赖 |
| `platform-data` | MyBatis Mapper 与实体 |
| `platform-common` | JWT 编解码、异常与响应码、K8s DTO、工具类 |

```
platform-api ──HTTP（原样透传用户的 Authorization 头）──► k8s-server
                                                            │
                        ┌───────────────────────────────────┤
                        │ ① BoundaryAuthorizationManager  ← 本层新增的唯一授权判定点
                        │ ② ResourceAccessResolver        ← 边界 + 凭据选择
                        │ ③ k8s-core operations           ← fabric8
                        ▼
                  Kubernetes API Server ── K8s RBAC（第二道闸）
```

**两跳 URL 同名**：api 侧 `/configmaps` ≡ k8s-server 侧 `/configmaps`。历史上 api 的 `/resource/**`、k8s-server 的 `/resources/**` 与 `/admin/**` 三条前缀族已统一去掉 —— **路径此后只用于寻址，不表达任何授权含义**。

---

## 2. 核心能力

### 2.1 通用资源 CRUD（六端点形态）
两个抽象基类把"访问流程"只写一次，具体 controller 只声明 `@RequestMapping` 与 `resourceType()`：

**集群级**（`AbstractClusterResourceController`，无 namespace，一律 admin client）

| 方法 | 路径 |
|------|------|
| POST | `/{resource}/list`（body 传 clusterId / labelSelector / fieldSelector） |
| GET | `/{resource}/{name}?clusterId` |
| GET | `/{resource}/{name}/yaml?clusterId` |
| POST | `/{resource}?clusterId` |
| PUT | `/{resource}/{name}?clusterId` |
| DELETE | `/{resource}/{name}?clusterId` |

覆盖：`/namespaces`、`/nodes`、`/persistentvolumes`、`/storageclasses`、`/clusterroles`、`/gatewayclasses`、`/calico/ippool`、`/calico/ipreservation`、`/calico/bgpconfiguration`、`/calico/bgppeer`、`/calico/bgpfilter`。

**命名空间级**（`AbstractNamespacedResourceController`，有 namespace，按模式选 tenant/admin client）

| 方法 | 路径 |
|------|------|
| POST | `/{resource}/list`（body 传 clusterId / namespace / labelSelector / fieldSelector） |
| POST | `/{resource}/list-all`（**跨全部命名空间列举**，平台侧） |
| GET | `/{resource}/{name}?clusterId&namespace[&tenantId]` |
| GET | `/{resource}/{name}/yaml?...` |
| POST | `/{resource}?clusterId[&tenantId]`（namespace 在 body） |
| PUT | `/{resource}/{name}?clusterId[&tenantId]`（namespace 在 body） |
| DELETE | `/{resource}/{name}?clusterId&namespace[&tenantId]` |

覆盖：`/workloads`、`/replicasets`、`/pods`、`/services`、`/configmaps`、`/secrets`、`/pvcs`、`/hpas`、`/rolebindings`、`/serviceaccounts`、`/resourcequotas`、`/limitranges`、`/servicemonitors`、`/podmonitors`、`/gateways`、`/httproutes`、`/grpcroutes`、`/tcproutes`、`/tlsroutes`、`/udproutes`。

### 2.2 跨命名空间列举（`/list-all`）
端点对**所有**命名空间级资源都存在，但**闸门只有两个，且都不在 controller 上**：

- **支不支持**：`NamespacedOperations#listAll` 有没有被实现。默认实现抛业务异常 `OPERATION_NOT_SUPPORTED` —— **绝不静默降级**成单命名空间 list（那会把"不支持"伪装成"支持"，返回一份看起来正常却不完整的数据）。
- **暴不暴露**：`platform-api` 的权限表有没有配码（没配即无可达路径；漏配会被那边的启动期交叉校验拒启）。

租户调用方到不了（`resolvePlatformNamespacedAccess` 里的平台侧身份校验会拒）。**曾经给这一格加过一个 controller 级开关，已删除** —— 那是同一个决定的第三个闸门。

### 2.3 专用端点（非六端点形态）
| controller | 路径 | 内容 |
|------|------|------|
| `NodeController` | `/nodes/**` | cordon / uncordon / drain / 标签与污点更新 / events / `podstats`（按节点聚合 Pod 数与 requests）/ `{name}/pods`（按 `spec.nodeName` 跨命名空间列 Pod）/ 流式 Pod 日志 |
| `PodController` | `/pods/{name}/logs` | 流式日志（增量轮询 `sinceTime`；初始化回看 `tailLines`/`sinceSeconds`） |
| `ClusterAdminController` | `/cluster/**` | `probe`（连通性）、`provision`（集群纳管开通）、`capability/refresh`（discovery 快照）、`client/evict`（失效客户端缓存） |
| `NamespaceAdminController` | `/namespace/create` | 确保命名空间存在 |
| `TenantAdminController` | `/tenant/**` | `sa/ensure`（确保租户 SA）、`cleanup`（租户 K8s 侧批量清理，best-effort） |
| `CalicoIpamAdminController` | `/calico/ipam/**` | `summary` / `blocks` / `is-free` / `next-free-blocks` / `block-ips`（IPAM 派生查询） |
| `CalicoFormOptionController` | `/calico/form-options`、`/calico/form-options/secrets` | 编辑器候选值 |
| `MeshController` | `/mesh/status` | 网格状态总览 |
| `PodExecWebSocketHandler` | `/ws/pod/exec` | Pod exec 终端（WebSocket） |

### 2.4 集群 / 租户生命周期（`K8sProvisioningService`）
全部走 admin client，幂等：

- `probeKubeconfig` —— 纳管前连通性探测（明文 kubeconfig，临时 client 测完即关，返回 K8s `gitVersion`）
- `refreshCapability` —— `getApiGroups()` 探测 → `{group: [versions]}`（先 evict admin client 保证用最新 kubeconfig 重建）
- `provisionCluster` —— 建 `platform-system` 命名空间 + 全部启用租户 SA + 全部模板 ClusterRole
- `ensureNamespace` / `ensureTenantSa` / `syncTemplateClusterRole` / `cleanupTenant`
- `evictClient` —— 失效某集群的 admin 与派生的全部 tenant client（kubeconfig 变更 / 禁用 / 删除后调用）

---

## 3. 访问控制模型（本模块最重要的部分）

`k8s-server` 的 `SecurityFilterChain` **始终要求认证**（JWT 校验：无 token / 过期 / 签名不对 = 401，这部分从未放松）。授权判定只有**一个点**：`BoundaryAuthorizationManager`。

### 3.1 `AccessBoundary` —— 边界是**端点自己声明**的属性
| 声明 | 含义 | 谁在用 |
|------|------|--------|
| **`PLATFORM`** | 只允许平台侧：要求 token 持有 `PLATFORM_SCOPE`（= `data.platformRoles` 非空，即持有任意 PLATFORM-scope 角色）。**与 token 有无 `tenantInfo` 无关** | 集群级资源基类的默认值；`ResourceQuota`/`LimitRange`；`NodeController`；Calico 派生查询；生命周期动作；`MeshController`；`GatewayClassController` |
| **`TENANT`** | 本层不额外要求平台身份，边界在 handler 内由 `ResourceAccessResolver` 做 | 命名空间级资源基类的默认值；`PodExecWebSocketHandler` |
| **`UPSTREAM`** | 本层**不做**边界判定，收窄责任在上游服务层 | **当前仅 `PersistentVolumeController`** |

**为什么用声明而不是路径前缀**：历史上本层用 `/admin/**` 前缀当"平台侧"的判据 —— 那是把**传输寻址**当成了**授权概念**。那条规则硬编码了 `admin` 这个角色名（与 `platform-api` 的权限表不同源），且只覆盖 14 个路由，于是挂在 `/resources/**` 下的集群级端点（nodes / persistentvolumes / storageclasses / clusterroles）**任何已认证 token 都能直接读写**。而 api 侧对应的权限行根本不用这个前缀。任何"单一前缀判据"因此必然至少错一个方向。

**为什么边界是端点属性，而不是 token 属性**：直觉上"JWT 带 `tenantInfo` 走租户逻辑、不带就要求平台侧"就够了，但那只覆盖命名空间级资源 —— 集群级 controller **不调用** `ResourceAccessResolver`，而租户成员的 token **是带** `tenantInfo` 的，按 token 属性判会落进"走原逻辑"，等于没有校验。

### 3.2 fail-closed
- handler 存在但**读不到 `AccessBoundary` 声明** → **拒绝**。未分类的端点会对**所有人 403（含平台管理员）**，因此不会被忽略。
- 因此本层**不需要**启动期交叉校验 —— 对比 `platform-api` 的 `PermissionCrossCheckRunner`：那里的失败模式是漏配 = **静默放行**，才必须启动期兜底；这里的失败模式是授权不足，不显眼但**不静默放行**。
- **未匹配到 handler**（404 / 静态资源 / 方法不支持）时不在这里拒 —— 交给 MVC 走它自己的语义，避免把 405 变成 403。

> **新增任何 controller / WebSocket handler 都必须实现 `AccessBoundaryAware`**，否则会被 403。两个资源基类已给出默认值；无基类者显式实现。

### 3.3 `ResourceAccessResolver` —— 边界校验与凭据选择
**双模身份**：
- **租户 token**（带 `tenantInfo` claim）：`tenantId` 以**签名字段为唯一来源**，客户端不可伪造；显式参数若传入必须与 token 一致，否则 `TENANT_MISMATCH`。→ **tenant client**（租户 SA 的 token，受 RoleBinding → 模板 ClusterRole 限制 —— 最小权限）
- **admin token**（无租户 claim）：`tenantId` 必须显式传参（代操作"替谁说话"），**且调用方必须是平台侧身份**（`assertPlatformSide`）。→ **admin client**（集群 kubeconfig，cluster-admin）

**统一且不可跳过的边界校验**：
- **命名空间域**：分配表三元组 `(tenantId, clusterId, namespace)` 必须存在，否则 `NAMESPACE_NOT_ACCESSIBLE`
- **集群域**：`clusterId` 必须是平台已登记的集群，否则 `CLUSTER_NOT_EXIST`

`resolvePlatformNamespacedAccess`（供 `/list-all` 与 `ResourceQuota`/`LimitRange`）**不做分配表校验**，只校验集群已登记 —— 它按定义要覆盖"不属于任何租户的命名空间"（`kube-system` 等）。**进入该模式前同样要求平台侧身份**，且对应端点还声明了 `PLATFORM` 边界（同一条判据的两个位置，有意保留）。

> **历史坑（本批修掉，勿回归）**：admin 分支原先只校验分配表三元组、**不校验调用方身份** → 任何持有"无 tenantInfo 的 token"的调用方，只要传任意租户的三元组，就能拿到 cluster-admin 凭据代操作。现在 `assertPlatformSide()` 是"谁能拿到 admin client"的**唯一守门点**，与端点声明无关 —— 因此即使在过滤器链之外（WS worker 线程、内部调用）也成立。

### 3.4 四层边界总览
```
① platform-api 权限表         ← 细粒度"能不能做这件事"只在这一处判（未认证/无分配表概念）
        │  (JWT 原样透传到 k8s-server)
        ▼
② k8s-server 端点边界         ← BoundaryAuthorizationManager：读 handler 的 AccessBoundary
        ▼
③ k8s-server 资源边界         ← ResourceAccessResolver：能碰哪些 namespace、用谁的凭据
        ▼
④ K8s RBAC（第二道闸）        ← tenant client 受 RoleBinding 限制；admin client = cluster-admin
```

**结论**：`K8s*Client`（api 侧）只是传输层，不参与授权。想收紧一个端点的访问面 → 改 ①（权限表）；想收紧数据范围 → 改 ③ 或上游 Service 层的归属过滤。

**信任边界**：`k8s-server` 假定"内网可达、主要被 `platform-api` 调用"。它**不是**纵深防御的第二道细粒度授权 —— 若 `platform-api` 自身有漏洞（漏配权限行、SSRF），本层不会拦住。

**已知残留（显式记录，勿当新问题上报）**：`/persistentvolumes/**` 声明 `UPSTREAM`（集群级但租户必须能看），持租户 token **直连** k8s-server 可读全集群 PV（含他人 `claimRef`）。闭合它需要"只接受 platform-api 调用"的内部凭证（gateway 侧签名 + 本层 filter），属下一步。

---

## 4. 目录结构

```
k8s-server/src/main/java/com/coding/k8sserver/
├── K8SServerApplication.java
├── components/
│   ├── AccessBoundary.java                 # 三态枚举：PLATFORM / TENANT / UPSTREAM
│   ├── AccessBoundaryAware.java            # controller 声明接口 + boundaryOf(handler) 静态读取
│   ├── BoundaryAuthorizationManager.java   # 唯一的授权判定点（读 handler 声明，fail-closed）
│   ├── ResourceAccessResolver.java         # 双模身份 + 边界校验 + client 选择
│   └── PodExecWebSocketHandler.java        # Pod exec 终端（TENANT 边界）
├── configs/
│   ├── ResourceServerConfig.java           # 安全链（boundaryAuthorizationManager + JwtDecoder + CORS）
│   ├── WebSocketConfig.java                # /ws/pod/exec + 握手期身份捕获拦截器
│   ├── OAuth2JwtProperties.java / OpenApiConfig.java / GlobalExceptionHandler.java
│   └── WebMvcConfig.java
├── controllers/
│   ├── base/AbstractClusterResourceController.java     # 六端点 + 集群边界（默认 PLATFORM）
│   ├── base/AbstractNamespacedResourceController.java  # 六端点 + list-all + 分配表边界（默认 TENANT）
│   ├── cluster/    # ClusterAdmin · NamespaceAdmin · TenantAdmin · Namespace · Node · PersistentVolume · StorageClass · ClusterRole
│   ├── namespace/  # Workload · ReplicaSet · Pod · Service · ConfigMap · Secret · Pvc · Hpa · RoleBinding · ServiceAccount · ResourceQuota · LimitRange · ServiceMonitor · PodMonitor · 6 类 Gateway API
│   ├── calico/     # Ippool · IpReservation · Bgp* · CalicoFormOption · CalicoIpamAdmin
│   ├── gateway/    # GatewayClass · Mesh
│   └── TestController.java                 # 仅剩 /callback（OAuth 回跳占位）
└── services/K8sProvisioningService.java    # 集群/租户开通与清理（全部 admin client，幂等）
```

---

## 5. 与 `k8s-core` 的协作

`k8s-core` 是本模块的**唯一** K8s 依赖，三层结构：

| 层 | 位置 | 职责 |
|----|------|------|
| **factory** | `KubernetesClientFactory` | 客户端缓存与构造。`adminClientCache` 按 clusterId；`tenantClientCache` 按 `(clusterId, tenantId)`。租户 client 由 admin client 创建 SA token 并做 TokenRequest 换取，受 K8s RBAC 限制。**kubeconfig 解密收敛在这里**（AES）。启动时预热：遍历启用集群 + 启用租户的分配集群 |
| | `KubernetesOperationsFactory` | 按 `ResourceType` 直接 `new` 出对应 operation；三个 getter 决定 client：`getNamespacedOperation`（tenant）、`getAdminNamespacedOperation`（admin）、`getClusterOperation`（admin）。跨版本字段发散的资源（HPA/Ingress/CRD/…）按**集群 capability** 分派 converter，目前仅 HPA 落地。L4 路由（TCP/TLS/UDPRoute）按集群是否支持 Gateway API v1 分派版本 |
| **operations** | `NamespacedOperations` / `ClusterOperations` / `ResourceOperations` | 六操作 + `listAll`；`ServerSideApply` 统一承担写路径（SSA） |
| **converter** | `converter/impl/**` | K8s 对象 ⇄ 平台 DTO 的双向映射（DTO 定义在 `platform-common` 的 `models/k8s/dto`） |

`ResourceType` 枚举（在 `platform-common`）把 DTO 类型与 operations 绑在一起，是两端共享的资源目录。

**DTO 驱动**：每个资源一个 `BaseResources` 子类，覆写 `getApiPath()` 决定 URL。`platform-api` 的 `K8sClient` 与数据库里的权限行都以它为准 —— 所以新增资源时，**DTO 的 `getApiPath()`、两跳 controller 的 `@RequestMapping`、权限行的 `resource` 三者必须同名**。

---

## 6. 配置说明（application.yml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | `8080` | 服务端口 |
| `k8s.cloud.kubeconfig.aes-key` | 内置值 | kubeconfig 落库解密密钥，**必须与 `platform-api` 一致**，否则解不开集群凭据 |
| `spring.datasource.*` | MySQL `192.168.31.80:1234/k8s_cloud_platform` | 只读边界所需的表：`k8s_cluster`、`platform_tenant`、`platform_tenant_namespace`、`platform_rbac_template`（可用 `MYSQL_URL/USERNAME/PASSWORD` 覆盖） |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `${OAUTH2_SERVER:http://127.0.0.1:9527/oauth2/jwks}` | 验签密钥源 |
| `…jwt.audiences` | `gateway-code-client` | ⚠️ 见下方注意事项 |
| `…jwt.type` / `jwe` | `JWS` | JWE 为对称密钥 / 非对称（非对称仍留 TODO） |
| `spring.security.ignore-urls` | 见 yaml | 免登录放行（actuator、文档、静态资源等） |
| `spring.servlet.multipart.max-file-size` | `5120MB` | 大对象上传 |
| `jwt.data-key` | `data` | JWT 自定义 claim 键名 |
| Knife4j | `/doc.html`、`/v3/api-docs` | 接口文档（中文） |

---

## 7. 构建与运行

**前置**：JDK 21 · Maven · 可达的 MySQL；**`platform-auth` 必须先起**（JWKS 验签）；集群凭据（加密 kubeconfig）已入库。

```bash
# 在仓库根目录，构建本模块及其依赖（k8s-core / platform-common / platform-data）
mvn -pl k8s-server -am package
```

运行 `com.coding.k8sserver.K8SServerApplication`，或：

```bash
java -jar k8s-server/target/k8s-server-1.0.0.jar
```

访问：`http://127.0.0.1:8080/doc.html`（Knife4j）。

### 单测
本模块原先零测试；2026-10-08 起引入 `spring-boot-starter-test`，因为 `BoundaryAuthorizationManager` 已成为**唯一的授权判定点**（三态 + fail-closed），必须有单测钉住决策表：

- `BoundaryAuthorizationManagerTest` —— 三态决策表；用"装饰后的真实形态"（`WebSocketHandlerDecorator` 包装）验证 WS 握手路径
- `AbstractNamespacedResourceControllerListAllTest` —— `/list-all` 的平台侧身份要求

```bash
mvn -pl k8s-server test
```

---

## 8. 注意事项与不变式

1. **不要在本模块加业务逻辑**。凡是"多对象聚合 / join / 派生计算"（除 `podstats` 这类 K8s 对象聚合的明示例外）、涉及数据库业务表、需要业务规则的，都属于 `platform-api`。判据见 [docs/development/backend-layering.md](../docs/development/backend-layering.md) §5。
2. **新增 controller 必须实现 `AccessBoundaryAware`**（或继承两个基类之一）。忘记的后果是**对所有人 403（含平台管理员）**—— 这是 fail-closed 的有意设计，不是 bug。
3. **`/list-all` 不要给 controller 加开关**，也**不要给 `/list` 加"namespace 可空 = 全部"的隐式分支**。暴露与否在 `platform-api` 的权限表里定，能力在 ops 的实现里定。
4. **WS 握手会被 `BoundaryAuthorizationManager` 判定**，而它拿到的 handler 是 `WebSocketHttpRequestHandler`（本对象被装饰器链包着）。管理器用 `WebSocketHandlerDecorator.unwrap(...)` 剥装饰器后读声明 —— **改动 WS 注册方式时别绕开这一步，否则握手 403**。
5. **WS 的 thread-local 陷阱**：`afterConnectionEstablished` 跑在 Tomcat WS worker 线程，`SecurityContextHolder` 已空。身份由 `SecurityContextHandshakeInterceptor` 在握手期（HTTP 线程）存入 session attributes，`ResourceAccessResolver` 有显式身份重载接收它。
6. **`/persistentvolumes/**` 的 `UPSTREAM` 是显式例外**，不是遗漏。它的跨租户收窄在 `platform-api` 的 `PersistentVolumeService`（按 `claimRef.namespace`）。新增集群级资源若需租户可见，覆写 `accessBoundary()` 并在类注释写明理由 —— 让例外可被一眼搜出。
7. **`spring.security.oauth2.resourceserver.jwt.audiences: gateway-code-client`** 与 `platform-api`（有意不校验 audience）**不一致**。这是两跳之间的一个真实差异：直连本模块的 token 必须带该 audience。排查"api 能调通但直连 403/401"时先看这里。
8. **直连本模块的路径不受权限表约束**：`platform-api` 的细粒度权限只在那一跳生效。租户持有合法 token **直连** `k8s-server` 时，只有 ②③ 两层在把关。已知残留见 §3.4。
9. **HTTP 状态码语义**：认证/授权失败由过滤器链返回 `{code, msg, data}` JSON 形态的 401/403（不进 `@ControllerAdvice`）；业务异常走 `GlobalExceptionHandler` 返回 `ResponseData`。`platform-api` 的 `K8sServerGateway` 刻意不用 `retrieve()`，而是统一读 body 的 `code`。
10. **不要恢复联调端点**：`TestController` 里曾经的 `GET /test`（把整个 JWT 打到 stdout）与授权码 `println` 已删除，仅保留 `/callback` 占位。新增任何 handler 都受 §8.2 约束。
11. **命名空间保护名单不在本模块**：`platform.namespace.protected-namespaces` 是 `platform-api` 的配置（业务规则）。本模块对系统命名空间**不做**特殊处理。
