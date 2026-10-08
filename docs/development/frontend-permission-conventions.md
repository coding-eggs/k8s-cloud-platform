# 前端权限约定（platform-web）

> 起因：2026-10 排查发现租户管理员进「我的租户」会弹出"无权访问角色列表"、租户用户进工作负载编辑器连吃
> 几个 403 —— 都是前端**按"页面可见"发请求，而不是按"接口可用"发请求**。同批还查出两个后端越权面
> （PV 跨租户可读、StorageClass 全员可读）。本文是判据，不是事后描述。

---

## 1. 两套码，一件事各管一段

| | Page 域 | API 域 |
|---|---|---|
| 表 | `pageCodes.ts` | `apiCodes.ts` |
| `platform_permission` 行 | `domain='Page'`, resource=**前端路由 path**, action=`VIEW` | `domain='API'`, resource=**URL 模式**, action=HTTP 方法 |
| 决定 | 路由能不能进、菜单露不露 | 接口调用会不会被拒 |
| 权威判定 | 前端（体验层，防手输 URL） | **后端** `PermissionAuthorizationManager` |
| 进 URL 规则表？ | 否（`PermissionRegistryFactory` 按 `domain='API'` 过滤） | 是 |

**为什么必须分开**：历史上路由/菜单直接复用 API code，代价是「收一个 API code ⇒ 页面连带消失」，
既不能单独把页面发出去，也无法表达"能看页面但只能只读"。V2026_10_07_1 起页面权限独立成码。

### 1.1 命名规则

```
<scope>:page:<pageKey>        scope ∈ {platform, tenant}
platform:page:cluster                    /clusters
platform:page:namespace.edit             /namespaces/editor
tenant:page:workload.list                /resources/workloads
tenant:page:persistentvolume.list        /resources/persistentvolumes
```

scope 前缀**必须与角色族一致**：后端 `RoleService.assertScopeMatches` 只允许 TENANT 角色持 `tenant:` 码、
PLATFORM 角色持 `platform:` 码（内置 admin 豁免，可同时持两族）。所以：

- 平台管理页 → `platform:page:*`
- 租户可达页（资源管理、自管租户） → `tenant:page:*`
- 同一个页面只有一个码。需要"平台角色也要能进"的场景，用 admin 这一条路径覆盖，不要给一个页面挂两个码。

### 1.2 单一来源

`pageCodes.ts` 是页面码的唯一来源。路由 `meta.requiresPerm`、菜单 `v-if`、总览快捷卡片一律引用它，
**禁止写字面量**，**禁止回退去复用 API code**。

### 1.3 两个管理页按域分 tab

`PermissionView`（权限点目录）与 `RoleView`（角色勾选树）都**按域分 tab 展示**，一次只看一个域：

- 理由：三个域的 `resource` 语义完全不同（API=URL 模式 / K8S=资源名 / Page=前端路由 path），
  混在一张表里读不出重点，且「域」列占宽度却零信息量。分 tab 后该列可以去掉，宽度让给 resource。
- **呈现口径**：Page 域下 `resource` 是**前端路由 path**，列标题必须写「路由 path」而不是「URL 模式」；
  同时 Page 域的勾选项**不要**再按 resource 分组（每个页面码的 route path 各不相同，
  分组只会刷出 N 个「单条分组」），应扁平列出并把 route path 作为行内次要信息。
- **勾选状态跨 tab 保留**：`checkedCodes` 不随 tab 变化，保存提交的始终是所有域的并集 ——
  切 tab 只是换视图，不是换数据集。tab 角标显示「本域已勾 / 本域可选」。

### 1.4 `/overview` 有意没有 page code

它是路由守卫的回落目标（无权时 `return { name: 'overview' }`）。给它加码会形成重定向死循环。
同理，守卫里判 `to.name === 'overview'` 时直接放行。

---

## 2. 三层门控

```
① 路由 meta.requiresPerm   —— 防手输 URL、防权限回收后旧页停留（无权 → 回落总览）
② 菜单 v-if / 卡片过滤      —— 与 ① 同码（pageCodes），保证"有标题没条目"的空分组不出现
③ 可选调用的权限位短路      —— **发请求之前**判断"这次拉取有没有意义"（见 §4）
```

②③ 都属于体验层，**不构成安全**。真正拦住越权的是后端权限表 + k8s-server 边界 + K8s RBAC。

---

## 3. 用完即弃的"能力位"

`apiCodes.ts` 只登记**前端真的用来做判断**的 API 码。接口自身的鉴权不需要在这里登记 ——
那由权限表负责，前端复制一份必然会漂移的清单只会制造假的安全感。

用法：

- **按钮置灰 / 隐藏**：`v-if="perm.has(apiCodes.roleManage)"`
- **可选拉取短路**：见 §4
- **能力门禁**：如 `useClusterCapability` 在无读权限时不请求，退化到"未探测"态

---

## 4. 可选拉取必须短路（本批修的主要问题）

规则：**任何"锦上添花"的拉取（候选值、下拉选项、徽标计数），在发请求前先判权限位；无权限则直接给空，
不发请求。**

理由：这类请求失败时页面仍可用（下拉本来就 `filterable` + `allow-create`），弹一条
"权限不足"只会让用户看到与自己操作无关的红条，且掩盖真正的失败。

### 4.1 已修的主动越权清单（V2026_10_07 批次）

| # | 触发点 | 被调端点 / code | 触发场景 | 修法 |
|---|---|---|---|---|
| 1 | `components/TenantDetailPanel.vue` `loadRoleCatalog()` | `/role/list` → `platform:role:read` | 租户管理员进 `/tenants/detail` | 权限位短路，无权时保持既有降级（授予角色禁用） |
| 2 | `TenantDetailPanel.vue` `searchUsers()` | `/user/list` → `platform:user:manage` | 自管轨「添加成员」 | 按钮已 disabled；服务层再加短路兜住"从别处调用" |
| 3 | `composables/useResourceOptions.ts` | `/namespace/list` → `platform:allocation:list` | 租户开任何工作负载编辑器 + PvcView | 按权限位短路 |
| 4 | `composables/useResourceOptions.ts` | `/storageclasses/list` | 同上 + PvcView 的存储类过滤列 | 按 `platform:cluster:manage` 短路（见 §6 的收窄） |
| 5 | `composables/useClusterCapability.ts` | `/cluster/capability/get` | 租户开 HPA 页 / 工作负载编辑器（Calico 门禁） | 双层：前端短路 + 后端补 `tenant:cluster:capability:view`（V2026_10_07_2） |
| 6 | `views/resource/WorkloadEditorView.vue` | `/cluster/get` → `platform:cluster:manage` | 租户开工作负载编辑器（IP 栈门禁） | 该块整体已在权限位之后，改用 `apiCodes` 常量 |
| 7 | `views/NamespaceView/Editor/DetailView.vue` | `/cluster/list` → `platform:cluster:manage` | 角色只有命名空间权限时进这三个页面 | **新增窄端点** `/cluster/options`（见 §5） |
| 8 | `stores/nodeCatalog.ts` | `/nodes/list` → `platform:cluster:manage` | 租户开工作负载编辑器（「调度策略」的 nodeName/nodeSelector 下拉，页面 mount 即触发） | 权限位短路；候选为空时各下拉退化为自由输入。**租户侧节点候选尚无替代**，见 §6 备注 |
| 9 | `views/NamespaceEditorView.vue` | `/calico/ippool/list` → `platform:cluster:manage` | 角色只有命名空间权限时进命名空间编辑页 | 显式权限位短路（此前只靠 `hasCalico` 间接挡住，依赖不显式的不变量） |

> 8 与 9 是靠**浏览器实测**发现的：给假 admin token 装 XHR stub 后驱动页面，记录实际发出的请求，
> 才看出 `/nodes/list` 被触发了。光靠读代码做清单会漏 —— 交互触发的调用不 grep 不出来。
> 复现方式见本仓 `docs/superpowers/plans/2026-10-07-architecture-convergence.md` §3。

> 顺带发现：`views/OverviewView.vue` 与 `views/resource/WorkloadEditorView.vue`（保留 IP 候选块）
> 早就是正确写法（`perm.has(...) ? api() : Promise.resolve([])` / 函数首行 return），是本批其它地方的参照样板。

### 4.2 `silent403` 的使用规则

`api/http.ts` 支持 `silent403?: boolean`（axios config 扩展）：置位后 403 不弹全局提示。

- **允许**：①纯只读候选值；②调用方自带降级路径；③失败不影响任何写操作。
- **禁止**：写操作、主数据（列表/详情）、任何"失败即必须让用户知道"的请求。
- **优先级**：`silent403` 是第二层收敛。**更优先的做法是 §4 的权限位短路（不发这个请求）**。
  只有"权限位判不准"（如后端该 code 是 ANY-of、前端拿不到完整语义）时才用它。

---

## 5. ★ 需要数据的页面：请求**同粒度的窄端点**，不要借更高的权限码

这是本批第 7 项越权的根因，也是最容易复发的一条：

> 命名空间管理页需要一个"集群选择框"，于是调 `/cluster/list`（平台管理面，`platform:cluster:manage`）。
> 结果"只做命名空间运维"的角色一进页面就 403。

**错误修法**：给前端加个权限位门控（那只是不发请求，页面还是不可用）；
或者给 `/cluster/list` 补一条 ANY-of 行（把平台管理面下放给运维角色）。

**正确修法**：暴露与需求同粒度的窄端点 —— `POST /cluster/options` 只返回
`clusterId/clusterName/enabled/ipStack`，权限码与这些页面一致（`platform:allocation:list`）。

判据：**页面需要什么数据，就为那份数据开一个权限码匹配的端点**；
"先借一个权限更高的现有端点，再用前端门控兜住"是本末倒置。

---

## 6. 后端越权面（本批修复，前端无法单方面解决）

| 资源 | 问题 | 修法 |
|---|---|---|
| PersistentVolume | `tenant:persistentvolume:*` 绑在租户角色上，而 k8s-server 的集群域 controller 无租户维度 → 任意租户成员可列出**全集群 PV**（含他租户 `claimRef`） | `PersistentVolumeService` 按 `claimRef.namespace ∈ 本租户在该集群的已分配命名空间` 收窄；按名寻址不可见时返回 **not-found**（不泄露存在性）；未绑定 PV 属集群存储池，对租户不显示 |
| StorageClass | 无命名空间维度，无法按租户收窄 | 权限码由 `tenant:storageclass:*` 收归 `platform:cluster:manage`（V2026_10_07_3），页面收归 `platform:page:storageclass.list` |

**已知后果（接受）**：StorageClass 收归后，租户侧的 `storageClassName` 下拉没有候选值
（`components/workload/PvcTemplateEditor.vue`、`views/resource/PvcView.vue` 的过滤列），退化为手填
（两处均 `filterable`，前者另有 `allow-create`）。
若要恢复候选值，**正确做法是新增窄投影只读端点**（只返回 `name/provisioner/reclaimPolicy/allowVolumeExpansion`，
**不含 `parameters`** —— 部分 provisioner 的 parameters 含凭据），而不是恢复整表可读。

**同类待办**：租户侧「调度策略」的节点候选（`stores/nodeCatalog.ts`，清单第 8 项）同样退化为自由输入。
恢复它需要**产品/安全决策是否对租户暴露节点拓扑** —— `/nodes/list` 回带容量、可分配、
内网 IP 等集群信息，不能直接开放。若决定开放，走 §5 的窄投影端点（只返回 `name` + `labels`）。

---

## 7. 绝不放松的清单

- **`/user/list` 不得加 tenant ANY-of 行**：那是全平台用户表（含 email 等 PII），任一租户成员即可拖库
  = 跨租户 PII 泄露。自管轨要解禁须另建**按租户过滤、限返回字段**的搜索端点。
- **`/role/list` 放宽需显式安全决策**：角色目录非 PII，风险面小得多，但仍是鉴权面变更。
- **Prom discovery 四端点与 `/context` 的豁免是有意的**（纯候选值/上下文查询），不要当越权缺口上报。

---

## 8. 新增一个页面的 checklist

1. **建 Page 行**：`platform_permission` 加一行（`domain='Page'`，`resource=路由 path`，`action='VIEW'`，
   `code='<scope>:page:<key>'`），写进 Flyway 迁移。
2. **绑内置角色**：新页面的码需要绑到 `builtin_role_admin`（以及租户页的 `builtin_role_tenant_admin` /
   `builtin_role_tenant_member`），否则升级后内置角色看不到它。
3. **`pageCodes.ts`** 加常量。
4. **路由**：`meta.requiresPerm: [pageCodes.xxx]`；`context: 'full'` 表示顶栏展示租户→集群→命名空间级联。
5. **菜单**：`layouts/MainLayout.vue` 加条目，`v-if` 用同一个 page code；分组标题的 `v-if` 与其下条目同源。
6. **该页的数据拉取**：逐个检查是否需要 §4 的权限位短路；若需要的是"更高的权限码才拿得到的数据"，
   走 §5 开窄端点。
7. **API 域行**：页面调用的每个后端接口都要有 API 域权限行（那是后端的事，但漏了会在启动期被交叉校验拦下）。
