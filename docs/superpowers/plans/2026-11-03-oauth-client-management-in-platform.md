# OAuth 客户端管理搬进平台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 客户端管理从 platform-auth 的无权限页面搬到平台（权限表 + 审计），同时按「内置 / 外部」区分 token 内容、保护内置客户端、支持停用。

**Architecture:** 写客户端表的逻辑留在 platform-auth，加固后改为只对 platform-api 开放的内部接口 `/internal/clients/**`（共享密钥，未配置拒启）；platform-api 新增 `/oauth-clients/**` 做权限、审计并转调 auth；platform-web 新增「OAuth 客户端」页。auth 的 token customizer 按客户端类别写 claim，客户端仓库包一层「停用即不存在」。

**Tech Stack:** Spring Boot 4 / Spring Authorization Server（auth）、Spring MVC + RestClient（api）、Vue 3 + Element Plus + TS（web）、MySQL 手工迁移。

**Spec:** `docs/superpowers/specs/2026-11-03-oauth-client-management-in-platform-design.md`

## Global Constraints

- 内置客户端：只有 `platform-web-client`；client_settings 键 `platform.client.builtin`=true，由迁移打标，任何接口都不能设置或清除它。
- 停用标记：client_settings 键 `platform.client.enabled`（缺省视为 true）。内置客户端不可停用、不可删除。
- 内置客户端可改字段只有：`redirectUris`、`postLogoutRedirectUris`、`accessTokenTimeToLive`；其余字段请求里出现也忽略（以库内值为准）。
- 外部客户端类型 `CONFIDENTIAL`（`client_secret_basic`）/ `PUBLIC`（`none`），创建后不可改。
- 授权方式：CONFIDENTIAL 可选 `authorization_code`、`refresh_token`、`client_credentials`（至少一个）；PUBLIC 只能 `authorization_code`。含 `authorization_code` 时 `requireProofKey` 恒为 true；`refresh_token` 必须伴随 `authorization_code`。
- scope 目录（唯一来源，auth 的 `PlatformScopes`）：`openid`「认证用户身份并获取登录状态」、`profile`「查看你的显示名」。
- 外部客户端带用户的 token：只有 `sub` + `name`（显示名），不写 `data` claim；无用户 token（client_credentials）：不查用户、不写 `data`。内置客户端维持现状。
- token 形态：`JWS`（RSA 默认 / HS256/384/512）或 `JWE`（对称：dir、A128KW/A192KW/A256KW；非对称：RSA*/ECDH* 需 `jweJwkUrl`）。对称密钥由 auth 生成，只在创建 / 重置响应中出现一次；落库用 auth 现有的 `JweTokenStrategy` 加密格式（与现有行兼容）。
- 读接口（list/get）绝不返回任何密钥材料，只返回 `hasClientSecret` / `hasJwsSecret` / `hasJweSecret` 与 kid。
- 新权限码：`platform:oauth-client:view`（list/get/scopes）、`platform:oauth-client:manage`（其余写端点）、页面码 `platform:page:oauth-client`；只绑 `builtin_role_admin`。
- 迁移文件 `platform-data/src/main/resources/db/migration/V2026_11_03_2__oauth_client_management.sql`；本仓无 Flyway，手工重放；MySQL 注释 `--` 后必须跟空白。
- 共享密钥配置：auth `platform.internal.token`（env `PLATFORM_INTERNAL_TOKEN`）、api `platform.auth.internal-token`（同 env）+ `platform.auth.internal-url`（默认 `http://${AUTH_HOST:127.0.0.1}:${AUTH_PORT:9527}/auth`）。任一侧为空即拒启。
- 请求头：`X-Platform-Internal-Token`。
- 不引入新前端依赖；前端不做任何浏览器端加密。

## Review Focus

1. **内置客户端被「绕过前端」修改**：直接调 api / auth 内部接口改 `platform-web-client` 的授权方式、删除或停用它——后端必须拒绝。（Task 4 测试）
2. **旧行与新规则不一致**：`gateway-code-client` 带 `message.read` scope、HS256 设置；打开详情不报错、编辑保存时 scope 被规整到目录内、token 形态保持。（Task 4 测试）
3. **停用后刷新 / session-renewal**：停用外部客户端后用它的 refresh_token 换 token 应 `invalid_client`；内置客户端永远不受影响。（Task 3 测试）
4. **client_credentials 发 token 不再 500**：principal 不是 `User` 时 customizer 不得强转。（Task 2 测试）
5. **审计泄密**：`@Auditable.target` 只允许 `#request.id` / `#request.clientId`；创建 / 重置的响应体里有明文密钥但审计不记响应体。（Task 7 测试）

---

## File Structure

**platform-auth**（`src/main/java/com/coding/auth/`）
- Create `client/PlatformClientSettings.java` — 内置 / 启用两个键的读写与判定（纯静态）。
- Create `client/PlatformScopes.java` — scope 目录（唯一来源）。
- Create `config/TokenClaimsPolicy.java` — 按客户端类别决定写哪些 claim（从 customizer 抽出，可单测）。
- Create `client/EnabledOnlyRegisteredClientRepository.java` — 停用即不存在的仓库装饰器。
- Create `client/ClientAdminRequest.java` / `ClientAdminView.java` / `ClientSecrets.java` / `ClientType.java` — 内部接口 DTO。
- Rename+rewrite `service/ClientService.java` → `service/ClientAdminService.java` — 规则与读写。
- Create `controller/InternalClientController.java`、`config/InternalTokenFilter.java`。
- Modify `config/AuthorizationServerConfig.java`（customizer、仓库 bean）、`config/SecurityConfig.java`（内部链）、`config/LoginSuccessHandler.java`、`controller/AuthorizationConsentController.java`、`resources/application.yaml`。
- Delete `controller/ClientController.java`、`controller/TestController.java`、`client/RegisteredClientReq.java`、`client/RegisteredClientRes.java`、`resources/static/client-manage.html`。

**platform-data**：Create 迁移 `V2026_11_03_2__oauth_client_management.sql`。

**platform-api**（`src/main/java/com/coding/platformapi/`）
- Create `outbound/AuthClientAdminClient.java`、`controllers/OAuthClientController.java`、`models/oauthclient/*`（与 auth DTO 同形）。
- Modify `audit/AuditAction.java`、`resources/application.yaml`。

**platform-web**：Create `views/OAuthClientView.vue`、`components/oauthclient/OAuthClientForm.vue`、`components/oauthclient/SecretOnceDialog.vue`；Modify `api/index.ts`、`pageCodes.ts`、`apiCodes.ts`、`router/index.ts`、`nginx.conf`。

---

### Task 1: auth — 客户端标记与 scope 目录

**Files:**
- Create: `platform-auth/src/main/java/com/coding/auth/client/PlatformClientSettings.java`、`PlatformScopes.java`
- Modify: `platform-auth/src/main/java/com/coding/auth/controller/AuthorizationConsentController.java`（`toScopeDTO` 改查 `PlatformScopes`，未知 scope 仍回落 `scope + " 权限"`）
- Test: `platform-auth/src/test/java/com/coding/auth/client/PlatformClientSettingsTest.java`

**Interfaces — Produces:**
- `PlatformClientSettings.BUILTIN_KEY = "platform.client.builtin"`、`ENABLED_KEY = "platform.client.enabled"`
- `static boolean isBuiltin(RegisteredClient c)`、`static boolean isEnabled(RegisteredClient c)`（缺键 → true）、`static ClientSettings.Builder withEnabled(ClientSettings base, boolean enabled)`
- `PlatformScopes.CATALOG: List<OAuth2Scope>`（顺序 openid, profile）、`static boolean contains(String)`、`static OAuth2Scope describe(String)`

- [ ] **Step 1: 写失败测试** — `builtinFlagReadFromSettings`（settings 含 `platform.client.builtin=true` → true；缺键 → false）、`enabledDefaultsTrue`、`withEnabledFalseIsReadBack`、`catalogIsOpenidAndProfileOnly`（`CATALOG` 的 name 列表 == `["openid","profile"]`）。
- [ ] **Step 2:** `mvn -q -pl platform-auth -am test -Dtest=PlatformClientSettingsTest -Dsurefire.failIfNoSpecifiedTests=false` → 编译失败。
- [ ] **Step 3: 实现** 两个类；改 consent controller。
- [ ] **Step 4:** 同命令 → PASS。
- [ ] **Step 5: Commit** `feat(auth): 客户端内置/启用标记与 scope 目录`

### Task 2: auth — token claim 按客户端类别区分

**Files:**
- Create: `platform-auth/src/main/java/com/coding/auth/config/TokenClaimsPolicy.java`
- Modify: `AuthorizationServerConfig.jwtTokenCustomizer`（现 246–290 行）：只做「取 principal → 调策略」；`setCustomClientSettings` 照旧对所有客户端执行（token 形态与类别无关）。
- Test: `platform-auth/src/test/java/com/coding/auth/config/TokenClaimsPolicyTest.java`

**Interfaces — Produces:**
- `enum TokenClaimsPolicy.Kind { PLATFORM_USER, EXTERNAL_USER, NO_USER }`
- `static Kind classify(RegisteredClient client, Object principal)`：principal 不是 `org.springframework.security.core.userdetails.User` → `NO_USER`；否则 `isBuiltin` ? `PLATFORM_USER` : `EXTERNAL_USER`。
- customizer 行为：`PLATFORM_USER` = 现有逻辑原样；`EXTERNAL_USER` = 只 `claim("name", displayName)`（displayName 为空回落 username）；`NO_USER` = 不加任何自定义 claim。

- [ ] **Step 1: 写失败测试** — `clientCredentialsPrincipalIsNoUser`（principal 为 `String` client_id → `NO_USER`，不抛异常）、`builtinClientWithUserIsPlatformUser`、`externalClientWithUserIsExternalUser`。
- [ ] **Step 2:** 运行 → 失败。
- [ ] **Step 3: 实现** 策略 + 改 customizer（`(User) context.getPrincipal().getPrincipal()` 的强转只在 `PLATFORM_USER`/`EXTERNAL_USER` 分支里做）。
- [ ] **Step 4:** 运行 → PASS；再跑 `mvn -q -pl platform-auth -am test` 全绿。
- [ ] **Step 5: Commit** `feat(auth): 外部客户端 token 只含 sub+name，client_credentials 不再强转 User`

### Task 3: auth — 停用即不存在

**Files:**
- Create: `platform-auth/src/main/java/com/coding/auth/client/EnabledOnlyRegisteredClientRepository.java`
- Modify: `AuthorizationServerConfig.registeredClientRepository`：新增非 primary bean `jdbcRegisteredClientRepository`（`JdbcRegisteredClientRepository`）；`@Primary` 的 `registeredClientRepository` 返回装饰器包住它。
- Test: `platform-auth/src/test/java/com/coding/auth/client/EnabledOnlyRegisteredClientRepositoryTest.java`

**Interfaces — Produces:** `EnabledOnlyRegisteredClientRepository(RegisteredClientRepository delegate)`；`findById` / `findByClientId` 对 `!isEnabled` 返回 null；`save` 直接委托。管理侧（Task 4）注入 `@Qualifier("jdbcRegisteredClientRepository")`。

- [ ] **Step 1: 写失败测试**（delegate 用 Mockito）— `disabledClientIsInvisibleById`、`disabledClientIsInvisibleByClientId`、`enabledAndUnflaggedClientsPassThrough`。
- [ ] **Step 2:** 运行 → 失败。
- [ ] **Step 3: 实现**装饰器与 bean 改动（`SessionRenewalAuthenticationProvider`、`ClientSecretAuthenticationProvider` 均经 primary bean，自动受管）。
- [ ] **Step 4:** 运行 → PASS。
- [ ] **Step 5: Commit** `feat(auth): 停用的客户端对授权与 token 端点不可见`

### Task 4: auth — 客户端管理服务（规则 + 密钥生成）

**Files:**
- Create: `client/ClientType.java`（`CONFIDENTIAL`/`PUBLIC`）、`client/ClientAdminRequest.java`、`client/ClientAdminView.java`、`client/ClientSecrets.java`
- Rename+rewrite: `service/ClientService.java` → `service/ClientAdminService.java`（保留现有 `validateJwtConfig` 里的算法判定、HMAC 长度规则、`buildTokenSettings`、序列化帮助方法；删掉「解密前端提交的密钥」路径）
- Test: `platform-auth/src/test/java/com/coding/auth/service/ClientAdminServiceTest.java`

**Interfaces — Produces（Task 5、7 照此原样暴露）:**
- `ClientAdminRequest { String id; String clientId; String clientName; ClientType type; Set<String> grantTypes; Set<String> scopes; Set<String> redirectUris; Set<String> postLogoutRedirectUris; Boolean requireAuthorizationConsent; Long accessTokenTimeToLive /*秒*/; Long refreshTokenTimeToLive /*秒*/; String jwtType /*JWS|JWE*/; String jwsSigAlg; String jweKeyAlg; String jweEncMethod; String jweJwkUrl; }`
- `ClientAdminView { id, clientId, clientName, ClientType type, boolean builtin, boolean enabled, Set grantTypes, scopes, redirectUris, postLogoutRedirectUris, boolean requireAuthorizationConsent, long accessTokenTimeToLive, Long refreshTokenTimeToLive, String jwtType, jwsSigAlg, jweKeyAlg, jweEncMethod, jweJwkUrl, boolean hasClientSecret, boolean hasJwsSecret, boolean hasJweSecret, String jwsSecretKid, String jweSecretKid, Instant clientIdIssuedAt }`
- `ClientSecrets { String clientSecret; String jwsSecret; String jweSecret; }`（只放本次新生成的，其余 null）
- `record ClientCreated(ClientAdminView client, ClientSecrets secrets)`
- 方法：`List<ClientAdminView> list()`、`ClientAdminView get(String id)`、`ClientCreated create(ClientAdminRequest)`、`ClientCreated update(ClientAdminRequest)`（改了 token 形态需要新对称密钥时才有 secrets）、`void delete(String id)`、`void setEnabled(String id, boolean enabled)`、`ClientSecrets resetSecret(String id, String which /*client|jws|jwe*/)`、`List<OAuth2Scope> scopes()`
- 错误一律抛 `CloudPlatformException`；新增 `EnumResponseType`：`OAUTH_CLIENT_BUILTIN_LOCKED`、`OAUTH_CLIENT_INVALID`（码段接在现有 OAUTH2_* 之后）。

**规则（服务端强制，测试逐条钉）：**
- type=PUBLIC → authMethod `none`、grantTypes 只能 `{authorization_code}`、无任何密钥、`jwtType` 只能 JWS-RSA 或 JWE 非对称（对称算法需要共享密钥，公共客户端无处安放）。
- type=CONFIDENTIAL → authMethod `client_secret_basic`；创建时生成 client_secret（32 字节随机 → Base64URL），BCrypt 落库，明文只出现在 `ClientCreated.secrets`。
- 含 `authorization_code` → `requireProofKey=true`；`refresh_token` 不带 `authorization_code` → `OAUTH_CLIENT_INVALID`；含 `authorization_code` 时 redirectUris 至少一个。
- scopes ⊆ `PlatformScopes`；update 时把旧行里目录外的 scope 丢弃（Review Focus 2）。
- 对称 JWS（HS256/384/512）→ 生成 32/48/64 字节密钥；对称 JWE（dir 依 enc 长度，A*KW 依 KW 长度）→ 生成对应长度；用 `JweTokenStrategy` 加密后存入现有 `CustomClientSetting` 键（与 auth 签发时的解密路径一致）；切回 RSA 时清掉对应密钥键。
- builtin → delete / setEnabled(false) 抛 `OAUTH_CLIENT_BUILTIN_LOCKED`；update 只采用三个可改字段，其余取库内值；create 永远不写 builtin 键；任何请求都无法设置 builtin / enabled（enabled 只经 `setEnabled`）。
- clientId：请求为空则生成 `ext-` + 12 位小写字母数字；创建后不可改（update 忽略）；重复 → `OAUTH_CLIENT_INVALID`。
- accessTokenTimeToLive ∈ [300, 86400]，refreshTokenTimeToLive ∈ [3600, 2592000]，缺省 3600 / 604800。

- [ ] **Step 1: 写失败测试**（`RegisteredClientRepository` 用内存实现或 Mockito，`JweTokenStrategy` mock 成「加前缀」）：
  `publicClientGetsNoSecretAndOnlyAuthCode`、`confidentialCreateReturnsSecretOnceAndViewHasNoSecret`（`view` 序列化后的 JSON 不含 secrets 明文）、`authCodeForcesPkce`、`refreshWithoutAuthCodeRejected`、`hs512GeneratesSixtyFourByteKeyStoredEncrypted`、`builtinDeleteAndDisableRejected`、`builtinUpdateOnlyTouchesThreeFields`（请求里改 grantTypes/scopes/consent 均无效，redirectUris 生效）、`legacyScopesDroppedOnUpdate`（库内 `openid,profile,message.read` → 保存后 `openid,profile`，jwsSigAlg 仍 HS256）、`clientCredentialsAllowedForConfidential`、`resetJwsSecretRotatesKid`。
- [ ] **Step 2:** `mvn -q -pl platform-auth -am test -Dtest=ClientAdminServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → 失败。
- [ ] **Step 3: 实现**服务与 DTO；`ClientAdminService` 注入 `@Qualifier("jdbcRegisteredClientRepository")`（必须能读到已停用的行）。
- [ ] **Step 4:** 同命令 → PASS。
- [ ] **Step 5: Commit** `feat(auth): 客户端管理服务（内置保护、类型规则、密钥服务端生成）`

### Task 5: auth — 内部接口、共享密钥、删除旧页面

**Files:**
- Create: `controller/InternalClientController.java`（`@RequestMapping("/internal/clients")`，全部 POST：`/list`、`/get`、`/create`、`/update`、`/delete`、`/enable`、`/reset-secret`、`/scopes`；body 用 Task 4 的 DTO，`{id}` / `{id, enabled}` / `{id, which}` 用小 record）
- Create: `config/InternalTokenFilter.java` —— 构造器读 `platform.internal.token`，`@PostConstruct` 为空抛 `IllegalStateException`；`/internal/**` 请求头 `X-Platform-Internal-Token` 常量时间比较不等 → 401 JSON。
- Modify: `config/SecurityConfig.java` —— 新增 `@Order(1)` 的 `SecurityFilterChain`：`securityMatcher("/internal/**")`、无 session、csrf 关、`permitAll`、加 `InternalTokenFilter`（不走表单登录，未登录不会被重定向到 login.html）。
- Modify: `config/LoginSuccessHandler.java` —— 无 saved request / `.well-known` 时 `requestURI` 改为 `"/"`（同源下即平台前端首页），不再指向 client-manage。
- Modify: `resources/application.yaml` —— `ignore-urls` 删 `/test/**`；新增 `platform.internal.token: ${PLATFORM_INTERNAL_TOKEN:}`。
- Delete: `ClientController.java`、`TestController.java`、`RegisteredClientReq.java`、`RegisteredClientRes.java`、`static/client-manage.html`、`ViewController` 里指向 client-manage 的映射。
- Test: `platform-auth/src/test/java/com/coding/auth/config/InternalTokenFilterTest.java`

- [ ] **Step 1: 写失败测试** — `missingTokenConfigRefusesToStart`、`wrongHeaderGets401`、`correctHeaderPassesThrough`、`nonInternalPathIgnored`（MockHttpServletRequest + MockFilterChain）。
- [ ] **Step 2:** 运行 → 失败。
- [ ] **Step 3: 实现**上述改动与删除。
- [ ] **Step 4:** `mvn -q -pl platform-auth -am test` 全绿；`grep -rn "client-manage\|ClientController\|RegisteredClientReq" platform-auth/src` 无结果。
- [ ] **Step 5: Commit** `feat(auth): 客户端管理改为内部接口，删除旧管理页与测试控制器`

### Task 6: 迁移 — 内置标记 + 权限行

**Files:**
- Create: `platform-data/src/main/resources/db/migration/V2026_11_03_2__oauth_client_management.sql`
- Test: `platform-api/src/test/java/com/coding/platformapi/security/OAuthClientPermissionMigrationTest.java`（照 `ApplicationPermissionMigrationTest` 的套路：反射 `OAuthClientController` 的 `@PostMapping` → 每个路径在 SQL 里有行；另断言 SQL 含 `'platform:page:oauth-client'` 且所有 `rp_*` 行的 role 都是 `builtin_role_admin`）

**内容：**
- 头注释照 `V2026_10_13_1` 风格：背景、权限码、只给 admin 的理由、「先重放再起构建（否则 PermissionCrossCheckRunner 拒启）」、「重放后重新登录或等 30s」、幂等说明、`--` 后要空白。
- `UPDATE oauth2_registered_client SET client_settings = JSON_SET(client_settings, '$."platform.client.builtin"', true) WHERE client_id = 'platform-web-client';`
- API 行（id 前缀 `perm_oauth_client_`）：`/oauth-clients/list|get|scopes` → `platform:oauth-client:view`；`/create|update|delete|enable|reset-secret` → `platform:oauth-client:manage`；Page 行 `perm_page_oauth_client`：`Page`,`/oauth-clients`,`view`,`platform:page:oauth-client`。全部 ODKU。
- `platform_role_permission` 显式 id 列表 `rp_admin_oauth_client_*` → `builtin_role_admin`。权限说明里写一句：持有 manage 码者等同 root（可改内置客户端回调）。

- [ ] **Step 1:** 写测试（此时控制器尚不存在 → 编译失败即视为失败）。
- [ ] **Step 2:** 写 SQL。测试在 Task 7 完成后转绿；本任务只验证 SQL 自身：用 jshell+JDBC 对开发库重放（见记忆「本仓无 Flyway」的配方），`SELECT JSON_EXTRACT(client_settings,'$."platform.client.builtin"') FROM oauth2_registered_client WHERE client_id='platform-web-client'` → `true`，且 auth 重启后前端仍能登录（SAS 能反序列化新增键）。
- [ ] **Step 3: Commit** `feat(data): OAuth 客户端管理权限行与内置标记迁移`

### Task 7: platform-api — 转调 auth + 权限 + 审计

**Files:**
- Create: `outbound/AuthClientAdminClient.java` —— `RestClient`（baseUrl=`platform.auth.internal-url`），每次带 `X-Platform-Internal-Token`；构造时 token 为空抛 `IllegalStateException`；auth 返回 `ResponseData` 非成功码时原样抛 `CloudPlatformException`（保留 code/msg）。方法与 Task 4 服务一一对应。
- Create: `models/oauthclient/` 下与 auth 同形的 `ClientAdminRequest`、`ClientAdminView`、`ClientSecrets`、`ClientCreated`、`ClientType`、`OAuthScope`，以及 `IdRequest{id}`、`EnableRequest{id, enabled}`、`ResetSecretRequest{id, which}`。
- Create: `controllers/OAuthClientController.java`（`@RequestMapping("/oauth-clients")`，路径与 Task 6 一致，全部 POST；只做绑定 + `ResponseData` 包装，风格同 `NotificationChannelController`）
- Modify: `audit/AuditAction.java` —— `OAUTH_CLIENT_CREATE="oauth-client:create"`、`_UPDATE`、`_DELETE`、`_ENABLE`（enable/disable 同一个）、`_RESET_SECRET`；前端审计页如有动作中文名映射一并补。
- Modify: `resources/application.yaml` —— `platform.auth.internal-url`、`platform.auth.internal-token: ${PLATFORM_INTERNAL_TOKEN:}`。
- Test: `controllers/OAuthClientControllerTest.java`（照 `CredentialControllerTest`：所有 `@Auditable.target` ∈ `{"#request.id","#request.clientId"}`；写端点都有 `@Auditable`）、`outbound/AuthClientAdminClientTest.java`（`MockRestServiceServer`：带头、错误码透传、空 token 拒启）。

- [ ] **Step 1: 写失败测试**（上述两个 + Task 6 的迁移测试）。
- [ ] **Step 2:** `mvn -q -pl platform-api -am test -Dtest='OAuthClient*,AuthClientAdminClientTest' -Dsurefire.failIfNoSpecifiedTests=false` → 失败。
- [ ] **Step 3: 实现。**
- [ ] **Step 4:** 同命令 PASS；再 `mvn -q -pl platform-api -am test` 全绿。
- [ ] **Step 5: Commit** `feat(api): /oauth-clients 管理接口（权限 + 审计，转调 auth 内部接口）`

### Task 8: platform-web — OAuth 客户端页

**Files:**
- Modify: `src/api/index.ts` —— `oauthClientApi`（list/get/create/update/remove/enable/resetSecret/scopes，全部 `http.post('/oauth-clients/...')`）与对应 TS 类型（字段与 Task 4 一致）。
- Modify: `src/pageCodes.ts` —— `oauthClient: 'platform:page:oauth-client'`；`src/apiCodes.ts` —— view/manage 两个码。
- Modify: `src/router/index.ts` —— 平台视图 · 组织：`{ path: 'oauth-clients', name: 'oauth-clients', component: () => import('@/views/OAuthClientView.vue'), meta: { title: 'OAuth 客户端', domain: 'org', icon: 'Connection', order: 6, requiresPerm: [pageCodes.oauthClient] } }`（不挂 context）。
- Create: `src/views/OAuthClientView.vue` —— 列表（名称、client_id、类型、内置标签、启用开关、回调地址、token 形态、操作）；内置行无删除 / 停用；写按钮按 manage 码 `v-if`。
- Create: `src/components/oauthclient/OAuthClientForm.vue` —— 抽屉表单。创建时选类型；PUBLIC 隐藏 client_credentials / refresh_token / 对称算法；内置客户端只显示三个可改字段，其余只读；scope 选项来自 `scopes` 接口。
- Create: `src/components/oauthclient/SecretOnceDialog.vue` —— 展示 `ClientSecrets` 中非空项，带复制按钮（复制必须带 `execCommand` 兜底，见记忆「http 下 navigator.clipboard 不存在」）、「关闭后不再显示」提示。
- Modify: `nginx.conf` —— `/auth/` 之前加 `location /auth/internal/ { return 404; }`（纵深防御：内部接口不经前门暴露）。

- [ ] **Step 1:** `cd platform-web && npm run build` → 当前通过（基线）。
- [ ] **Step 2: 实现**上述文件。
- [ ] **Step 3:** `npm run build` → type-check 与构建通过。
- [ ] **Step 4: 浏览器验证**（按记忆「UI preview 验证法」：假 token + XHR stub）：管理员菜单出现「OAuth 客户端」；新建 CONFIDENTIAL → 弹出一次密钥、关闭后列表无密钥；PUBLIC 表单无密钥与 client_credentials；内置行只能改三项、无删除；截图留证。
- [ ] **Step 5: Commit** `feat(web): OAuth 客户端管理页`

### Task 9: 文档与部署收尾

**Files:**
- Modify: `platform-auth/README.md`（管理页已移除、内部接口与共享密钥、token 按类别区分、停用语义）、`platform-api/README.md`（`/oauth-clients`、两个新配置项）、`docs/components/` 或部署文档里环境变量清单加 `PLATFORM_INTERNAL_TOKEN`（auth 与 api 同值）。
- Modify: spec 状态改「已实施待部署」。

- [ ] **Step 1:** 写文档；部署顺序写明：重放 `V2026_11_03_2` → 两侧配 `PLATFORM_INTERNAL_TOKEN` → 重建 platform-auth / platform-api / k8s-server（aud 改动同批）/ platform-web → 重新登录 → 在新页面核对 `gateway-code-client` 是否保留。
- [ ] **Step 2:** 全量 `mvn -q test` 与 `npm run build` 全绿。
- [ ] **Step 2b: 真跑验收清单写进部署文档**（单测覆盖不到、需活的 auth）：用一个测试 RP 走授权码 + PKCE，解码 token 只有 `sub`/`name`；该 token 调 platform-api 得 401；HS256 / JWE 对称 / JWE 非对称（测试 JWK URL）各签一次能验 / 解；client_credentials 换到无 `data` 的 token；停用后再授权得 `invalid_client`；`/auth/internal/clients/list` 经前门得 404、内网不带头得 401。
- [ ] **Step 3: Commit** `docs: OAuth 客户端管理迁入平台的部署说明`
