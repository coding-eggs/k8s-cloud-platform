# OAuth 客户端管理搬进平台 — 设计

日期：2026-11-03
状态：待用户审阅（已过一轮实现者视角评审）

## 1. 为什么做

platform-auth 里的客户端管理（`client-manage.html` + `/clients/**`）是早期为方便配置写的，**只要求登录、不判权限**，而平台与 auth 共用同一个用户系统。后果（均已在代码中确认）：

1. 任何租户用户登录 auth 后都能增删改客户端；登录成功的默认落点就是这个页面（`LoginSuccessHandler`）。
2. **可提权到管理员**：租户用户把自己控制的地址加进 `platform-web-client` 的回调地址 → 把授权链接发给已登录的管理员 → 授权码落到攻击者手里 → 换出 `aud=platform-web-client` 的管理员 token。2026-11-03 新加的 aud 白名单挡不住（被篡改的正是平台客户端本身），PKCE 也挡不住（流程由攻击者发起）。
3. 可删掉 `platform-web-client` 让全站登不上。
4. 列表接口把客户端的 `jwsSecret` / `jweSecret` 返回给任何登录用户（`ClientService.toRes`）。库里存的是用 auth 的 ECDH 公钥加密过的 JWE 密文（前端提交前加密、auth 使用时解密），所以泄露的是密文而非明文，但密钥材料本就不该出现在任何读接口里。
5. auth 给**每个客户端**签的 token 都写入平台内部信息（`data` claim：platformRoles、email、displayName、status 等），外部系统也能拿到「谁是平台管理员」。

## 2. 用户意图（来自讨论）

- 使用者**只有平台内部管理员**。外部的人不自助注册；以后外部系统接入，是「自己人给外人建客户端」，把 client_id / 密钥交给对方。
- 现在实际只用来维护平台前端自己的客户端（`platform-web-client`）。
- 外部客户端**建好即可用平台账号做三方登录（SSO）**。
- 外部客户端的 token **只留显示名**，平台内部信息一律不塞。
- 外部客户端停用 / 删除后，已签出的 token 与会话**接受自然过期**，不做即时吊销。
- 「第三方代表用户调平台接口」以后打算做，**这次不做**，但不能把路堵死（见 §8）。后续可能还会开发专门给外部的接口。
- 现有的 token 形态能力（per-client 的 HMAC 对称签名、JWE 对称 / 非对称加密）与 client_credentials **是完好功能，保留并在新页面继续提供**（用户明确否决了删除）。

## 3. 目标 / 非目标 / 反目标

**目标**
- 客户端管理页进平台（platform-web），后端在 platform-api，走平台权限表 + 审计日志。
- auth 只保留登录页、授权确认页、OAuth/OIDC 协议端点；管理页与 `/clients/**` 删除。
- 内置客户端（平台前端）受保护：不可删、不可停用、只放开少数字段。
- 外部客户端 token 只含身份 + 显示名。

**非目标**
- 第三方调平台接口（业务 scope、按 scope 收窄权限、aud 分类）。
- 停用 / 删除后即时吊销 token 或会话。
- 设备码授权。
- 资源服务（platform-api / k8s-server）侧对外部 token 的接收与解密——将来开放外部接口时再设计。

**反目标（做成这样算失败，哪怕能跑）**
- 未持有客户端管理权限码的人能看到或调用客户端管理（默认只有 admin 持有，见 §4.9）。
- 管理员在页面上能把客户端改成「内置」，或把外部客户端配出带平台内部信息的 token。
- 改错内置客户端的某个字段导致全站登不上（授权方式、PKCE、认证方式等必须锁死）。
- 列表 / 详情接口返回任何密钥材料（明文或密文都不返回，只返回「已配置」与 kid）。
- 任何对称密钥以明文落库。

## 4. 已定的做法

### 4.1 客户端分两类，由平台决定、页面不可改

- **内置**：目前只有 `platform-web-client`。由迁移脚本打标，页面上不能新建内置客户端，也不能改标记。
- **外部**：管理员在页面上新建的都是外部。
- 标记存在客户端自身（`client_settings` 里一个平台自定义键，键名由实现定），auth 的 token customizer 与 platform-api 都按它判断。

### 4.2 外部客户端可配置项

| 项 | 规则 |
|---|---|
| 类型 | **机密**（有后端，client_secret_basic + 密钥）或 **公共**（SPA / App，认证方式 none，无密钥）。两种都要支持（用户：「需要啥样的就建啥样的」）；**创建时选定、之后不可改**（设计方决定） |
| 名称 | 必填 |
| client_id | 创建时生成或填写（实现定），创建后不可改 |
| 回调地址 / 登出回调地址 | 多行；回调至少一个 |
| 需要用户确认 | 默认开 |
| access token 有效期 | 有下限 |
| 密钥 | 仅机密客户端有：创建时 / 「重置密钥」时**只显示一次**，之后任何接口都不返回明文；公共客户端无密钥、无重置按钮 |
| 启用 / 停用 | 停用后不能再发起授权、不能换 token；已签出的 token 自然过期 |
| 授权方式 | 机密客户端可勾选：授权码、刷新令牌、client_credentials；公共客户端只有授权码。凡启用授权码一律**强制 PKCE**（设计方决定）。公共客户端没有刷新令牌（SAS 不给公共客户端发） |
| token 形态 | 保留现有能力：默认 JWS（auth 的 RSA 密钥，JWKS 可验）；可选 JWS 对称签名（HS256/384/512，沿用现有密钥长度校验）、JWE 对称加密（共享密钥）、JWE 非对称加密（客户端提供 `jwkSetUrl`，RSA/ECDH，`CustomJweEncoder` 已实现）。对称密钥（HMAC 签名密钥、JWE 共享密钥）**改由 auth 服务端按算法要求的长度生成**，与 client_secret 一样只在创建 / 重置时显示一次；**库里只存 auth 加密后的密文**（存储格式不变，签发时解密）；读接口不回任何密钥材料（设计方决定，见 §4.10） |
| scope | 集中定义的可选列表（§8）。本次与用户授权相关的只有 `openid`、`profile`（外部 token 本就只给显示名，`email/phone/address` 无数据可给，去掉）；旧的演示 scope `message.read/write` 去掉，将来的业务 scope 加到同一列表 |

### 4.3 内置客户端

- 不可删除、不可停用。
- 只可改：回调地址、登出回调地址、access token 有效期。
- 授权方式（含 session-renewal）、PKCE、认证方式、scope、确认开关全部只读展示。

### 4.4 token 内容按类别区分（改 platform-auth 的 token customizer）

- 内置客户端：维持现状（`data` claim 照旧，平台前后端判权依赖它）。
- 外部客户端：access token / id_token / userinfo 只含 `sub`（用户名）与显示名（建议用 OIDC 标准 `name`）；**不写 `data` claim**，不含角色、租户、邮箱、状态、权限。
- **无用户的 token**（client_credentials）：没有用户，customizer 不查用户、不写 `data` claim，`sub` 为 client_id。现状 customizer 无条件把 principal 强转为 `User`，client_credentials 会抛 ClassCastException，这次一并修好。
- 外部客户端的 token 调 platform-api / k8s-server 一律 401——已由 2026-11-03 的 aud 白名单保证（`PlatformJwtValidators`，`audiences` 默认 `platform-web-client`），本设计不改它。

### 4.5 权限与审计

- 新权限码（建议 `platform:oauth-client:view` / `platform:oauth-client:manage`，按现有权限表约定定稿），迁移里**显式绑定 admin 角色**（新权限行不按 code 继承）。
- 所有写操作（建、改、删、停用 / 启用、重置密钥）挂审计注解；审计内容不得含密钥。
- 页面挂在平台视图（管理员专属）下，菜单由路由 meta 派生，按 view 码门控。

### 4.6 auth 侧收尾

- 删除 `client-manage.html`、对外的 `ClientController` 与演示 scope 列表。
- **写客户端表的逻辑留在 auth**（`ClientService` 加固后改为只对 platform-api 开放的内部接口 `/internal/clients/**`，共享密钥鉴权、未配置即拒启）；platform-api 负责权限、审计与对前端的接口，转调 auth。理由：密钥加解密依赖 auth 自己的加密私钥，搬到 platform-api 就得把私钥也带过去（设计方决定，评审后补）。
- 签发侧保留：`CustomJweEncoder`、`jwtEncoder` 的 HMAC / JWE 分支、`RemoteJwkSetCache`、加密密钥的解密（`jweTokenStrategy`）全部保留。
- `LoginSuccessHandler` 的默认跳转不再指向管理页（无 saved request 时落到平台前端首页）。
- 授权确认页保留；确认页展示的 scope 说明与 §4.2 对齐。
- 顺手：`/test/**` 从放行清单移除、`TestController` 删除。

### 4.7 数据与生效

- auth 与 platform-api 同库 `k8s_cloud_platform`，客户端表即 SAS 的 `oauth2_registered_client`；SAS 读客户端每次查库无缓存，**平台侧写入即时生效**，auth 不需重启或通知。
- 迁移 `V2026_11_03_x`：给 `platform-web-client` 打内置标记 + 新权限行与 admin 绑定。本仓**没有 Flyway，需手工重放**。

### 4.8 旧数据（设计方决定，评审后补）

- **迁移脚本不改写非内置客户端的行**。它们在运行时一律按「外部」处理（不带 `data` claim），token 形态设置（HMAC / JWE）照旧生效；不再提供的演示 scope 在新页面打开时如实展示，**保存时按 §4.2 规整**。
- 已知旧行：`gateway-code-client`（回调 `127.0.0.1:8080`，HS256 对称签名 + 演示 scope）是早期演示客户端，是否保留由部署时核对决定（§6）。
- 注意：外部客户端若配了 HMAC / JWE，它的 token 本就不会被平台资源服务接收（aud 白名单 + 资源服务只配了 JWKS 验签）——这只影响「调平台接口」，不影响三方登录。将来开放外部接口时，资源服务需按客户端类别配置对应的验签 / 解密，属 §8 的范围。

### 4.9 权限码可分配性（设计方决定，评审后补）

- `platform:oauth-client:manage` 按现有约定处理：平台角色可持有任意码，迁移只绑定 admin；**接受持有者等同 root**（能改内置客户端回调地址 = 能拿到管理员 token），与 `role:manage` 已被接受为事实 root 同一口径。不做「仅限 admin、不可分配」的特殊机制，在权限说明里写明风险即可。

### 4.10 对称密钥改为服务端生成（设计方决定，交计划时请用户确认）

旧页面由管理员输入对称密钥，浏览器用 jose（WebCrypto）以 auth 公钥做 JWE 加密后提交。平台在内网以 http 访问时 `crypto.subtle` 不存在（PKCE 已踩过），新页面照搬会在内网直接失败。改为 auth 服务端生成（长度满足 HS256/384/512 与 JWE 算法要求），只显示一次、可重置。能力不减：仍是 per-client 的对称签名 / 加密，只是密钥由平台发给对方，而不是对方提供给平台。

### 4.11 停用的实现

停用标记存在 `client_settings`（平台自定义键）。auth 用一个包在 `JdbcRegisteredClientRepository` 外面的仓库：按 id / client_id 查到**已停用**的客户端时当作不存在 → 授权端点、token 端点（含刷新、session-renewal）一律 `invalid_client`。管理接口读写用未包装的仓库，以便看到并重新启用它。

## 5. 留给实现者的

- 内置标记的具体键名；client_id 生成规则；token 有效期上下限数值。
- 页面布局（列表 + 抽屉 / 弹窗），沿用平台现有组件与约定（一页一 controller）。

## 6. 部署与迁移注意

- 顺序：重放迁移 → 重建 platform-auth、platform-api、platform-web → 管理员重新登录。
- 现有库里除 `platform-web-client` 外的客户端（如历史的 `gateway-code-client` 或测试客户端）会自动归为「外部」：token 不再带内部信息、授权方式按 §4.2 收窄。上线前应在新页面逐个核对，不用的删掉。
- 与 2026-11-03 的 aud 白名单改动同批上线。

## 7. 验收

- 非管理员：菜单不可见、接口 403；auth 上已无 `/clients/**` 与管理页（404）。
- 内置客户端：删除 / 停用 / 改锁定字段均被后端拒绝（不只是前端隐藏）。
- 机密、公共两类各建一个；公共客户端无密钥字段、无重置按钮。
- 机密客户端分别配 HS256 签名、JWE 对称、JWE 非对称（指向一个测试 JWK URL）三种 token 形态，各签发一次并能按对应密钥验 / 解；库里对称密钥是密文，读接口不返回任何密钥材料。
- 机密客户端启用 client_credentials 后能换到 token：`sub`=client_id、无 `data` claim、不报错。
- 新建外部客户端 → 用一个测试 RP 走完授权码 + PKCE → 拿到的 token 只有 `sub` 与显示名；该 token 调 platform-api 401。
- 密钥只在创建 / 重置响应里出现一次；列表、详情、审计记录中都没有。
- 停用后该客户端无法发起新授权。

## 8. 给将来「第三方调平台接口」留的口子

本次不实现，只要求不堵路：
- 客户端分类是可扩展的（将来可增「可调用平台 API」类，签 `aud=platform-api` 一类的统一受众，资源服务白名单按类别放行）。
- scope 列表是集中定义的，将来加业务 scope（如 `k8s.read`）时只需扩展列表；资源服务届时按「用户权限 ∩ token scope」收窄。
- 外部 token 不带 `data` claim 的规则保持不变——将来的 API 调用类客户端也不该依赖 token 里的内部信息，判权仍由资源服务现算。
