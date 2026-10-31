# 平台 / 租户边界收敛设计方案

> 2026-10-31 立项。目标读者：平台后端 / 前端、集群运维。
> **本文合并了《租户镜像拉取凭据》设计**（原 `2026-10-31-tenant-image-pull-secret-design.md`，
> 内容并入 §6 后删除原文件）：两件事是同一个模型的症状与一条具体功能，分开改会互相打架。

## 1. 要解决的问题

三件事是**同一个模型的三个症状**：

| # | 症状 | 根因 |
|---|---|---|
| 1 | 平台代管租户资源：得先让那个 ns 出现在分配表里；切到租户视图后**还只能用租户权限** | admin 模式也过分配表校验；且判定顺序是「租户身份优先于平台身份」 |
| 2 | 同一个 ns 可以分配给多个租户，导致租户之间没有边界 | 分配表唯一键是 `(tenant_id, cluster_id, namespace)` |
| 3 | 租户拉不到自己 Harbor 项目的镜像 | 凭据在库里，没有送进命名空间 |

第 1、2 条是边界模型；第 3 条是该模型下的一个具体功能。合在一份 spec 里，是因为第 3 条里
「共享 ns 的凭据装不下」这个问题**只有 1:1 之后才消失**。

**这不是引入新模型，是把代码里已有的例外变成默认。**

## 2. 现状事实（2026-10-31 核实）

| 事实 | 位置 |
|---|---|
| 分配表校验对**租户与 admin 两种模式都生效** | `ResourceAccessResolver.resolveNamespacedAccess` 末尾统一 `assertNamespacedAccess` |
| 已有「不查分配表」的平台通道，但只给 ResourceQuota / LimitRange 用 | `ResourceAccessResolver.resolvePlatformNamespacedAccess` |
| `/list-all`（跨 ns 列举）已走那条通道 | `AbstractNamespacedResourceController.listAll` |
| 所有命名空间域资源端点都收敛在同一个 `resolve(...)` | `AbstractNamespacedResourceController:218` |
| 分配表唯一键是三元组（允许一 ns 多租户） | `uk_tenant_cluster_ns` |
| 已有「租户可调的窄投影」先例 | `/mesh/gatewayclasses`（platform-api）、`/mesh/gatewayclass-refs`、`/nodes/addresses`、`/storageclasses/provisioners` |
| 对象存储整条链已是 `AccessBoundary.UPSTREAM` + admin client | `ObjectStorageController` / `SeaweedS3Service` |
| 父 Gateway 候选**没有**窄端点：前端直接调租户侧 `/gateways/list` | `useParentCandidates.ts:147` |
| `platform-storage` 里有 SeaweedFS **管理员** Secret | `SeaweedAdminSecretObjects` |
| 平台**没有** ServiceAccount 管理页；租户 SA `tn-<sa>` 在 `platform-system` | `K8sProvisioningService.ensureTenantSa` |

## 3. 目标模型

### 3.1 一句话

**平台的权限来自身份，租户的权限来自分配。**

### 3.2 三条推论

| 推论 | 含义 |
|---|---|
| 平台侧不需要分配行 | 只要有 `PLATFORM_SCOPE`，就能看/管这个集群里的任何命名空间 |
| ns 与租户 **1:1** | 分配表从「授权表」降级为「归属表」：只回答"这个 ns 属于谁" |
| 平台代管 ns 里的对象 → **只投影，不分配** | 租户要几个对象，就给几个字段；不把整个 ns 交出去 |

### 3.3 判断准则（每遇到一个特殊 ns 都要过一遍）

> 问一句：**租户要的是这个 namespace，还是这个 namespace 里的某几个对象？**
>
> - 要 namespace → 它本来就是租户自己的 ns，正常分配
> - 要几个对象 → **不分配**，出窄投影端点

按这条过现有需求，没有一个需要「把平台 ns 给租户」：

| 需求 | 租户要的 | 结论 |
|---|---|---|
| 拉自己 Harbor 项目的镜像 | 一个 Secret 的内容 | 平台写进**租户自己的** ns（§6） |
| 桶 / 对象凭据管理 | SeaweedFS 的业务视图 | 已是 UPSTREAM，保持 |
| 把路由挂到对外网关 | `istio-system/external-gateway` 的几个字段 | 新增窄端点（§5.3） |
| 选 GatewayClass | 集群级对象的只读引用 | 已有 `/mesh/gatewayclasses`，核对权限码 |
| 看网格拓扑 | 平台算出来的聚合 | 已是平台侧，核对租户可达性 |
| 看组件状态 | 平台探针结果 | 已是平台侧 |

### 3.4 平台侧身份与租户成员（2026-10-31 追加）

「平台代管」原来还有一个隐性前提：**管理员必须先被加成某个租户的成员**，才能切进那个租户的视图 ——
因为切租户视图的两道门都查成员表：

| 门 | 位置 | 作用 |
|---|---|---|
| 切换器列哪些 | `CurrentUserQuery.myTenants()` | 决定下拉里有什么 |
| 能不能真切过去 | `TokenExtrasService.resolveTenant()`（platform-auth，续期 grant 换 token 时）| 否则 `invalid_grant` |

这会让 `platform_user_tenant` 变成**切换器开关**，而它的本义是"这个租户有哪些人"。定案：

1. **平台侧身份（`platformRoles` 非空）切租户视图不依赖成员关系**：两处都放行 —— `myTenants()` 返回
   全部**启用**租户，`resolveTenant()` 对启用租户直接给出 `UserTenantInfo`。**两处必须一起改**，
   只改一半就会出现"切换器里点得动、切过去 400"。
2. **禁止新增**：平台用户不得被加为租户成员、也不得被新授予租户角色（`TenantMemberService`）。
   理由：平台身份的权限不来自租户角色（admin 的平台角色本身持有全部 `tenant:*` 码），
   把加进成员表只会让成员列表变脏，还会出现"租户管理员给平台管理员授权"的语义倒挂。
3. **存量保留**：已有的成员行与租户角色一律不动 —— 撤销平台角色后要能回到原状态，
   删掉等于把降级做成不可逆。
4. **给一句提示**：授予平台角色时若该用户仍是某些租户的成员，回一句"这些关系只影响他能切进哪些租户的视图"。

> ⚠️ 平台用户切进**不是自己成员**的租户后，权限闭包里只有平台族（他没有那个租户的角色）。
> 功能上无碍（admin 的平台角色已含全部 tenant 码），但"不是成员却拿到了 `tenantInfo`"要在文档里说清 ——
> 审计按帽子记录，只是"成员名单"和"上下文来源"从此不是同一件事。

## 4. 边界解析改造

### 4.1 `ResourceAccessResolver`

两处改动：**① 平台身份优先；② 平台路径不再查分配表。**

```java
public AccessContext resolveNamespacedAccess(Authentication auth, String explicitTenantId,
                                             String clusterId, String namespace) {
    String tokenTenantId = tokenTenantIdFrom(auth);

    // ① 平台身份优先：只要 token 有 PLATFORM_SCOPE 就是 admin client。
    //    租户帽子（tokenInfo）此时只作「归属标注」，不再决定 client。
    //    ⚠️ 这是与今天的语义反转：现在是「即使 token 同时带 admin authority，也以 token 租户为准」。
    if (isPlatformSide(auth)) {
        assertClusterAccess(clusterId);
        String tenantId = StringUtils.hasText(tokenTenantId) ? tokenTenantId
                : StringUtils.hasText(explicitTenantId) ? explicitTenantId
                : tenantNamespaceMapper.selectTenantId(clusterId, namespace);   // 新增，可返回 null
        return new AccessContext(tenantId, true);
    }

    // ② 租户模式：身份以签名为准，边界 = 分配表三元组（行为不变）
    if (tokenTenantId == null) {
        throw new CloudPlatformException(EnumResponseType.NON_AUTH_ENTRY_POINT);
    }
    if (StringUtils.hasText(explicitTenantId) && !tokenTenantId.equals(explicitTenantId)) {
        throw new CloudPlatformException(EnumResponseType.TENANT_MISMATCH);
    }
    assertNamespacedAccess(tokenTenantId, clusterId, namespace);
    return new AccessContext(tokenTenantId, false);
}
```

配套：`assertPlatformSide` 里那段 authority 判断抽成 `isPlatformSide(auth)`，断言方法复用它。

- **`assertPlatformSide` 语义不变** —— 它仍是「谁能拿到 admin client」的**唯一**守门点，
  只是现在它是**第一道**判断，而不是最后一个 else 分支。
- `resolvePlatformNamespacedAccess(clusterId)` 退化成平台分支的特例，保留以兼容现有调用方。
- `resolveNamespacedAccessOrPlatform`（中间件实例在用）**不用改**：它把两种身份都转给
  `resolveNamespacedAccess`，语义自动跟着变。
- `assertNamespacedAccess` **保留为 public**：租户路径仍在用，个别服务也会主动问。

**为什么必须先判平台身份（而不是先看帽子）**：现在的顺序让管理员切到租户视图后用的是**租户 SA**，
于是租户把角色模板改坏、RoleBinding 误删时，管理员也进不去 —— 平台的兜底能力被交给被兜底方。
代管的语义应该是「保留管理员身份，只换看谁的数据」。

### 4.2 `tenantId` 的语义

| 调用方 | 入参 `tenantId` | 用途 |
|---|---|---|
| 租户成员（无 `PLATFORM_SCOPE`） | 必须与 token 一致，否则 `TENANT_MISMATCH` | **身份** + 分配表边界 |
| 平台管理员，**无**租户帽子 | 可空 | 仅作归属标注；为空时按 `(clusterId, namespace)` 反查分配表 |
| 平台管理员，**戴**租户帽子 | 忽略，**以帽子为准** | 仅作归属标注；client 仍是 admin |

优先级：**帽子 > 显式参数 > 反查**。"你切到了哪个租户就以哪个为准"——比让请求体改标注更可预期。

反查得到空（该 ns 没分配给任何租户，如 `kube-system`）是合法状态：对象没有归属，`stamp` 写 null。

> 这一步让 admin 调用的 `tenantId` 参数从「授权依据」降级为「可选的标注」。
> **后续可以进一步把它从 API 里删掉**（服务端一律反查），但那是一次跨前后端的机械改造，不在本批。

### 4.3 三类边界各自的变化

| 边界 | 现在 | 之后 |
|---|---|---|
| `PLATFORM` | 要求 `PLATFORM_SCOPE` | 不变 |
| `TENANT` | 租户走分配表 + 租户 client；平台走分配表 + admin client | 租户不变；**平台不再查分配表**，直接 admin client |
| `UPSTREAM` | 本层不判，收窄在上游 | 不变 |

### 4.4 安全性

- 拿 admin client 的唯一入口是 `assertPlatformSide` / `isPlatformSide`（`data.platformRoles` 非空，
  签发期已按 `platform_role.scope='PLATFORM'` 过滤），与 HTTP 路径无关 —— WS worker 线程、内部调用同样成立。
- **不授予任何新能力**：平台管理员本来就通过 `PLATFORM` 边界端点使用 admin client。
  本次只是把「同一件事在几条路径上的不一致」抹平。
- **真正的代价是「租户视角」没了**：管理员在租户视图里看到的不再是租户的真实权限，
  而是管理员权限。所以：
  - 页面 / 文档要写清「管理员看到的权限 ≥ 该租户成员」；
  - 需要真实验证租户权限时，**用租户账号登录**——那才是唯一可信的验证，平台内的模拟永远可能有偏差。
- 风险从「越权」变成「误伤」：平台可以看到并删除任何 ns 的对象。处置见 §7.1 归属显示。

## 5. 租户可见的平台对象 → 窄投影端点

### 5.1 四条规范（缺一不可）

1. **固定 namespace 白名单** —— 不能由调用方随便传；清单写死在服务端常量里。
2. **字段投影** —— 返回一个窄 DTO，不复用全量 `XxxDTO`。
3. **`AccessBoundary.TENANT` + 独立权限码** —— 不复用平台侧的码。
4. **只读** —— 不做写。

与既有先例同形：`/nodes/addresses`、`/storageclasses/provisioners`、`/mesh/gatewayclasses`。

### 5.2 首批清单

| # | 端点 | 状态 | 动作 |
|---|---|---|---|
| 1 | 父 Gateway 候选 | ✅ **已实现**：`/mesh/parent-gateways`（§5.3） | — |
| 2 | GatewayClass 引用 | 有（`/mesh/gatewayclasses`） | 核对：TENANT 可达 + 权限码独立 |
| 3 | 网格拓扑 | 有（`/mesh/topology`） | 核对租户可达、按租户收窄 |
| 4 | 组件状态 | 有（`ClusterComponentService`） | 核对走 admin |
| 5 | 对象存储 | 有（UPSTREAM） | 不动 |

> 这张表是**开放清单**：以后每加一个「租户要看平台 ns 里的东西」，就往这里加一行。
> 不允许用「分配 ns」解决。

### 5.3 父 Gateway 候选（新增）

**问题**：租户配 HTTPRoute 时要选 `istio-system/external-gateway` 当父，而 `useParentCandidates`
调的是租户侧 `/gateways/list` —— 租户在那个 ns 里 401/403，候选为空，只能手打名字（打错静默不挂载）。

**做法**：k8s-server 新增 `POST /parent-gateways`（`AccessBoundary.TENANT`），admin client 读：

```
入参：clusterId
      namespace        路由**自己**所在的 ns —— 边界：租户必须对它有一条分配行
      targetNamespace  父引用**指向**的 ns（可空 = 与 namespace 同）
      tenantId         平台管理员代操作时可空
出参：[{ name, namespace, listenerNames: string[], waypointFor: string|null }]
可读判定：target 与路由同级 / target 在固定白名单 {"istio-system"} / 调用方是平台侧身份 /
          租户对 target 本来就有分配行；否则返回**空列表**（候选列表不该报错）
```

⚠️ **两个命名空间参数不能合并**：合并之后租户就能拿"自己有分配行的 ns"当通行证去读任意命名空间，
这个端点会变成越权入口 —— 比不开还糟。

- 白名单写死在服务端，**不接受调用方传**；
- 只回 `parentRefs` 需要的那几个字段（不给 labels/annotations/status）；
- platform-api 侧对应一个 `POST /mesh/parent-gateways`，**复用 `tenant:gateway:list`**
  （多端点共用一个码是本项目的既有约定，`V2026_10_08_4` 已有 `/gateways/list` 用它；
  不新造码就不用再加权限行和角色绑定）；
- 前端 `useParentCandidates` 在「kind=Gateway」时统一改调这个端点（本租户 ns 也能走它，
  候选集合不变，省掉两套分支）。

## 6. 租户镜像拉取凭据

### 6.1 问题与目标

租户流水线把镜像推到自己租户的 Harbor 项目 `t-<sa>`，那个项目是**私有**的。租户建的工作负载 Pod
拉这些镜像时没有凭据，`ImagePullBackOff`。凭据数据已经在库里，缺的只是「送进命名空间」这一步。

目标：租户零操作；平台自己的镜像（在公开读的 `platform` 项目里）行为不变；不削弱租户隔离。

### 6.2 方案：`default` SA 打底

```
租户命名空间 <ns>
├── Secret platform-registry-pull      ← 平台写
├── ServiceAccount default             ← imagePullSecrets: [platform-registry-pull]
└── Deployment / StatefulSet / …       ← 不写 serviceAccountName → 用 default → 自动带凭据
```

为什么是 `default` 而不是租户 SA：

- 租户 SA `tn-<sa>` 在 `platform-system`，Pod 的 `serviceAccountName` **只能引用同一个 ns 里的 SA**；
- 就算在每个 ns 再造一份 `tn-<sa>`，容器里就会挂上**租户的完整 RBAC token** —— 应用被攻破就是租户被接管。
  **隔离边界不发给业务容器。**
- `default` 在租户 ns 里没有任何 RoleBinding，是个无权限的空 SA —— 正好「能拉镜像、摸不到 API」。

### 6.3 哪些凭据进 Secret

| 顺序 | 来源 |
|---|---|
| 1 | `platform_registry_project`（clusterId + tenantId，status=READY）的 `credential_id`（平台 robot，必须有） |
| 2 | `platform_credential` 中 `tenant_id` = 该租户、`enabled = 1`、`type ∈ {HARBOR, DOCKERHUB}` 的其余行 |

**不进**：`GIT` 类型、`enabled = 0`、别的租户的行；没有 READY 行时整个跳过（不报错）。

**同 host 冲突**：平台 robot 优先，其余跳过并 warn。理由见 §6.4。

> §3.2 的 1:1 之后，「一个 ns 归两个租户」不再存在，所以这里**不需要**任何「共享 ns 跳过」的规则 ——
> 这正是先做 §4 / §8 的原因。

### 6.4 Secret 的形状

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: platform-registry-pull
  namespace: <租户 ns>
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: <base64>
```

解码后 `{"auths": {"harbor.example.com": {username, password, auth}, ...}}`。

- **`auths` 是 map，一个 Secret 能装多个 registry** —— 「一个 ns 用两个仓库」绝大多数情况仍是
  **一个 Secret、两条 auths**。
- auths 的 key 用 `PipelineConnectionRef.hostOf(endpoint)`（去 scheme、留端口），与流水线那份
  `dockerConfigJson()` **同一套规则**，不写第二份。
- 用 `createOrReplace`（幂等）。
- 去重到「一个 host 一条」：containerd 对同一 host **按顺序取第一条匹配**，与其让它运行时随机赢，
  不如写入时定死。

### 6.5 写入通道

k8s-server 新增 `POST /tenant/registry-pull/ensure`（`TenantAdminController`，`AccessBoundary.PLATFORM`），
与 `/tenant/sa/ensure` 同形：

```java
record AdminRegistryPullEnsureRequest(String clusterId, String namespace,
                                      String secretName, String dockerConfigJson) {}
```

k8s-server 侧 `ensureRegistryPullSecret(...)`（幂等，纯执行）：

1. 写/覆盖 `<ns>/<secretName>`（type `kubernetes.io/dockerconfigjson`，key `.dockerconfigjson`）；
2. 读 `<ns>/default` SA → `imagePullSecrets` **合并去重**（保留已有条目，本平台的追加在末尾）→ SSA 写回；
3. SA 不存在则创建（兜底）。

**为什么走 admin client 而不是通用资源端点**：分配命名空间时 RoleBinding 是同一个事务里才建好的；
裸建命名空间（`NamespaceService.create`）则根本没有租户上下文。业务判断（选凭据、拼 JSON）全在
platform-api，k8s-server 只收一个拼好的 JSON。

**配套 DTO 改动**：`ServiceAccountDTO.imagePullSecrets` + `CoreV1ServiceAccountConverter` 双向映射
（现在不建模这个字段）。

### 6.6 触发点

| 触发 | 位置 | 范围 |
|---|---|---|
| **分配命名空间**（主路径） | `NamespaceAllocationService.allocate`（`ensureRoleBinding` 之后） | 这一个 ns |
| **Harbor robot 新建 / 轮换** | `HarborRegistryService.ensureOne`，**仅真重建 robot 时** | 该租户在该集群全部已分配 ns |
| 租户启用 | 租户启用路径 | 该租户已分配的全部 ns |
| **手动重下发** | `POST /registry/projects/pull-secret/sync`（`platform:registry:project:manage`） | 全部集群 × 租户 × 已分配 ns |

- 除「分配命名空间」外一律 **best-effort**（失败只 warn）；
- robot 轮换那条**必须做**：老口令留在 Secret 里会 401，现象和"没配凭据"一模一样。

> 不在 `NamespaceService.create` 上挂：`NamespaceUpsertRequest` 没有 tenantId，而命名空间是集群级资源、
> 创建走平台路径 —— 只能靠"调用方当时戴着哪顶租户帽"猜，不可靠。租户真正拿到 ns 的时刻是**分配**。

### 6.7 工作负载侧

**① 去掉 `serviceAccountName` 输入框**
前端删掉 `WorkloadEditorView.vue` 的 `el-form-item` 与 computed；后端 `PodSpecDTO.serviceAccountName`
**保留**（Pod 详情页还在读，删是破坏性变更），存量值原样 round-trip。
理由：平台没有 SA 管理页，租户建不了 SA，填任何值 Pod 都起不来，而报错要到 Pod 事件里才看得见。

**② 保留 `imagePullSecrets` 手填框**（工作负载级覆盖）
K8s 的 ServiceAccount admission 会把 SA 上的 `imagePullSecrets` **追加到 Pod 自己那份之后**（同名去重），
所以合并顺序是 `[工作负载自己填的…] + [default SA 的 platform-registry-pull]`，同 host 冲突时
**工作负载自己选的那条先被尝试** —— 「工作负载覆盖选择 secret」天然成立。

**③ 写入时兜底**
仅当 `serviceAccountName` 非空且 ≠ `default`、且 `ownerReferences` 为空时，才把 `platform-registry-pull`
追加进 `podTemplate.spec.imagePullSecrets`。（租户建不了 SA，这条几乎触发不到；留着是为了将来
应用编排 chart 自带 SA 时不至于拉不下来。）

### 6.8 回填

存量命名空间不写迁移脚本 —— ns 是集群里的对象，SQL 碰不到。靠一次「重下发」。
页面入口放在「镜像仓库 → 租户镜像项目」（`RegistryView.vue`），与「同步全部 / 重建」同屏。
重下发只覆盖 `platform_tenant_namespace` 里分配出来的 ns，不去扫集群全部命名空间。

## 7. 前端

**导航结构不动。** 平台侧看全部租户的能力放在后端；前端沿用「平台视图 / 租户视图」两套菜单 + 切租户换 token 的既有交互。

要改的只有四处小的：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `WorkloadEditorView.vue` | 去掉 `serviceAccountName` 输入框（§6.7①） |
| 2 | `RegistryView.vue` | 加「重下发拉取凭据」按钮（§6.6） |
| 3 | `useParentCandidates.ts` | Gateway 候选改调 `/mesh/parent-gateways`（§5.3） |
| 4 | 工作负载 / Pod 列表 | 平台视图下显示**归属租户**列（见下） |

### 7.1 归属显示

平台侧能看到所有 ns 之后，列表里"这个对象是谁的"必须显式标出来，否则误操作面变大。
后端 `stamp` 已回填 `tenantId`，前端补一列即可。

### 7.2 管理员切租户视图 = 代管（本轮由 §4.1 落地，前端零改动）

`TenantSwitcher` 仍是 `switchTenant()` 重签一个带租户上下文的 token —— **前端行为一点不变**。
变的是后端的解读：token 里有 `PLATFORM_SCOPE` 时一律走 admin client，帽子只作归属标注。

所以管理员切过去之后：

- ✅ 用的是**管理员权限**（不再是租户 SA）——租户把模板改坏、RoleBinding 误删，管理员照样进得去
- ✅ 能越出那个租户的 ns，去看任意命名空间
- ⚠️ 看到的不再是租户的真实权限（§4.4）

**下一批（不在本轮）**：前端把「身份」与「作用域」拆开 —— 管理员不再靠换 token 切租户，
而是用一个**作用域筛选器**；8 个域常驻，切换不再整页刷新。那批会动
`nav.ts` / `MainLayout` / `TenantSwitcher` / context store，另立 spec。

## 8. 迁移

### 8.1 顺序（必须按这个顺序）

```
① 边界解析改造（平台侧放行）  → ② 补窄端点  → ③ 撤平台 ns 的租户分配 + 加唯一键
                                              → ④ 拉取凭据  → ⑤ 前端
```

- **① 可以最先落**：它只**放宽**平台侧，不改变任何租户行为，单独上线没有回退风险。
- **③ 必须等 ②**：撤分配会让依赖它的页面立刻空掉（父 Gateway 候选、网格页），窄端点先上。
- **③ 内部再分两步**：先删重复分配 → 再加唯一键（§8.2），顺序反了会建索引失败。

### 8.2 唯一键

迁移文件：**`V2026_10_31_3__tenant_ns_single_tenant.sql`**（去重 + 撤平台 ns 分配 + 加唯一键，含预检 SELECT）。

> 唯一键要**手工重放**才生效，所以代码侧也先判一次（`NamespaceAllocationService.create`：
> 任何已存在的 `(cluster, ns)` 分配都拒，文案指名道姓说归哪个租户）—— 否则用户撞上的是
> SQL 的 `Duplicate entry`。前端分配对话框同时把已分配的 ns 从候选里滤掉（省一次白跑）。

```sql
-- ① 查重复：哪个 (cluster, namespace) 被分配给了多个租户
SELECT cluster_id, namespace, GROUP_CONCAT(tenant_id ORDER BY create_time) AS tenants
  FROM platform_tenant_namespace
 GROUP BY cluster_id, namespace HAVING COUNT(*) > 1;

-- ② 规则：保留 create_time 最早的那一行（最早那次分配视为「原本的归属」），其余删掉；
--    同一时刻的按 id 兜底，保证结果确定。
--    ⚠️ 删之前先人工核对上面那条 SELECT 的结果 —— 这条 DELETE 只处理「有更早同行」的行。
DELETE older FROM platform_tenant_namespace older
  JOIN platform_tenant_namespace keeper
    ON older.cluster_id = keeper.cluster_id
   AND older.namespace  = keeper.namespace
   AND (older.create_time > keeper.create_time
        OR (older.create_time = keeper.create_time AND older.id > keeper.id));

-- ③ 再加唯一键
ALTER TABLE platform_tenant_namespace
  DROP INDEX uk_tenant_cluster_ns,
  ADD UNIQUE INDEX uk_cluster_ns (cluster_id, namespace);
```

删掉的那些分配对应的 RoleBinding 也要清 —— 靠「重下发模板」不覆盖删除路径，需要显式删
`tn-tpl-*` RoleBinding（或在撤分配的同一条流程里做）。

### 8.3 撤掉平台 ns 的分配

`istio-system` / `platform-storage` / `kube-system` 这类**不由任何租户拥有**的 ns：

- 撤分配行 → `DELETE FROM platform_tenant_namespace WHERE namespace IN (...)`
- 删对应的 RoleBinding：靠「重下发模板」或显式清理（`ensureRoleBinding` 是幂等 upsert，
  删除路径要单独做一次）
- 撤之前必须确认 §5 的窄端点已上线

### 8.4 存量数据

用户明确这批分配是**测试数据**，可以清。但迁移脚本仍按「先查、再删、后加约束」写，
不假设库是空的。

## 9. 不做的事

| 不做 | 理由 |
|---|---|
| 把 `tenantId` 参数从所有 API 里删掉 | 机械改造，跨前后端；本轮只把它降级为可选标注 |
| 前端导航结构重构（两套菜单合并） | 管理员切租户视图已是真代管（§7.2），本轮不值得动刚合入的 IA 结构。**下一批单独做** |
| 「以租户身份模拟」入口 | 平台侧改用 admin client 后，管理员看到的不是租户真实权限（§4.4）。要验证就用租户账号登录 —— 那才是唯一可信的验证 |
| 把平台 ns 分配给租户 | 打开的是四个边界（RBAC / 配额 / 网络 / Secret 可见性） |
| Mutating webhook / 默认 SA 自动注入 | 平台没有这个先例 |
| 给每个 ns 造一份 `tn-<sa>` | 业务容器会拿到租户完整 RBAC |
| 应用编排 chart 的 imagePullSecrets 入口 | 本轮由 default SA 覆盖 |

## 10. 验收清单

**边界**
- [ ] 平台管理员在**未分配**的 ns 上能 list / get / 修改对象（admin client）
- [ ] 租户在未分配给它的 ns 上仍然 `NAMESPACE_NOT_ACCESSIBLE`
- [ ] 无 `PLATFORM_SCOPE` 的 token 自称代任何租户 → 仍被拒
- [ ] **管理员切到租户视图（token 同时带 `tenantInfo` 与 `PLATFORM_SCOPE`）后走 admin client**：
      在该租户 ns 上做租户 RBAC 不允许的事（如模板只读时仍可写）应当成功
- [ ] **租户的 RoleBinding 被删掉后，管理员仍能进入该 ns 并把它修好**（兜底能力的验收）
- [ ] `/list-all` 行为不变
- [ ] 唯一键生效：同一 (cluster, ns) 插第二条被数据库拒

**窄投影**
- [ ] 租户能拿到 `istio-system/external-gateway` 作为父候选，且只拿到白名单字段
- [ ] 租户拿不到 `istio-system` 的 Secret / Pod
- [ ] 白名单之外的 ns 不能作为候选来源

**拉取凭据**
- [ ] 新建分配 → `default` SA 上出现 `platform-registry-pull`
- [ ] 在该 ns 拉 `harbor.<域名>/t-<sa>/<repo>:<tag>` → Pod `Running`
- [ ] 另一个租户的私有镜像 → 401，确认隔离没破
- [ ] 工作负载自己填的 imagePullSecrets 保存后不被抹掉（合并而非覆盖）
- [ ] 重建 robot → 重下发后新口令生效
- [ ] 工作负载编辑器里不再有 ServiceAccount 输入框

## 11. 风险与待实测

| 风险 | 说明 | 处置 |
|---|---|---|
| **存量 Pod 不重拉** | `imagePullSecrets` 是 Pod 创建时解析的 | 重下发后提示"存量 Pod 需重建" |
| **合并顺序** | SA 的追加在 Pod 自己之后是 K8s admission 的行为 | 真集群验一次；反了就得显式排序 |
| **误伤面变大** | 平台可删任何 ns 的对象 | §7.1 归属显示 + 审计 |
| **撤分配后页面空掉** | 网格 / 暴露 / 组件页可能依赖租户能看到那些 ns | 迁移顺序 §8.1；逐页核 |
| **SSA 覆盖 SA 字段** | 用 fieldManager `platform-system` 写 SA | 实现时看一眼 `managedFields` |
| **HTTPS 证书** | 自签证书需节点 containerd 信任 CA，现象像凭据错 | 集群前提，不在本方案内 |

## 12. 实施顺序（对应 §8.1）

1. ✅ `ResourceAccessResolver`：平台身份优先 + 平台路径不查分配表（含 `isPlatformSide` 抽取、
   `selectTenantId` 反查），补单测：租户仍拒 / 平台放行 / **戴帽子的管理员仍走 admin** /
   无平台身份且无帽子仍拒
2. ✅ 开放清单里的窄端点（父 Gateway）：k8s-server `/parent-gateways` + platform-api `/mesh/parent-gateways`
   + 权限行 `V2026_10_31_4` + 前端 `useParentCandidates` 改数据源
3. ⏳ 唯一键迁移 + 撤平台 ns 分配 + 重下发模板（`V2026_10_31_3` 已就绪，待重放）
4. ✅ 拉取凭据（§6）：`RegistryPullSecretService` + k8s-server `/tenant/registry-pull/ensure`
   + 四个触发点（分配 / robot 轮换 / 租户启用 / 手动重下发，权限行 `V2026_10_31_5`）+ 前端按钮
5. 前端四处小改（§7）：✅ 去掉 SA 输入框、✅ 重下发按钮、✅ 父 Gateway 数据源；⏳ 归属列
6. ✅ 平台侧身份与租户成员解耦（§3.4）：`myTenants` / `resolveTenant` 两处放行、禁止新增、
   存量保留、授予时提示
7. 验收清单逐条过
