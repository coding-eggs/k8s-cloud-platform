# 平台权限与鉴权体系设计（RBAC 管理 + 租户上下文签发）

- 日期：2026-09-24
- 状态：已评审（对话式逐条确认，待书面复核）
- 范围：platform-auth / platform-api / platform-data / platform-web；k8s-server 仅做减法（删死代码），不加逻辑

## 1. 背景与目标

平台已有租户 CRUD、命名空间分配（`platform_tenant_namespace` + RBAC 模板 + 各集群 SA）等能力，
但"谁能管什么"这一层只落地了一半：六张 RBAC 表已建，登录链路已读 `platform_user_role`（平台域）
与 `platform_user_tenant_role`（租户域），而管理面（用户/角色/权限/成员的 CRUD 与授权写入口）完全缺失，
后端接口除 `/admin/**` 外基本"登录即可调"。

目标：补齐管理面，建立**一套 RBAC、两个作用域（平台族 / 租户族）**的权限体系，
支撑"平台管理员代管（v1 主态）"与"业务团队租户自管（演进方向）"两种使用方式。

不改变既有架构原则：k8s-server 零业务逻辑、一页一 controller、不加外键（不变量由服务层守）、
K8s 侧只管资源边界。

## 2. 分层：两层权限，职责不重叠

| 层 | 管什么 | 权威数据 | 执行点 |
|---|---|---|---|
| **平台管理层**（本文新增） | 管理动作 + 页面可见：建租户、分配 ns、管用户、配角色、管成员 | `platform_permission`（domain = API/Page） | platform-api 的 `PermissionAuthorizationManager` |
| **K8s 资源边界层**（已有，不动） | 租户对分配到的 ns 里能执行哪些 K8s verb | `platform_rbac_template` + `platform_tenant_namespace` | k8s-server 的 `ResourceAccessResolver` + 各集群 RoleBinding |

关键语义（讨论定论）：

1. **权限点只编码平台管理动作**。`domain=K8S` 一类权限点本期不启用——K8s 动词已由模板
   （apiGroups/resources/verbs）精确表达，角色系统再编码一份会造成双权威。
2. **"分配 namespace" 是平台族动作**。租户当前没有配额维度，多划一个 ns = 平台在划资源；
   租户管理员只在已分配的边界内管人。
3. **同一租户内成员的 K8s 动词能力相同**（共享租户 SA），本期接受此限制：
   租户族角色不约束 K8s 动词。"按用户细分 K8s 权限"进 roadmap（§10）。

## 3. 数据模型

### 3.1 表关系

```
platform_permission ◄── platform_role_permission ──► platform_role (+scope, +built_in)
   domain=API/Page                                        │
   resource=URL 模式/前端路由        scope=PLATFORM ◄─────┴────► scope=TENANT
                                            │                        │
                                platform_user_role        platform_user_tenant_role
                                (user × role, 全局)        (user × tenant × role)
                                            │                        │
                                            └───► platform_user ◄────┘
                                                        │
                                          platform_user_tenant   ← 成员资格闸门
                                                        │
                                                platform_tenant
                                                        │
                                          platform_tenant_namespace
                                          (tenant × cluster × ns × role_template_id)
                                                        │
                                               platform_rbac_template   ← §2 边界层，本次不动
```

### 3.2 DDL 变更（仅一张表两列）

```sql
ALTER TABLE platform_role
  ADD COLUMN scope     varchar(16) NOT NULL DEFAULT 'PLATFORM' COMMENT '角色族：PLATFORM/TENANT',
  ADD COLUMN built_in  tinyint     NOT NULL DEFAULT 0  COMMENT '内置角色：不可删除、code/scope 不可改';
```

`platform_permission` 已有 domain/resource/action/code 四元组，够用，不加列。
**不加 owner 字段**——"租户负责人"= 该租户内持有 `tenant-admin` 角色的人，从
`platform_user_tenant_role` 反查即可。

### 3.3 种子数据（migration 维护，运行时只读）

**权限点目录**（code 命名规范：`<族>:<资源>:<动作>`，两族 code 互斥防混淆）：

平台族（domain=API，resource 为 URL 模式，action 存 HTTP 方法，本平台全 POST）：

| code | resource 示例 | 语义 |
|---|---|---|
| `platform:tenant:provision` | `/tenant/create`,`/tenant/update`,`/tenant/delete`,`/tenant/provision` | 租户生命周期 |
| `platform:tenant:read` | `/tenant/list`,`/tenant/get` | 租户全量查看 |
| `platform:allocation:manage` | `/tenant/namespace/allocate`,`/tenant/namespace/deallocate` | ns 分配 |
| `platform:cluster:manage` | `/cluster/**` | 集群管理 |
| `platform:user:manage` | `/user/create`,`/user/update`,`/user/delete`,`/user/resetPassword`,`/user/platformRole/**` | 平台用户与平台族授权 |
| `platform:role:manage` | `/role/**`,`/permission/**` | 角色↔权限配置、目录查看 |
| `platform:member:manage` | `/tenant/member/**`（与 `tenant:member:manage` 同 URL ANY-of 共存） | 代管：平台管理员替租户管成员（v1 主路径） |

租户族（仅授权给"带租户上下文"的请求，且服务层校验请求 tenantId == token 租户上下文）：

| code | resource 示例 | 语义 |
|---|---|---|
| `tenant:member:manage` | `/tenant/member/add`,`/tenant/member/remove`,`/tenant/member/role/grant`,`/tenant/member/role/revoke` | 管本租户成员与角色 |
| `tenant:overview:view` | `/tenant/member/list`,`/tenant/namespace/list`（限本租户） | 看本租户成员与边界 |

Page 域（后端不消费，仅供前端菜单/按钮显隐）：`page:tenant`,`page:user`,`page:role`,`page:cluster`
等资源页对租户用户全可见（§2.3 限制），v1 不细铺。

**内置角色**：

| 角色 | scope | built_in | 权限 |
|---|---|---|---|
| `admin`（平台管理员） | PLATFORM | 1 | 全部平台族（v1 粗交付；将来可拆"开通专员"等，零接口改动） |
| `tenant-admin`（租户管理员） | TENANT | 1 | `tenant:member:manage` + `tenant:overview:view` |
| `tenant-member`（租户成员） | TENANT | 1 | `tenant:overview:view` |

### 3.4 不变量（服务层同事务保证，不加外键）

1. **scope 隔离**：TENANT 角色只能关联 TENANT 族的权限点；PLATFORM 角色反之。
   种子加载与 `role/permission/save` 均校验（否则"租户管理员"可被勾出建租户权限）。
2. **成员资格先行**：写 `user_tenant_role` 前必须已有同 (user, tenant) 的 `user_tenant` 行。
   反之允许"已加入未定角色"中间态；删 `user_tenant` 行时级联删其 `user_tenant_role` 行。
3. **租户不能失去最后一个 admin**：移除/降级某租户最后一个 `tenant-admin` 必须同请求指定继任者。
4. **内置保护**：`built_in=1` 的角色不可删、code/scope 不可改；权限点目录运行时不可增删改
   （只有 migration 动它——权限点的 resource 字段是代码契约的一部分）。

## 4. 认证与 token 流转

### 4.1 grant 职责：session-renewal 单通道

| 动作 | 调用 |
|---|---|
| 登录（一次性） | PKCE `authorization_code` → base token（`tenantInfo` 为空，只含平台族角色） |
| 选择/切换租户 X | `session-renewal` + **新增参数 `tenant_id=X`** → 重新签发含租户上下文的 token |
| 退回平台视图 | `session-renewal`（不带 tenant_id）→ 回到 base token |
| access_token 到期自动续 | `session-renewal` + `tenant_id=<current_tenant，若有>` |
| 会话续命 | `/session/keepalive`（不变） |

原则：**身份续命与上下文切换永远从会话根（HttpOnly Cookie）出发，token 不再生 token**。

**token-exchange 退役**：`AuthorizationServerConfig.jwtTokenExchangeCustomizer` 中
`grant==TOKEN_EXCHANGE` 才 setTenantInfo 的分支删除（无任何调用方的僵尸代码，与 §8 删
`TenantValidateAspect` 同理）；`gateway-code-client` seed 的 `token-exchange` grant 一并移除。
租户上下文复用同一套"查 `selectTenantByUser` 补 `tenantInfo`"逻辑，挂到 session-renewal 上。
"透传→派生下游票"（gateway 用 exchange 换缩权短票）记入 roadmap（§10），届时重写。

### 4.2 auth-server 侧改动（三处，均为薄改动）

1. **`SessionRenewalAuthenticationConverter`**：解析请求里的可选 `tenant_id` 参数，
   放入 `SessionRenewalAuthenticationToken`。
2. **`SessionRenewalAuthenticationProvider`**：把含 `tenant_id` 的 grant 对象放进
   token context（现在未传自定义参数通道），使 customizer 可读。
3. **`jwtTokenExchangeCustomizer`（改名 `jwtTokenCustomizer`）**：租户分支条件改为
   "context 的 authorizationGrant 中 `tenant_id` 非空"（session-renewal 从此分支进来）；
   查 `user_tenant` 得 `UserTenantInfo` 补进 `data`。同时新增 permissions 闭包计算（§5.2）。

**错误语义**：带 `tenant_id` 但该用户不属于此租户 → 抛 `invalid_grant`（会话级错误，
SPA 提示"无权进入该租户"并回落 base 视图），**不可**静默签发无租户 token。

### 4.3 前端 token 状态（platform-web）

存储维持单 key，新增一个：

```
platform_access_token   ← 当前上下文 token（base 或含租户），唯一被 http.ts 使用
platform_current_tenant ← {tenantId, tenantName}，仅记录"下次续期带谁"，非凭证
```

- 新增 `switchTenant(tenantId | null)`：POST `/oauth2/token`
  （`grant_type=session-renewal & tenant_id=... & client_id=...`, `credentials:'include'`），
  成功→原子写 `platform_access_token` + `platform_current_tenant`（同一函数收口，禁止分开读写）。
- **续期定时器改造（`oauth.ts` `renewIfDue`）**：`current_tenant` 非空 ⇒ 每次 session-renewal
  必带 `tenant_id`。若漏带，定时器会在 token 到期时把租户上下文冲掉、用户被踢出当前租户——
  这是本设计最大的静默坑，代码上以"一个 renew 函数内读 tenant 再发请求"保证，评审点。
- 401/`UNLOGIN` 处理不变（回登录）；`invalid_grant` → 清 `current_tenant` 回落平台视图。

### 4.4 双轨：代管与自管

| | 平台管理员（代管，v1 主态） | 租户成员（自管，演进态） |
|---|---|---|
| token | base（无 tenantInfo） | 含 `tenantInfo` 上下文 |
| 看/操作某租户资源 | 每请求**显式传 `tenantId`**（`ResourceAccessResolver` adminMode 已实现） | token 即边界，伪造无效（签名租户为唯一来源） |
| 前端"租户"控件 | **筛选器**（改请求参数，不碰 token） | **切换器**（重签 token；单租户用户无感） |

平台管理员若本身也是某租户成员，可正常切租户（拿帽子 token），两轨互不干扰。

### 4.5 登录/进入流程（产品时序）

```
PKCE 登录 → base token
  ├─ platformRoles 含 admin → 进平台视图；看具体租户 = 筛选器
  └─ /user/my-tenants：
       0 个   → 平台视图（只读空态，提示联系管理员）
       1 个   → 自动 switchTenant(该租户)
       多个   → 弹租户选择（或顶栏切换）
```

开通流（平台管理员一步表单）：`POST /tenant/create` 带 `ownerUserId`，
服务层原子写 `platform_tenant` + `user_tenant` + `user_tenant_role(tenant-admin)` + ns 分配。
之后加人由租户 admin 自助，平台不再介入（"业务团队自己上"的兑现点）。

## 5. 授权执行（platform-api）

### 5.1 PermissionAuthorizationManager（表驱动，非注解）

- 新建 `PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext>`，
  配 `PermissionRegistry`（启动时加载 domain=API 权限点：URL 模式 + method → code 集合）。
- `ResourceServerConfig` 中 `.anyRequest().access(...)`，规则优先级：
  **命中权限行（多个 ANY-of）＞ 豁免前缀（仅 authenticated）＞ 默认拒绝**。
- 豁免前缀（v1 固定列表，代码内声明）：`/resource/**`（K8s 资源操作，边界在 §2 层）、
  `/user/me`、`/user/my-tenants`（登录即可）、`/callback`、`/error` 等。
- 权限点不做运行时编辑（§3.4.4），故注册表**启动加载、无失效通道**；随发布重启更新。
- converter 侧：`PlatformJwtAuthenticationConverter` 增加 `data.permissions[] → PERM:<code>`
  authorities（与既有 `PLATFORM:<code>` 前缀风格并列）。

### 5.2 token 内权限闭包（签发时算，请求时零查库）

`TokenUserInfo` 新增 `permissions: List<String>`。auth-server 签发时：

```
permissions = ∪(用户平台族角色的权限点)
            ∪ (tenantInfo 非空时：该租户内用户角色的权限点)
```

角色↔权限变更的生效时机 = 下次签发（自动续期 ≈ 分钟级），与既有 token 时效模型一致。

### 5.3 启动期交叉校验（表驱动方案的地基）

`SmartInitializingSingleton`：遍历 `RequestMappingHandlerMapping` 全部 endpoint ×权限表×豁免前缀：

- **endpoint 既无权限行也不在豁免内 → 启动失败**（防漏配静默裸奔）；
- 权限行匹配不到任何 endpoint → **WARN**（防重构后幽灵规则静默失效）；
- 资源路径中的 `{var}` 归一化为 `*` 后再比对（豁免前缀本身允许含 `{var}`——管理域禁用路径变量，见 §6.2）。

### 5.4 租户族接口的上下文一致性（双路径）

删旧 aspect（§8）后，租户成员管理接口在服务层做**二选一**放行（做成可复用的小工具/注解，不重造旧切面）：

- **自管路径**（token 含 `tenantInfo`）：请求 tenantId 必须 == token 租户上下文，不一致抛
  `TENANT_MISMATCH`（复用现有错误码）——服务端兜住伪造 body。
- **代管路径**（base token，无 tenantInfo）：凭 `platform:member:manage` 放行，
  tenantId 由请求显式指定（与 `ResourceAccessResolver` adminMode 同构）。

即 §3.3 表中 `/tenant/member/**` 的 ANY-of 两码各开一扇门：`tenant:member:manage`（帽子态）
或 `platform:member:manage`（base 态）。k8s-server 侧边界仍由 `ResourceAccessResolver` 独家负责。

### 5.5 k8s-server 侧（只做减法）

- 不装 `PermissionAuthorizationManager`——授权是 platform-api 的职责，k8s-server 只认边界（符合"零业务逻辑"）。
- 保留 `/admin/** → hasAuthority(PLATFORM:admin)` 作为**纵深防御**（gateway 被绕过直连时兜底），
  注释标明非主鉴权。
- 其余维持 `.authenticated()` + `ResourceAccessResolver`。

## 6. 管理 API

### 6.1 controller 清单（一表一 controller，代码边界）

| controller | 新接口（全 POST + @RequestBody） |
|---|---|
| `UserController`（新） | create/list/update/delete/resetPassword；platformRole/grant；platformRole/revoke；`/user/me`（登录即，含权限与租户列表数据源）；`/user/my-tenants` |
| `RoleController`（新） | list（可按 scope 过滤）/create/update/delete/built-in 拒绝改删；role/permission/save（全量勾选保存，校验 §3.4.1） |
| `PermissionController`（新） | list（只读目录，domain/scope 分组，供角色配置页右列渲染） |
| `TenantController`（扩展） | create 增加 `ownerUserId`；member/list；member/add（写 user_tenant，可选同请求带 roleId）；member/remove；member/role/grant；member/role/revoke |

前端页面数 ≠ controller 数：3 页（§7）对应 4 controller，页面合并为用户体验，controller 拆分为代码边界。

### 6.2 路径风格约定

**管理域（本设计新增的一切接口）禁用路径变量**，参数一律走 body——延续 `TenantController`
现有房规，使 §5.3 交叉校验可做严格集合比对，且权限表 resource 列不含实例变量。
资源域（`/resource/**` 等既有接口）允许 `{name}`，由归一化与豁免前缀消化，不迁移。

## 7. 前端页面（3 页 + 顶栏）

```
① 用户管理    平台用户 CRUD + 平台族角色授予（无权限者菜单不可见，下同）
② 角色与权限  左列角色（平台族/租户族分组）→ 右列权限勾选树；
              权限目录只读，仅"角色↔权限"可编辑，built_in 角色只读
③ 租户管理    列表 → 详情 tab：
              ├ 命名空间分配（现有能力迁入）
              └ 成员与角色（搜索平台用户添加；授予/回收 tenant 族角色；owner 标识）
```

- 顶栏租户上下文控件按 §4.4 双轨渲染（admin=筛选器 / 成员=切换器）。
- 菜单与按钮显隐由前端 decode JWT `data.permissions`（仅体验；权威在后端）。
- v1 交付裁剪：页面 ③ 成员 tab 与页面 ① 即可支撑代管主流程，②可后置——
  但**接口与表全部按本 spec 建齐**（按 A 设计、按 B 交付）。

## 8. 死代码清理

- 删除 `TenantValidate` 注解 + `TenantValidateAspect`（全仓无调用点；且其 `isSimpleType`
  跳过 String 参数，对现有 `@RequestParam` 风格接口即使挂上也是半失效的安慰剂）。
- 删除 §4.1 所述 token-exchange 分支与 client seed 中的 grant 声明。
- `SecurityConfig` 中读 `user_tenant_role` 的 `selectTenantRole`（租户域 SecurityRole）
  保留并接通写入侧。

## 9. 测试策略

- **单元**：权限闭包计算（平台/租户/双身份三用例）；converter PERM 展开；
  Registry 匹配（ANY-of、豁免、默认拒绝、`{var}` 归一化）；不变量 1-3（服务层）。
- **MockMvc**：403/200 矩阵——缺权限 403、TENANT 角色勾到 PLATFORM 权限被拒、
  跨租户上下文（member 管别人的租户）403、adminMode 显式传 tenantId 通过。
- **auth-server**：session-renewal 带/不带/带非法 tenant_id 三态（非法 → invalid_grant）。
- **启动校验**：交叉校验单测（构造裸 endpoint 断言启动失败）。
- **前端**：`switchTenant` 原子写、续期必带 tenant_id（定时器 mock 用例：过期重续后
  current_tenant 未丢）、租户选择流程。
- **回归**：现有租户 CRUD + ns 分配 + 资源页（透传链路）行为不变。

## 10. Roadmap（已知限制，显式挂账）

1. **按用户细分 K8s 动词**：现同租户成员共享 SA，能力=模板上限。解法方向：
   分配表从 `(tenant,ns)→template` 扩为 `(tenant,租户角色)→template` + 按用户 k8s 身份
   （per-user SA 或 impersonation），涉及 token/开通链路。
2. **租户配额**：补配额维度后，"申请 ns"才可开放租户自助（审批仍在平台）。
3. **透传 → 派生下游票**：gateway 调 k8s-server 由透传用户长票改为 exchange 缩权短票
   （届时 token-exchange 按 RFC 8693 语义复活，含 `act` 审计）。
4. **LDAP/OIDC JIT**：外部身份首登自动建 platform_user（v1 用户来源仅 LOCAL 手工建）。
5. **平台族角色拆分**：admin 之外派生"开通专员"（provision+allocation）等，纯种子零代码。

## 11. 决策记录与默认项

已定（对话确认）：两层分工、两族角色、全局目录不开放租户自定义、seed 权限点+粗角色交付、
ns 分配归平台族、成员管理并入租户详情页、授权层仅 platform-api、k8s-server 只边界、
session-renewal(+tenant_id) 单通道 + token-exchange 退役、管理域禁路径变量、交叉校验。

默认（未逐条确认，评审可否决）：用户来源 v1 仅 LOCAL（§10.4）；交叉校验的豁免前缀清单（§5.1）；
§3.3 权限点目录为首版种子，允许实现期按接口面微调（新增可以、语义改动需回本文改）。
