# 架构收敛批次实施记录（2026-10-07）

七项诉求：PV/SC 只读页 · api 分层改造 · 退出登录 · 页面权限 · client 分层 · 越权修复 · 开发文档。

配套文档（规范，不是本批专属）：
- `docs/development/backend-layering.md`
- `docs/development/frontend-permission-conventions.md`

---

## 1. 交付清单

### 1.1 PV / StorageClass 只读管理页（前端）

后端（controller + 权限行 `perm_res_pv_*` / `perm_res_sc_*`）与 api 封装、TS 类型此前已就绪，本批只补视图与接线。

| 新增 | 说明 |
|---|---|
| `platform-web/src/views/resource/PersistentVolumeView.vue` | 列表（容量/访问模式/存储类/回收策略/状态/绑定 PVC/创建时间）+ 客户端过滤（命名空间/存储类/状态）+ 概览与只读 YAML 抽屉 |
| `platform-web/src/views/resource/StorageClassView.vue` | 列表（provisioner/回收策略/允许扩容/创建时间）+ 概览与只读 YAML 抽屉 |
| 路由 | `resources/persistentvolumes`、`resources/storageclasses`（`group: 存储`，`context: 'full'`） |
| 菜单 | 「存储」分组下补两项 |

要点：这两个页面是**集群级**（无 namespace），故用本地 `clusterReady`（`state.loaded && tenantId && clusterId`）
而不是 `useResourceContext().ready`（后者多要求 namespace，集群无已分配命名空间时会误挡）。

### 1.2 api 侧分层改造（Part B）

12 个资源 controller 全部改为只做「参数绑定 + `ResponseData` 包装」；`dto(...)` 组装、路径字符串、
`gateway` 直调一律下沉。

新建 Service：`ConfigMapService` / `SecretService` / `PvcService` / `PodService` / `StorageClassService` /
`PersistentVolumeService`。
补进已有 Service：`ServiceService` / `HpaService` / `PodMonitorService` / `ServiceMonitorService` /
`WorkloadService`（各 2～4 个方法）、`NodeService`（8 个专用端点 + 流式日志）。
`PermissionController` 顺手去掉直连 `PlatformPermissionMapper`，改走 `PermissionService.list()`。
新增请求模型：`models/NodeDrainRequest` / `models/NodeLabelTaintRequest`（原为 controller 内部类）。

**同批修复 `NodeService` 的 N+1 + 死代码**：原实现在 `list()` 里先查一次 podstats 建 map 却从未使用，
随后在循环里逐节点调用一个每次内部重发 podstats 请求的方法 —— 10 个节点 = 11 次 HTTP。
改为 podstats 只取一次、按 nodeName 建索引后内存回填，并加 `NodeServiceTest` 断言**调用次数**（不是返回值）。

### 1.3 退出登录真正失效（Part C）

**根因**：`platform-auth` 根本没有登出端点（`application.yaml` 的 `ignore-urls` 里那个 `/user/logout`
是死条目，无任何 handler）。SPA 的 `logout()` 只清 localStorage，Redis 里的会话（`SESSION` cookie，8h 滑动）
原样存活 → 再点「登录」时 `/oauth2/authorize` 见会话有效直接发码 → 静默 SSO 回原用户，**换不了账号**。

改法：

| 文件 | 改动 |
|---|---|
| `platform-auth/.../SecurityConfig.java` | `defaultSecurityFilterChain` 挂 `.logout(logoutUrl("/session/logout"))`，成功返回既有 `ResponseData` JSON 风格；默认 `SecurityContextLogoutHandler` invalidate Redis 会话，再显式加一个 `CookieClearingLogoutHandler("SESSION")` 兜底（默认清的是 `JSESSIONID`，本应用是 `SESSION`） |
| `platform-auth/.../application.yaml` | 死条目 `/user/logout` → 真实的 `/session/logout` |
| `platform-web/src/auth/oauth.ts` | 拆成 `clearLocalSession()`（同步、无网络）+ `logout(): Promise<void>`（先打服务端登出，`catch` 容错，**再**清本地）；本地清理追加 `platform_resource_context` 与 `sessionStorage.clear()` |
| `platform-web/src/api/http.ts` | `redirectToLogin()` 改为 `void logout().finally(() => location.href='/login')` |
| `platform-web/src/layouts/MainLayout.vue` | `handleLogout` 改为 `await logout()` |

**未做（有意）**：曾考虑在授权请求加 `prompt=login` 作"换账号"第二道保险。核查 SAS 7.0.5 后放弃——
它只校验该参数值、并未用它强制重新认证（整份 jar 无 `requireReauthentication`），加了不生效；
在注释里把它写成"保险"正是本次 bug 的同类错误。

### 1.4 页面权限独立成码（Part D）

模型：`domain='Page'`，`resource=前端路由 path`，`action='VIEW'`，`code=<scope>:page:<key>`。
全览与命名规则见 `frontend-permission-conventions.md`。

| 项 | 内容 |
|---|---|
| 迁移 `V2026_10_07_1__page_permissions.sql` | 47 行 Page 权限点 + 自定义角色回填 + 内置角色兜底 |
| `PermissionService` | `ACTIONS` 拆为 `API_ACTIONS` / `PAGE_ACTIONS`（`VIEW`）；`validateFields` 按 domain 取用；Page 域额外要求 resource 以 `/` 开头 |
| `PermissionView.vue` | 动作下拉按 domain 联动；resource placeholder 与提示按 domain 变化 |
| `RoleView.vue` | 无需改（`grouped` 已按 domain 动态分组，Page 行自动出现为「域：Page」）；补了一段区分「页面可见性 vs 接口授权」的提示 |
| `pageCodes.ts`（新） | 页面码唯一来源 |
| `apiCodes.ts`（新） | 客户端能力位用到的 API 码常量表 |
| `permCodes.ts`（删） | 路由/菜单改用 pageCodes 后已无消费者 |
| `router/index.ts` | 全部 `meta.requiresPerm` 换成 pageCodes；`/overview` 保持无码（守卫回落目标，加码会死循环） |
| `layouts/MainLayout.vue` | 菜单与分组 `v-if` 全部换 pageCodes；「集群运维」分组从写死的 `perm.isAdmin` 改为可分配的 page code |
| `views/OverviewView.vue` | 快捷卡片按 pageCodes 过滤（卡片是页面入口）；三块统计的数据拉取仍按 apiCodes 短路（那是"拉数据的能力"） |

**回填策略**：迁移第 2 段用一张显式映射（page_code ↔ 该页主 API code）把 page code 补挂给所有已持有该
API code 的角色，避免"升级即菜单全隐身"。仍属**破坏性变更**：未被映射覆盖的自定义角色会失去菜单，
升级后须在「角色与权限」页补勾（见 §3 runbook）。

**`action` 用大写 `VIEW`**：`validateFields` 先 `toUpperCase()` 再比对，DB 里与 API 域行统一大写。
（实现时先写成小写 `view`，被 `PermissionServiceTest` 的新用例当场拦下。）

### 1.5 client 分层（Part E）

**api 侧**

| 动作 | 文件 |
|---|---|
| 改名 | `K8sResourceClient` → **`K8sClient`**（公共行为：DTO 六操作） |
| 新建 | `K8sCalicoClient`（`/admin/calico/**` 全族）、`K8sNodeClient`（节点专属 9 端点）、`K8sPodClient`（流式日志）、`K8sLifecycleClient`（`/admin/**` 生命周期 7 方法）、`StreamQueryParams`（日志 query 三选一组装） |
| 删除 | `K8sAdminClient`（其方法全部有归属） |

消费方改点：`CalicoService` → `K8sCalicoClient`；`ClusterService` / `NamespaceAllocationService` /
`TenantService` / `K8sProvisioningService` → `K8sLifecycleClient`。

**k8s-server 侧**

去掉 `AbstractAdminNamespacedResourceController`，资源 controller 只留两个基类：

- `AbstractClusterResourceController`：集群级。类注释**如实改写**（原注释声称"仅 PLATFORM:admin 可访问
  （SecurityFilterChain 对 `/admin/**` 统一要求）"，而 PV/SC/ClusterRole 实际挂在 `/resources/**`，
  只要求 `authenticated()` —— 这个错误认知正是两个越权面的根因）。
- `AbstractNamespacedResourceController`：命名空间级，新增 `boundary()`（`TENANT` 默认 / `PLATFORM`）；
  `@PostConstruct` 断言 PLATFORM 边界必须挂在 `/admin/**` 之下（把"可配置的跳过校验"变成受保护的不变式）。
  `ResourceQuotaController` / `LimitRangeController` 覆写为 `PLATFORM`。
- `ResourceAccessResolver` 新增 `resolvePlatformNamespacedAccess(clusterId)`（只校验集群可达性）。

### 1.6 越权修复（Part F）

前端 9 项主动越权（清单与修法见 `frontend-permission-conventions.md` §4.1）：

- 新增 `POST /cluster/options`（窄投影：`clusterId/clusterName/enabled/ipStack`，码 `platform:allocation:list`）
  —— 命名空间三个页面不再借 `platform:cluster:manage` 的 `/cluster/list`。迁移 `V2026_10_07_4`。
- `useResourceOptions` / `useClusterCapability` / `TenantDetailPanel` 按权限位短路；`http.ts` 新增
  `silent403` 作为第二层收敛（用法有明示约束）。
- 第 8、9 项（`stores/nodeCatalog.ts` 的 `/resource/nodes/list`、`NamespaceEditorView` 的
  `/calico/ippool/list`）**不是读代码找出来的**，是浏览器实测记录实际请求时露出来的 ——
  交互触发的调用 grep 不出来。详见 §4。

后端两个真实越权面：

- **PV 跨租户可读** → `PersistentVolumeService` 收窄到本租户已分配命名空间；按名寻址不可见时返回
  not-found（不泄露存在性）。`PersistentVolumeServiceTest` 9 个用例钉住，含"clusterId 缺失时 fail-closed"。
- **SC 全员可读** → 迁移 `V2026_10_07_3` 把三个端点的码改为 `platform:cluster:manage`，
  **并删除原有 `platform_role_permission` 关联**（id 不变只改 code ⇒ 不删关联就是给租户角色凭空授予
  `platform:cluster:manage`，提权事故）。

### 1.7 新增测试

`NodeServiceTest`（5）· `PersistentVolumeServiceTest`（9）· `PermissionServiceTest` 新增 4 个 Page 域用例。
`platform-api` 全量 151 个测试通过。

---

## 2. 部署 runbook

### 2.1 顺序（fail-closed，不可颠倒）

```
1) 重放 Flyway：
   V2026_10_07_1__page_permissions.sql          ← 47 行 Page 权限 + 角色回填
   V2026_10_07_2__cluster_capability_read.sql   ← /cluster/capability/get 补租户只读行
   V2026_10_07_3__storageclass_admin_only.sql   ← StorageClass 收归平台管理员（含删关联）
   V2026_10_07_4__cluster_options_permission.sql← 新端点 /cluster/options 的权限行
2) 起新构建（platform-auth → platform-api → k8s-server → platform-web）
```

**为什么必须先重放**：`PermissionCrossCheckRunner` 在启动期核对"每个活端点都有权限行或豁免"，
无行即抛异常拒绝启动。新构建引入了 `/cluster/options` 这个新端点，迁移未重放则 platform-api 起不来。

启动成功标志：日志出现
`[RBAC] 交叉校验通过：N 个 endpoint 全部有权限声明或豁免`，且无新增的幽灵行 WARN。

### 2.2 升级后必做

1. **为自定义角色补勾页面权限**：进入「角色与权限」→ 左列选角色 → 右列「域：Page」分组。
   迁移的回填只覆盖"持有该页主 API code"的角色；未被覆盖的自定义角色升级后会看不到对应菜单。
2. **验证登出**：用 A 账号登录 → 退出 → 点登录，**必须出现凭据页**（不是直接回到平台）；换 B 账号登录，
   顶栏用户名应已变更。
3. **验证 PV 收窄**：以租户身份开「持久卷」，只应看到绑定到本租户已分配命名空间的 PV；
   对他人 PV 手输名字应提示不存在。

### 2.3 回滚

- 代码回滚：`V2026_10_07_3` 的 `UPDATE`/`DELETE` 需手工反向（把三个端点行改回 `tenant:storageclass:*`
  并重建角色关联）—— **无自动回滚**，因为它删了关联行。
- `V2026_10_07_1` 新增的 Page 行可保留（旧前端不读 Page 码，无副作用）；`V2026_10_07_2`/`_4` 同理。
- 前端回滚会退回"路由用 API code 门控"，仍可用。

---

## 3. 浏览器验证（无活后端时的做法）

后端未起（8081/9527 均拒连），故用**假 JWS token + XHR stub** 在预览里真跑一遍。这套做法可复用，
且正是它找出了越权清单漏掉的两项。

### 3.1 前置修复：`.claude/launch.json` 的 root 错位

原配置 `npm --prefix platform-web exec vite -- --port 5199` **不起作用**：`npm --prefix` 只改包解析位置，
**不改 cwd**（实测 `npm --prefix platform-web exec -- node -e "console.log(process.cwd())"` 输出仓库根）。
于是 Vite 的 root 落在仓库根、找不到 `platform-web/vite.config.ts`，页面是空白的
（`/` 404、`/platform-web/index.html` 200）。

改为显式传 root：`runtimeArgs: [..., "vite", "--", "platform-web", "--port", "5199", "--strictPort"]`。

### 3.2 步骤

1. `preview_start`（配置名 `main-dev-check`，端口 5199）。
2. 先 `location.reload()` 让 app 正常启动（此时无 token → 落在 `/login`）。
3. **一次 eval** 装 XHR stub 并写 localStorage（token 已被上一步启动的 app 读到，故顺序是 reload → 装 stub）：
   - `XMLHttpRequest.prototype.open/send` 覆写：凡 URL 含 `/api/` 的请求一律本地应答
     `{code:200,msg:'成功',data:...}`，并按路由表给数据；非 `/api/` 透传原 send。
   - `localStorage`：`platform_access_token` = `b64u(header) + '.' + b64u(payload) + '.sig'`
     （前端只 base64 解 payload、**不验签**，故可伪造）；payload 的 `data` claim 带
     `permissions` / `platformRoles` / `tenantInfo`。另写 `platform_user`、
     `platform_resource_context`（种子上下文，省得点级联）。
4. **驱动到目标页**：动态 import 已有单例模块（`/src/stores/context.ts`、`/src/router/index.ts`）
   —— 与 app 用的是同一份模块实例，不是新开一份：
   ```js
   const ctx = (await import('/src/stores/context.ts')).useResourceContext()
   await ctx.load(); ctx.ensureDefaults()
   await (await import('/src/router/index.ts')).default.push('/resources/persistentvolumes')
   ```
   换身份时：改 localStorage 的 token → `(await import('/src/stores/permission.ts')).usePermission().load()`
   → 再 push。**不要整页刷新**（刷新会丢 stub）。
5. 断言：读 DOM（表格行文本、菜单条目数、分组）+ 读 `window.__stubCalls`（实际发出的请求清单）。

### 3.3 两个坑

- **stub 的 `setTimeout` 派发会被节流**：预览面板隐藏时，隐藏页面的定时器被推迟到分钟级，
  promise 永远不 resolve，eval 直接 30s 超时。改为在 `send()` 内**同步**调用
  `onreadystatechange` / `onload` / `onloadend`（axios 的 XHR adapter 在 `send()` 之前就挂好了回调，
  同步派发是安全的）。
- **取样要等够 microtask**：stub 同步返回后，axios 的 promise 链与 Vue 的渲染各要若干 microtask。
  `await Promise.resolve()` 一次不够；用 `for (let i=0;i<200;i++) await Promise.resolve()` 轮询
  （microtask 不受隐藏页节流影响）。

### 3.4 本轮实测到的事实

| 断言 | 结果 |
|---|---|
| 持久卷页渲染 | 3 行齐全（容量 `10 Gi`/`20 Gi`/`5 Gi`、访问模式、存储类、回收策略、状态徽标、绑定 `ns-a / pvc-alpha`、未绑定显示「未绑定」）+ 3 个过滤下拉 + 「共 3 个」 |
| 存储类页渲染 | 2 行（provisioner、回收策略、允许扩容 是/否）+ YAML 抽屉（概览 4 项 + YAML 懒加载 1 次调用） |
| 菜单 | 26 个条目、8 个分组，含「存储」下 PVC/持久卷/存储类 |
| Page 门禁（无 page code 的身份） | 直达 `/resources/storageclasses`、`/resources/persistentvolumes` 均**回落 `/overview`**；菜单只剩 1 项（总览）、0 分组 —— 印证迁移里"自定义角色会失去菜单"的警告与回填的必要性 |
| 租户身份进工作负载编辑器 | `/namespace/list`、`/resource/storageclasses/list`、`/cluster/get`、`/resource/nodes/list` **均未发出**；`/cluster/capability/get`、configmaps/secrets/pvcs list 正常发出 |
| admin 身份进工作负载编辑器（无回归） | `/resource/nodes/list`、`/calico/ippool/list`、`/calico/ipreservation/list`、`/cluster/get` 均正常发出 |

---

## 4. 遗留与待办| 项 | 说明 |
|---|---|
| 租户侧节点候选 | 「调度策略」的 nodeName/nodeSelector 下拉在租户侧退化为自由输入（越权清单第 8 项已修，但能力缺失未补）。恢复需**产品/安全决策**是否对租户暴露节点拓扑 —— `/resource/nodes/list` 回带容量/可分配/内网 IP，不能直接开放；若开放走窄投影端点（只 `name` + `labels`） |
| StorageClass 下拉候选缺失 | 租户侧 `storageClassName` 退化为手填（`filterable`，一带 `allow-create`）。恢复须新增**窄投影只读端点**（不含 `parameters` —— 部分 provisioner 的 parameters 含凭据），不要恢复整表可读 |
| 自管轨成员管理 | 「添加成员」在自管轨仍禁用（`/user/list` 是全平台 PII 表，严禁加 tenant ANY-of 行）。解禁须另建按租户过滤、限返回字段的搜索端点；「授予角色」须另建租户可读的角色目录端点 |
| 页面码 ↔ API 码成对授予 | 页面码与对应的 API 码目前需分开展示与勾选。可考虑在 RoleView 里做"勾页面自动带上所需 API 码"的联动，但那是体验优化，不影响正确性 |
| `.claude/launch.json` | 已修 root 错位（见 §3.1）。若你本地另有依赖旧行为的脚本，注意这条改动 |
| k8s-core 单测 | `WorkloadConverterStaticIpTest.revert_ipAddrs_annotation_maps_to_staticIps_and_is_removed_from_annotations` 在 HEAD（`c16e4e8`）上即失败（`PodTemplateDTO` 缺 `setStaticIps`），与本批无关，未处理 |
