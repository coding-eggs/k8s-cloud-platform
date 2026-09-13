# platform-auth

K8s 云平台的**统一身份认证与授权服务**：基于 Spring Authorization Server（SAS）实现的 OAuth2 / OIDC 授权服务器，负责用户登录、客户端（RegisteredClient）管理、令牌签发（支持按客户端自定义 JWS/JWE 编码）、JWK 密钥管理，以及一个为 **PKCE 公共客户端**量身打造的**会话续期（session-renewal）自定义授权类型**。

- **运行端口**：`9527`
- **Issuer（默认）**：`http://127.0.0.1:9527`（可用 `OAUTH2_SERVER` 覆盖）
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

- **管理端页面**（本模块自带的 `client-manage.html` / `login.html` / `consent.html`）——服务端渲染的静态页，走表单登录。
- **业务 SPA**（独立工程 `platform-web`，Vue 3 + Vite）——以 OAuth2 公共客户端（PKCE）身份对接本模块，是 session-renewal grant 的主要使用方。

---

## 核心能力

1. **用户认证**：DB 账号 + BCrypt 密码的表单登录；用户/角色/租户来自 `platform-data`。
2. **OAuth2 / OIDC 授权服务器**：标准 `authorization_code`（含 PKCE）、`refresh_token`、`token_exchange`，OIDC 发现文档与 `/userinfo`。
3. **会话续期 grant（自定义）**：`urn:coding:grant-type:session-renewal` —— 解决 PKCE 公共客户端拿不到 refresh_token 的续期问题（详见下文「关键设计」）。
4. **按客户端自定义 JWT 编码**：每个客户端可独立配置 JWS 签名算法（对称/非对称）与可选的 JWE 加密。
5. **JWK 密钥管理**：生成签名 / 加密密钥对并入库，供 SAS 的 `/oauth2/jwks` 使用。
6. **客户端注册管理**：`RegisteredClient` 的增删改查（页面 + REST），含 scope 目录与「token TTL < session TTL」注册约束。
7. **Redis 会话存储**：登录态（根凭证）存 Redis，支持多实例 / 重启不丢；8h 空闲超时 + keepalive 滑动续期。

---

## 目录结构

```
src/main/java/com/coding/auth/
├── PlatformAuthApplication.java        # Spring Boot 入口
├── config/
│   ├── SecurityConfig.java             # 默认安全链：表单登录、DB 用户、会话过期处理、CORS
│   ├── AuthorizationServerConfig.java  # OAuth2 AS 链（最高优先级）、客户端/授权持久化、自定义 JwtEncoder
│   ├── RedisSessionConfig.java         # 显式启用 Redis HTTP 会话（Boot 4.x 必须手写，见下文）
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
│   ├── ClientController.java           # /clients —— RegisteredClient CRUD + scope 目录
│   ├── SessionController.java          # /session/keepalive —— 会话滑动续期
│   ├── AuthorizationConsentController.java # /oauth2/consent、/consent/details —— 授权同意页
│   ├── JwkController.java              # /jwk/generate —— 生成并入库密钥对
│   ├── ViewController.java             # / → client-manage.html
│   ├── CallbackController.java         # /callback —— 测试用回调（打印 code）
│   └── TestController.java             # /test —— 调试端点（空实现）
├── service/
│   └── ClientService.java              # 客户端 CRUD + 校验（含 token TTL < session TTL 约束）
└── client/
    ├── RegisteredClientReq.java        # 客户端注册请求 DTO
    ├── RegisteredClientRes.java        # 客户端响应 DTO（secret 脱敏为 ****）
    └── OAuth2Scope.java                # scope 名称 + 描述

src/main/resources/
├── application.yaml                    # 端口 / issuer / MySQL / Redis / 放行白名单 / 文档
└── static/                             # login.html · consent.html · client-manage.html + assets(axios/element-plus/jose/app.js)
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

> **不变式：access_token TTL 必须严格小于会话空闲超时。** 否则会出现「session 已死但 token 还有效」的孤儿窗口——用户活跃时反而被踢、被迫重登。该约束在**客户端注册时强制校验**（`ClientService#validate`，超限抛 `OAUTH2_CLIENT_TOKEN_TTL_EXCEEDS_SESSION`=90015；前端 `client-manage.html` 同步校验）。会话超时的唯一来源是常量 `RedisSessionConfig.SESSION_MAX_INACTIVE_SECONDS`（28800s），注解与注册校验都引用它，避免两处漂移。

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
- 登录成功走 `LoginSuccessHandler`：更新最后登录时间，返回 JSON `{ userinfo, requestURI }`（回跳地址，默认 `/client-manage.html`）。

### 授权码 + PKCE

标准 SAS 流程；`AuthorizationServerConfig` 里配置了自定义同意页 `/oauth2/consent`。客户端 `requireProofKey=true` 时自动追加 `none` 认证方式（PKCE 公共客户端）。

### token_exchange（携带租户上下文）

`jwtTokenExchangeCustomizer` 对 `token_exchange` grant 额外从请求参数取 `tenant_id`，查询并写入租户信息到 claim——供跨租户场景使用。

---

## 自定义 JWT 编码（JWS / JWE）

`AuthorizationServerConfig#jwtEncoder` 是一个自定义 `JwtEncoder`，**按客户端配置**决定如何签名/加密：

1. **标准 claims**（iss/sub/aud/scope/iat/exp/jti）由 `JwtGenerator` 生成；
2. **自定义 claim**：`jwtTokenExchangeCustomizer` 写入 `data`（用户信息 + 平台域角色，token_exchange 时再加租户）与 `custom.client.setting`（客户端 JWT 配置，编码前会被剔除、不透传）；
3. **JWS 签名**：
   - 对称算法（HS*）——密钥以密文存库，用 `jweTokenStrategy.getData()` 解密后构造 `OctetSequenceKey`；
   - 非对称算法——从 `JWKSource` 按算法 + `sig` 用途匹配密钥。
4. **可选 JWE**：客户端 `jwtType=JWE` 时，`CustomJweEncoder` 用客户端提供的公钥/secret（JWK Set URL 或对称密钥）对已签名 JWT 再加密。

每个客户端的算法/密钥/kid 存在 `ClientSettings` 的扩展 map 里（键见 `CustomClientSetting`），由 `client-manage.html` 表单维护，注册时对密钥长度做校验（HS256≥32B、A128GCM=16B 等）。

---

## 会话存储（Redis）与 Spring Boot 4.x 注意点

- **Boot 4.x 移除了 `spring.session.*` 整套自动装配**（3.x 里加 starter 即生效，4.0.6 的 autoconfigure 中已无相关类）。因此必须用 `RedisSessionConfig` 上的 `@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 28800)` **显式启用**；只加依赖不启用 → 会话仍是内存态，Redis 里不会有 key。
- `spring.session.*` 属性在 4.x 无效（IDE 标红属正常）；`spring.data.redis.*` 前缀不变、仍有效。
- Redis 会话用 **JDK 序列化**存属性（含 `SPRING_SECURITY_CONTEXT`）。整条图里只有自定义的 `SecurityRole` 不可序列化 → 已让其 `implements GrantedAuthority, Serializable`（在 `platform-data`）。注意「实现继承 Serializable 的接口」不等于类可序列化，必须类自己声明。
- **滑动语义**：Redis 会话 TTL 只在「会话被修改并保存」时重置，光读不刷新。所以 `/session/keepalive` 会 `setAttribute(...)` 触发一次保存来滑动 TTL——这是 SPA 侧唯一的续命手段。

---

## HTTP 端点

### OAuth2 / OIDC 标准端点（SAS 提供）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST | `/oauth2/authorize` | 授权端点（含自定义同意页跳转） |
| POST | `/oauth2/token` | 令牌端点 —— 支持 authorization_code / refresh_token / token_exchange / **session-renewal** |
| GET | `/oauth2/jwks` | JWK Set（供资源服务器验签） |
| GET | `/.well-known/oauth-authorization-server` | OAuth2 发现文档 |
| GET | `/oauth2/.well-known/openid-configuration` | OIDC 发现文档 |
| GET/POST | `/userinfo` | OIDC 用户信息端点 |

### 应用端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/login` | 表单登录（白名单放行） |
| POST/GET | `/user/logout` | 登出（白名单放行） |
| GET | `/session/keepalive` | 会话滑动续期（需已登录） |
| POST | `/clients/create` | 创建客户端 |
| POST | `/clients/update` | 更新客户端 |
| DELETE | `/clients/delete?id=` | 删除客户端 |
| GET | `/clients/getById?id=` | 查询单个客户端 |
| GET | `/clients/list` | 客户端列表 |
| GET | `/clients/scope-list` | 可选 scope 目录（含描述） |
| POST | `/jwk/generate` | 生成签名/加密密钥对并入库 |
| GET | `/oauth2/consent` | 授权同意页（重定向到 `consent.html`） |
| GET | `/consent/details` | 待批准 / 已批准的 scope 明细 |
| GET | `/` | → `client-manage.html`（客户端管理页） |
| GET | `/callback` | 测试用回调（打印 code，非核心） |
| GET | `/test` | 调试端点（空实现，非核心） |

> 白名单（`spring.security.ignore-urls`）：登录/注册/找回密码页、静态资源、Swagger/Knife4j 文档、`.well-known` 等，见 `application.yaml`。

---

## 客户端管理与 Scope

- **管理页**：`client-manage.html`（Element Plus + axios + jose），维护 grant 类型、认证方式、redirect URI、scope、token TTL、JWS/JWE 配置。
- **注册约束**：access_token TTL 必须 < 会话空闲超时（前后端双重校验）；授权码模式必须有 secret 或 PKCE + redirect URI；对称密钥长度按算法校验。
- **scope 目录**：`openid` / `profile` / `email` / `phone` / `address` / `message.read` / `message.write`（见 `ClientController#scopeList`）。
- **secret 脱敏**：查询返回时 client_secret 一律显示为 `****`。

---

## 配置说明（application.yaml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | `9527` | 服务端口 |
| `spring.security.oauth2.authorizationserver.issuer` | `${OAUTH2_SERVER:http://127.0.0.1:9527}` | issuer，必须与访问地址一致 |
| `spring.datasource.*` | MySQL `192.168.31.80:1234/k8s_cloud_platform` | 用户/角色/客户端/授权/JWK 持久化（可用 `MYSQL_URL/USERNAME/PASSWORD` 覆盖） |
| `spring.data.redis.*` | `192.168.31.80:6379`, database `1` | Redis 会话存储（可用 `REDIS_HOST/PORT/PASSWORD` 覆盖） |
| `jwt.enabled` / `jwt.issuer` / `jwt.data-key` | `true` / 同 issuer / `data` | JWT 组件开关、issuer、自定义用户 claim 的键名 |
| `spring.security.ignore-urls` | 见 yaml | 免登录放行的 URL 白名单 |
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

本模块读写的主要表（DDL 见仓库根 `k8s_cloud_platform.sql`）：

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

- 客户端管理页：`http://127.0.0.1:9527/`（先登录）
- 接口文档（Knife4j）：`http://127.0.0.1:9527/doc.html`
- OIDC 发现：`http://127.0.0.1:9527/.well-known/oauth-authorization-server`

> **本地 Maven 仓库**在本环境为 `D:\repository`（非默认 `~/.m2`）；属性名存疑时优先「写代码 → 编译」验证，`javap`/`unzip` 在这套 Windows 环境有摩擦。

---

## 安全不变式与注意事项

1. **token TTL < session TTL**：注册时强制（见上）。安全上还应把 access_token 绝对值设小（分钟级）——它管「泄露危害面」和「禁用/改角色多快生效」；session 才管「能登录多久」。
2. **SPA 跨域带 Cookie**：CORS 已 `allowCredentials`（`SecurityConfig#corsConfigurationSource`）。但会话 Cookie 能否跨站发出去取决于部署拓扑——SPA 与 platform-auth **同 host 不同端口**（如都 localhost）时默认 `Lax` 即可；**不同域名**时 Cookie 需 `SameSite=None; Secure`，否则续期请求带不上 JSESSIONID。
3. **后台标签页定时器节流**：浏览器会节流/冻结后台 tab 的定时器，SPA 用 `visibilitychange` 回前台补跑 keepalive 兜底；若 tab 长时间（>8h）无活动导致会话过期，则需重登——这是根凭证模型的固有边界。
4. **JWK 存储**：对称密钥以密文存库、运行时解密使用；`oauth2_jwk.jwk_json` 建议加密存储（代码中已留提示）。
5. **测试端点**：`/callback`（重定向到外部域名打印 code）、`/test`（空实现）为调试用途，生产环境应移除或加保护。
