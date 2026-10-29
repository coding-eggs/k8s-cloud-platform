# 凭据与镜像仓库的信息架构调整 · 设计

- 日期：2026-10-29
- 状态：形态已与用户逐轮确认，待评审
- 关联：S0.2 凭据管理（`V2026_10_13_3`）、S3.1 镜像仓库浏览（`V2026_10_14_1`）、
  Harbor 集群组件与租户镜像项目（`docs/superpowers/specs/2026-10-27-harbor-registry-design.md`）

## 1. 背景

镜像相关的能力现在散在三个入口，且分属三个不同的权限域：

| 页面 | 菜单位置 | 后端 | 权限码 | 管什么 |
|---|---|---|---|---|
| 凭据管理 | 平台管理 | `/credential/*` | `platform:credential:*`（只绑 admin） | 一张表 `platform_credential`：GIT / HARBOR / DOCKERHUB |
| 镜像仓库 | 资源管理 | `/registry/{suggest,browse,connections}` | `tenant:registry:catalog`（三个内置角色） | 只读浏览 repo/tag |
| Harbor 组件 | 集群 → 组件 | 组件安装 + `/registry/projects/*` | `component:manage` / `platform:registry:project:*`（只绑 admin） | 装/升级/卸载 + 一个租户项目弹窗 |

问题不是"东西太多"，是**切分维度选错了**：

1. **一个实体被切成三个视图。** `platform_credential` 一行同时装着认证材料、端点、归属租户、
   来源（`MANUAL` / `HARBOR`）、以及靠 `platform_registry_project.credential_id` 反查出的项目。
   三个页面各看一角，谁都不完整。
2. **两个「Harbor」挤在同一个 type 里。** 平台自己装的 Harbor 与用户外部的 Harbor 都是
   `type=HARBOR`，只靠 `source` 区分；界面上只表现为"这行不能改"，不说为什么、改要去哪改。
3. **租户项目台账没有归属页。** `platform_registry_project` 只存在于
   集群详情 → 组件 → Harbor → 弹窗。而"我的仓库项目叫什么、robot 还有效吗"正是仓库页该有的。
4. **菜单按"做的人"分，不是按"找的人"分。** 凭据在「平台管理」（与节点、审计同组），
   实际是流水线要用的；镜像仓库在「资源管理」（与工作负载同组），实际是制品资产。

### 已经试过、被否掉的两版切法

- **按连接类型拆**（Git 归一页、registry 归另一页）：把流水线要用的东西劈成两半。
- **合成一个「连接管理」页**（所有类型同页、浏览做行内抽屉）：镜像仓库被降格成流水线的附属，
  而它实际承担的不止流水线的事（组件安装的镜像改写、中间件、应用编排拉镜像都经过它）。

## 2. 目标形态

按**产物生命周期**分组：源码凭据 → 构建 → 产物仓库。

```
持续集成
 ├─ 流水线           （从「资源管理」移入，内容不变）
 ├─ Git 凭据管理      （原「凭据管理」收窄为 GIT 一类）
 └─ 镜像仓库          （原只读浏览页 → 仓库的管理）
```

### 组名为什么是「持续集成」

这三页恰好是 CI 全集：源码凭据、构建流水线、产物仓库。而且它与相邻的「应用交付」
（应用编排 / 中间件）天然接成一条线：

```
持续集成（把代码变成镜像） → 应用交付（把镜像跑起来）
```

两个组名摆在一起自己会解释关系，用户不会问"这两个分组什么区别"。
备选「构建与制品」（更直白，但凭据落不进去）、「研发交付」（与「应用交付」只差二字，易混）。

## 3. 各页职责

### 3.1 流水线

内容不变，只换分组。运行详情、编辑页一并不动。

### 3.2 Git 凭据管理

`platform_credential where type='GIT'` 的 CRUD + 测试连接。

- 列：名称 / 地址 / 归属 / 认证方式 / 指纹 / 上次验证 / 启用 / 流水线可用 / 操作
- **对租户开放**：租户管理员可以给自己租户建 Git 连接，平台级的行租户只读（规则见 §6.4.1）
- 页面结构与现在的 `CredentialView.vue` 一致，只是**去掉类型下拉里的 HARBOR / DOCKERHUB**，
  不再有"这行只读但我不知道为什么"的行
- 测试连接沿用 `CredentialProbe`（GIT 探 `info/refs`）

### 3.3 镜像仓库

registry 类的全部事项，主从两栏：

```
┌─ 仓库 ────────────┐┌─ 选中仓库 ──────────────────────────────┐
│ 平台 Harbor        ││ 连接   该 host 下的账号                 │
│  harbor.ackcp.cloud││        外部仓库：增删改测               │
│  3 个项目 · 健康   ││        平台 robot：只读 + 重建          │
│                   ││ 项目   租户项目台账（仅平台 Harbor）    │
│ 外部 Harbor        ││        t-team-a / platform · 同步       │
│  harbor.corp.x     ││ 浏览   repo / tag 树 + 复制镜像地址     │
│  1 个连接          ││                                        │
│                   ││                                        │
│ 公共 Docker Hub    ││                                        │
└───────────────────┘└────────────────────────────────────────┘
```

左栏「仓库」的每一行 = 一个 `host[:port]`。**类型只有两种**：

| 类型 | 判据 | 地址从哪来 |
|---|---|---|
| **Harbor** | Harbor 组件安装记录里的 host，或 `type=HARBOR` 的凭据端点 | 平台 Harbor：**固定**（取自安装记录，不可选、不可改）<br>外部 Harbor：**手填** |
| **Docker** | `type=DOCKERHUB` | 固定 `docker.io`，不用填 |

「平台 / 外部 / 公共」**不是类型**，是每行行尾的一个小标签，用来告诉用户这条能不能动：

- `平台` —— host 来自组件安装记录，地址固定；其上的连接是 `ensureTenant` 自动发的 robot，只读
- `外部` —— 用户（管理员或租户）手填的地址；其上的连接可增删改
- `公共` —— 内置档，没有凭据行，匿名

**另有一行没有连接的：`Docker Hub（公共）`。** 它就是既有的 `BuiltinRegistry.DOCKER_HUB`
（合成 id `builtin:dockerhub`，匿名、只答公共镜像，受 `platform.registry.dockerhub.enabled`
开关控制）。它今天已经在浏览页的连接下拉里，本批只是把它摆到左栏。

它与「租户自己建的 Docker Hub 账号」并存，不重复：前者答公共镜像，后者只答"我名下"
（含私有），判据是这条 ref **有没有凭据** —— `DockerHubDialect` 已经这么实现了，
本批不新增逻辑。

右栏三块：

- **连接** —— 该 host 下的 `platform_credential` 行。外部仓库可增删改测；
  `source=HARBOR` 的平台 robot 只读，但可「重建」（原 `/registry/projects/rebuild`）
  - **谁能建**：外部 Harbor 与 Docker 的连接，租户管理员可以给自己租户建
    （`tenant:credential:write`，见 §6.4）。平台 Harbor 的连接**只有管理员能建** ——
    而且它其实是 `ensureTenant` 自动发的 robot，不是手建的
    「重建 / 同步」按钮固定按 `platform:registry:project:manage` 显隐，只对管理员出现
  - **新建连接时的地址字段**（三条规则，别让用户填不该填的东西）

    | 类型 | 地址字段 |
    |---|---|
    | 外部 Harbor | 手填 `host[:port]`，必填 |
    | 平台 Harbor | **没有这个选项**。它的连接是 `ensureTenant` 建租户项目时自动发的 robot；表单里不出现「平台 Harbor」，手工填一个与安装记录相同的地址直接拒（否则会长出一条假的平台连接，`credentialMatches` 也认不出来） |
    | Docker | 不出现地址字段，固定 `docker.io`（与今天 `CredentialView` 里 `DOCKER_HUB` 的处理一致） |
- **项目** —— `platform_registry_project` 台账（仅平台 Harbor）。每行：租户 / 项目名 /
  robot 状态 / 上次同步 / 重建。工具栏「同步全部」（原 `/registry/projects/sync`）
- **浏览** —— repo / tag 树 + 复制镜像地址（原 `RegistryView.vue` 的全部内容）

### 3.4 Harbor 组件（不变，只减）

集群 → 组件 → Harbor 保留：装 / 升级 / 卸载 / 暴露方式 / 存储 / 外部 PG-Redis。
**删掉**「项目管理」弹窗（`HarborProjectsDialog`），内容已在镜像仓库页。

镜像仓库页左栏的平台 Harbor 行显示**只读状态**（地址、是否健康、暴露方式），
带一个「去组件页」的跳转 —— 装是装的事，管是管的事。

## 4. 术语钉死

现在有六个词在混用（凭据 / 连接 / 仓库 / registry / harbor / 组件）。钉死成下面四个
（**库里先不拆**，只统一界面语义）：

| 词 | 含义 |
|---|---|
| **仓库** | 一个镜像服务实例（`host[:port]`）。左栏一行 |
| **项目** | Harbor 下的命名空间（`platform` / `t-<sa>`），平台自动创建 |
| **连接** | 一个账号 + 它所属的仓库 +（Harbor 的）项目。流水线节点里引用的就是它 |
| **凭据** | 连接背后的认证材料（账号 + 口令/令牌），永不回显 |

规矩：说「连接」时不叫「凭据」，说「仓库」时不叫「Harbor 组件」，说「项目」时不叫「仓库」。

## 5. 数据来源：不新增表

这是本设计最省的地方 —— **仓库这个概念不需要新表**，它就是现有数据的一个聚合视角：

| 展示项 | 来源 |
|---|---|
| 仓库清单 | Harbor 组件安装记录 ∪ `platform_credential` 按 `endpoint` 去重分组 ∪ `BuiltinRegistry` |
| 连接 | `platform_credential where endpoint = host` |
| 项目 | `platform_registry_project where host = host` |
| 镜像内容 | `POST /registry/browse`（保持现状；内置档走已有的合成 id 分支） |
| 健康/状态 | `last_verified_at` / `last_verify_error` / `platform_registry_project.status` |

## 6. 后端改动

### 6.1 新增：仓库清单聚合端点

```
POST /registry/warehouses
→ [{ id, host, scheme, kind, origin, displayName, clusterId, exposeMode,
     connectionCount, projectCount, health, message }]
```

- `kind` ∈ `HARBOR` / `DOCKER` —— **只有两种**
- `origin` ∈ `INSTALLED`（组件安装记录）/ `CREDENTIAL`（凭据行）/ `BUILTIN`（内置档），
  对应界面上的 `平台` / `外部` / `公共` 标签；`clusterId` / `exposeMode` 只有 `INSTALLED` 有意义
- 清单里**固定含一条 `id=builtin:dockerhub`**（`kind=DOCKER`、`origin=BUILTIN`、
  `connectionCount=0`），受 `platform.registry.dockerhub.enabled` 控制。
  它的浏览直接复用已有的合成 id 路径（`/registry/browse` 认这个 id，不查凭据表），
  浏览端点**不用改**
- 权限码 `tenant:registry:catalog`（复用，只读）
- 聚合逻辑放在 `RegistryService` 里，读 `HarborRegistryService.harborHost()` 与凭据表；
  **不合并两个 service** —— `HarborRegistryService` 管供给（建项目、发 robot），
  `RegistryService` 管浏览与清单，职责不同

### 6.2 改动：凭据列表加过滤

两处，都在 `POST /credential/list` 上：

1. 请求体增加可选 `type` —— Git 页传 `GIT`，镜像仓库页按选中仓库取对应的类型与 host。
   不传 = 全量（保持兼容）
2. **按 `visibleTo` 过滤** —— 今天这个端点是全量返回（Controller 连租户帽子都没接），
   开放给租户之后必须先过一遍，否则租户一眼看到平台上所有凭据的名字与地址

### 6.3 改动的复用端点

| 端点 | 变化 |
|---|---|
| `/registry/projects/list` | 无（按 `clusterId` 查，镜像仓库页把选中仓库的 clusterId 传进来） |
| `/registry/projects/sync` `/rebuild` | 无 |
| `/registry/browse` | 无（按来源 id：凭据 id，或内置档的合成 id `builtin:dockerhub`，两条路今天都已存在） |
| `/registry/suggest` | 无 |
| `/credential/*` | 见 6.2；保持"永不回显"约束（`CredentialView` 有反射测试钉着） |

### 6.4 权限行：本批第一次把凭据开放给租户

今天是 `platform:credential:read/write` 且只绑 admin。按已确认的决定开放：
**Git 凭据与外部仓库连接对租户开；平台 Harbor 内部的只对管理员。**

#### 新增 API 行（5 条，与现有 `platform:credential:*` 同端点多码并存）

| id | resource | action | code | 绑定 |
|---|---|---|---|---|
| `perm_tenant_credential_list` | `/credential/list` | POST | `tenant:credential:read` | admin / tenant_admin / tenant_member |
| `perm_tenant_credential_test` | `/credential/test` | POST | `tenant:credential:read` | 同上 |
| `perm_tenant_credential_create` | `/credential/create` | POST | `tenant:credential:write` | admin / tenant_admin |
| `perm_tenant_credential_update` | `/credential/update` | POST | `tenant:credential:write` | admin / tenant_admin |
| `perm_tenant_credential_delete` | `/credential/delete` | POST | `tenant:credential:write` | admin / tenant_admin |

同端点多码是既有机制（`V2026_09_24_2` 放开了 (domain,resource,action) 的唯一约束），
授权按 **ANY-of**。`platform:credential:*` 那五条保留不动 —— 平台视图下的管理员走它。
租户角色**不能**持有 `platform:` 码（角色不变量），所以必须新增 `tenant:` 这一族。

#### Page 行

| 变更 | 说明 |
|---|---|
| 新增 `tenant:page:credential` → `/git-credentials` | 绑三个内置角色（与 `tenant:page:registry` 同口径） |
| `platform:page:credential` 的 `resource` 由 `/credentials` 改为 `/git-credentials` | 码与绑定不变，管理员兜底 |
| `tenant:page:registry` | 不变（`/registries`） |
| 新增分组「持续集成」 | 纯前端菜单分组，无权限行 |

> 同一页留两个 Page 码是过渡态：`platform:page:credential` 已无独立语义，
> 但直接停用会让持有它的自定义角色静默失去入口。先并存，排查完自定义角色再删。

> ⚠️ 本仓没有 Flyway，`db/migration/*.sql` 一律手工重放；先重放再起新构建。
> 本批**新增 5 个 API 行的 id 是新的，resource 是已有的** —— 端点没有变多，
> 启动期交叉校验不会报未覆盖。

#### 6.4.1 可见性与可写性

`CredentialService.visibleTo(c, hatTenantId)` 已有，本批把它真正用到列表上
（今天只在流水线路径里用）。

| 来源 | 租户可见 | 租户可改 | 说明 |
|---|---|---|---|
| **自己租户的** `source=HARBOR`（平台建项目时发的 robot） | ✓ 只读 | ✗ | 流水线选它推镜像；重建由管理员触发 |
| **平台级 `MANUAL`**（公共读的那几个仓库） | ✓ 只读 | ✗ | 平台统一配的公共仓库；`usableByPipelines` 由管理员定 |
| **自己建的**（`tenant_id=自己` 且 `source=MANUAL`） | ✓ | ✓ | Git / 外部 Harbor / Docker Hub 账号 |
| **公共 Docker Hub**（`builtin:dockerhub`） | ✓ 只读 | ✗ | 没有凭据行，固定 `docker.io`，匿名 |
| 平台级 `source=HARBOR`（`platform` 共享 robot） | ✗ | ✗ | 只给管理员 |
| 其他租户的行 | ✗ | ✗ | |

管理员：全部可见；`source=HARBOR` 的行仍不可手改（只能同步 / 重建）。

> ⚠️ 「平台级 `MANUAL` 可见」而「平台级 `HARBOR` 不可见」不是笔误 —— 前者是平台统一配的
> **公共读**仓库（大家都能拉），后者是平台自建项目的**写**账号（能往 `platform` 项目推镜像）。
> 两件事，别合并判定。

> 「公共读」今天**没有单独的开关**：判据就是 `tenant_id = null` 且 `source = MANUAL`，
> 也就是**所有平台级手建连接都对租户可见（只读）**。如果只想公开其中几条，得加一列
> `public_read`，见 §8。

#### 6.4.2 写侧的三条校验

1. **归属由服务端定**：租户调用 `create` 时强制 `tenant_id = auth.hatTenantId()`，
   忽略请求体里的 `tenantId` —— 与 `PipelineRunService` 那次修的是同一个原则
   （用调用者身份，不用目标租户）
2. **内部 Harbor 不接受手工新建**：`endpoint` 命中任一平台 Harbor 安装记录 → 拒（见 §3.3）
3. **不能改别人的行**：`update` / `delete` 先过 `visibleTo` + `tenant_id` 比对

## 7. 前端改动

| 文件 | 动作 |
|---|---|
| `views/CredentialView.vue` | 收窄：删 HARBOR / DOCKERHUB 类型选项、删"归属"以外的 registry 分支 → 改名 `GitCredentialView.vue` |
| `views/RegistryView.vue` | 重写为主从两栏；原浏览逻辑抽成右栏的「浏览」块；右栏「连接」块内置新建/编辑表单（地址字段按 §3.3 的三条规则显隐与锁定） |
| `components/cluster/HarborProjectsDialog.vue` | 内容并入镜像仓库页右栏「项目」块；组件页删掉入口 |
| `router/index.ts` | 新增分组 `持续集成`；三条路由改 `group`；`/credentials` → `/git-credentials` |
| `api/index.ts` | 加 `registryApi.warehouses()`；`credentialApi.list` 加可选 `type` |
| `types.ts` | 加 `RegistryWarehouse` |

先做**只读**部分（仓库清单 + 项目 + 浏览），再做写操作（连接的增删改、同步、重建），
这样重排本身可以先上线验证。

## 8. 待定决策（不阻塞本批只读部分）

### 除 Docker Hub 外的公共源

左栏固定有 `Docker Hub（公共）` 一行 —— 它就是既有的 `BuiltinRegistry.DOCKER_HUB`，
匿名可浏览，零新增逻辑。

`quay.io` / `ghcr.io` / `gcr.io` 等其他公共源**本期不进左栏**：`BuiltinRegistry` 目前只有
`DOCKER_HUB` 一个实例，每加一个都要配一份方言与搜索实现，而它们大多没有"列仓库 / 列 tag"
的公开 API。留在镜像补全的候选里即可。

### 平台级连接要不要"只公开一部分"

今天的判据是 `tenant_id = null 且 source = MANUAL` —— 平台级手建连接**一律**对租户可见（只读）。
若只想公开其中几条，加一列 `platform_credential.public_read`（默认 0），
可见性判据改成 `public_read = 1`。本期按"全部公开"实现，不加列。

## 9. 不做的事

- **不拆表**（把"连接"与"凭据"拆成两张）：现在没有第二个用途需要复用同一份口令，拆了只是多一次 join
- **不合并** `HarborRegistryService` 与 `RegistryService`
- **不动** Harbor 组件的安装/升级/卸载逻辑与 recipe
- **不动** 镜像改写的四级回落（`ImageRegistryService`：集群列 → 本集群 Harbor 安装记录 →
  管理集群 Harbor → 平台默认）。它本身就是"仓库地址只有一份真相源"的实现，
  与本设计同向
- **不动** 流水线画布的两个连接端点（`/pipeline/git-connections`、
  `/pipeline/registry-connections`）—— 画布按类型各取一份窄投影，是渲染需要

## 10. 成功判据

- 找"我有哪些镜像仓库"只去一个页面（镜像仓库），不需要知道 Harbor 组件在哪
- 仓库类型只有 Harbor 与 Docker 两种；平台 Harbor 的地址不用填也不能填，外部 Harbor 才要手填
- 配 Git 连接和配镜像仓库账号，各自去各自的页面，两页都不出现"我用不上的行"
- 租户在镜像仓库页能看到自己租户的 Harbor 项目与 robot 状态，不需要进集群
- 租户管理员能自己建 Git 连接和外部 Harbor / Docker 连接并在流水线里用，不用找管理员
- 租户看不到 `platform` 共享 robot，也建不出指向平台 Harbor 的连接
- 租户左栏看到的仓库恰好四类：自己租户的、平台配的公共可读仓库、公共 Docker Hub、自己建的；
  别的租户的与 `platform` 共享 robot 一条都不出现
- 三页同处一组，流水线与它的两个依赖在侧边栏相邻
