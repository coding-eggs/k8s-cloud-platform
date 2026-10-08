# 2026-10-08 跨命名空间列举（`/list-all`）：ConfigMap / Pod

## Context

前一批把 k8s-server 的授权收干净（`AccessBoundary` 声明 + 三族前缀去掉）之后，冒出一个**模型缺口**：

- 命名空间级资源只有 `/list`，而 **namespace 是接口层面的必填**（`NamespacedOperations.list(namespace, ...)`）。
- 跨命名空间的读只有按节点那几个（`/nodes/{name}/pods`、`/nodes/podstats`、Calico form-options），都是**手写的**
  `inAnyNamespace()`，不是通用形态。
- 结果：拿不到"集群里现在有哪些 Pod/ConfigMap"；也运维不了**不属于任何租户**的命名空间
  （`assertNamespacedAccess` 要分配表三元组，而这些命名空间必然没有分配行），除非给它们编一个影子租户。

根因是**两个正交轴被压成一维**：作用域（单命名空间 / 跨命名空间）× 边界（租户 / 平台），
而 `namespace` 被做成了必填参数，于是"平台边界 × 跨命名空间"这一格无处安放。

## 设计：两条归属判据（能力 + 授权）

| 层 | 落点 | 为什么在这 |
|---|---|---|
| 能力 | `k8s-core` `NamespacedOperations#listAll(labelSelector, fieldSelector)` | `inAnyNamespace()` 是 K8s 能直接回答的问题；k8s-core 里已手写过 3 处，本批收进接口。**实现与否 = 本资源支不支持** |
| 形态 | `k8s-server` `AbstractNamespacedResourceController` 的 `/list-all`（所有子类都有） | 支不支持由 ops 决定、暴不暴露由 api 侧权限表定，这里不再加第三处判断 |
| 授权 | `platform-api` **独立路径** + 独立权限码 | 权限表按 `(方法, 路径)` 定码 —— 同一路径同一方法只能是一组 code，想单独授权就必须另开路径 |

**刻意没做的三件事**：

1. **不给 `list()` 加"namespace 可空 = 全部"的隐式分支**。那会把危险语义藏进"漏传参数"里
   （拼错参数就从"看不见"变成"看见全集群"），而且写操作会跟着一起放开。
2. **不做租户侧的"我的全部命名空间"**。那是业务（命名空间集合来自分配表），该在 platform-api 扇出，
   不该塞进这一层的语义。
3. **不给 `/list-all` 加 controller 级开关**（初版加过 `platformCrossNamespaceList()`，随后删除）。它是同一个
   决定的第三个闸门：暴露与否本来就在 api 侧权限表里定，而漏配权限行会被启动期交叉校验拒启。
   它买到的只有"挡住 ops 已实现但平台不想暴露时的直连路径"这一条薄边，不值得在每个资源上多维护一个概念。

**默认不降级的安全性**：`listAll` 在接口上是 `default` 实现**抛业务异常** `OPERATION_NOT_SUPPORTED`
（不是退化成单命名空间 list，也不是 500）。于是：资源没实现 → 明确的业务错误（响亮，而不是一份看起来正常
却不完整的残缺数据）；资源实现了但平台没配码 → 没有可达路径（安全）。

## 改动清单

**k8s-core**
- `operations/NamespacedOperations.java`：新增 `listAll`（default 抛 `UnsupportedOperationException`）。
- `operations/core/CoreV1ConfigMapOperations.java`、`CoreV1PodOperations.java`：实现 `listAll`（`inAnyNamespace()`）。

**k8s-server**
- `controllers/base/AbstractNamespacedResourceController.java`：
  - `@PostMapping("/list-all")`：`resolvePlatformNamespacedAccess(clusterId)`（**平台侧身份校验在此**：
    租户 token 不持 `PLATFORM_SCOPE` 直接拒）→ 经 `ops(ctx, clusterId)`（adminMode ⇒ admin client）调 `listAll`；
  - `stampCluster(...)`：**只回写 clusterId，不碰 namespace**（跨命名空间时每项 namespace 各不相同，
    用原来的 `stamp` 会把它们全写成同一个）。
- `controllers/namespace/ConfigMapController.java`、`PodController.java`：**无代码改动** —— 只支持这一能力的
  声明落在各自 ops 的 `listAll` 实现上，controller 的类注释写明"本资源支持 /list-all"。

**platform-api**
- `k8s/K8sClient.java`：`listAll(query)` → `POST {apiPath}/list-all`。
- `services/ConfigMapService.java`、`PodService.java`：`listAll(query)`。
- `controllers/ConfigMapController.java`（`POST /configmaps/list-all`）、`PodController.java`（`POST /pods/list-all`）。

**权限**：`V2026_10_08_2__cross_namespace_list_permissions.sql`
—— 两行 API 记录（`platform:configmap:list-all` / `platform:pod:list-all`）+ 绑 `builtin_role_admin`。

**前端**
- `apiCodes.ts`：`configmapListAll` / `podListAll`。
- `api/index.ts`：`ListAllCtx`（`Ctx2 & { labelSelector? }`，**不带 namespace**）+ 两个 `listAll`。
- `views/resource/ConfigMapView.vue`、`PodView.vue`：「全部命名空间」开关（仅持码可见）+ 全局视图下的
  命名空间列 + 只读说明条；**全局视图下整列隐藏行内操作**。

## 验证（2026-10-08）

| 项 | 结果 |
|---|---|
| `mvn test` | k8s-server **13** ✓（新增 3 例）/ platform-api 151 ✓ / platform-common 6 ✓ / k8s-core 87 中 1 个**既有失败**（与本批无关） |
| 前端 | `vue-tsc --build` + `vite build` 通过 |
| 浏览器实测（假 token + XHR stub，未打真实后端） | 普通模式：`POST /api/pods/list`，表头无「命名空间」、有操作列；切「全部命名空间」后：**新增 `POST /api/pods/list-all`**，表头**多出「命名空间」**、**操作列整列消失**，两行分别显示 `kube-system` / `default`（后者即"不属于任何租户的命名空间"这一诉求） |

新增单测 `AbstractNamespacedResourceControllerListAllTest` 钉住两条不变式：
返回项**各自保留自己的 namespace**（只回写 clusterId）；**未实现 `listAll` 的资源返回
`OPERATION_NOT_SUPPORTED`**，而不是 500 或静默降级成单命名空间 list。

## 部署

与 `V2026_10_08_1` 一起：**先重放迁移，再起新构建**（`PermissionCrossCheckRunner` fail-closed，无权限行即拒启）。
本批无破坏性变更（既有 `/list` 语义一字未改）。

## 未做 / 下一步

- 只给 **ConfigMap + Pod** 实现 `listAll`（=`/list-all` 可用）。其余资源要开放：覆写其 ops 的 `listAll`
  + 在 platform-api 加路径与权限行（**两处**）。
- **租户侧的"我的全部命名空间"**没做（判据：属 platform-api 扇出）。
- **前端全局视图是只读的**：要支持跨命名空间直接操作，得先解决"行内动作按顶栏 namespace 解析"这个错位
  （按 row.namespace 构造上下文，且编辑页要能接收显式 namespace）。这是独立的一批。
- `CoreV1*Operations.list` 里 `ns = StringUtils.hasText(namespace) ? namespace : ""` 后 `inNamespace("")`
  是未定义行为（今天靠上游 resolver 兜住 namespace 必填）。本批没动；若要清理需 15 个实现类统一。
