# platform-auth

K8s 云平台的**统一身份认证与授权服务**：基于 Spring Authorization Server（SAS）实现的 OAuth2 / OIDC 授权服务器，负责用户登录、客户端（RegisteredClient）管理、令牌签发（支持按客户端自定义 JWS/JWE 编码）、JWK 密钥管理，以及一个为 **PKCE 公共客户端**量身打造的**会话续期（session-renewal）自定义授权类型**。

- **运行端口**：`9527`
- **上下文路径**：`/auth`（`server.servlet.context-path`）——**所有端点都在 `/auth/` 下**：
  `/auth/oauth2/**`、`/auth/session/**`、`/auth/login.html`、`/auth/user/**`、`/auth/assets/**`、`/auth/.well-known/**`。
  前端与反代因此只需一条 `/auth/` 前缀转发；配套改动见 `platform-web/.env` 与本文件末尾的「入口」。
- **Issuer（默认）**：按请求现算 = `scheme://host[:port]/auth`（代码里的 `AuthorizationServerSettings` 未固定 issuer）；
  yaml 里的 `spring.security.oauth2.authorizationserver.issuer` 只是兜底配置项
- **技术栈**：Java 21 · Spring Boot 4.0.6 · Spring Authorization Server 7.x · Spring Session 4.x · MyBatis · Redis

---

## 在整体架构中的位置

`k8s-cloud-platform` 是多模块 Maven 工程，各模块职责如下：

| 模块 | 职责 |
|------|------|
| `platform-common` | 公共组件：JWT 编解码（JWS/JWE）、异常与响应码、通用模型/工具 |
| `platform-data` | 持久层：用户 / 角色 / 租户 / JWK 等 MyBatis Mapper 与实体 |
| **`platform-auth`** | **本模块 —— 身份认证 + 令牌签发（授权服务器）** |
| `k8s-core` | K8s 资源操作封装（fabric8 client） |
| `k8s-server` | 对接 K8s API 的服务（零业务逻辑，只做边界转换） |
| `platform-api` | 面向前端的网关 / 业务 API（资源服务器，校验本模块签发的令牌） |

`platform-auth` 是**令牌的唯一签发方**。它签出的是 **self-contained JWT**（`access_token_format = SELF_CONTAINED`），claim 里直接携带用户信息、平台域角色、租户信息等；下游模块（`platform-api` / `k8s-core` / `k8s-server`）作为资源服务器，只需校验签名 + 读取 claim 即可鉴权，无需回查授权服务器。

前端有两类消费方：

- **登录 / 授权确认页**（本模块自带的 `login.html` / `consent.html`）——服务端渲染的静态页，走表单登录。客户端管理页已于 2026-11 搬进平台（`platform-web` 的「组织 → OAuth 客户端」），本模块只留内部接口，见下文「客户端管理」。
- **业务 SPA**（独立工程 `platform-web`，Vue 3 + Vite）——以 OAuth2 公共客户端（PKCE）身份对接本模块，是 session-renewal grant 的主要使用方。

---

## 核心能力

1. **用户认证**：DB 账号 + BCrypt 密码的表单登录；用户/角色/租户来自 `platform-data`。
2. **OAuth2 / OIDC 授权服务器**：标准 `authorization_code`（含 PKCE）、`refresh_token`，OIDC 发现文档与 `/userinfo`。（SAS 默认仍支持 `token_exchange`，但平台已不再对其做任何定制。）
3. **self-contained JWT**：`data` claim 内联用户信息、平台域角色、租户上下文与**权限点闭包**——下游资源服务器只需验签 + 读 claim，无需回查。
4. **会话续期 grant（自定义）**：`urn:coding:grant-type:session-renewal` —— 解决 PKCE 公共客户端拿不到 refresh_token 的续期问题（详见下文「关键设计」）。
5. **按客户端自定义 JWT 编码**：每个客户端可独立配置 JWS 签名算法（对称/非对称）与可选的 JWE 加密。
6. **JWK 密钥管理**：生成签名 / 加密密钥对并入库，供 SAS 的 `/oauth2/jwks` 使用。
7. **客户端管理（内部接口）**：`/internal/clients/**` 只给 platform-api 调（共享密钥），规则在 `ClientAdminService`：内置客户端保护、机密 / 公共两类、对称密钥服务端生成；停用即对授权端点不可见。
8. **Redis 会话存储**：登录态（根凭证）存 Redis，支持多实例 / 重启不丢；8h 空闲超时 + keepalive 滑动续期。

---

## 目录结构

```
src/main/java/com/coding/auth/
├── PlatformAuthApplication.java        # Spring Boot 入口
├── config/
│   ├── SecurityConfig.java             # 默认安全链：表单登录、DB 用户、会话过期处理、CORS
│   ├── AuthorizationServerConfig.java  # OAuth2 AS 链（最高优先级）、客户端/授权持久化、自定义 JwtEncoder
│   ├── RedisSessionConfig.java         # 显式启用 Redis HTTP 会话（Boot 4.x 必须手写，见下文）
│   ├── RedisConnectionKeepalive.java   # 定时 PING Redis，避免共享连接被中间设备静默断开（见下文）
│   ├── LoginSuccessHandler.java        # 登录成功：返回 JSON（用户信息 + 回跳地址），更新最后登录时间
│   ├── CustomClientSetting.java        # 客户端自定义 JWT 配置（JWS/JWE 算法、密钥、kid）的载体与常量
│   ├── CustomJweEncoder.java           # 客户端要求 JWE 时，对已签名 JWT 再加密
│   ├── RemoteJwkSetCache.java          # 远端 JWK Set 缓存
│   └── GlobalExceptionHandler.java     # 异常 → ResponseData 统一映射
├── grant/                              # ★ session-renewal 自定义授权类型（四件套）
│   ├── SessionRenewalGrantType.java            # grant_type 常量 urn:coding:grant-type:session-renewal
│   ├── SessionRenewalAuthenticationToken.java  # 未认证载体 token（sessionId + 端用户 + 客户端）
│   ├── SessionRenewalAuthenticationConverter.java # 从请求构建载体（仅本 grant 生效，否则放行）
│   └── SessionRenewalAuthenticationProvider.java  # 校验链 + 以端用户身份重签 access_token
├── controller/
│   ├── InternalClientController.java   # /internal/clients/** —— 客户端管理内部接口（只给 platform-api）
│   ├── SessionController.java          # /session/keepalive —— 会话滑动续期
│   ├── AuthorizationConsentController.java # /oauth2/consent、/consent/details —— 授权同意页
│   ├── JwkController.java              # /internal/jwk/generate —— 生成并入库密钥对（只经网关）
│   └── CallbackController.java         # /callback —— 测试用回调（打印 code）
├── config/（节选）
│   ├── TokenClaimsPolicy.java          # token 按客户端类别写 claim：平台用户 / 外部用户 / 无用户
│   ├── InternalApiSecurityConfig.java  # /internal/** 链：校验网关透传的用户 JWT（aud 白名单，未配置拒启）
│   └── InternalEndpointCrossCheckRunner.java # /internal/** 端点缺 service=platform-auth 权限行即拒启
├── service/
│   ├── ClientAdminService.java         # 客户端管理入口（内置保护、启停、重置密钥）
│   ├── ExternalClientAssembler.java    # 外部客户端组装规则（创建 / 更新共用，规整老行）
│   ├── ClientTokenForms.java           # token 形态合法取值 + 对称密钥长度
│   ├── ClientSecretSealer.java         # 对称密钥加密落库（与签发侧同一解密路径）
│   └── TokenExtrasService.java         # 签发时的附加查库：平台角色 code / 租户上下文 / 权限闭包
└── client/
    ├── PlatformClientSettings.java     # client_settings 里的内置 / 启用两个标记
    ├── PlatformScopes.java             # scope 目录（唯一来源）
    ├── EnabledOnlyRegisteredClientRepository.java # 停用 = 对授权服务器不存在
    ├── ClientAdminRequest/View/Secrets/Created, ClientType # 内部接口 DTO
    └── OAuth2Scope.java                # scope 名称 + 描述

src/main/resources/
├── application.yaml                    # 端口 / issuer / MySQL / Redis / 放行白名单 / 内部接口密钥 / 文档
└── static/                             # login.html · consent.html + assets(axios/element-plus/app.js)
```

---

## 关键设计：根凭证模型 + 会话续期（session-renewal grant）

### 为什么需要它

标准 OAuth2 里，`client_authentication_method = none` 的**公共客户端**（即 PKCE SPA）**不签发 refresh_token**——这是 SAS 源码层面的行为，不是配置问题。于是 `platform-web` 这类 SPA 在 access_token 过期后无法用标准 refresh 续期，只能重走一遍 authorization_code（打断用户体验、重新弹授权）。

### 根凭证模型

本模块把 **HttpOnly 的 JSESSIONID 会话 Cookie** 当作「根凭证」（root credential）：

- 用户表单登录成功后建立会话，会话存 **Redis**（`Spring Session`），空闲超时 **8h**。
- access_token 只是根凭证派生出的**短期凭证**（TTL 由客户端配置，通常分钟级）。
- 只要根凭证（会话）还活着，就能随时重新签发 access_token，无需重登。

> **不变式：access_token TTL 必须严格小于会话空闲超时。** 否则会出现「session 已死但 token 还有效」的孤儿窗口——用户活跃时反而被踢、被迫重登。该约束对内置客户端（平台前端，唯一用会话续期的客户端）在**编辑时强制校验**（`ClientAdminService`，超限抛 `OAUTH_CLIENT_INVALID`=90017；平台的 OAuth 客户端页同步限制输入上限）。会话超时的唯一来源是常量 `RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS`（28800s），注解与注册校验都引用它，避免两处漂移。

### grant 四件套（`grant/` 包）

| 组件 | 职责 |
|------|------|
| `SessionRenewalGrantType` | grant_type 值 `urn:coding:grant-type:session-renewal`（唯一 URI，避免与标准/第三方冲突）+ 供集合比较的 `INSTANCE` |
| `SessionRenewalAuthenticationConverter` | 仅当 `grant_type` 命中时生效（否则返回 null 放行给其他 grant）。从 SecurityContext 取已认证客户端，从请求会话的 `SPRING_SECURITY_CONTEXT` 取端用户身份，构建未认证载体 token |
| `SessionRenewalAuthenticationToken` | 载体：`sessionId` + `endUserPrincipal`（可能为空）+ `clientPrincipal` |
| `SessionRenewalAuthenticationProvider` | 校验链 → 以**端用户**为 principal 重签 access_token（授权 openid 时附带 id_token），持久化 authorization，**不签发 refresh_token** |

Provider 的校验链（任一失败即抛标准 OAuth2 错误码，由 token endpoint 统一渲染，不进 `GlobalExceptionHandler`）：

1. 客户端已认证且类型正确 —— 否则 `invalid_client`；
2. 客户端被授权本 grant 类型 —— 否则 `unauthorized_client`；
3. 存在有效且已登录的会话 —— 否则 `invalid_grant`（SPA 据此跳转重新登录）。

> **实现要点**：SAS 默认 token generator 不是 Spring bean（内部构建），且 `DefaultOAuth2TokenCustomizers.jwtCustomizer()` 是包私有。因此 provider 的 generator 在 `AuthorizationServerConfig#sessionRenewalAuthenticationProvider` 里**内联构建**（自定义 `JwtEncoder` + 现有 `OAuth2TokenCustomizer<JwtEncodingContext>` bean），**不注册为全局 bean**——authorization_code / refresh_token / token_exchange 等其他 grant 仍用 SAS 内部默认生成器，行为不受影响。

### 时序（SPA 侧）

```
登录：  SPA → /oauth2/authorize (PKCE) → 表单登录(建会话,写Redis) → 回调 code
        SPA → /oauth2/token (authorization_code + code_verifier) → access_token

续期：  access_token 快过期或已过期时都行 —— 请求只带 grant_type + client_id + 会话 Cookie，
        不携带旧 token；服务端只看「会话是否有效」，与旧 token 处于什么状态无关：
        SPA → /oauth2/token (grant_type=urn:coding:grant-type:session-renewal
                              + client_id, 携带会话 Cookie)
             → 校验通过 → 重签 access_token（无 refresh_token）

保活：  SPA 活跃期间定期(约10min) → /session/keepalive
        → 写一个会话属性触发 Redis 保存 → 重置会话空闲 TTL（滑动续期）
```

SPA 侧逻辑已落地在 `platform-web/src/auth/oauth.ts`：`renewAccessToken()`（续期，**任意时刻可调**——快过期或已过期都行，只要会话有效）、`startSessionMaintenance()`/`stopSessionMaintenance()`（10min keepalive + token 到期前一次性续期定时器 + `visibilitychange` 回前台补跑）。登录成功自动 start，登出 stop；**页面刷新恢复已有会话时也要各调一次 start**。定时器是「提前续」的优化；要彻底无缝（token 过期后下一次请求即自动恢复），可在 SPA 的 HTTP 拦截器里对 401 调 `renewAccessToken()` 并重试一次。

---

## 认证与令牌流程

### 表单登录（管理端 / 授权页）

- 入口 `/login.html`，提交到 `/user/login`（参数 `username`/`password`）。
- `UserDetailsService` 从 DB 查用户（`PlatformUserMapper`），校验状态，加载租户域角色（`SecurityRole`），BCrypt 比对密码。
- 登录成功走 `LoginSuccessHandler`：更新最后登录时间，返回 JSON `{ userinfo, requestURI }`（回跳地址；没有待续的授权请求时为 `/`，同源部署下即平台前端首页）。

### 授权码 + PKCE

标准 SAS 流程；`AuthorizationServerConfig` 里配置了自定义同意页 `/oauth2/consent`。客户端 `requireProofKey=true` 时自动追加 `none` 认证方式（PKCE 公共客户端）。

### 自定义 claim：`data`（下游鉴权的唯一依据）

**只有内置客户端（`platform.client.builtin=true`，即平台前端）的 token 带 `data`**。`TokenClaimsPolicy` 按客户端类别分三种：

| 类别 | 条件 | 自定义 claim |
|------|------|------|
| 平台用户 | 内置客户端 + 有用户 | `data`（下表） |
| 外部用户 | 外部客户端 + 有用户 | 只有 `name`（显示名，空则用户名）；`sub` 为用户名 |
| 无用户 | client_credentials | 无（principal 是客户端本身，不查用户） |

外部 token 的 `aud` 是它自己的 client_id，平台资源服务（只认 `platform-web-client`）会 401 —— 这是有意的。

签发时由 `OAuth2TokenCustomizer`（`AuthorizationServerConfig#jwtTokenCustomizer`）为内置客户端写入 `data` claim，其内容是 `TokenUserInfo`：

| 字段 | 含义 | 谁在用 |
|------|------|--------|
| `username` / `displayName` / `email` / `status` | 用户基本信息 | 前端展示、`/user/me` |
| `platformRoles` | **平台域角色 code 列表**（如 `admin`）。签发期按 `platform_role.scope='PLATFORM'` 过滤，**租户成员恒为空** | `platform-api` / `k8s-server` 据此判定"平台侧身份"（`PLATFORM_SCOPE`） |
| `tenantInfo` | **租户上下文**（仅 session-renewal 携带 `tenant_id` 时非 null，其它 grant 恒为 null） | 决定走租户模式还是管理员代管模式 |
| `permissions` | **当前上下文的权限点 code 闭包** = 平台族角色权限 ∪（有租户上下文时）该租户内角色权限 | `platform-api` 的表驱动授权按 code 匹配 authority |

查库逻辑集中在 `TokenExtrasService`（平台角色 code / 租户成员资格校验 / 权限闭包），避免把多个 mapper 塞进 customizer。

> **`token_exchange` 的平台侧定制已退役**：曾经对 `token_exchange` grant 额外从请求参数取 `tenant_id` 并注入租户 claim，该逻辑已随"租户上下文只由 session-renewal 表达"的收敛而移除。当前租户上下文的**唯一来源**是 session-renewal grant 的 `tenant_id` 参数；`token_exchange` 若仍被客户端注册，走的是 SAS 默认行为、不带平台租户语义。

> **权限闭包是"签发时刻"的快照**：改角色/权限后，**已签发的 token 不会自动更新** —— 要么等 token 过期，要么靠 session-renewal 重签（这也是"access_token TTL 要设小"的另一条理由）。

---

## 自定义 JWT 编码（JWS / JWE）

`AuthorizationServerConfig#jwtEncoder` 是一个自定义 `JwtEncoder`，**按客户端配置**决定如何签名/加密：

1. **标准 claims**（iss/sub/aud/scope/iat/exp/jti）由 `JwtGenerator` 生成；
2. **自定义 claim**：`jwtCustomizer` 写入 `data`（见上）与 `custom.client.setting`（客户端 JWT 配置，编码前会被剔除、不透传）；
3. **JWS 签名**：
   - 对称算法（HS*）——密钥以密文存库，用 `jweTokenStrategy.getData()` 解密后构造 `OctetSequenceKey`；
   - 非对称算法——从 `JWKSource` 按算法 + `sig` 用途匹配密钥。
4. **可选 JWE**：客户端 `jwtType=JWE` 时，`CustomJweEncoder` 用客户端提供的公钥/secret（JWK Set URL 或对称密钥）对已签名 JWT 再加密。

每个客户端的算法/密钥/kid 存在 `ClientSettings` 的扩展 map 里（键见 `CustomClientSetting`）。对称密钥**由服务端生成**（长度按算法：HS256/384/512 = 32/48/64 字节、A128/192/256KW = 16/24/32、dir 按内容加密算法），用 auth 的加密公钥做 JWE 后落库（`ClientSecretSealer`），明文只在创建 / 重置 / 改算法的响应里出现一次；kid 每换一把密钥就换。

---

## 会话存储（Redis）与 Spring Boot 4.x 注意点

- **Boot 4.x 移除了 `spring.session.*` 整套自动装配**（3.x 里加 starter 即生效，4.0.6 的 autoconfigure 中已无相关类）。因此必须用 `RedisSessionConfig` 上的 `@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 28800)` **显式启用**；只加依赖不启用 → 会话仍是内存态，Redis 里不会有 key。
- `spring.session.*` 属性在 4.x 无效（IDE 标红属正常）；`spring.data.redis.*` 前缀不变、仍有效。
- Redis 会话用 **JDK 序列化**存属性（含 `SPRING_SECURITY_CONTEXT`）。整条图里只有自定义的 `SecurityRole` 不可序列化 → 已让其 `implements GrantedAuthority, Serializable`（在 `platform-data`）。注意「实现继承 Serializable 的接口」不等于类可序列化，必须类自己声明。
- **滑动语义**：Redis 会话 TTL 只在「会话被修改并保存」时重置，光读不刷新。所以 `/session/keepalive` 会 `setAttribute(...)` 触发一次保存来滑动 TTL——这是 SPA 侧唯一的续命手段。
- **连接保活（`RedisConnectionKeepalive`）**：Lettuce 对同步命令使用一条**常驻共享连接**（`shareNativeConnection` 默认开启）。应用长时间无会话读写时，Redis 服务端或中间网络设备会因空闲而 RST 关闭这条 TCP 连接；下一次续期在**已被对端关闭的僵尸连接**上发的首个命令（`hGetAll` 读 session）即抛 `SocketException: Connection reset`，表现为「偶发跳登录、重试一次就好」。该类定时 PING 这条共享连接，既让设备的空闲超时不会触发，也在被断开时于 `fixedDelay` 内触发 Lettuce 自动重连，使用户到达时连接已恢复健康。

---

## HTTP 端点

### OAuth2 / OIDC 标准端点（SAS 提供）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST | `/oauth2/authorize` | 授权端点（含自定义同意页跳转） |
| POST | `/oauth2/token` | 令牌端点 —— 支持 authorization_code / refresh_token / **session-renewal**（平台侧租户上下文的唯一来源） |
| GET | `/oauth2/jwks` | JWK Set（供资源服务器验签） |
| GET | `/.well-known/oauth-authorization-server` | OAuth2 发现文档 |
| GET | `/oauth2/.well-known/openid-configuration` | OIDC 发现文档 |
| GET/POST | `/userinfo` | OIDC 用户信息端点 |

### 应用端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/login` | 表单登录（白名单放行） |
| POST | `/session/logout` | 登出 —— **无 controller**，由 `SecurityFilterChain` 的 `LogoutFilter` 处理（排在授权过滤器之前，会话已过期时重复调用是幂等 no-op） |
| GET | `/session/keepalive` | 会话滑动续期（需已登录） |
| POST | `/internal/clients/{list,get,scopes,create,update,delete,enable,reset-secret}` | 客户端管理**内部接口**：只经平台网关（浏览器 `/api/oauth-clients/*`），带用户 JWT；接口级权限由网关判、写操作在此记审计；nginx 前门对 `/auth/internal/` 一律 404 |
| POST | `/internal/jwk/generate` | 生成签名/加密密钥（原 `/jwk/generate`）；同上只经网关，码 `platform:jwk:manage` 只绑 admin |
| GET | `/oauth2/consent` | 授权同意页（重定向到 `consent.html`） |
| GET | `/consent/details` | 待批准 / 已批准的 scope 明细 |
| GET | `/callback` | 测试用回调（打印 code，非核心） |

> 白名单（`spring.security.ignore-urls`）：登录/注册/找回密码页、静态资源、Swagger/Knife4j 文档、`.well-known` 等，见 `application.yaml`。

---

## 客户端管理与 Scope

设计见 `docs/superpowers/specs/2026-11-03-oauth-client-management-in-platform-design.md`。

- **入口**：平台「组织 → OAuth 客户端」页 → platform-api `/oauth-clients/**`（权限 `platform:oauth-client:view|manage`，只绑 admin；写操作进审计）→ 本模块 `/internal/clients/**`。写逻辑留在这里，因为对称密钥要用 auth 自己的加密密钥落库。
- **内置客户端**（`platform.client.builtin=true`，迁移 `V2026_11_03_2` 给 `platform-web-client` 打标）：不可删除、不可停用，只能改回调地址、登出回调与 access token TTL（< 会话空闲超时）。
- **外部客户端**（能力与旧管理页对齐：可以不配，但不删）：
  - 机密：认证方式 `client_secret_basic` / `client_secret_post` / `private_key_jwt`（可兼带 `none`），授权方式可选授权码、刷新令牌、客户端凭据、设备码、会话续期（Token 交换已于 RBAC 改造时退役，不再提供；老行若带着它保存时原样保留）；授权码可关 PKCE。
  - 公共：认证方式固定 `none`，授权方式只有授权码（强制 PKCE）与会话续期 —— SAS 只对「PKCE 换 token」请求做公共客户端认证，设备码等走不通。
  - `private_key_jwt` 与非对称 JWE 共用对方的 JWK Set 地址；`refresh_token` 须伴随授权码或设备码；建后类型与 client_id 不可改；client_id 留空则生成 `ext-` + 12 位。
  - 签名 RS256/384/512、ES256/384/512、HS256/384/512（非 RS256 的非对称算法要求平台已有对应 JWK）；JWE 含 `A*KW` / `A*GCMKW` / `dir` / `RSA-OAEP*` / `ECDH-ES*`。
  - 密钥一律服务端生成、只显示一次；client_secret 不设过期时间，重置时清掉老行遗留的过期时间。
  - 老行里库内已有、但不在可选项里的值（旧页面写进去的 scope / 授权方式 / 认证方式）保存时原样保留。
- **停用**：`platform.client.enabled=false` → 主仓库（`EnabledOnlyRegisteredClientRepository`）返回 null，授权、换 token、refresh、续期都得到 `invalid_client`；已签出的 token 自然到期。
- **scope 目录**（`PlatformScopes`）：只有 `openid` / `profile` —— 外部 token 目前只带 `sub` + `name`。旧页面的 email / phone / address / message.* 没有对应数据，经用户确认先去掉，要用时连同含义再补；老客户端身上已有的保存时原样保留。
- **不回显密钥**：列表 / 详情只给 `hasClientSecret` / `hasJwsSecret` / `hasJweSecret` 与 kid。

---

## 配置说明（application.yaml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | `9527` | 服务端口 |
| `server.servlet.context-path` | `/auth` | 全局上下文路径；前端 `VITE_OAUTH_ISSUER=/auth`、platform-api/k8s-server 的 `OAUTH2_SERVER` 都要带这个前缀 |
| `spring.security.oauth2.authorizationserver.issuer` | `${OAUTH2_SERVER:http://127.0.0.1:9527}` | 兜底配置项；实际 issuer 由 `AuthorizationServerSettings`（未固定）按请求现算，含 `/auth` |
| `spring.datasource.*` | MySQL `192.168.31.80:1234/k8s_cloud_platform` | 用户/角色/客户端/授权/JWK 持久化（可用 `MYSQL_URL/USERNAME/PASSWORD` 覆盖） |
| `spring.data.redis.*` | `192.168.31.80:6379`, database `1` | Redis 会话存储（可用 `REDIS_HOST/PORT/PASSWORD` 覆盖） |
| `jwt.enabled` / `jwt.issuer` / `jwt.data-key` | `true` / 同 issuer / `data` | JWT 组件开关、issuer、自定义用户 claim 的键名 |
| `spring.security.ignore-urls` | 见 yaml | 免登录放行的 URL 白名单 |
| `platform.internal.jwt.audiences` | `${JWT_AUDIENCES:platform-web-client}` | `/internal/**` 校验 JWT 的 aud 白名单（逗号分隔）；**留空拒启**；与 platform-api / 网关同一个值 |
| Knife4j / springdoc | `/doc.html`, `/v3/api-docs` | 接口文档（中文） |

> ⚠️ **Redis 会话超时不在此文件**：由 `RedisSessionConfig` 的注解常量 `SESSION_MAX_INACTIVE_SECONDS`（8h）控制。

---

## 依赖说明（pom.xml）

| 依赖 | 用途 |
|------|------|
| `spring-boot-starter-oauth2-authorization-server` | OAuth2 / OIDC 授权服务器核心 |
| `spring-boot-starter-webmvc` | Web MVC |
| `spring-boot-starter-actuator` | 健康检查 / 监控端点 |
| `spring-boot-starter-data-redis` | Lettuce 连接（自动装配 `LettuceConnectionFactory`） |
| `spring-session-data-redis` | 把 HttpSession 存到 Redis |
| `caffeine` | 本地缓存（JWK Set 等） |
| `knife4j-openapi3-jakarta-spring-boot-starter` | 接口文档 UI |
| `platform-common` | 公共组件（JWT 编解码、异常/响应码、模型、工具）；传递引入 `platform-data` 的 Mapper/实体 |

---

## 数据库表

本模块读写的主要表（DDL 见仓库根 `../docs/dbback/k8s_cloud_platform.sql`）：

- `oauth2_registered_client` —— 客户端注册信息（SAS 标准结构，client_settings / token_settings 为 JSON）。
- `oauth2_authorization` / `oauth2_authorization_...` —— 授权与令牌持久化。
- `oauth2_authorization_consent` —— 用户对各客户端的 scope 同意记录。
- `platform_user` / `platform_role` / 用户-角色、租户相关表 —— 登录与鉴权数据源。
- `oauth2_jwk` —— JWK 密钥（kid、kty、use、alg、jwk_json、有效期、优先级）。

---

## 构建与运行

**前置**：JDK 21 · Maven · 可访问的 MySQL 与 Redis。

```bash
# 在仓库根目录，构建本模块及其依赖（platform-common / platform-data 等）
mvn -pl platform-auth -am package

# 或直接编译校验
mvn -pl platform-auth -am compile
```

运行：启动 `com.coding.auth.PlatformAuthApplication`（IDE 或 `java -jar target/platform-auth-1.0.0.jar`）。

访问：

- 客户端管理页：`http://127.0.0.1:9527/auth/`（先登录）
- 接口文档（Knife4j）：`http://127.0.0.1:9527/auth/doc.html`
- OIDC 发现：`http://127.0.0.1:9527/auth/.well-known/oauth-authorization-server`

> **本地 Maven 仓库**在本环境为 `D:\repository`（非默认 `~/.m2`）；属性名存疑时优先「写代码 → 编译」验证，`javap`/`unzip` 在这套 Windows 环境有摩擦。

---

## 安全不变式与注意事项

1. **token TTL < session TTL**：注册时强制（见上）。安全上还应把 access_token 绝对值设小（分钟级）——它管「泄露危害面」和「禁用/改角色多快生效」；session 才管「能登录多久」。
2. **SPA 跨域带 Cookie**：CORS 已 `allowCredentials`（`SecurityConfig#corsConfigurationSource`）。但会话 Cookie 能否跨站发出去取决于部署拓扑——SPA 与 platform-auth **同 host 不同端口**（如都 localhost）时默认 `Lax` 即可；**不同域名**时 Cookie 需 `SameSite=None; Secure`，否则续期请求带不上 JSESSIONID。
3. **后台标签页定时器节流**：浏览器会节流/冻结后台 tab 的定时器，SPA 用 `visibilitychange` 回前台补跑 keepalive 兜底；若 tab 长时间（>8h）无活动导致会话过期，则需重登——这是根凭证模型的固有边界。
4. **JWK 存储**：对称密钥以密文存库、运行时解密使用；`oauth2_jwk.jwk_json` 建议加密存储（代码中已留提示）。
5. **测试端点**：`/callback`（重定向到外部域名打印 code）、`/test`（空实现）为调试用途，生产环境应移除或加保护。
