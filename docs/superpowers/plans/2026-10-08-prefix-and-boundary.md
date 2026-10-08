# 2026-10-08 k8s-server 鉴权收敛 + 两侧路径去前缀（实施记录 + 部署 runbook）

## Context

k8s-server 一直被当作"内网适配器"，但它必然站在授权链上：`K8sServerGateway.passThroughAuthorization`
把调用方的 `Authorization` 头**原样转发**，k8s-server 拿到的就是用户 token。它必须做的判断
（"这个 token 能碰哪个 namespace"，即 `ResourceAccessResolver` 的分配表三元组）是对的，问题出在
第二处判断 —— `ResourceServerConfig` 的 `/admin/** → PLATFORM:admin`：

1. **把传输寻址当授权概念**：`/admin/**` 在 k8s-server 侧只是 14 个路由的前缀；platform-api 侧对应的
   权限行根本不用这个前缀（是 `/calico/**`），且同一族上挂着两个不同权限码
   （`platform:cluster:manage` / `platform:bgp:config:manage`，见 `V2026_10_06_1`）。
   任何"单一前缀判据"必然至少错一个方向。
2. **硬编码角色名**：要求 `data.platformRoles` 里有叫 `admin` 的角色码；一个在权限表里持有
   `platform:cluster:manage` 的自定义 PLATFORM 角色会被 403 —— 两跳判据不同源。
3. **覆盖不全**：集群级的 `/resources/{nodes,storageclasses,clusterroles}` 不在 `/admin/**` 下，
   只要求 `authenticated()`，**任何登录用户（含租户成员）直连即可读写**。它们的 javadoc 却写着
   "PLATFORM:admin，平台管理员专属"。

同批查出的第二个洞：`ResourceAccessResolver` 的 admin 分支（token 无 `tenantInfo` → `tenantId` 由
**调用方提供** + admin client）**不校验任何平台身份** → 传任意租户的三元组即可拿到 cluster-admin 凭据
代操作。（platform-api 侧 `TenantContextResolver` 只校验 tenantId、从不校验 namespace；
`hasNamespaceAccess` 全仓唯一调用点就是 k8s-server。）

## 改了什么

### 1. 授权判据：从"路径前缀 + 角色名"改成"端点声明 + 平台侧身份"

- `PlatformJwtAuthenticationConverter` 新增合成 authority **`PLATFORM_SCOPE`**（`platformRoles` 非空时追加）。
  判据精确：签发期 `selectRoleCodesByUser` 已 `INNER JOIN platform_role` 并按 `scope='PLATFORM'` 过滤，
  所以**租户成员的 `platformRoles` 必为空**。刻意不带冒号 ⇒ 与 `PLATFORM:<roleCode>` 构造上不可能相撞。
- 新增 `AccessBoundary`（`PLATFORM` / `TENANT` / `UPSTREAM`）+ `AccessBoundaryAware`，挂在两个资源基类上：
  集群级默认 `PLATFORM`，命名空间级默认 `TENANT`，`ResourceQuota`/`LimitRange` 覆写 `PLATFORM`。
- 新增 `BoundaryAuthorizationManager`（SecurityFilterChain 里替换掉 `/admin/**` 那行）：用
  `List<HandlerMapping>` 解析目标 handler，读它声明的边界。

  | 声明 | 动作 |
  |---|---|
  | `PLATFORM` | 要求 `PLATFORM_SCOPE`，否则 403 |
  | `TENANT` / `UPSTREAM` | 放行（边界在 handler 内 / 上游服务层） |
  | 读不到声明 | **拒绝**（fail-closed） |
  | 未匹配到 handler | 放行（否则 404/405 会变 403） |

- **`PLATFORM` 与 token 有无 `tenantInfo` 无关** —— 这条是必须的：集群级 controller 不调用
  `ResourceAccessResolver`，而租户成员的 token 是**带** `tenantInfo` 的。
- `ResourceAccessResolver` 新增 `assertPlatformSide()`：admin 分支与 `resolvePlatformNamespacedAccess`
  都要求 `PLATFORM_SCOPE` —— "谁能拿到 admin client"收敛到一个守门点，与 HTTP 入口无关
  （WS worker 线程同样成立）。
- 删除 `AbstractNamespacedResourceController.assertPlatformBoundaryEntryPoint()`：它断言
  `boundary()==PLATFORM ⇒ @RequestMapping 以 /admin/ 开头`，前缀去掉后恒假；其目的由上述管理器直接承担。
- `PersistentVolumeController` 覆写为 **`UPSTREAM`**：集群级但**租户必须能看**（权限码
  `tenant:persistentvolume:*`），跨租户收窄在 platform-api 的 `PersistentVolumeService`。请见"残留风险"。
- 删除调试残留 `GET /test`（把整个 JWT 打到 stdout）与 `/callback` 里的授权码 println。

### 2. 路径：三族前缀统一去掉，两跳 URL 同名

| 变更前 | 变更后 |
|---|---|
| api `/resource/configmaps` | `/configmaps` |
| k8s-server `/resources/configmaps` | `/configmaps` |
| k8s-server `/admin/calico/ippool` | `/calico/ippool`（与 api 侧同名） |
| k8s-server `/resources/persistentvolumeclaims` vs api `/resource/pvcs` | 两侧统一为 **`/pvcs`** |
| api `/resource/context` | `/context`（同步 `ExemptPaths`） |
| k8s-server `/admin/{namespaces,namespace,cluster,clusterroles,tenant,resourcequotas,limitranges}` | 去 `/admin` |

路径唯一来源是 platform-common 里 25 个 DTO 的 `getApiPath()`（`ResourceType` 不带路径），
所以 31 个 k8s-server controller 的 `@RequestMapping`、12 个 api controller、5 个专用 client 里的
硬编码字符串、前端 3 个 api 模块必须同步改 —— 漏一处即两跳错位。

**范围外**：前端**浏览器路由**（`/resources/workloads` 等）与 `domain='Page'` 权限行**未动** ——
那是页面 URL，与 HTTP 端点无关。

### 3. 数据迁移

`V2026_10_08_1__drop_api_resource_url_prefix.sql`：

```sql
UPDATE platform_permission
SET resource = SUBSTRING(resource, LENGTH('/resource') + 1)
WHERE domain = 'API' AND resource LIKE '/resource/%';
```

- 必须带 `domain='API'`（Page 行的 resource 是前端路由）。
- 有意不加 `deleted_at IS NULL`：软删行一并改写，避免将来恢复时留下指向不存在端点的幽灵行。
- `uk_domain_resource_action` 已在 `V2026_09_24_2` 降级为普通索引，无唯一键冲突；
  `k8s_cloud_platform.sql` dump 里**没有** `platform_permission` 的 INSERT，故无需同步 dump。

### 4. 测试

- `PlatformJwtAuthenticationConverterTest`：+4 例（自定义平台角色也拿到 `PLATFORM_SCOPE`；租户成员 /
  claim 缺失 / 空白角色码均不拿到）。
- **新增 `k8s-server/src/test/.../BoundaryAuthorizationManagerTest`（10 例）** —— 本模块原先零测试，
  为此在 `k8s-server/pom.xml` 加了 `spring-boot-starter-test`（test 作用域）。覆盖决策表全部行 +
  未分类 handler fail-closed + WS 拆分。**这个测试立刻抓到一个真 bug**：`WebSocketHandlerRegistry`
  注册的 handler 被 `ExceptionWebSocketHandlerDecorator` 包着，按最外层读声明读不到 → 真实运行时
  `/ws/pod/exec` 握手会被 403。修法是 `WebSocketHandlerDecorator.unwrap(...)`，测试用**装饰后的真实形态**钉住。
- `ExemptPathsTest` / `PermissionAuthorizationManagerTest` / `PermissionCrossCheckTest`：路径字面量同步。

## 验证结果（2026-10-08）

| 项 | 结果 |
|---|---|
| `mvn test` | platform-common 6 ✓ / k8s-server 10 ✓ / platform-api 151 ✓ / k8s-core 87 其中 **1 个既有失败**（见下） |
| 前端 | `vue-tsc --build` + `vite build` 均通过 |

> **k8s-core 的既有失败**（与本批无关，未修）：`WorkloadConverterStaticIpTest
> .revert_ipAddrs_annotation_maps_to_staticIps_and_is_removed_from_annotations`。该测试第 72 行传
> `dto(null, null)`（无 staticIps ⇒ `withIpAddrs` 不写注解），第 75 行却假设 build 已写入注解 →
> `new LinkedHashMap<>(null)` 必然 NPE，测试自身不自洽。相关文件（`k8s-core` 全体、`PodTemplateDTO`、
> `WorkloadConverter`）均未在本批改动。**建议单独开一批处理**（可能是静态 IP 功能某次改动后测试没跟上）。

## 部署 runbook

**迁移与两侧代码必须同批上线**（platform-api 的 `PermissionCrossCheckRunner` 是 fail-closed）：

1. 停旧服务 → 重放 `V2026_10_08_1__drop_api_resource_url_prefix.sql` → 起新构建。
   - 先起新构建、后重放迁移 ⇒ platform-api 启动失败（裸端点拒启）。
   - 先重放、后起新构建 ⇒ 旧构建的权限行匹配不到端点，授权全拒。
2. 启动后确认日志：platform-api 打出 `[RBAC] 交叉校验通过：N 个 endpoint 全部有权限声明或豁免`。
   k8s-server 侧无启动期断言（边界判定在请求期）。
3. 前端与 platform-api 同批发布（前端 api 路径已改，旧前端打新后端会 404）。

**冒烟清单**（需活后端）：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 租户成员 token 打 `/configmaps/list` | 正常 |
| 2 | 租户成员 token 打 `/nodes/list` | **403**（改前能过） |
| 3 | 自定义 PLATFORM 角色（非 admin，持 `platform:cluster:manage`）打 `/nodes/list` | 通过（改前 403） |
| 4 | 无 `tenantInfo` 的 token 打 `/workloads/list` 并传他人 tenantId | **403**（改前会给 admin client） |
| 5 | 带 `tenantInfo` 的租户 token 传他人 namespace | 原 `NAMESPACE_NOT_ACCESSIBLE` 不变 |
| 6 | Pod 终端（WS exec） | 能连上（重点：装饰器 unwrap 的回归） |
| 7 | 资源页 CRUD / 节点页 8 个专用端点 / Calico CRUD / 集群开通清理 | 全通 |

## 残留风险（已知并接受，勿当新问题上报）

- **`/persistentvolumes/**` 的直连暴露**：持租户 token 直连 k8s-server 可读全集群 PV（含他人
  `claimRef`）。本层没有可用判据（PV 集群级但租户可见），故声明 `UPSTREAM` 并显式记录。
  闭合方向：给 gateway 加内部调用凭证（HMAC/mTLS），k8s-server 侧 filter 只接受来自 platform-api 的调用。
- **平台侧不再是纵深防御的细粒度层**：`PLATFORM` 判据只回答"你在栅栏哪一侧"，不回答"这个端点要哪个权限码"。
  若 platform-api 自身有漏洞（漏配权限行、SSRF），k8s-server 不会拦住。
- k8s-server 仍 `permitAll` `/actuator/**`、`/doc.html`、`/v3/api-docs/**` —— 非测试环境应关闭。
