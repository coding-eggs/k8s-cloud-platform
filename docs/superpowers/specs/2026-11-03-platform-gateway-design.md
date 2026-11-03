# 平台 API 网关（接口级鉴权上收）设计

- 日期：2026-11-03
- 状态：**方案已定稿，本周不实施**（下周开工，先写实施计划）（用户：「先敲定个方案，然后不做。下周有 token 再做」）
- 相关：`2026-11-03-oauth-client-management-in-platform-design.md`（本方案会替换其中的「api 转调 auth 内部接口」那一段）

## 1. 为什么做

- platform-auth 不只是协议服务，它自己也有**管理类接口**：客户端管理（`/internal/clients/**`）、`/jwk/generate`。
  这些接口需要平台权限，但 auth 不认识权限表。眼下的解法是 platform-api 转发一层 + 共享内部令牌
  （`PLATFORM_INTERNAL_TOKEN`），每多一个 auth 管理接口就要在 api 里多写一份 DTO + client + controller。
- `/jwk/generate` 目前**只要登录就能调**（会话鉴权），任何登录用户都能往 jwk 表里插带优先级的密钥 —— 现存缺口。
- platform-api 将来**可能拆分**（用户：「也许会拆，暂时不拆」）。接口级鉴权现在长在 api 进程里，
  拆出去的每个服务都得再带一份，权限驱动会被复制 N 次。

用户的方向（原话）：「把权限、审计都放进网关，业务继续写业务」。

## 2. 目标 / 非目标 / 反目标

**目标**
1. 新增一个网关服务，所有对外业务流量（现 nginx `/api/`）经它进入后端。
2. 接口级鉴权（权限表：请求路径+方法 → 权限码 → 用户闭包里有没有）**只在网关做一次**。
3. auth 的管理接口直接挂在网关后面、受同一套权限表约束；删除 api 的转发层与内部令牌机制。
4. 被网关拒掉的请求（403）由网关记审计。
5. 为 platform-api 将来拆分留好位置：新服务只要注册路由 + 在权限表里有行，就自动受控。

**非目标**
- 本周不写代码。
- 不拆 platform-api。
- **数据范围校验不上收**：分配表、`effectiveTenantId`、`AccessBoundary`、受保护命名空间等仍在业务服务里。
  这些要读业务实体/请求体才能判断，放进网关等于让网关懂业务。
- 不改 auth 的协议端点（authorize/token/jwks/登录/登出/续期）语义，它们照旧只需会话或匿名。
- k8s-server 不进网关（它只被 platform-api 内部调用，现有边界不变）。

**反目标（做成这样就算失败，哪怕跑得通）**
- 网关解析业务请求体、按租户/命名空间做判断 —— 网关里出现业务逻辑。
- 业务服务能绕过网关被直接访问，导致权限检查形同虚设。
- 网关挂了以后 WebSocket（Pod exec、日志流）或长连接流式接口不可用/被缓冲截断。
- 同一个请求在网关和服务里各做一次**接口级**判定、且两边规则可能不一致。

## 3. 职责划分（已定）

| 关注点 | 位置 | 说明 |
|---|---|---|
| JWT 校验（签名/aud/过期） | 网关 + 服务**都做** | 服务保留本地校验作纵深防御（见 §6，我的决定） |
| 权限闭包计算 | 网关 | 复用 `platform-common` 的 `PermissionClosureService`，Redis TTL 30s 缓存跟着搬 |
| 接口级鉴权（权限表） | **只在网关** | 现 `PermissionAuthorizationManager` 的逻辑整体迁过去 |
| 豁免路径（`ExemptPaths`） | 网关 | 登录即可的路径（bootstrap、自助接口等） |
| 租户上下文解析 | 网关解析并校验「用户属于该租户」；服务再按需读取 | 现 `TenantContextResolver` |
| 数据范围校验 | 服务 | 不动 |
| 审计 SUCCESS / FAILED | 服务（公共库） | 见 §5 |
| 审计 DENIED（403） | 网关 | 只有网关知道请求被拒了 |
| 当前用户/身份工具 | 公共库 | 现 `AuthContext`、`CurrentUserQuery` 中与业务无关的部分 |

## 4. 流量拓扑

```
浏览器 ─ nginx ─┬─ /auth/**（协议、登录页、自助）──────────────→ platform-auth
                ├─ /api/**  ───→ platform-gateway ─┬─→ platform-api（现有全部业务接口）
                │                                  └─→ platform-auth 管理接口（客户端、JWK）
                └─ 静态资源
platform-api ──（集群内）──→ k8s-server（不经网关）
```

- nginx 的 `/api/` 上游从 platform-api 改成网关；WS upgrade、1800s 超时、`proxy_buffering off` 原样保留。
- auth 管理接口在网关上的外部路径：`/api/oauth-clients/**`（沿用平台页面现在调用的路径，前端零改动）、
  `/api/jwks/**`。网关重写到 auth 的内部路径。auth 侧这些接口**只接受来自网关的请求**（见 §7）。
- `/auth/internal/` 在 nginx 继续 404。

## 5. 审计

用户确认审计可以放进业务服务（「那审计可以放到业务里吗」→ 可以）。做法：

- 把 `Auditable` / `AuditAspect` / `AuditWriter` / `AuditTargetResolver` / `AuditAction` 从 platform-api
  搬进公共库（建议放 `platform-common` 下独立包，或新模块 `platform-audit`，由 builder 定）。
  行为保持：`@Order(0)` 在事务外层、`AuditWriter` 用 `REQUIRES_NEW`、写同一张 `platform_audit_log`。
- 服务继续用 `@Auditable` 标注写操作，记 SUCCESS / FAILED。auth 的管理接口也加上标注
  （auth 需要能写平台库的审计表 —— 它本来就连同一个库）。
- 网关只写 DENIED：拒绝时按路由表知道「这是哪个接口、需要什么码」，记 actor、路径、方法、所需码、结果 DENIED。
  目标对象（SpEL 取请求体字段）**网关不取**，DENIED 记录里 target 为空 —— 这是有意的，取它就得解析业务请求体。
- 服务里现有的 `AuditDenied`（靠 accessDeniedHandler 记 403）删掉，否则会和网关重复；
  但服务内**数据范围校验**抛出的拒绝仍由服务记（那是业务层的拒绝，网关不知道）。
- `AuditAction` 常量放公共库，各服务共用一份命名空间。

## 6. 技术选型（我的决定，评审时请重点看）

**6.1 网关实现：Spring Cloud Gateway（WebFlux 版）**——**用户定（2026-11-04）**。
- 理由：WebSocket / 长流式接口的转发是 WebFlux 版的成熟能力，少量线程扛长连接；
  原先担心的「MVC 版能否代理 WS」不再是风险，§4 拓扑里 WS 照常经网关。
- 代价（只落在网关，业务服务仍是 servlet，不动）：
  - 鉴权改写成 `ReactiveAuthorizationManager<AuthorizationContext>`，安全链换 `ServerHttpSecurity`，
    JWT 解码换 `ReactiveJwtDecoder`（同样的 aud/签名校验规则，见 `PlatformJwtValidators`）。
  - 闭包查询（MyBatis，阻塞）与 DENIED 审计写库：包进 `Schedulers.boundedElastic()` 调用，**不换 R2DBC**。
  - Redis 闭包缓存：用 `ReactiveRedisTemplate`，或同样包 `boundedElastic`（实施者定）。
  - 测试改用 `WebTestClient`。
- **公共库约束**：被网关与业务服务同时引用的部分（权限表匹配、闭包计算、豁免规则、审计记录模型）
  只放纯逻辑，**不得依赖 servlet 或 reactive API**；两侧各写一层薄适配。否则网关会把 servlet 依赖拖进 WebFlux 应用
  （Spring Boot 检测到 servlet 栈会按 MVC 启动）。
- WS 例外（nginx 直连 api）原为 MVC 版兜底，WebFlux 下预计用不上；若实施时 WS 透传仍有问题，用户已接受该例外。

**6.2 身份传递：服务继续本地校验 JWT**，网关原样转发 `Authorization` 头，不另造 `X-User-*` 头。
- 理由：服务本来就是资源服务器，零改动；即使有人绕过网关直连服务也拿不到身份；不需要设计网关→服务之间的头签名。
- 网关额外加 `X-Platform-Tenant-Id`（已校验过的租户上下文）不做——服务自己从请求里解析，避免两份真相。

**6.3 权限闭包缓存**：沿用 Redis TTL-only 30s，不做手工失效（理由同 `permissions-out-of-token`：失效点≥10）。

**6.4 路由表**：静态配置（`application.yaml` 的 routes），不接注册中心。服务个数个位数，拆分时手加一条。

## 7. 防绕过

- 部署上：platform-api 与 auth 管理接口所在端口只对网关开放。K8s 部署时 Service 不暴露 NodePort/Ingress，
  用 NetworkPolicy 限制入站来源=网关 Pod（auth 的协议端点仍需 nginx 可达，所以 auth 要把管理接口和协议端点分开判断）。
- 代码上（auth 管理接口）：**我的决定**：auth 管理接口本地校验 JWT（同 §6.2）但**不做权限判定**，依赖网关；
  因此 auth 管理接口必须靠网络隔离保证只有网关能访问。
  **用户已定（2026-11-03）：不加内部令牌**，网关只把用户的 JWT 原样透传，防绕过靠网络隔离。
- 服务里保留一条兜底：没有被任何网关路由覆盖的 controller 端点，启动时报错（见 §8.5）。

## 8. 权限表：网关如何知道、如何与业务对齐

**8.1 来源**：网关直连平台库，读同一张 `platform_permission`（`domain=API` 的行：`resource`=路径规则、
`action`=HTTP 方法、`code`=权限码）。表是唯一真相，不需要服务向网关上报。

**8.2 按服务区分（新增列，我的决定）**：表里现存的是 api 的服务内路径，没有「属于哪个服务」。
auth 管理接口、将来拆出的服务进来后路径可能撞车，因此给 `platform_permission` 加一列 `service`
（`platform-api` / `platform-auth` …），与网关路由 id 一一对应；现有 API 行回填 `platform-api`，
`V2026_11_03_2` 的 8 行客户端管理行改 `platform-auth`（路径同步改成 auth 内部路径）。

**8.3 判定**：请求 `/api/**` → 按路由识别服务 → 剥前缀得服务内路径 → 用（service, 路径, 方法）查码 →
按用户 id 取闭包（Redis TTL 30s）→ ANY-of 命中放行。行多码、码多行的现有语义不变。

**8.4 刷新**：现在 api 只在启动时加载一次权限表（`PermissionRegistryFactory`），权限点管理页改行后要重启才生效。
网关改为**定时重载**（建议 60s，实施者定），载入失败保留上一份并 ERROR 日志，不清空（清空=全站 403）。
角色/用户分配变化不需要重载——那走闭包缓存的 30s TTL。

**8.5 两头对账**（替代 api 里的 `PermissionCrossCheckRunner`）：
- 每个业务服务启动时反射自己的端点，对照「本服务的」权限行 + 豁免表，缺行拒启——Runner 搬进公共库，各服务各跑一份。
  新加接口忘配权限，服务起不来。
- 网关启动（及每次重载）检查：每一行都能对上一条路由；对不上 WARN，不拒启。

**8.6 权限 / 角色管理接口放在哪（我的建议）**：角色增删改、给角色分配权限、权限点管理、可分配集合
（`/role/permission/assignable`）、用户分配角色、`/user/me` 等**全部留在 platform-api**（或将来拆出的身份服务），
**不进网关**。理由：它们是带业务规则的写接口（角色不变量、可分配集合、审计），放进网关等于网关写业务（反目标）。
网关对这些表**只读**；管理接口改了表，网关靠 §8.4 的重载和闭包 TTL 自然看到。
这些管理接口本身照常受网关鉴权（如 `role:manage`）。

## 9. 对已上线/在途功能的影响

- **OAuth 客户端管理**（刚合入 main 未部署）：网关上线后删除 api 的 `OAuthClientController`、
  `AuthClientAdminClient`、`models/oauthclient/*`，auth 的 `InternalClientController` 改挂到网关路由下，
  删 `InternalTokenFilter` 与 `PLATFORM_INTERNAL_TOKEN`。前端路径不变。
  —— 若网关上线前就要部署客户端管理，就先按现状（内部令牌）上，网关上线时再切。
- **`/jwk/generate`**：加权限行（建议新码 `platform:jwk:manage`，显式绑 admin），挂网关后面；
  **用户已定（2026-11-03）：等网关一起修**，网关上线前这个缺口保持现状。
- 前端：零改动（路径前缀 `/api` 不变）。
- 部署顺序：网关先起 → nginx 切上游 → 观察 → 删除 api 内的接口级鉴权（同批或下一批）。
  切换期两边都判一次是可以接受的过渡（规则同源，读同一张表）。

## 10. 留给实施者的

- 公共库放 `platform-common` 还是新开 `platform-audit`/`platform-security` 模块。
- 网关模块名（建议 `platform-gateway`）、端口、Dockerfile、Helm/部署清单。
- 网关 DENIED 审计的写库方式（直接复用 `AuditWriter`，网关连平台库）。
- 慢请求/超时/请求体大小限制的具体数值，以 nginx 现值为准。
- 测试：网关鉴权单测（迁移现有 `PermissionAuthorizationManager` 的测试）+ 路由集成测 + WS 透传测试。

## 11. 已拍板（2026-11-03）

1. auth 管理接口防绕过：**不加内部令牌**，网关透传用户 JWT，靠网络隔离（§7）。
2. `/jwk/generate` 缺口：**等网关一起修**，这期间不单独收紧（§9）。
3. WS 若网关代理不了：**接受**由 nginx 直连 platform-api、鉴权留在 api 的例外（§6.1）。
4. 网关用 **WebFlux 版**（2026-11-04，§6.1）。
5. 权限 / 角色管理接口**留在 platform-api**，网关只读权限相关表（§8.6）——我的建议，待用户确认。
