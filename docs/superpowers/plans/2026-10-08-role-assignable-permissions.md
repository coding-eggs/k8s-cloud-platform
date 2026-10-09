# 2026-10-08 角色可分配权限：不变量改单向 + 可分配集合由后端下发

## Context

起点是一个观察：**新建的 PLATFORM 角色能勾的权限点比 TENANT 角色还少**（API 域 17 vs 62），
而"能勾全部"的只有内置 `admin`。查下来是两个独立问题叠在一起：

1. **不变量做成了双向的**。`RoleService.assertScopeMatches` 两边都拦：TENANT 角色只能持 `tenant:` 码、
   PLATFORM 角色只能持 `platform:` 码。而"PLATFORM 侧那一半"没有安全动机，纯对称 —— 它的代价很具体：
   造不出"平台运维/审计员"这类只管一部分的平台角色。
2. **`admin` 是硬编码特例**。V2026_09_29_1 加资源域细粒度权限时，那 59 个码（ConfigMap/Pod/Service/
   Secret/PVC/HPA/Workload… 每族 6 个动作，另加 Page 域）**全部建成 `tenant:*`**，而 admin 必须能进这些
   页面 → 于是 V2026_09_29_2 给 `code === 'admin'` 开了"两族都能勾"的豁免，而不是给那些码分族。
   结果：`admin` 是全 code 的唯一角色，任何新建角色都走严格不变量。

3. **判据在前端复写了一遍**。`RoleView.vue` 按 `scope → code 前缀` 自己过滤，并同样硬编码 `code === 'admin'`。
   与后端校验各写一份，改一处忘另一处就会造出"能勾但存不了"（后端 ROLE_SCOPE_MISMATCH）或
   "存得下但看不见"的错配。

**"比租户少"是码数不可比的错觉**（统计自迁移脚本）：

| 族 | API 域码数 | 最大一个码覆盖 | Page 域码数 |
|---|---|---|---|
| platform | 17 | `platform:cluster:manage` = **60 条 URL**（集群生命周期 + 节点 + 存储 + Calico + CRD） | 26 |
| tenant | 62 | 逐资源逐动作切开，最大 `tenant:workload:get` 才 5 条 | 21 |

勾满 17 个 platform 码 = 平台全权；勾满 62 个 tenant 码 = 只在租户边界内。数量反着读会得出错误结论。

## 决策

| 项 | 结论 |
|---|---|
| 不变量方向 | **单向**：只禁止 TENANT 角色持非 `tenant:` 码。PLATFORM 角色不限 |
| `code === 'admin'` 特例 | **删除**（前后端各一处）。不变量不再需要例外 |
| 可分配集合 | **后端下发**：新端点 `POST /role/permission/assignable`，前端只渲染，不再按前缀过滤 |

### 为什么单向是对的

- **运行时本来就是超集**：`TokenExtrasService.permissions()` = 平台族角色的码（**无条件**并入）
  ∪（带 `tenantInfo` 时）该租户内角色的码。也就是说平台角色的码不带租户上下文也生效，租户角色的码只在
  租户上下文里才并进来。运行时是包含关系，配置面却按"互斥"限制 —— 不一致的其实是配置面。
- **k8s-server 的平台侧身份判据与码族无关**：`PLATFORM_SCOPE` 取自 `platformRoles` 非空
  （见 `docs/development/backend-layering.md` §3.1），平台角色勾 `tenant:` 码不会污染边界判定。
- **TENANT 侧那半必须留**：它防的是"租户管理员被勾出建租户权限"（spec §3.4 不变量 1 的原始动机），
  同时保证"租户上下文里的 token 永不含 `platform:` 码"。

### 必须写明的语义

**给 PLATFORM 角色勾租户码 ≠「他能在某个租户里做这件事」，而是「他能对任何租户做这件事」。**
平台角色带 `platformRoles` → 无 `tenantInfo` 的 token 走 `ResourceAccessResolver` 的 admin 代操作分支
（`assertPlatformSide` 过 → admin client + 调用方传的 tenantId），边界只剩命名空间分配表三元组。
这正是 admin 今天的路径，是设计意图 —— 但配角色的人得看懂。已写进 RoleView 的 hint 与 §1.1。

### 显式接受的后果

**`platform:role:manage` 持有者 ⇒ 事实上的全权**：他能给自己勾满两族。放开前他只能自举到平台族，
现在覆盖到租户面。本平台规模下（内网、管理员个位数）接受，记录在此而非默认发生。
若将来不接受，需要另加约束（例：只有 admin 能编辑 PLATFORM 角色的权限集）—— 那是独立一批。

**读性代价**：族名不再是能力标签，看 PLATFORM 角色不能再推断"它不碰租户内"，得点进去看权限集。

## 改动清单

**后端**
- `RoleService.assignablePermissions(roleId)`：返回该角色可分配的权限点行（TENANT → 只 `tenant:` 族；
  PLATFORM → 全量）。**可分配判据只此一处**。
- `RoleService.assertScopeMatches`：删 PLATFORM 分支与 `isAdminSuperuser`，只留 TENANT 判据。
- `RoleController`：新增 `POST /permission/assignable`（`platform:role:read`，与 `/permission/list` 同族）。

**迁移**：`V2026_10_08_3__role_assignable_permissions.sql` —— 一行端点记录（`platform:role:read`）。
无需补角色绑定：授权按 **code** 判（`PERM:<code>`），`builtin_role_admin` 已持该 code。

**前端**
- `api/index.ts`：`roleApi.permissionAssignable(roleId)`。
- `views/RoleView.vue`：删除 `scope → 前缀` 过滤与 `isAdmin` 特例（`nodesByDomain` 只做聚合）；
  数据源改为随所选角色拉取的 `assignablePerms`（**不能像以前那样挂载时拉一次复用** —— 可分配集合随角色变）；
  `onMounted` 只拉角色列表；hint 文案按单向不变量重写；顺带删掉 `submitCreate` 里重复的一次加载。

**测试**（`RoleServiceTest`）
- `platform_role_rejects_tenant_perm` → 反转为 `platform_role_may_hold_tenant_perm`。
- `admin_exempt_from_scope_invariant_saves_both_families` → **删除**（不再有特例）。
- 新增 `assignable_for_tenant_role_is_tenant_family_only` / `assignable_for_platform_role_is_everything`
  （后者同时覆盖 admin 与自定义平台角色：两者结果必须相同，即"没有特例"）。
- `tenant_role_rejects_platform_perm` 保留不动。

**文档**
- `docs/development/frontend-permission-conventions.md` §1.1：改成单向描述 + "可分配范围由后端下发"。
- `docs/superpowers/specs/2026-09-24-rbac-management-design.md` §3.4 不变量 1：加修订注（日期化记录，只加注）。

## 验证（2026-10-08）

| 项 | 结果 |
|---|---|
| `mvn -pl platform-api test` | **152/152 通过**（RoleServiceTest 14 例，含两条新增的 assignable 用例） |
| 前端 | `vue-tsc --build` + `vite build` 通过 |
| 启动期交叉校验 | 未跑（需活后端 + DB）。新端点的权限行在 `V2026_10_08_3`，**重放顺序见下** |
| 浏览器实测 | **未做**（预览启动被权限分类器拒过，未绕过）。待验证点：TENANT 角色只列 `tenant:` 码；PLATFORM 角色（admin 与自定义都）列两族；新建平台角色能勾到资源域 tenant 码并保存成功 |

## 部署

**先重放 `V2026_10_08_3__role_assignable_permissions.sql`，再起新构建** —— `PermissionCrossCheckRunner`
在启动期核对"每个活端点都有权限行或豁免"，新端点无行即 fail-closed 拒启。本批对既有数据零破坏
（不变量只是放宽：原本能存下的仍能存下）。

升级后建议自查两条：

1. 给一个自定义 PLATFORM 角色勾上 `tenant:workload:list` 保存 → 应成功（旧版会 ROLE_SCOPE_MISMATCH）。
2. 给一个 TENANT 角色勾 `platform:user:manage` → 仍应被拒（这条不变量没动）。

## 未做 / 下一步

- **`platform:cluster:manage` 拆码**：它一个码吃 60 条 URL（集群生命周期 + 节点 + 存储 + Calico + CRD），
  是平台族"码少"的根因，也是"平台运维只管节点"做不到的直接原因。拆完（a）的紧迫性也跟着降。
- **同 URL 双码的说明文案**：`/tenant/member/add` 同时挂 `tenant:member:manage` 与
  `platform:member:manage`，放开后更容易勾错，靠 `description` 区分（「加入成员(自管/代管 ANY-of)」vs
  「代管成员加入」）。把那几条文案写清即可，不算改动。
- **"只有 admin 能编辑 PLATFORM 角色权限集"**：若上面对 `platform:role:manage` 的后果不接受，这是补法。
