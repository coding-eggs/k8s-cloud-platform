# 权限点管理：目录页 + CRUD + 热加载（实施计划）

日期：2026-09-27。状态：**已评审批准，待实施**（新 session 按本文档执行）。

## Context

RBAC 大需求（见 `2026-09-24-rbac-management.md`）落地了用户管理、角色与权限管理，但 `platform_permission`（URL→权限码映射表）本身没有管理页面——它只作为角色配置页的勾选项数据源存在（`POST /permission/list` 已有）。使用者看不到"具体有哪些 URL 规则、每个权限码覆盖哪些端点"。

本次交付三件事：
1. **权限点目录页**：按 code 分组展示全部 URL 规则；
2. **CRUD**：新增/编辑/软删权限行；
3. **热加载**：改动免重启即时生效，且保留 fail-closed 安全线——任何操作不得把活端点的权限覆盖删空。

## 关键设计决策（已定，勿重开）

### D1 热加载机制：Registry 内 volatile 原子换表
`PermissionRegistry` 现为 `private final List<Rule> rules`（`List.copyOf` 不可变，构造后无替换入口）。`PermissionAuthorizationManager` **每请求**调 `registry.requiredCodes(method, path)`、不缓存规则——因此原地原子换表对在途请求安全（每个请求看到旧或新快照，不会混合；`AntPathMatcher` 线程安全）。

改法（最小）：
- `rules` 字段 `final` → `volatile`；
- 新增 `public void replaceRules(Collection<Rule> rules) { this.rules = List.copyOf(rules); }`。
- **不要**换 bean 实例：manager 的 registry 字段是 final，换实例要连带换 manager，无必要。

### D2 安全线：写前校验，拒绝制造"裸端点"
启动时 `PermissionCrossCheckRunner`（SmartInitializingSingleton）用 `RequestMappingHandlerMapping` 枚举活端点，`PermissionCrossCheck.uncoveredEndpoints()` 非空 → 抛 IllegalStateException 拒启；`ghostRules()` 仅 WARN。热加载后此保证不能只剩启动期：

- 把端点枚举逻辑从 Runner 抽成可复用静态方法（如 `PermissionCrossCheck.liveEndpoints(RequestMappingHandlerMapping)`），Runner 与 Service 共用同一语义（含"无 method 条件 → `*`"的归一）；
- **每个写操作（create/update/delete）在 DB 写入前**，用内存候选规则集（当前 active 行 ± 本次变更；仅 domain=API 行进规则，与 `PermissionRegistryFactory` 同口径：`Rule(action, resource, code)`）跑 `uncoveredEndpoints(candidate)`：
  - 有裸端点 → 抛错（消息列出受影响端点），**零写入**；
  - 通过 → `@Transactional` 写 DB → **提交后** `selectAllActive()` 重读 → `registry.replaceRules(...)` + 幽灵规则 WARN（复用 `PermissionCrossCheck.ghostRules`）。
- 校验在写前、换表用提交后实读：两条路径都不需要回滚，DB 与运行时不漂移。并发双管理员同时编辑的竞态在本平台规模下可接受（文档注明即可）。

### D3 显式重载端点
`POST /permission/reload`：重读 DB → 同样裸端点校验 → 换表。用途：绕过 UI 直接改库（SQL seed）后把运行时对齐。CRUD 本身自动生效，此端点是 SQL 工作流的补口。

### D4 权限码：复用 `platform:role:read` / `platform:role:manage`
`/permission/list` 已在 seed 归 `platform:role:read`（行 id `perm_perm_read`）；写操作归同族 `platform:role:manage`。admin 经 `platform:%` LIKE 链接自动覆盖新端点行，**不新增权限码、不动 admin 角色关联**。

## 现状锚点（探索已核实，2026-09-27）

| 项 | 位置 | 要点 |
|---|---|---|
| Registry | `platform-api/.../security/PermissionRegistry.java`（51 行） | `record Rule(String method, String pattern, String code)`；方法 `rules()` / `matches(rule,method,path)` / `requiredCodes(method,path)→Optional<Set<String>>`（LinkedHashSet，ANY-of） |
| Registry 构建 | `.../security/PermissionRegistryFactory.java` L22-30 | 唯一构造点：`selectAllActive()` → filter `"API".equals(domain)` → `Rule(action, resource, code)`。**列映射：resource→URL pattern，action→HTTP method** |
| 启动交叉校验 | `.../security/PermissionCrossCheckRunner.java`（SmartInitializingSingleton）+ `PermissionCrossCheck.java`（静态工具 L25-42） | Runner L41-56 枚举端点（`getHandlerMethods().keySet()` → patterns × methods，空 methods→`"*"`）；`uncoveredEndpoints(eps, reg)` = 非豁免（`ExemptPaths.isExempt`）且 `requiredCodes` 空；裸端点 → log.error + throw；ghost → WARN。v1 有意保留三条 planned-endpoint ghost 行（`/tenant/update`、`/user/update`、`/role/update`） |
| 鉴权消费方 | `.../security/PermissionAuthorizationManager.java` | 构造注入 final registry；每请求 `requiredCodes` 一次；优先级：命中权限行 ANY-of > 豁免 authenticated（匿名排除）> default deny |
| Service 风格范本 | `platform-api/.../services/RoleService.java` | `@Service @RequiredArgsConstructor`；校验抛 `new CloudPlatformException(EnumResponseType.X, "中文消息")`；id = `ULIDGenerator.generateULID()`；写方法逐个 `@Transactional`（类上不标）；先全量校验后写入 |
| Controller 范本 | `.../controllers/RoleController.java` / 现有 `PermissionController.java` | 全 `@PostMapping` + `@RequestBody`，返回 `ResponseData<T>`，swagger `@Tag/@Operation`；controller 只委托 service |
| 模型 | `platform-data/.../models/auth/PlatformPermission.java` | id/domain/resource/action/code/description/createdAt/**deletedAt**（软删，NULL=活）。**无 updatedAt 列** |
| Mapper | `.../mapper/auth/PlatformPermissionMapper.java` + XML | 已有 `selectAllActive()`（`deleted_at is null order by domain, code`）、`selectAllByCode`、`updateByPrimaryKeySelective`；`deleteByPrimaryKey` 是**硬删**（勿动）；缺软删方法 |
| 错误码 | `platform-common/.../exception/EnumResponseType.java` | 10xxx 连号用到 **10030**（LIMIT_RANGE_VALUE_INVALID）→ 新码从 **10031** 起；可复用 `PERMISSION_NOT_FOUND(10027)`、`BEAN_VALIDATION_EXCEPTION(1004)` |
| 迁移惯例 | `platform-data/src/main/resources/db/migration/` | **手工 .sql，无 Flyway**；命名 `V<YYYY_MM_DD>_<seq>__<desc>.sql`（最新 `V2026_09_26_1__b3_namespace_permissions.sql`）；幂等靠**显式唯一 id 命中 PRIMARY KEY + ON DUPLICATE KEY UPDATE**（见 V2026_09_24_2 L70-79 范式）；根 dump `k8s_cloud_platform.sql` 保持同步 |
| 前端 API | `platform-web/src/api/index.ts` L169-172 | `permissionApi = { list: () => http.post<never, PlatformPermission[]>('/permission/list') }`；CRUD 风格仿 L158-167 `roleApi`（`http.post<never, T>`） |
| 前端类型 | `platform-web/src/types.ts` L92-105 | `PlatformPermission { id; domain?; resource?; action?; code; description?; createdAt? }`（无 status/updatedAt） |
| 页面骨架范本 | `platform-web/src/views/TenantView.vue` | toolbar（创建/刷新）+ `el-table v-loading stripe` + 共享 create/edit dialog（isEdit 切换、手动 `ElMessage.warning` 校验，不用 el-form rules）+ `ElMessageBox.confirm` 删除 + **MoreFilled 行操作下拉**（L184-203，用户近期钦定的当前风格） |
| code 聚合范式 | `platform-web/src/views/RoleView.vue` L35-70 | 按 code 去重聚合、ruleCount 计数；scope 前缀过滤 `p.code.startsWith('platform:'/'tenant:')` |
| 路由/菜单 | `platform-web/src/router/index.ts` L44-45；`MainLayout.vue` L168-175 | roles 行：`{ path: 'roles', ..., meta: { title: '角色与权限', group: '平台管理', requiresPerm: ['platform:role:manage'] } }`；菜单 `el-menu-item v-if="can('platform:role:manage')" index="/roles"`。组头 v-if（L142）已含 `platform:role:manage`，**无需改组头**。全仓无 el-table expand/tree 先例 |

## 实施步骤

### B1. `EnumResponseType.java`（platform-common）
新增：`PERMISSION_ENDPOINT_UNCOVERED(10031, "操作会使端点失去权限覆盖")`——实际消息经双参构造器带受影响端点清单。

### B2. `PermissionRegistry.java`
`rules` 改 volatile + `replaceRules(Collection<Rule>)`（见 D1）。类 javadoc 补一句热加载语义（换表原子、读方见整快照）。

### B3. `PermissionCrossCheck.java` + `PermissionCrossCheckRunner.java`
抽 `liveEndpoints(RequestMappingHandlerMapping)` 静态方法（Runner L41-56 原样搬移，含空 methods→`"*"`）；Runner 改调它。行为零变化。

### B4. Mapper + XML（platform-data）
新增 `int softDeleteById(String id)`：
```xml
<update id="softDeleteById">
  update platform_permission set deleted_at = now() where id = #{id} and deleted_at is null
</update>
```
update 走既有 `updateByPrimaryKeySelective`。

### B5. 新 `PermissionService.java`（platform-api services/，仿 RoleService）
- `list()` → `selectAllActive()`；
- `create(req)` / `update(id, req)` / `delete(id)` / `reload()`：
  - **字段校验**（`BEAN_VALIDATION_EXCEPTION` + 中文消息）：
    - code 非空且以 `platform:` 或 `tenant:` 开头（scope 前缀不变量，角色族过滤依赖它）；
    - domain ∈ {API, K8S, Page}；
    - action ∈ {GET, POST, PUT, DELETE, PATCH, *}；
    - API 域 resource 必须以 `/` 开头（支持 Ant 通配如 `/xxx/**`）；resource/action 非空；
  - update/delete：行不存在或已软删 → `PERMISSION_NOT_FOUND(10027)`；
  - **写前裸端点校验**（D2）→ 失败抛 `PERMISSION_ENDPOINT_UNCOVERED`（带端点清单），零写入；
  - `@Transactional` 写库（create：ULID id + createdAt；delete：softDeleteById）；提交后重读 `selectAllActive()` → `replaceRules` → ghost WARN。
- reload()：跳过字段校验与写入，直接"读 DB → 裸端点校验 → 换表"。

### B6. `PermissionController.java`（扩展现有，list 不动）
新增四端点（全 POST + @RequestBody，仿 RoleController）：
- `/permission/create`、`/permission/update`、`/permission/delete` → `ResponseData<Void>`；
- `/permission/reload` → `ResponseData<Void>`。

### M1. 迁移文件
新文件 `platform-data/src/main/resources/db/migration/V2026_09_27_1__permission_manage.sql`：
```sql
-- 权限点管理端点的 seed 行（幂等：显式 id 命中 PRIMARY KEY + ODKU，同 V2026_09_24_2 范式）。
-- ⚠️ 部署顺序：先重放本文件再起新构建——新端点无行时启动交叉校验 fail-closed 拒启。
INSERT INTO platform_permission (id,domain,resource,action,code,description) VALUES
 ('perm_perm_create','API','/permission/create','POST','platform:role:manage','新建权限点'),
 ('perm_perm_update','API','/permission/update','POST','platform:role:manage','编辑权限点'),
 ('perm_perm_delete','API','/permission/delete','POST','platform:role:manage','删除权限点'),
 ('perm_perm_reload','API','/permission/reload','POST','platform:role:manage','重载运行时授权规则')
ON DUPLICATE KEY UPDATE description=VALUES(description), resource=VALUES(resource), action=VALUES(action);
```
runbook（`2026-09-24-rbac-management-runbook.md`）部署序列补一行：V2026_09_27_1 先于 platform-api 新构建。

### F1. `src/api/index.ts`
`permissionApi` 扩为（仿 roleApi 风格）：
```ts
create: (payload: { domain: string; resource: string; action: string; code: string; description?: string }) => http.post<never, PlatformPermission>('/permission/create', payload),
update: (payload: { id: string; domain: string; resource: string; action: string; code: string; description?: string }) => http.post<never, void>('/permission/update', payload),
delete: (id: string) => http.post<never, void>('/permission/delete', { id }),
reload: () => http.post<never, void>('/permission/reload'),
```

### F2. 新 `src/views/PermissionView.vue`（骨架仿 TenantView）
- **表格**：平铺 el-table，列 = 权限点(code) / 说明(description) / 域(domain) / URL 模式(resource) / 方法(action)；`:span-method` 把相邻同 code 行的 code、说明两列纵向合并——一眼看出"哪个权限码覆盖哪些 URL"（不引入 expand/tree 新范式，合并单元格是 el-table 原生能力）。数据按 code 排序分组（服务端 order by domain,code，前端可再稳定排序）；
- **toolbar**：创建权限点 / 刷新 / 「重载规则」按钮（调 `/permission/reload`，成功 toast "运行时规则已与数据库对齐"——SQL 改库后的工作流补口）；
- **dialog**（create/edit 共用，isEdit 切换）：domain（el-select API/K8S/Page）、resource（input，placeholder 示例 `/xxx/**`）、action（el-select GET/POST/PUT/DELETE/PATCH/*）、code（input，form-tip 提示 `platform:` / `tenant:` 前缀及含义）、description；手动 `ElMessage.warning` 校验（仿 RoleView/TenantView）；
- **行操作**：MoreFilled 下拉（编辑/删除，TenantView L184-203 范式）；删除 `ElMessageBox.confirm`；后端拒绝（裸端点保护）时 http 拦截器统一弹错，前端不重复处理；
- 写操作成功 toast："已保存，授权规则已实时生效" + 重新拉列表。

### F3. 路由与菜单
- `router/index.ts` roles 行后加：
  `{ path: 'permissions', name: 'permissions', component: () => import('@/views/PermissionView.vue'), meta: { title: '权限点', group: '平台管理', requiresPerm: ['platform:role:manage'] } },`
- `MainLayout.vue`「角色与权限」菜单项后加「权限点」（icon 用 `Key`，`v-if="can('platform:role:manage')"`；组头 v-if 已含该码，无需改）。

## 测试（TDD：先写测试 RED，再实现 GREEN）

- `PermissionRegistryTest`：`replaceRules` 后 `requiredCodes` 反映新规则集；换表前构造的引用不受影响；空集合合法。
- `PermissionServiceTest`（mock mapper + 用真实 `RequestMappingInfo` 构造 handlerMapping stub，或 mock 其 `getHandlerMethods()`）：
  - 字段校验：code 无合法前缀 / domain 非法 / API 域 resource 不以 `/` 开头 → 拒绝且零写入；
  - **裸端点保护**：删除某端点唯一覆盖行 → 抛 10031 且 mapper 零写入（verify never）；同 URL 有其他 code 行覆盖时允许删；update 改 resource 使旧 pattern 失守 → 拒绝；
  - create/update/delete 通过校验后：DB 写发生 + `replaceRules` 被调（捕获参数断言规则集正确，含"仅 API 域进规则"口径）；
  - reload：重读 DB 并换表；DB 状态有裸端点时拒绝且不换表。
- 回归：全量 `platform-api` 测试套件 + `npm run build`（vue-tsc type-check）。

## 验证与部署（用户侧手工步骤）

1. `mvn install -DskipTests -pl platform-common,platform-data` → `mvn test -pl platform-api` 全绿；
2. `npm run build` 通过；
3. **先重放 `V2026_09_27_1__permission_manage.sql`（重连 DB 会话后执行）→ 再起新构建的 platform-api**（顺序反了会 fail-closed 拒启）；
4. 页面「权限点」增/改/删各一次：确认即时生效（另一标签页验证新规则立刻拦截/放行）、删除唯一覆盖行被拒且提示端点名、「重载规则」按钮可用；
5. 启动日志无新增幽灵规则 WARN（三条既有 planned-endpoint ghost 行除外）。

## 明确不做

- 不引入 Flyway（沿用手工迁移惯例）；
- 不给 K8S/Page 域行做运行时语义（Registry 只吃 API 域，现状保持）；
- 不在页面暴露幽灵规则清单（后端 WARN 已覆盖，v1 够用）；
- 不动 admin 角色关联、不新增权限码（D4）。
