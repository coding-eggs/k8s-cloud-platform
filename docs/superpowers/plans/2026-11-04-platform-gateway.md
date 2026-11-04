# 平台 API 网关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 WebFlux 网关 `platform-gateway`，接口级鉴权（权限表）与 DENIED 审计只在网关做一次；auth 管理接口直接挂网关后面，删掉 api 转发层与内部令牌；长连接（exec WebSocket、大文件下载）经网关不断、不缓冲。

**Architecture:** nginx `/api/`（去前缀）→ `platform-gateway`（Spring Cloud Gateway WebFlux）→ 按路由表转到 platform-api 或 platform-auth 的 `/internal/**`。网关与业务服务共享 `platform-common` 里的**纯逻辑**（权限表匹配、判定、闭包缓存键），两侧各写薄适配；业务服务继续本地校验 JWT、保留数据范围校验并用公共审计库写 SUCCESS/FAILED。

**Tech Stack:** Java 21、Spring Boot 4.1.2、Spring Cloud Gateway（WebFlux，`spring-cloud-starter-gateway-server-webflux`）、Spring Security 7 reactive、MyBatis（阻塞，包 `Schedulers.boundedElastic()`）、Redis（`StringRedisTemplate`，同样包 boundedElastic）、Vue 3（PodTerminal）。

**Spec:** `docs/superpowers/specs/2026-11-03-platform-gateway-design.md`

## Global Constraints

- 业务服务（platform-api、platform-auth）保持 servlet，不引入 reactive；k8s-server 不改、不进网关。
- `platform-common` 中网关会用到的类**不得依赖 servlet 或 reactive API**；servlet 专属的（审计切面、AuthContext）放进只被 api/auth `@Import` 的配置类，网关不扫描 `com.coding.common`。
- 网关 `spring.main.web-application-type: reactive`（显式写死，防 classpath 上意外出现 webmvc 后按 MVC 启动）。
- 网关端口 **8090**（api 8081、auth 9527、k8s-server 8080 已占）。
- 不加内部令牌：网关原样转发 `Authorization` 头与 `access_token` query，服务继续本地校验 JWT；防绕过靠网络隔离（spec §7）。
- 权限闭包缓存键格式必须与现有 api 完全一致：`platform:cache:perm:closure:<username>\u0000<tenantId 或空串>`，值为 JSON 字符串数组，TTL 30s，不做手工失效。
- 401/403 响应体与现状逐字节同形：`{"code":<EnumResponseType.code>,"msg":"<msg>","data":null}`，401 用 `USER_UN_LOGIN`，403 用 `NON_AUTH_ENTRY_POINT`，`Content-Type: application/json;charset=UTF-8`。
- 判定语义不变：命中权限行（ANY-of）＞ 豁免路径（仅需登录）＞ 默认拒绝；匿名一律拒；方法 `*` 在运行时不是通配（见 `PermissionRegistry.runtimeMatches`）。
- 任何日志、审计记录都**不得包含请求 query**（WS 握手的 query 里有 access_token）。
- 本仓没有 Flyway：迁移 SQL 手工重放；MySQL 注释 `--` 后必须跟空白。
- 提交按文件名 `git add`，不要 `git add -A`；工作区里两个 `application.yaml` 的令牌默认值改动**不属于本计划**，Task 11 删除那两行配置时一并丢弃。

## Review Focus

1. **网关重启/滚动发布时正在用的终端**：用户应看到「连接已断开」并能一键重连，而不是终端冻住无提示 —— Task 8 的心跳超时测试 + Task 13 验收项。
2. **上游 Tomcat 先关掉空闲连接，网关复用了死连接**（偶发 `Connection prematurely closed BEFORE response`，表现为随机 500/502）—— Task 7 的连接池 `max-idle-time` 断言。
3. **粘贴大段文本到终端**（单帧 > 64KB，SCG 默认帧上限）应正常发送，不应以 1009 断开 —— Task 7 的 256KB 帧测试。
4. **权限点管理页新增/修改行后**，网关在一个重载周期内生效；重载期间库抖动不应清空规则（清空 = 全站 403）—— Task 4 的重载失败保留旧表测试。
5. **审计/日志泄露 token**：WS 握手被拒时 DENIED 记录的 requestPath 不含 `?access_token=` —— Task 6 测试。

---

## 文件结构

| 路径 | 职责 |
|---|---|
| `platform-gateway/`（新模块） | 网关应用：路由表、安全链、DENIED 审计、长连接配置 |
| `platform-gateway/src/main/java/com/coding/gateway/route/PlatformRouteProperties.java` | 路由表配置（service、外部前缀、内部前缀、上游 uri、上游 context-path） |
| `.../gateway/route/PlatformRouteResolver.java` | 外部路径 → (service, 服务内路径)，鉴权与路由共用这一份 |
| `.../gateway/route/PlatformRouteLocatorConfig.java` | 由同一份配置生成 SCG `RouteLocator` |
| `.../gateway/security/GatewaySecurityConfig.java` | `SecurityWebFilterChain`、`ReactiveJwtDecoder`、401/403 JSON |
| `.../gateway/security/GatewayAuthorizationManager.java` | `ReactiveAuthorizationManager<AuthorizationContext>`，委托 `PermissionDecider` |
| `.../gateway/security/BlockingJwtAuthenticationConverter.java` | 把 `PlatformJwtAuthenticationConverter` 放到 boundedElastic 上跑 |
| `.../gateway/security/PermissionRegistryReloader.java` | 定时重载权限表，失败保留旧表 |
| `.../gateway/audit/GatewayDeniedAuditor.java` | 写 DENIED 记录（fail-open、boundedElastic） |
| `platform-common/.../components/permission/`（新包） | `PermissionRegistry`、`ExemptPaths`、`PermissionDecider`、`PermissionCrossCheck`（纯逻辑部分）从 api 迁入 |
| `platform-common/.../components/cache/RedisJsonCache.java`、`.../jwt/CachedPermissionClosureResolver.java` | 从 api 迁入，api 与网关共用同一缓存键 |
| `platform-common/.../audit/`（新包） | 审计注解/切面/写入/目标解析/AuditAction/AuthContext，由 `ServletAuditConfiguration` 统一注册 |
| `platform-data/.../db/migration/V2026_11_04_1__permission_service_column.sql` | 加 `service` 列 + JWK 权限行 |
| `platform-data/.../db/migration/V2026_11_04_2__oauth_client_rows_to_auth.sql` | 8 行客户端管理权限改归 platform-auth |

---

### Task 1: 网关模块骨架 + 版本兼容

**Files:**
- Create: `platform-gateway/pom.xml`、`platform-gateway/src/main/java/com/coding/gateway/PlatformGatewayApplication.java`、`platform-gateway/src/main/resources/application.yaml`、`platform-gateway/Dockerfile`
- Modify: `pom.xml`（`<modules>` 加 `platform-gateway`；`<dependencyManagement>` 导入 `spring-cloud-dependencies` BOM）
- Test: `platform-gateway/src/test/java/com/coding/gateway/PlatformGatewayApplicationTest.java`

**Interfaces:**
- Produces: 可启动的空网关；后续任务往里加 bean。

- [ ] **Step 1: 确认 Spring Cloud 版本与 Boot 4.1.2 兼容。** 根 pom 现有 `spring-cloud.version=2025.1.1`（还没有模块真正用过）。查 Spring Cloud 官方 release train 兼容表；若 2025.1.x 不支持 Boot 4.1，改成支持 4.1 的 train，结论记进 ledger（`Ruling:`）。**不要**用 `spring.cloud.compatibility-verifier.enabled=false` 绕过。同时核对该版本 SCG 的配置前缀（预期 `spring.cloud.gateway.server.webflux.*`），Task 7 的属性名以核对结果为准。
- [ ] **Step 2: 写失败测试** `PlatformGatewayApplicationTest`（本任务尚无 DB/Redis：测试里排除 DataSource、MyBatis、Redis 自动配置）：
  - `startsAsReactive`：`ctx` 是 `ReactiveWebServerApplicationContext`
  - `noServletApiOnClasspath`：`ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest", null)` 为 false
- [ ] **Step 3: 跑测试确认失败**：`mvn -pl platform-gateway -am test -Dtest=PlatformGatewayApplicationTest -Dsurefire.failIfNoSpecifiedTests=false` → 编译失败。
- [ ] **Step 4: 实现。** 依赖：`spring-cloud-starter-gateway-server-webflux`、`spring-boot-starter-security`、`spring-boot-starter-oauth2-resource-server`、`spring-boot-starter-data-redis`、`spring-boot-starter-actuator`、`platform-common`、`platform-data`。`application.yaml`：`server.port: 8090`、`spring.main.web-application-type: reactive`、`spring.application.name: platform-gateway`。`@SpringBootApplication` 只扫 `com.coding.gateway`（**不扫** `com.coding.common`），`@MapperScan` 只扫审计与权限两个 mapper 所在包。Dockerfile 照 `platform-api/Dockerfile`，端口 8090。`noServletApiOnClasspath` 若失败，找出谁传递了 servlet-api 并在网关 pom 里 exclude。
- [ ] **Step 5: 跑测试确认通过**（同 Step 3）→ 2/2 PASS。
- [ ] **Step 6: 提交** `feat(gateway): 新增 platform-gateway 模块骨架（WebFlux）`

---

### Task 2: 闭包缓存迁入 platform-common（api 与网关共用同一缓存键）

**Files:**
- Move: `platform-api/.../cache/RedisJsonCache.java` → `platform-common/src/main/java/com/coding/common/components/cache/RedisJsonCache.java`
- Move+Rename: `platform-api/.../security/PermissionClosureResolver.java` → `platform-common/.../components/jwt/CachedPermissionClosureResolver.java`
- Modify: `platform-common/pom.xml`（加 `spring-data-redis`，`<optional>true</optional>`）；api 内所有 import；api `configs/SecurityBeans.java`
- Test: 两个类在 api 的既有测试随迁；新增 `platform-common/.../jwt/CachedPermissionClosureResolverKeyTest.java`

**Interfaces:**
- Produces: `CachedPermissionClosureResolver implements JwtPermissionResolver`，构造器 `(PermissionClosureService, RedisJsonCache, Duration ttl)`；与 `RedisJsonCache` 都**去掉** `@Component`，由 api 与网关各自 `@Bean` 注册（api 的 TTL 仍取 `platform.permissions.cache-ttl-seconds:30`）。

- [ ] **Step 1: 写失败测试** `CachedPermissionClosureResolverKeyTest`（mock `StringRedisTemplate`）：
  - `resolve("alice","t1")` 读键 `"platform:cache:perm:closure:alice\u0000t1"`；`resolve("alice",null)` 读 `"platform:cache:perm:closure:alice\u0000"`
  - 缓存值 `["a:b"]` → 返回 `List.of("a:b")`，不调 loader
  - loader 抛异常 → 返回空列表，且没有写缓存
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-common -am test -Dtest=CachedPermissionClosureResolverKeyTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 迁移**（逻辑一行不改：只改包名、去 `@Component`、类改名），api 在 `SecurityBeans` 里 `@Bean` 注册两者。
- [ ] **Step 4: 跑测试**：上面命令 PASS；`mvn -pl platform-api -am test` 全绿。
- [ ] **Step 5: 提交** `refactor: 闭包缓存与 RedisJsonCache 迁入 platform-common`

---

### Task 3: 权限判定纯逻辑迁入 platform-common + `service` 维度

**Files:**
- Move: `PermissionRegistry`、`ExemptPaths` → `platform-common/src/main/java/com/coding/common/components/permission/`
- Move: `PermissionCrossCheck` 的纯函数部分（`record Endpoint`、`uncoveredEndpoints`、`ghostRules`，都加 service 参数）→ 同包；依赖 `RequestMappingHandlerMapping` 的 `liveEndpoints` 留在各服务的 Runner 里（common 不依赖 webmvc）
- Create: `.../permission/PermissionDecider.java`、`.../permission/PlatformServices.java`
- Modify: `platform-data` 的 `PlatformPermission`（加 `service`）与 `PlatformPermissionMapper.xml`（列清单、resultMap、insert、update）；api 的 `PermissionAuthorizationManager`、`PermissionRegistryFactory`、`PermissionCrossCheck*`、权限点管理 service（新建行写 `service`，默认 platform-api）
- Create: `platform-data/src/main/resources/db/migration/V2026_11_04_1__permission_service_column.sql`
- Test: `platform-common/.../permission/PermissionDeciderTest.java`；api 中 registry/manager 既有测试随迁并保持全绿

**Interfaces:**
- Produces:
  - `PlatformServices.API = "platform-api"`、`PlatformServices.AUTH = "platform-auth"`
  - `PermissionRegistry.Rule(String service, String method, String pattern, String code)`；`Optional<Set<String>> requiredCodes(String service, String method, String path)`；`replaceRules(Collection<Rule>)`、`rules()`、`matches`、`runtimeMatches` 保留（都多一个 service 参数）
  - `ExemptPaths.isExempt(String service, String path)`：只有 platform-api 有清单（原样搬现 PREFIXES），其它 service 恒 false
  - `PermissionDecider(PermissionRegistry)`：`Decision decide(String service, String method, String path, Collection<String> authorities, boolean anonymous)`；`sealed interface Decision permits Allow, Deny`；`record Allow()`、`record Deny(Set<String> requiredCodes)`（未命中行且不豁免 → 空集）

- [ ] **Step 1: 写失败测试** `PermissionDeciderTest`（authority 用 `PermissionAuthorityNames.perm(code)` 构造）：
  - `anonymousAlwaysDenied`：anonymous=true、`/user/me` → `Deny(Set.of())`
  - `ruleHitWithCodeAllows`：规则 `(platform-api, GET, /pods/**, tenant:pod:view)`，持码 → `Allow`
  - `ruleHitWithoutCodeDeniesEvenIfExempt`：规则命中 `/user/me`、不持码 → `Deny({该码})`（行优先于豁免）
  - `exemptWithoutRuleAllows`：`/ws/pod/exec` 无行 → `Allow`
  - `sameRuleOtherServiceDoesNotMatch`：规则 service=platform-auth、`/internal/clients/list`；以 platform-api 判同一路径 → `Deny(Set.of())`
  - `starMethodIsNotWildcardAtRuntime`：规则方法 `*`、请求 GET → 不命中
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-common -am test -Dtest=PermissionDeciderTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 实现**：判定顺序照抄现 `PermissionAuthorizationManager.authorize`。api 的 manager 改为委托 decider（service 固定 `PlatformServices.API`），`Deny` 且 requiredCodes 非空时照旧 `AuditDenied.mark`。api 的 `PermissionRegistryFactory` 与权限点 CRUD 后的 `replaceRules` **只装 service=platform-api 的行**（否则 Task 11 后 api 交叉校验会把 auth 的行当幽灵规则）。
- [ ] **Step 4: 迁移 SQL** `V2026_11_04_1`：
  - `ALTER TABLE platform_permission ADD COLUMN service varchar(32) NOT NULL DEFAULT 'platform-api' COMMENT '所属服务，与网关路由 service 对应' AFTER domain;`
  - JWK 权限行：`id=perm_jwk_generate, domain=API, service=platform-auth, resource=/internal/jwk/generate, action=POST, code=platform:jwk:manage, description='生成签名/加密密钥'`
  - 绑 `builtin_role_admin`（id `rp_admin_jwk_generate`，写法照 `V2026_11_03_2`）
  - 插入一律 `INSERT IGNORE`，可重放
- [ ] **Step 5: 跑测试**：`mvn -pl platform-common,platform-data,platform-api -am test` 全绿。
- [ ] **Step 6: 提交** `refactor: 权限判定纯逻辑迁入 platform-common，权限表加 service 维度`

---

### Task 4: 网关路由表 + 权限表定时重载

**Files:**
- Create: `platform-gateway/.../route/PlatformRouteProperties.java`、`PlatformRouteResolver.java`、`PlatformRouteLocatorConfig.java`；`.../security/PermissionRegistryReloader.java`
- Modify: `platform-gateway/src/main/resources/application.yaml`
- Test: `PlatformRouteResolverTest`、`PermissionRegistryReloaderTest`、`GatewayRoutingIT`（上游用 OkHttp `MockWebServer`）

**Interfaces:**
- Consumes: Task 3 的 `PermissionRegistry`、`PlatformServices`
- Produces:
  - `PlatformRouteProperties`（前缀 `platform.gateway`）：`List<Route> routes`、`Duration permissionReloadInterval`；`record Route(String service, String externalPrefix, String internalPrefix, URI uri, String contextPath)`
  - `PlatformRouteResolver.resolve(String externalPath) -> Optional<ResolvedRoute>`，`record ResolvedRoute(Route route, String internalPath)`；**最长前缀优先**
  - SCG 路由由同一份配置生成：转发路径 = `contextPath + internalPath`，query 原样保留（**不得**剥 `access_token`，api 的 exec 中继要用）

- [ ] **Step 1: 写配置**（按最长前缀匹配，书写顺序无关）：

```yaml
platform:
  gateway:
    permission-reload-interval: 60s
    routes:
      - { service: platform-auth, external-prefix: /oauth-clients/, internal-prefix: /internal/clients/, uri: "${PLATFORM_AUTH_URL:http://platform-auth:9527}", context-path: /auth }
      - { service: platform-auth, external-prefix: /jwks/,          internal-prefix: /internal/jwk/,     uri: "${PLATFORM_AUTH_URL:http://platform-auth:9527}", context-path: /auth }
      - { service: platform-api,  external-prefix: /,               internal-prefix: /,                  uri: "${PLATFORM_API_URL:http://platform-api:8081}",   context-path: "" }
```

- [ ] **Step 2: 写失败测试**
  - `PlatformRouteResolverTest`：`/oauth-clients/list` → (platform-auth, `/internal/clients/list`)；`/jwks/generate` → (platform-auth, `/internal/jwk/generate`)；`/pods/x/logs` → (platform-api, `/pods/x/logs`)；`/oauth-clientsX/a` → platform-api（前缀以 `/` 收尾，不误吞）
  - `PermissionRegistryReloaderTest`：mapper 依次返回 2 行 / 抛 `DataAccessException` / 返回 3 行 → registry 依次是 2、2（并有 ERROR 日志）、3 行；只装 `domain=API` 的行（不区分 service）；首次加载抛异常 → `start()` 抛出
  - `GatewayRoutingIT`（本任务用测试安全配置 permitAll）：网关 `POST /oauth-clients/list?x=1` → 上游收到 `POST /auth/internal/clients/list?x=1` 且带原 `Authorization` 头；`GET /pods/a/logs?access_token=abc` → api 上游收到同样的 path 与 query
- [ ] **Step 3: 跑测试确认失败**：`mvn -pl platform-gateway -am test -Dtest='PlatformRouteResolverTest,PermissionRegistryReloaderTest,GatewayRoutingIT' -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 4: 实现。** `PermissionRegistryReloader`：启动时同步加载一次，失败则启动失败（空表 = 全站 403，不能带病起）；之后按间隔在 boundedElastic 上重载，失败保留旧表 + ERROR。每次加载后检查每条规则的 service 都有路由，没有的 WARN（spec §8.5）。
- [ ] **Step 5: 跑测试确认通过**（同 Step 3）。
- [ ] **Step 6: 提交** `feat(gateway): 路由表与权限表定时重载`

---

### Task 5: 网关安全链（JWT 校验 + 接口级鉴权）

**Files:**
- Create: `platform-gateway/.../security/GatewaySecurityConfig.java`、`GatewayAuthorizationManager.java`、`BlockingJwtAuthenticationConverter.java`、`AuthJsonResponses.java`
- Modify: `platform-gateway/src/main/resources/application.yaml`（jwk-set-uri、audiences、public-paths）
- Test: `GatewayAuthorizationManagerTest`、`GatewaySecurityIT`

**Interfaces:**
- Consumes: Task 2 `CachedPermissionClosureResolver`；Task 3 `PermissionDecider`；Task 4 `PlatformRouteResolver`；common 的 `PlatformJwtAuthenticationConverter`、`PlatformJwtValidators.withAudiences`
- Produces:
  - `GatewayAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext>`：按外部路径 resolve 出 (service, internalPath)，交给 decider；`Deny` 且 requiredCodes 非空时把码集放进 exchange 属性 `GatewayAuthorizationManager.DENIED_CODES_ATTR`（Task 6 读）
  - `BlockingJwtAuthenticationConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>>`：`Mono.fromCallable(() -> delegate.convert(jwt)).subscribeOn(Schedulers.boundedElastic())`

**决定（照做，不再讨论）：**
- 只支持 JWS（与 platform-api 当前 `type: JWS` 一致）；解码器 `NimbusReactiveJwtDecoder.withJwkSetUri(${OAUTH2_SERVER}/oauth2/jwks)`，validator 用 `PlatformJwtValidators.withAudiences(<与 api 同一份 audiences 配置>)`，为空拒启。
- Bearer 解析：`ServerBearerTokenAuthenticationConverter` 且 `setAllowUriQueryParameter(true)` —— **仅对 `/ws/**`**；其余路径只认 `Authorization` 头（现状 api 是全路径回退 query，网关收窄，因为只有浏览器 WS 需要）。
- 不需要登录、也不判权限的路径（`platform.gateway.public-paths`）：照抄 api 的 `spring.security.ignore-urls`（含 `/alert/webhook`、`/actuator/**`、swagger 系列），只对 service=platform-api 生效；对这些路径**不解析 Bearer**（`/alert/webhook` 的共享密钥不是 JWT）。
- 租户上下文：闭包键用 token 里 `data.tenantId`，网关**不读**请求里的 tenantId（请求 tenantId 与 token 是否一致由 api 的 `TenantContextResolver` 判，属于业务校验）。这修正 spec §3 表中「网关解析并校验租户」的写法 —— 实施时同步改 spec。
- 401/403 响应体见 Global Constraints；CSRF 关；不配 CORS（同源经 nginx）。

- [ ] **Step 1: 写失败测试**
  - `GatewayAuthorizationManagerTest`：未认证 → denied；`/oauth-clients/list` 持 `platform:oauth-client:view`→ granted；不持 → denied 且 exchange 属性含该码；`/user/me` 无行 → granted；未知路径无行不豁免 → denied 且属性为空
  - `GatewaySecurityIT`（MockWebServer 作上游 + 本地 RSA 密钥签 token，`ReactiveJwtDecoder` 换成该公钥）：
    - 无 token → 401，body 等于 `{"code":<USER_UN_LOGIN.code>,"msg":"<msg>","data":null}`
    - token 的 aud 不在名单 → 401
    - 有效 token 无码访问有行接口 → 403，body 为 `NON_AUTH_ENTRY_POINT` 形状，**上游未收到请求**
    - 有效 token 有码 → 200，上游收到 `Authorization` 头原样
    - `GET /pods/a/logs?access_token=<有效>` → 401（非 `/ws/**` 不认 query）
    - `/alert/webhook` 带 `Authorization: Bearer sd`（非 JWT）→ 上游收到，网关不 401
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-gateway -am test -Dtest='GatewayAuthorizationManagerTest,GatewaySecurityIT' -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 实现**（闭包解析一律在 boundedElastic；`CachedPermissionClosureResolver` 与 `RedisJsonCache` 在网关里 `@Bean`，TTL 取 `platform.permissions.cache-ttl-seconds:30`，与 api 同键）。
- [ ] **Step 4: 跑测试确认通过**；`mvn -pl platform-gateway -am test` 全绿。
- [ ] **Step 5: 提交** `feat(gateway): JWT 校验与表驱动接口鉴权`

---

### Task 6: 网关 DENIED 审计

**Files:**
- Create: `platform-gateway/.../audit/GatewayDeniedAuditor.java`
- Modify: `GatewaySecurityConfig`（`accessDeniedHandler` 先审计后写 403）
- Test: `GatewayDeniedAuditorTest`

**Interfaces:**
- Consumes: Task 5 的 `DENIED_CODES_ATTR`；platform-data 的 `PlatformAuditLogMapper`
- Produces: `Mono<Void> record(ServerWebExchange exchange, Authentication auth)`，**永不出错**（`onErrorResume` 记 ERROR 后 `Mono.empty()`）

字段填法（与 api 现 `AuditService.recordDenied` 对齐）：`occurredAt=now`；`actorUsername`=token `data.username`，取不到用 `"unknown"`；`actorDisplayName`=`data.displayName`；`tenantId`=`data.tenantId`；`action`=`String.valueOf(LinkedHashSet<码>)`（与 `AuditDenied.codesOf` 同格式）；`result="DENIED"`；`httpMethod`；`requestPath`=外部路径（`exchange.getRequest().getPath().value()`，**不含 query**）；`clientIp`=`X-Real-IP` → `X-Forwarded-For` 第一段 → 远端地址；`userAgent`。写库在 boundedElastic，直接 `mapper.insert`（网关没有业务事务，不需要 REQUIRES_NEW）。

- [ ] **Step 1: 写失败测试** `GatewayDeniedAuditorTest`：
  - 被拒请求 `/ws/pod/exec?access_token=xyz` → 插入记录的 `requestPath` 为 `/ws/pod/exec`、任何字段都不含 `xyz`
  - `action` 为 `"[platform:oauth-client:manage]"`
  - mapper 抛异常 → `record` 正常完成（`StepVerifier...verifyComplete()`），403 照常返回
  - 无 DENIED_CODES_ATTR（未命中行）→ 不写记录（与现状一致：没有码可记）
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-gateway -am test -Dtest=GatewayDeniedAuditorTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑测试确认通过**；全模块 `mvn -pl platform-gateway -am test` 绿。
- [ ] **Step 5: 提交** `feat(gateway): 被拒请求写审计`

---

### Task 7: 长连接与流式不断、不缓冲（网关侧）

**Files:**
- Modify: `platform-gateway/src/main/resources/application.yaml`
- Test: `GatewayLongConnectionIT`、`GatewayHttpClientConfigTest`

**断点清单与对策（本任务的依据）：**

| 断点 | 现象 | 对策 |
|---|---|---|
| 网关连接池复用了上游 Tomcat 已关的空闲连接 | 偶发 `Connection prematurely closed BEFORE response` → 502/500 | 池连接 `max-idle-time: 15s`（< Tomcat 默认 keepAliveTimeout 20s）、`max-life-time: 5m` |
| SCG WS 单帧默认上限 64KB | 粘贴大段文本到终端 → 1009 断开 | `websocket.max-frame-payload-length: 2097152`（2MB） |
| 网关响应超时 | 慢接口、大文件下载被掐 | **不设** `response-timeout`（与现状一致，由 nginx 的 1800s 兜底）；`connect-timeout: 5000` |
| 网关服务端空闲超时 | 终端静默被掐 | 不设 `server.netty.idle-timeout`（Reactor Netty 默认不掐）；静默连接靠 Task 8 心跳保活 |
| 网关缓冲响应体 | 下载要等全部到齐才开始、内存暴涨 | 不加任何读 body 的 filter（SCG 默认流式），用测试钉住 |
| 网关发布重启 | 正在用的终端全断 | `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`（只能让短请求收尾；WS 必断，由 Task 8 的断线提示 + 重连兜底） |
| access log 泄露 token | 握手 URL 带 access_token | 不开 Reactor Netty access log；网关自身日志只打 path |

属性名以 Task 1 Step 1 核对的 SCG 版本为准（预期前缀 `spring.cloud.gateway.server.webflux.httpclient.*`）。

- [ ] **Step 1: 写失败测试**
  - `GatewayHttpClientConfigTest`：从容器取 SCG 的 `HttpClientProperties`，断言 `connectTimeout==5000`、`responseTimeout==null`、`pool.maxIdleTime==15s`、`pool.maxLifeTime==5m`、`websocket.maxFramePayloadLength==2097152`
  - `GatewayLongConnectionIT`（上游用 WebFlux 起一个随机端口的回显 WS 服务 + 一个慢速分块下载接口；安全用 Task 5 的测试密钥签 token）：
    - `wsUpgradeWithQueryToken`：`ws://gateway/ws/pod/exec?access_token=<有效>&ns=a` 握手成功，上游收到的 query 含 `access_token` 与 `ns=a`，回显一条消息
    - `wsLargeFrame`：发 256KB 文本帧，原样回显，连接不断
    - `wsSurvivesIdle`：握手后静默 70s（> 池 idle 与常见 LB 60s），再发一条仍能回显 —— 标 `@Tag("slow")`
    - `downloadIsStreamed`：上游每 200ms 写 1MB、共 10MB；客户端在上游写完之前就收到首个 1MB（断言首字节到达时间 < 1s）
    - `pooledConnectionReuseAfterUpstreamIdleClose`：上游 Tomcat（`server.tomcat.keep-alive-timeout=20s`）—— 连发两次请求、中间等 21s，第二次 200 —— 标 `@Tag("slow")`
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-gateway -am test -Dtest='GatewayHttpClientConfigTest,GatewayLongConnectionIT' -Dgroups='slow | !slow' -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 实现**：按上表写配置。
- [ ] **Step 4: 跑测试确认通过**（同 Step 2，含 slow）。
- [ ] **Step 5: 提交** `feat(gateway): 长连接与流式转发配置`

---

### Task 8: 终端心跳 + 断线提示与重连（api 中继 + 前端）

**Files:**
- Modify: `platform-api/src/main/java/com/coding/platformapi/components/PodExecRelayHandler.java`、`platform-web/src/components/PodTerminal.vue`
- Test: `platform-api/src/test/java/com/coding/platformapi/components/PodExecRelayHandlerHeartbeatTest.java`

**协议（决定）：**
- 前端在 `open` 状态每 **25s** 发 `{"type":"ping"}`（25s < 常见 LB/Ingress 60s 空闲上限，nginx 1800s 更不在话下）。
- api 中继收到 `type=="ping"` 的文本帧：**不转发**给 k8s-server，直接回 `{"type":"pong"}`。其它帧照旧原样转发（判 type 时只做轻量 JSON 解析；解析失败按普通帧转发，不能因此断开）。
- 前端连续 **60s** 未收到任何帧（输出或 pong）→ 主动 `close()`，状态置 closed，终端写 `[连接已断开（心跳超时）]`。
- closed 状态显示「重新连接」按钮：用 `getAccessToken()` 取**当前**token 重新 `buildUrl()` 建连（新 shell，旧会话状态不保留 —— 按钮旁文字写明「将打开新的会话」）。进程正常 `exit` 也显示该按钮。
- 不改 k8s-server：kubelet 的 streaming 空闲上限默认 4h，且 ping 不到它；用户长时间不输入且无输出超过 4h 的会话被 kubelet 关是可接受的（会走断线提示）。

- [ ] **Step 1: 写失败测试** `PodExecRelayHandlerHeartbeatTest`（mock 浏览器 session 与上游 session，按现有 handler 构造方式）：
  - `pingIsAnsweredNotForwarded`：浏览器发 `{"type":"ping"}` → 浏览器 session 收到 `{"type":"pong"}`，上游 `sendMessage` 未被调用
  - `inputIsForwarded`：`{"type":"input","data":"ls\n"}` → 上游收到原文
  - `nonJsonIsForwarded`：`"abc"` → 上游收到原文，浏览器 session 未被关闭
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-api -am test -Dtest=PodExecRelayHandlerHeartbeatTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 实现 api 侧。**
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: 实现前端**（定时器在 `onBeforeUnmount` 与每次 close 时清掉；重连复用现有建连函数，抽成 `connect()`）。`cd platform-web && npm run build` 通过。
- [ ] **Step 6: 预览验证**（按 memory「UI preview 验证法」的 Approach B 挂 PodTerminal，用本地 WS 桩服务）：静默 60s 不回 pong → 出现「连接已断开（心跳超时）」与「重新连接」；点按钮重新建连成功。截图存证。
- [ ] **Step 7: 提交** `feat: 终端心跳保活与断线重连`

---

### Task 9: 审计与身份工具迁入公共库（servlet 侧）

**Files:**
- Move: `platform-api/.../audit/{Auditable,AuditAspect,AuditWriter,AuditTargetResolver,AuditResult,AuditAction,AuditDenied}.java`、`.../security/AuthContext.java` → `platform-common/src/main/java/com/coding/common/audit/` 与 `.../common/security/AuthContext.java`
- Move: `AuditService.record(PlatformAuditLog)` 与 `recordDenied` 的写入部分 → `platform-common/.../audit/AuditRecorder.java`（api 的 `AuditService` 保留查询/清理/动作列表，委托 `AuditRecorder` 写）
- Create: `platform-common/.../audit/ServletAuditConfiguration.java`（`@Configuration`，`@Bean` 注册上述组件；**上述类全部去掉 `@Component`**）
- Modify: `platform-common/pom.xml`（`spring-boot-starter-aspectj`、`spring-tx` 设 `<optional>true</optional>`）；api 全部 import（`AuditAction` 被大量 controller 引用，机械替换包名）
- Test: api 既有审计测试随迁到 platform-common（`AuditAspect*Test`、`AuditTargetResolver*Test` 等），api 侧保留控制器上的审计集成测试

**Interfaces:**
- Produces: 包 `com.coding.common.audit`：`@Auditable`、`AuditAction`（常量类，新增 `JWK_GENERATE = "JWK_GENERATE"`）、`AuditRecorder.record(PlatformAuditLog)`、`AuditRecorder.recordDenied(HttpServletRequest)`；`com.coding.common.security.AuthContext`（方法签名不变）。业务服务用 `@Import(ServletAuditConfiguration.class)` 启用。
- 行为不变：切面 `@Order(0)`、`AuditWriter` 的 `REQUIRES_NEW`、fail-open。

- [ ] **Step 1: 迁移前跑一次基线**：`mvn -pl platform-api -am test > <workspace>/baseline.txt`，记下通过数。
- [ ] **Step 2: 迁移**（逻辑不改；`ServletAuditConfiguration` 只在 servlet 应用里有意义 —— 标 `@ConditionalOnWebApplication(type = SERVLET)`，防网关误引）。api 因扫描 `com.coding.common` 会自动拿到该配置；auth 在 Task 10 显式 `@Import`。
- [ ] **Step 3: 跑测试**：`mvn -pl platform-common,platform-api -am test` 通过数 = 基线（测试随迁，总数不减）。
- [ ] **Step 4: 提交** `refactor: 审计与 AuthContext 迁入 platform-common`

---

### Task 10: auth 管理接口改用 JWT 校验 + 审计，JWK 生成收口

**Files:**
- Modify: `platform-auth/.../config/SecurityConfig.java`（`@Order(1)` 的 `/internal/**` 链）、`platform-auth/.../controller/InternalClientController.java`、`platform-auth/.../controller/JwkController.java`（`@RequestMapping("/internal/jwk")`）、`PlatformAuthApplication.java`（`@Import(ServletAuditConfiguration.class)`；`@MapperScan` 加审计 mapper 包）、`platform-auth/src/main/resources/application.yaml`（加 audiences 配置，与 api 同一份值）
- Delete: `platform-auth/.../config/InternalTokenFilter.java` 及其测试；`application.yaml` 中 `platform.internal.token`
- Test: `InternalChainSecurityTest`（MockMvc）、`InternalClientControllerAuditTest`

**决定：**
- `/internal/**` 链：`oauth2ResourceServer(jwt)`，`JwtDecoder` 用 auth 自己的 `JWKSource` 构造（`OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)`），validator `PlatformJwtValidators.withAudiences(<platform 客户端 client_id 列表>)`；`authorizeHttpRequests(anyRequest().authenticated())` —— **auth 不判权限码**（spec §7：权限由网关判，防绕过靠网络隔离）。converter 用 `PlatformJwtAuthenticationConverter(dataKey, JwtPermissionResolver.noop())`（auth 不需要权限闭包，只要身份）。无状态、CSRF 关、401 JSON 同 Global Constraints。
- 启动自检：auth 新增 `InternalEndpointCrossCheckRunner`（照 api 的 `PermissionCrossCheckRunner`），只扫 `/internal/**` 端点，对照 service=platform-auth 的行，缺行拒启（spec §8.5）。注意它在 Task 11 的迁移之前就会跑 —— 因此 auth 本任务的构建与 `V2026_11_04_2` 必须同批上线，部署顺序见 Task 13。
- 审计：`InternalClientController` 每个写方法标 `@Auditable`，action 与 target 照抄 api 现 `OAuthClientController` 上的写法（`OAUTH_CLIENT_*`、`#request.id` / `#request.clientId`）；`JwkController.generate` 标 `@Auditable(action = AuditAction.JWK_GENERATE)`。
- nginx 已有 `location /auth/internal/ { return 404; }`，`/jwk` 挪进 `/internal` 后前门自然关闭 —— 这就是 `/jwk/generate` 缺口的修复。

- [ ] **Step 1: 写失败测试**
  - `InternalChainSecurityTest`：无 token → 401 JSON；aud 不在名单的 token → 401；有效 token → 200；`/jwk/generate`（旧路径）→ 404；`/internal/jwk/generate` 有效 token → 200；请求头 `X-Platform-Internal-Token` 不再有任何作用（只带它不带 JWT → 401）
  - `InternalEndpointCrossCheckRunnerTest`：权限表缺 `/internal/clients/delete` 的行 → 启动抛异常并点名该端点；行齐全 → 正常
  - `InternalClientControllerAuditTest`：`/internal/clients/create` 成功 → 审计表多一条 `action=OAUTH_CLIENT_CREATE, result=SUCCESS, actorUsername=<token 用户>`；规则校验失败（90017）→ `result=FAILED, errorCode=90017`
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-auth -am test -Dtest='InternalChainSecurityTest,InternalEndpointCrossCheckRunnerTest,InternalClientControllerAuditTest' -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 实现。**
- [ ] **Step 4: 跑测试**：上面通过；`mvn -pl platform-auth -am test` 全绿。
- [ ] **Step 5: 提交** `feat(auth): 管理接口改 JWT 校验并记审计，JWK 生成收进 /internal`

---

### Task 11: 删 api 转发层 + 切流量入口

**Files:**
- Delete: `platform-api/.../controllers/OAuthClientController.java`、`.../outbound/AuthClientAdminClient.java`、`.../models/oauthclient/`（整个目录）及 `OAuthClientControllerTest`、`AuthClientAdminClientTest`；api `application.yaml` 中 `platform.auth.internal-url` / `internal-token`（工作区里该行的未提交改动随之丢弃）
- Create: `platform-data/src/main/resources/db/migration/V2026_11_04_2__oauth_client_rows_to_auth.sql`
- Modify: `platform-web/nginx.conf`、`platform-web/nginx-https.conf`（`/api/` 上游改 `${PLATFORM_GATEWAY_URL}/`，其余 WS/超时/不缓冲配置原样）、`platform-web/Dockerfile`（`PLATFORM_API_URL` 换成 `PLATFORM_GATEWAY_URL=http://platform-gateway:8090`，注释同步）、`platform-web/vite.config.ts`（`/api` 代理 target 改 `http://localhost:8090`，`ws: true` 保留）
- Modify: `OAuthClientPermissionMigrationTest`（改为校验新迁移后的形状）
- Test: `OAuthClientPermissionMigrationTest`、api 全量测试（交叉校验）

**迁移 `V2026_11_04_2` 内容：** 8 行 `perm_oauth_client_{list,get,scopes,create,update,delete,enable,reset_secret}` 的 `service='platform-auth'`、`resource` 由 `/oauth-clients/<x>` 改为 `/internal/clients/<x>`（`reset_secret` → `/internal/clients/reset-secret`）；页面行 `perm_page_oauth_client` 不动；角色绑定不动。

- [ ] **Step 1: 改 `OAuthClientPermissionMigrationTest`**：断言新迁移把 8 行改成 service=platform-auth 且 resource 以 `/internal/clients/` 开头；跑它确认失败。
- [ ] **Step 2: 写迁移、删 api 转发代码与配置、改 nginx / Dockerfile / vite。**
- [ ] **Step 3: 跑测试**：`mvn test`（全项目）全绿 —— 特别确认 api 交叉校验没有把 auth 的行报成幽灵规则（Task 3 已让 api 只装 platform-api 的行）；`cd platform-web && npm run build` 通过；前端 `oauthClientApi` 路径 `/oauth-clients/*` **零改动**。
- [ ] **Step 4: 提交** `feat: OAuth 客户端管理改经网关直达 auth，删除 api 转发层与内部令牌`

---

### Task 12: api 退出接口级鉴权

**Files:**
- Modify: `platform-api/.../configs/ResourceServerConfig.java`（`anyRequest().access(authorizationManager)` → `anyRequest().authenticated()`；`accessDeniedHandler` 不再调 `recordDenied`）
- Delete: `platform-api/.../security/PermissionAuthorizationManager.java`、`AuditDenied` 的使用点（`AuditRecorder.recordDenied` 若再无调用者一并删）及对应测试
- Keep: api 的 `PermissionRegistry`（`/permission/match` 按 URL 反查还要用）与启动交叉校验（spec §8.5「每个服务自检」）

**上线约束（写进 Task 13 的部署步骤）：** 本任务的构建**只能**在网关已上线、且 api 端口只对网关开放（NetworkPolicy 生效）之后部署；否则等于 api 对所有登录用户全开。

- [ ] **Step 1: 写失败测试** `ResourceServerAuthenticatedOnlyTest`（MockMvc）：有效 token 但不持码访问有权限行的接口 → **200**（接口级判定已交给网关）；无 token → 401；`/alert/webhook` 仍放行到控制器。
- [ ] **Step 2: 跑测试确认失败**：`mvn -pl platform-api -am test -Dtest=ResourceServerAuthenticatedOnlyTest -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **Step 3: 实现**；删掉断言「api 返回 403」的旧测试（这些用例在网关的 `GatewaySecurityIT` 里已有等价覆盖，ledger 记一条 Ruling 列出被删的测试名）。
- [ ] **Step 4: 跑测试**：`mvn test` 全绿。
- [ ] **Step 5: 提交** `refactor(api): 接口级鉴权移交网关，api 只校验登录`

---

### Task 13: 文档、部署顺序与真跑验收清单

**Files:**
- Create: `platform-gateway/README.md`
- Modify: `README.md`（模块表、端口表加网关）、`platform-auth/README.md`（删内部令牌说明，写 `/internal/**` 的 JWT 链与网络隔离要求）、`platform-api/README.md`（鉴权一节改为「接口级在网关」）、spec 状态改「已实施待部署」并按 Task 5 修正 §3 的租户一行
- Memory: 更新 `platform-gateway-design.md` 与 `oauth-client-management-in-platform.md`（内部令牌已删）

`platform-gateway/README.md` 必须含：

1. **部署顺序（缺一步就坏）：**
   1. 重放 `V2026_11_04_1`（加 `service` 列 + JWK 行）→ 重放 `V2026_11_04_2`
   2. 部署网关（配 `OAUTH2_SERVER`、audiences、MySQL、Redis、`PLATFORM_API_URL`、`PLATFORM_AUTH_URL`）
   3. 部署 auth（Task 10 起的构建）与 api（**Task 11 的构建，不含 Task 12**）
   4. nginx 切 `PLATFORM_GATEWAY_URL` → 重新登录 → 跑下方验收
   5. 配 NetworkPolicy：api 入站只放网关（以及 Alertmanager 若直连 api 则放其来源）；auth 的 `/internal/**` 只放网关 —— auth 协议端点仍需 nginx 可达，故 auth 的入站放 nginx + 网关，`/internal/` 靠 nginx 404 + 网络上 nginx 不转发该前缀
   6. 确认 5 生效后，再部署含 Task 12 的 api 构建
2. **NetworkPolicy 示例 YAML**（按 Pod label `app: platform-gateway` 放行）。
3. **长连接说明**：Task 7 表格 + 心跳协议 + 「网关发布会断开所有终端，用户点重连即可」。

**真跑验收（逐项打勾，结果写回 spec）：**
- [ ] 登录后菜单与改造前一致；租户视图切换正常
- [ ] 无权用户调有行接口 → 403，审计页出现一条网关写的 DENIED（requestPath 无 query）
- [ ] OAuth 客户端页增删改、重置密钥、停用全部可用，审计页出现 auth 写的 SUCCESS 记录
- [ ] 非管理员调 `/api/jwks/generate` → 403；直接访问 `/auth/jwk/generate` 与 `/auth/internal/jwk/generate` → 404
- [ ] 终端静默 40 分钟后仍可输入（跨过 nginx 1800s 也不断 —— 心跳在跑）
- [ ] 终端里粘贴 300KB 文本不断开
- [ ] 下载 100MB 对象：进度立刻开始走，网关内存无明显上涨
- [ ] 终端打开时滚动重启网关 → 终端提示断开，点「重新连接」恢复
- [ ] 权限点管理页新增一行 → 60s 内网关按新行判定
- [ ] Alertmanager 告警推送仍能进来（`/alert/webhook`）

- [ ] **提交** `docs: 网关部署与验收说明`
