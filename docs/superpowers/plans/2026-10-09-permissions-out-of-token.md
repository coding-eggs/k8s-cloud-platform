# 2026-10-09 权限闭包移出 token（请求期现算 + 30s TTL）

## Context

起点是一个线上故障：**exec 容器直接连不上**，栈是
`CompletionException → BufferOverflowException @ WsWebSocketContainer.createRequest:727`。

对着 jar 核过字节码后定位：Tomcat 的 WS 客户端构造握手请求时，请求行（含 query）用的是
**4KB 固定缓冲**（`sipush 4096`），**只有 header 走 `putWithExpand` 会自动扩容**；而中继把浏览器的
原始 query（含 `access_token`）原样透传给 k8s-server —— 于是 token 一大就崩。

token 为什么大：`data.permissions` 装的是**权限点 code 全闭包**，长度 = 产品权限点总数：

| 迁移 | 累计码数 | token 估算 |
|---|---|---|
| …`V2026_10_06_2` | 76 | ~2.7KB |
| **`V2026_10_07_1` 页面权限独立成码（+47）** | **123** | **~4.6KB ← 越线** |
| `V2026_10_08_4` / `_5`（B6 服务网格 +51） | 176 | ~6.4KB |

**这是"把无上限的列表放进有硬上限的容器"**：token 里放身份/角色/租户这类小而稳的东西，
上限十年碰不到；放一个随产品增长的列表就一定碰。附带两个后果：每个请求（含转发到 k8s-server 那一跳）
都背 ~6.4KB 头；且闭包带 **1 小时保鲜期**（access_token TTL），而权限规则是热加载的 ——
**规则热了、主体没热**，回收权限最坏要等 1 小时。

> 同一故障的**直接止血**（上游那一跳改用 `Authorization: Bearer` 头）已随本批一起提交，
> 见 `PodExecRelayHandler` 与 `PodExecRelayHandlerTest`。本批解决的是根因。

## 决策

| 项 | 结论 |
|---|---|
| token 是否携带权限码 | **不携带**（`data.permissions` 不再签发） |
| 权限码从哪来 | 请求期现算：唯一算法 `PermissionClosureService`（platform-common） |
| 缓存 | platform-api 侧 `PermissionClosureResolver`：**TTL-only，30s**，不做手工失效 |
| k8s-server | 显式 `JwtPermissionResolver.noop()` —— 本层没有权限码语义 |
| 回滚 | `platform.jwt.permissions-in-token=true` 恢复旧行为（转换器 claim 优先） |

### 为什么语义放 platform-common、缓存放 platform-api

`TokenExtrasService.permissions()` 原本只在 platform-auth（无测试）。这次搬到
`PermissionClosureService` 由**两处共用**（platform-auth 回滚开关 / platform-api 常态解析）——
分两处写必然漂移，而这条语义漂移的后果是**静默过度授权**。缓存则纯粹是 platform-api 的取数策略，
不该污染共用语义（也因此 platform-auth 拿到的是无缓存的精确快照）。

`PermissionClosureService` **刻意不打 `@Service`**：三个应用都扫 `com.coding.common`，
打注解会让 k8s-server 凭空得到一个"能查业务权限表"的 bean —— 与它零业务逻辑的定位相悖，
将来有人注入一下就悄悄破了这条线。改由真正需要它的两个应用各自 `@Bean` 注册。

### 为什么 TTL-only 而不是手工失效（本批最重要的设计点）

失效点至少 10 个：`RoleService.savePermissions/delete`、`UserService.grantPlatformRole/revokePlatformRole`、
`TenantMemberService.grantRole/revokeRole/removeMember`、`PermissionService.create/update/delete/reload`
（改 code = 改所有人的集合）。**漏挂任何一个，该角色下所有人就永久保留已撤销的权限** ——
比"陈旧但有上界"差得多。TTL 30s 把上界钉死，且这个上界比原先 token 副本的 1 小时**更紧**。
（`TtlCache` 早就存在于 `CalicoService`，本批提到 `com.coding.common.utils` 复用；它同样只缓存成功结果。）

## 改动清单

**platform-common**
- `PermissionClosureService`（新）：闭包唯一实现，`permissions(username, tenantId)`，`tenantId` 空 → 只算平台族。
- `JwtPermissionResolver`（新）：取数策略接口 + `noop()`；**失败语义写进接口文档**（返回空，不要抛）。
- `PlatformJwtAuthenticationConverter`：改 2 参构造；权限码 **claim 优先、否则解析**；解析异常 → 空 + ERROR 日志（fail-closed，不掀翻认证链）。
- `utils/TtlCache`（从 `CalicoService` 的私有嵌套类提上来，行为不变）。

**platform-api**
- `security/PermissionClosureResolver`（新）：TTL-only 缓存（默认 30s，可配 `platform.permissions.cache-ttl-seconds`），键含租户（平台视图与各租户是不同闭包），失败不缓存。
- `ResourceServerConfig`：注入解析器传给转换器。
- `CurrentUserQuery.me()`：**不再回显 claim**，现算闭包后填充；`myTenants()` 走新加的 `currentOrThrow()`，不触发解析。
- `configs/SecurityBeans`：注册 `PermissionClosureService` bean。

**k8s-server**
- `ResourceServerConfig`：显式传 `JwtPermissionResolver.noop()` + 注释说明"本层没有权限码语义"。

**platform-auth**
- `AuthorizationServerConfig`：新增开关 `platform.jwt.permissions-in-token`（默认 false）；`TokenExtrasService` 移除 `permissions()` 及随之无用的两个 mapper 依赖。
- `application.yaml`：写开关 + 回滚说明。

**platform-web**
- `stores/permission.ts`：判定从"能否 decode"改成"**claim 里有没有 permissions 字段**"；没有就走 `/user/me`（这是新的常态路径）；`/user/me` 失败时别再把 `platformRoles` 一起清掉（否则管理员掉出平台视图）。

**文档**：`frontend-permission-conventions.md` 新增 §1.6；`backend-layering.md` §3.1 增一条；
spec §5.2 / §前端注 加 2026-10-09 修订注（日期化记录只加注不重写）。

## 验证（2026-10-09）

| 项 | 结果 |
|---|---|
| `mvn -pl platform-common test` | **19/19**（新增 `PermissionClosureServiceTest` 6 例 = 语义锚点；转换器 12 例，含 claim 优先/解析回退/失败 fail-closed/返回 null/sub 兜底） |
| `mvn -pl platform-api test` | **162/162**（新增 `PermissionClosureResolverTest` 4 例：命中缓存 / 租户分键 / 失败不缓存 / 空用户名短路；`CurrentUserQueryTest` 改为断言"现算 + 传对 tenantId"） |
| `mvn -pl k8s-server test` | **13/13** |
| `mvn -pl platform-auth test` | 编译通过（该模块无测试目录） |
| 前端 | `vue-tsc --build` + `vite build` 通过 |
| 端到端 | **未做**：需活后端 + 活 DB，且浏览器预览被权限分类器挡着 |

**未验证但影响面最大的两点，上线后请优先自查**：

1. 登录后 token 长度应回落到 ~300 字节（`echo -n <token> | wc -c`），且 **exec 恢复正常**。
2. **菜单不能全空**：`/user/me` 返回的 `permissions` 必须是真值。若前端那句"claim 有没有 permissions 字段"
   写错，症状就是"登录后所有菜单消失"（看起来像掉权限）。

## 部署 / 回滚

无迁移、无破坏性变更。四个服务都要重新构建：**platform-auth / platform-api / k8s-server**
（都依赖 platform-common 的转换器改动）。旧 token（带 permissions）在转换器里仍是 claim 优先，
所以**不需要强制重登**，最长 1 小时后自然全部走新路径。

回滚：`platform.jwt.permissions-in-token=true` → 重启 platform-auth 即恢复旧签发行为
（转换器会照旧消费 claim）。注意只关"消费"侧而签发侧继续塞，token 依然大，两道长度墙照旧 ——
开关只作事故止血，不是长期并存方案。

## 未做 / 下一步

- **前端那次 `/user/me` 每次都拉**：现在 token 变更（含每小时续期）后 `ready=false`，下一次导航补一次
  `/user/me`。可接受（一次轻量请求换 30s 级的实时性），若要再省可让 `/user/me` 结果落 sessionStorage。
- **WS 用一次性票据**（把 JWT 从浏览器 URL/历史/网关日志里拿掉）：与本批正交，独立一批。
- **`platform:cluster:manage` 拆码**：见 `2026-10-08-role-assignable-permissions.md` 的"未做"。
