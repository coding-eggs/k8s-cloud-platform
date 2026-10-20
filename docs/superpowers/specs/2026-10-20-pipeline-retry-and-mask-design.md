# 流水线节点重试与节点屏蔽设计

> 需求（用户 2026-10-20 原话）：「流水线功能我希望支持节点重试功能，还有就是希望支持可以选择节点屏蔽功能，
> 比如一个节点后接三个节点，我希望只运行两个」。
> 前置：`docs/superpowers/specs/2026-10-15-ci-tekton-design.md`（片① 定义·运行·日志，片② onError + finally）。
>
> 本批是 **S3.5 的增补**，不改变片①/片② 已定的边界（见 `2026-10-15-ci-tekton-design.md` §10 那张表）。

---

## 0. 两句话

- **节点屏蔽**：触发运行时可勾选「本次不跑」的节点，规则是**连同它的全部下游一起不跑**；定义本身不动。
- **节点重试**：一条**没成功**的运行结束后，从「断点」继续 —— 新建一条跑**全部断点及其下游**的 PipelineRun，
  **沿用父运行的工作区**（源码与已成功的构建产物都在里面）。断点 = 自己没成功、且上游全都成功了的节点。

---

## 1. 已定决策（brainstorming 结论，2026-10-20）

| 决策点 | 结论 | 理由 |
|---|---|---|
| **「节点重试」指哪种** | **运行结束后手工从断点继续**（不是定义期的自动重试） | 用户明确选择。自动重试（Tekton `retries`）本批不做 |
| **从哪个节点开始重试** | **只能点「断点」** —— 自己没成功、且**全部上游都已成功**的节点 | 用户 2026-10-20 明确：`A→B→C` 里 `B` 失败时 **`C` 不能作为起点**（上游没成功）。起点由连线推出来，不给用户自由拼接的机会 |
| **重试范围怎么定** | **所有断点的子树之并**；并行的其它断点自动一起补 | Tekton 的停止是整条运行级的（`A2` 失败会把 `B1` 一起取消）。只补一条分支会产出一条「成功但流水线没跑完」的记录，而成功即删工作区 —— 另一条分支就永远补不了了 |
| **用户能不能自己挑重跑集合** | **不能** | 用户只决定「从哪个断点开始」；集合由服务端按连线算。前端只负责按钮的可用性与原因提示 |
| **成功运行能不能重试** | **不能** | 用户选择。成功即删工作区（§6.3），没有可续跑的基础；想重跑就整条重来 |
| **收尾节点能不能重试** | **能，单独重跑它自己** | 收尾节点不参与连线，「上游全成功」对它不成立；它没跑成时把它当普通任务执行一次。重试普通节点时**不会**自动带上收尾节点（避免通知/清理重复执行） |
| **重试能不能省掉重跑** | **必须保留共享工作区** | 节点之间**没有任何 `$(tasks.X.results)` 引用**（2026-10-20 核实：三个内置节点都不写 results），数据全走共享 workspace。所以只要工作区还在，「只重跑失败节点及其下游」在语义上成立 |
| **工作区保留多久** | **24 小时**（可配 `retention`） | 用户选择「保留但窗口很短」。平衡配额占用与「隔天还能续跑」 |
| **屏蔽在哪一层生效** | **每次运行时选（单次生效）** | 用户选择。定义（`canvas_json`）不动，运行行上留存「本次屏蔽了谁」 |
| **屏蔽怎么落到 Tekton** | **渲染前把画布裁剪成子画布**，不引入 `when` | 见 §5：剪枝不依赖任何未实测的 Tekton 行为，且与「上游被移除 = 依赖已满足」的重试语义天然一致 |
| **剪枝规则写在哪** | **platform-api**（`PipelineCanvasRefs` 旁新增纯函数类） | 那里本来就在解析画布 JSON；渲染器因此**零改动**；闭包规则只有一个落点 |
| **重试普通节点时跑不跑 finally** | **不跑** | 收尾节点在原运行里已按当时的聚合状态跑过；重试再跑会让通知/清理类动作重复执行。想单独重发一次通知 → 直接点那个收尾节点 |
| **重试与屏蔽的关系** | 重试**继承**父运行的屏蔽集合 | 被屏蔽的节点在父运行里没有状态，本就不该出现在重试范围里 |
| **失败/取消/超时运行怎么处理工作区** | **保留**到 TTL；**成功立即删** | 只有「没跑成」的运行才需要续跑 |
| **本批的权限点** | **不新增**，屏蔽与重试都复用 `tenant:pipeline:run` | 它们都是「触发」的一种 |

---

## 2. 交付边界

**做**：触发时勾选屏蔽节点（含闭包展开与前端级联）· 运行行记录本次屏蔽集合 · 从断点继续
（断点判定、子图重建、工作区沿用、血缘）· 工作区 PVC 的平台自建与 TTL 回收 · 列表页的「待重试工作区」查看与清理 ·
运行详情新增三种节点态 · 迁移、审计、测试。

**不做**：自动重试（Tekton `retries`）· 运行中重试 · 成功运行的重试 · **只补一条分支**（有意不做，见 §1）·
屏蔽的持久化（写进定义）· 工作区内容浏览/下载 · 重试普通节点时顺带跑收尾节点 ·
老运行（本批之前，没有 `workspace_pvc`）的重试。

---

## 3. 语义

### 3.1 屏蔽：闭包，不是单点

用户勾选的是「想跳过的那几个节点」，平台展开成**闭包**：

```
屏蔽闭包 = 勾选的节点 ∪ 它们的全部传递下游
```

规则只有一条：**被屏蔽节点的下游必须一起屏蔽**。理由不是洁癖 —— 下游节点的输入往往就在共享工作区里，
由被屏蔽的节点产出。若只跳过它自己而让下游照跑，下游会读到上一次运行留下的、或者根本不存在的产物，
失败形态还很难看懂。

于是「一个节点后接三个叶子节点，只跑两个」= 勾选第三个叶子，闭包就是它自己。

**前后端分工**：
- 前端按闭包**级联勾选**（取消一个节点 → 它的下游一起取消并禁用），只是交互便利；
- **服务端自己再展开一次**（不信任提交的集合），落库存展开后的完整集合。

**边界**：展开后如果一个「非 finally 的普通节点」都不剩 → 拒绝触发。前端在对话框里先拦住
（可运行数为 0 时禁用提交），后端仍要有兜底 —— 渲染器既有的「全是收尾节点」校验会抛，但那条报错文案
说的是收尾，不该让用户看到；所以在触发入口用一句话拦住。

### 3.2 重试：从断点继续

**谁能重试**：

- 运行：状态 ∈ `FAILED` / `TIMEOUT` / `CANCELLED`（`RUNNING` 不行；`SUCCEEDED` 不行 —— 工作区已删，见 §6.3），
  且 `workspace_pvc` 非空（老运行没有这一列）。
- 节点：**断点**。

**断点** = 自己没成功、且**它的全部传递上游都已经成功**的节点。

- 「没成功」包括：`FAILED`、`CANCELLED`、`SKIPPED`、以及**根本没有状态**（没轮到它）。
- 「上游成功」用**有效状态**判定：本运行自己的 `steps_json` ∪ 本运行的沿用集合（见下）。
  重试运行会再重试时，链上更早的成功不会被重新计算 —— 沿用集合就是为此存在的。

**重试范围**（服务端算，用户不提交集合）：

```
frontier = { n ∈ 普通节点 | 有效状态(n) ≠ SUCCEEDED 且 ∀ 祖先 a: 有效状态(a) = SUCCEEDED }
toRun    = ⋃_{f ∈ frontier} ( {f} ∪ f 的全部传递下游 )   − 屏蔽闭包
```

**为什么并行断点要一起补**：Tekton 的停止是整条运行级的。`A2` 失败会把正在跑的 `B1` 取消、
把没开始的 `B2` 跳过 —— B 不是自己坏的，是被 A 掀翻的。只补 A 分支的话，新运行会「成功」，
而成功即删工作区，B 分支就**永远补不了**了。

**两个分支的例子**：

```
        ┌ A1(成功) ─ A2(失败) ─ A3(没轮到)
git ───┤
        └ B1(被取消) ─ B2(没轮到)
```

| 节点 | 可点？ | 原因 |
|---|---|---|
| `A2` | ✅ | 断点（`A1` 成功） |
| `B1` | ✅ | 断点（`git` 成功） |
| `A3` | ❌ | 上游 `A2` 没成功 →「请从 A2 开始重试」 |
| `B2` | ❌ | 上游 `B1` 没成功 →「请从 B1 开始重试」 |
| `A1` | ❌ | 已成功 →「这个节点已经成功，不需要重试」 |
| 被屏蔽的 | ❌ | 本次运行根本没跑它 |

点 `A2` 或点 `B1`，得到的都是 **`A2, A3, B1, B2`** —— 对话框如实列出「B 分支也停了，一起补完」。

**收尾节点**：不参与连线，「上游全成功」对它不成立。它没跑成时可以**单独**作为目标重试，
范围就是它自己（普通节点的重试不会带上收尾节点）。
渲染时要清掉它的 `finallyTask` / `finallyWhen` 再当普通任务跑：

⚠️ 正常情况下收尾节点渲染进 Tekton 的 `finally[]`，而「只有 finally、没有 tasks」的 PipelineSpec 不成立
（渲染器会拒）。所以裁剪子画布时要**清掉这两个字段**。理由也说得通：用户点了它，就是「现在就执行这一下」——
「前面是否成功」那个时机条件不该再拦它。这条让「裁剪」不再只是筛选，多了一个字段改写；
由测试正面钉住（重试收尾节点 → 子画布里的节点 `finallyTask` 为 false/null）。

**沿用集合**：有效状态为 `SUCCEEDED`、且**不在 `toRun` 里**的节点（不含屏蔽的）。
它们在新运行里不产生 TaskRun，详情页按「已沿用（上次成功）」着色。

**其余节点**：既不在 `toRun`、也不在沿用集合里的（没成功、又不是任何断点的下游）——
新运行里不跑，详情页按「上次未成功，本次未重试」显示。它们**不影响**新运行的成败。
> 普通节点里这种节点其实不存在（任何没成功的普通节点，要么自己是断点，要么是某个断点的下游）；
> 实际落到这一档的主要是**「收尾时机不满足」而没跑的收尾节点** —— 它们不参与连线，也不在断点集合里。

**工作区**：新运行绑定**父运行的同一块 PVC**，所以 git 克隆的源码、已成功节点的构建产物都在。
每次重试都把这块 PVC 的 `retain-until` 刷新一个窗口。

**血缘**：每次重试是一条新运行记录，`parent_run_id` 指向上一次尝试，`attempt_no = 父 + 1`；
可以再重试，形成链。

### 3.3 三种「没跑」

详情页从此有三种「没跑」的节点，**必须区分**（否则用户会以为是 Tekton 跳过了）：

| 显示 | 判据 | 文案 |
|---|---|---|
| 本次已屏蔽 | 节点 ∈ 运行行的 `masked_nodes_json` | 「本次运行屏蔽了它（连同下游）」 |
| 已沿用（上次成功） | 节点 ∈ 运行行的 `reused_nodes_json` | 「沿用上一次尝试的结果，本次没有重跑」 |
| 上次未成功，本次未重试 | 既不在上面两份集合里、也不在本次 steps 里 | 「它上次没成功，但不在这次重试范围内」 |

现有的「上游没成功 / 别的分支失败 / 运行提前结束」那几种 `skipWhy` 分支保持不变，
用于真正的 Tekton 跳过。

---

## 4. 数据模型与契约

### 4.1 迁移 `V2026_10_20_1__pipeline_retry_and_mask.sql`

⚠️ 本仓没有 Flyway，**手工重放**（与 `V2026_10_15_1__pipeline.sql` 同一套做法）。

| 列 | 类型 | 用途 |
|---|---|---|
| `parent_run_id` | `varchar(32) NULL` | 重试来源运行 id；NULL = 首跑 |
| `attempt_no` | `int NOT NULL DEFAULT 1` | 同一条链的第几次尝试 |
| `retry_from_node` | `varchar(64) NULL` | 用户点的那个断点（画布节点 id）。**范围不存这里** —— 由 `steps_json` 与画布共同还原 |
| `masked_nodes_json` | `mediumtext NULL` | 本次**实际不跑**的完整节点集合（JSON 数组，闭包展开后的） |
| `reused_nodes_json` | `mediumtext NULL` | 重试时沿用的节点（JSON 数组） |
| `workspace_pvc` | `varchar(128) NULL` | 本次运行（链）使用的共享工作区 PVC 名 |

**权限行**：本批**不新增权限码**，但**必须新增 4 条权限行** —— 授权是按「端点行」匹配的，
`PermissionCrossCheckRunner` 在启动时会把「没有权限行的端点」当成裸端点直接拒启。四条都用现有码：

| 权限行 | 端点 | 码 | 角色关联 |
|---|---|---|---|
| `perm_pipeline_runretry` | `POST /pipeline/run/retry` | `tenant:pipeline:run` | tenant_admin / tenant_member / admin |
| `perm_pipeline_runretryplan` | `POST /pipeline/run/retry/plan` | `tenant:pipeline:read` | 同上三个 |
| `perm_pipeline_retryws` | `POST /pipeline/retry-workspaces` | `tenant:pipeline:read` | 同上三个 |
| `perm_pipeline_retrywsclear` | `POST /pipeline/retry-workspaces/clear` | `tenant:pipeline:run` | 同上三个 |

⚠️ **角色关联必须逐条显式插**（`platform_role_permission` 按 permission_id 绑，不按 code 继承）——
这是本仓反复踩过的那条（新权限行不绑角色 = 上线即可见但点了 403）。

**`platform-data` 侧的连带改动**（容易漏）：

- `PlatformPipelineRun` 实体加六个字段；
- ⚠️ `PlatformPipelineRunMapper.xml` 的 **resultMap / `Base_Column_List` / `insert` / `List_Column_List` 都要改**。
  列表用的 `List_Column_List` 只加 `parent_run_id` 与 `attempt_no`（「第 N 次」徽标要它俩），
  **不要**把两个 JSON 大字段塞进列表查询 —— 那是既有的取舍（列表几十条不背大字段）。

### 4.2 DTO 与端点

**`PipelineRunDTO` 新增（仅创建期）**

```java
/**
 * 本次运行绑定的工作区 PVC 名。**必填**：
 * 首跑由 platform-api 生成 `platform-pipeline-ws-<runName>`；重试沿用父运行那一块。
 */
private String workspacePvc;

/** true = 沿用（重试）；false = 本次新建（首跑）。 */
private boolean reuseWorkspace;
```

⚠️ **必须区分「新建」与「沿用」**：沿用而卷不存在时必须**拒绝**（工作区已过期），
绝不能「没有就建一块空的」—— 那会让重试在一个空工作区上跑，看起来成功、产物却全缺。

⚠️ **名字由 platform-api 定，k8s-server 只负责「新建就建、沿用就校验并刷新 TTL」** ——
这样「PVC 叫什么」只有一个落点，两个模块不需要各自拼一遍前缀（与 `credsSecretName` 那种
「两边必须算出同一个名字」的约定相反，这里刻意不那样做）。platform-api 也因此能在**创建之前**
就把名字落进运行行，集群创建失败回滚时两边状态一致。

⚠️ 不发「屏蔽集合」「重跑集合」给 k8s-server：它们在 §5 的**画布裁剪**里已经体现，
k8s-server 只收到一份「本次要跑的子画布」。少一个契约，就少一处两边对不齐的地方。

**端点**

| 端点 | 入参 | 权限 | 说明 |
|---|---|---|---|
| `POST /pipeline/run` | `PipelineRunRequest(pipelineId, tenantId, maskedNodeIds)` | `tenant:pipeline:run` | **入参从 `PipelineDTO` 改为新 record**；`maskedNodeIds` 为空/缺省 = 不屏蔽 |
| `POST /pipeline/run/retry` | `{runName, nodeId, tenantId}` | `tenant:pipeline:run` | 返回新 runName；**范围由服务端按 §3.2 重算**（与 plan 同一个函数）；标 `@Auditable(action = PIPELINE_RUN, target = "#request.runName")` |
| `POST /pipeline/run/retry/plan` | `{runName, tenantId}` | `tenant:pipeline:read` | 只读：返回 `{frontier:[…], runNodeIds:[…], reusedNodeIds:[…], blocked:{nodeId: 原因}}`。**前端据此决定哪些节点有「重试」按钮、对话框里列出什么** —— 不让前端自己算断点 |
| `POST /pipeline/retry-workspaces` | `{clusterId, namespace, tenantId}` | `tenant:pipeline:read` | 窄投影：`{count, totalSize, items:[{runName, createdAt, retainUntil}]}` |
| `POST /pipeline/retry-workspaces/clear` | 同上 | `tenant:pipeline:run` | 批量删本命名空间里全部待重试工作区；审计标一个新动作 `pipeline:retry-workspace-clear` |

> 为什么 `/pipeline/run` 换 record 而不是给 `PipelineDTO` 加字段：`maskedNodeIds` 只对「触发」有意义，
> 放进定义 DTO 会让 `create/update` 也背上一个永远被忽略的字段。前端改一行（`pipelineApi.run`），
> `@Auditable` 的 `target` 跟着改成 `#request.pipelineId`。

### 4.3 字段口径

- `masked_nodes_json`：**闭包展开后的完整集合**（不是用户勾的那几个）。首跑与重试各自落一份；
  重试的那份从父运行继承。
- `reused_nodes_json`：只在重试运行上有值。
- `workspace_pvc`：首跑由 platform-api 生成（`platform-pipeline-ws-<runName>`，**在落库那一行时就写**），
  重试继承父运行的值。**老运行这一列为 NULL** —— 重试入口直接置灰并提示
  「这条运行没有保留工作区（功能上线前产生的）」。
- `attempt_no`：首跑 1，重试 +1。
- 两个 JSON 列都按 **JSON 数组** 存（`["git-1","build-2"]`），前端 `JSON.parse`；
  空集合存 `NULL` 而不是 `[]`（与 `stepsJson` 为空的处理一致）。

---

## 5. 画布裁剪（platform-api）

**发往 k8s-server 的不再总是完整画布**，而是按本次实际要跑的节点裁剪过的子画布：

```
首跑：toRun = 全部节点 − 屏蔽闭包
重试：toRun = ⋃ 断点子树  − 屏蔽闭包（断点定义见 §3.2，由服务端算，客户端不提交集合）
裁剪：只保留 toRun 里的节点；只保留两端都在 toRun 里的边
      （目标是收尾节点时，额外清掉它的 finallyTask / finallyWhen）
```

三个后果：

1. **`k8s-core` 渲染器零改动**。它照旧把「节点 + 边」翻成 task / `runAfter`。
   「上游被移除」天然等于「这个依赖已满足」—— 这正是重试要的语义，也是屏蔽闭包保证安全的原因。
2. **闭包与断点规则只有一个落点**：新增 `platform-api/.../services/PipelineCanvasMask.java`
   （包私有，与 `PipelineCanvasRefs` 同处），提供四个纯函数：
   - `maskedClosure(canvasJson, selectedIds)` —— 展开闭包；未知 id → 拒；
   - `frontier(canvasJson, effectiveStatus, masked)` —— 算断点：自己没成功、且全部传递上游都成功；
   - `retryScope(canvasJson, frontier)` —— 断点 ∪ 各自全部下游（收尾断点只有它自己）；
   - `trim(canvasJson, keepIds, retryTargetId)` —— 产出子画布 JSON；`retryTargetId` 是收尾节点时清掉它的
     `finallyTask` / `finallyWhen`。
   > 「落在哪个断点」只是用户的入口与提示；**范围完全由图决定**，与点了哪一个断点无关
   > （收尾节点除外，见 §3.2）。
3. **落库的画布与发送的画布从此不同**：`platform_pipeline_run.canvas_json` 存**完整画布**
   （详情页要画全图），`PipelineRunDTO.pipelineCanvasJson` 发**子画布**。
   这条要写进 `PipelineRunService.run/retry` 的注释里 —— 以后有人看到两处不一致，不会以为写错了。

**实现口径**：直接在 Jackson 树上操作（`tools.jackson`，与 `PipelineCanvasRefs` 一致），
不要反序列化成 `PipelineCanvas` —— 那个 record 在 `k8s-core`，platform-api 依赖不到。
节点对象**原样保留**（含 `position` / `name` / `onError` / `finallyTask`），只做筛选。
`finallyTask` 从 JSON 里读（`node.path("finallyTask").asBoolean(false)`）。

**校验（服务端）**：
- 屏蔽：勾选的节点 id 必须都在画布上（未知 id 直接拒，不静默忽略）；闭包展开后必须还剩至少一个非 finally 节点；
- 重试：`nodeId` 必须存在、不在屏蔽集合里，且**必须是断点或没跑成的收尾节点** ——
  否则按具体原因拒（「上游 X 没成功，请从 X 开始」「这个节点已经成功」「本次运行屏蔽了它」），
  **不静默降级成「从它开始」**；
- 重试：父运行必须已终结且**不是 `SUCCEEDED`**（工作区成功即删），并且 `workspace_pvc` 非空。

---

## 6. 工作区生命周期（本批唯一动到核心的地方）

### 6.1 为什么必须改

今天是 `volumeClaimTemplate`（每次运行现开一块临时 PVC，[PipelineRunOperations.java](../../../k8s-core/src/main/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperations.java) 的 `bindWorkspaces`）。
Tekton 建的 PVC 带 PipelineRun 的 ownerReference —— **删 PipelineRun 就级联没了**，
而归档那条路一结束就删 PipelineRun（`PipelineRunFinisher.finish`）。所以「从断点继续」必须先拥有一块
平台自己管、生命周期独立于 PipelineRun 的卷。

### 6.2 创建与绑定

- PVC 名由 platform-api 定（§4.2）：首跑 `platform-pipeline-ws-<runName>`，重试沿用父运行的。
- k8s-server 收到 `workspacePvc` + `reuseWorkspace` 后：
  - **首跑（reuse=false）且不存在 → 建**：`1Gi`、`ReadWriteOnce`（**与今天一致**，不借机改访问模式）、
    默认 StorageClass；标签 `app.kubernetes.io/managed-by=platform-system`
    + `platform.pipeline/role=workspace` + `platform.pipeline/run=<runName>`；
    注解 `platform.pipeline/retain-until=<RFC3339, now + retention>`。
  - **已存在 → 不重建**，只刷新 `retain-until`。
  - **重试（reuse=true）且不存在 → 拒绝**：「工作区已过期或已被清理，请整条重跑」。
    ⚠️ 绝不能在这里补一块空卷 —— 重试会「成功」而产物全缺。
- PipelineRun 的 `ws` workspace 绑定改成 `persistentVolumeClaim`（`claimName`）。
- PVC 建失败 → **运行不创建**（抛出，platform-api 回滚刚落的运行行）。
- PipelineRun 建失败 → 只有「本次新建的」PVC 才尽力删掉，再抛。
- 重试时父运行的 `workspacePvc` 为 NULL → platform-api 直接拒（§4.3，老运行）；
  名字非空但卷已被清理 → k8s-server 如实报「工作区不存在」，platform-api 转成「已过期」。
  > 两种情形**都要报**，但文案不同：前者是「这条运行产生于功能上线前」，后者是「保留窗口过了」。

### 6.3 归档时的分叉

`PipelineRunFinisher.finish` 在**落库成功之后**：

| 快照状态 | 工作区 PVC | 凭据 Secret |
|---|---|---|
| `SUCCEEDED` | **删**（与 Secret 同一处） | 删（现有行为） |
| `FAILED` / `TIMEOUT` / `CANCELLED` | **保留**到 TTL | 删（现有行为） |

⚠️ 删 PVC 失败**不让归档算失败**（对象已经删了、库已经写了，重投也修不了）—— 记 warn，TTL 兜底。
这与既有 `deleteCredsSecret` 的取舍同形。

⚠️ **凭据 Secret 不保留**：它里面是可解的 git token / registry 认证，重试时由 platform-api
重新解析连接、重新下发一份（与首跑同一条路径）。重试因此也会重新校验「这条连接还有没有被授权」。

### 6.4 TTL 回收

新增 `k8s-server/.../pipeline/PipelineRunWorkspaceReaper.java`：

- `@Scheduled(fixedDelayString = "${platform.pipeline.workspace.reap-interval:10m}")`；
- 逐个集群（集群清单来自 `K8sClusterMapper.listAll()`，与启动扫描同一套）：
  用 **admin 的 `PERSISTENT_VOLUME_CLAIM` ops** 跨全部命名空间 `listAll(labelSelector, null)`，
  过滤 `platform.pipeline/role=workspace`，解析 `retain-until`，过期的 `delete(namespace, name)`；
- 纯逻辑 `reap(Instant now, List<PersistentVolumeClaimDTO>)` 返回待删清单 —— 可单测；
- **一个集群失败不影响其余集群**（逐集群兜异常，与 `PipelineRunStartupSweep` 同形）。

⚠️ 不去读平台库判断「这条运行还在不在跑」：`retain-until` 在**每次运行创建时**（含重试）都会刷新，
而平台侧超时上限是 30 分钟，远小于 24 小时 —— 一块已过期的卷不可能还挂着活跃运行。
这条推理写进类注释，否则以后会有人来加一次 DB 查询。

### 6.5 过期与老运行

- 过期后再点重试：k8s-server 如实拒绝，platform-api 转成
  「工作区已过期（保留 24 小时），请整条重跑」；前端在按钮旁先说清楚。
- 本批之前的运行 `workspace_pvc` 为 NULL → 详情页的「重试」置灰并说明原因。

### 6.6 集群侧前提（**部署必查**）

- 租户的 K8s RBAC 模板里 `persistentvolumeclaims` 必须有 **create / delete** ——
  内置默认模板 `builtin_tenant_ns_admin` 是 `verbs: ["*"]`，**已满足**；
  ⚠️ 定制过模板的部署要自己补。缺了的表现是「触发运行时报 `pvc ... is forbidden`」。
- 有默认 StorageClass（与 `volumeClaimTemplate` 时代同一个前提）。

---

## 7. 重试链路时序

```
POST /pipeline/run/retry {runName, nodeId}
platform-api PipelineRunService.retry:
  1. requireRun(runName)              —— 存在性 + 命名空间边界（既有）
  2. 父运行状态 ∈ {FAILED, TIMEOUT, CANCELLED} 且 workspace_pvc 非空，否则拒
  3. 解析父 `steps_json` + `reused_nodes_json` → **有效状态**（⚠️ stepsJson 要兼容 2026-10-16 之前
     `<runName>-` 前缀的老键）；有效状态 = 本运行自己的结果 ∪ 沿用集合（恒为 SUCCEEDED）
  4. nodeId 存在、不在 masked 集合里，且**是断点或没跑成的收尾节点**；否则按具体原因拒
  5. 读父 canvas_json → `PipelineCanvasMask.frontier(...)` 得断点集合 → `retryScope(...)` 得 toRun；
     reused = 有效状态为 SUCCEEDED 且不在 toRun 里的节点
  6. 解析连接（**重新逐条校授权 + 解密**，与首跑同一条路径）
  7. 生成新 runName（`workspace_pvc` 取父运行那一块，**不新生成**）
  8. 先落库（完整画布 + parent_run_id + attempt_no + retry_from_node
            + masked/reused + workspace_pvc）
  9. 裁剪画布 → 调 k8s-server create（`workspacePvc` = 父的）
 10. 失败 → 删掉第 8 步那行（不能留 RUNNING 的孤儿、也不能删父运行的工作区），抛出
```

⚠️ **第 7 步必须在第 8 步之前、第 8 步必须在第 9 步之前** —— 与首跑同一条理由
（幂等键从源头就有；集群里有对象而库里没记录的那个窗口不存在）。

**首跑**同一条链，只是 `maskedNodeIds` 可能非空、没有 `parent`、`workspacePvc` 由 platform-api 新生成。

---

## 8. 前端

| 界面 | 改动 |
|---|---|
| **触发对话框**（新 `components/pipeline/PipelineRunDialog.vue`，列表页与编辑器的「运行」共用） | 按拓扑序列出节点，默认全选；取消一个节点 → 其传递下游自动取消并**禁用**（不能单独保留下游）；底部「本次运行 N 个 / 屏蔽 M 个」，M>0 时提示「屏蔽节点及其下游本次不执行」；可运行数为 0 时禁用提交 |
| **运行历史**（`PipelineRunView`） | `attempt_no > 1` 的行加「第 N 次」徽标 |
| **运行详情**（`PipelineRunDetailView`） | ① 三种新节点态（已屏蔽 / 已沿用 / 本次未重试），复用现有灰色「已跳过」样式，`skipWhy` 加三条分支；② **只有断点（及没跑成的收尾节点）卡片上有「重试」按钮**；其它节点置灰并给出原因（「上游 X 没成功，请从 X 开始」「已经成功」「本次已屏蔽」）。确认框列出「将重跑：所有断点及其下游 / 沿用：其余已成功的」，跨分支时额外注明「另一条分支也停了，会一起补完」；③ 顶部显示「第 N 次尝试 · 源自运行 xxx」（可点） |
| **流水线列表**（`PipelineListView`） | 构建缓存那一行下面加「待重试工作区：N 块 / X Gi」+「全部清理」；为 0 时显示「无」 |

⚠️ 详情页对「没跑」的三类判断**只读运行行上的两份集合**；而「哪些节点能点重试、点了会跑哪些」一律来自
`/pipeline/run/retry/plan` —— **前端不自己算断点**。断点规则在服务端只有一处实现（§5），
retry 端点与 plan 端点共用它，前端重算一次就多一处会走偏的判据。

---

## 9. 权限与审计

- 屏蔽：属于触发，`tenant:pipeline:run`。
- 重试：属于触发，`tenant:pipeline:run`；`@Auditable(action = PIPELINE_RUN, target = "#request.runName")`。
- 「清理待重试工作区」：`tenant:pipeline:run`；新审计动作 `PIPELINE_RETRY_WORKSPACE_CLEAR = "pipeline:retry-workspace-clear"`。
- 查看工作区列表与重试计划：`tenant:pipeline:read`。
- **不新增权限码，但要新增 4 条端点权限行 + 12 条角色关联**（§4.1）—— 否则启动期交叉校验拒启。
- 命名空间边界：全部走既有的「租户 × 集群 × 命名空间」分配表（`NamespaceAccessGuard` / `PipelineRunService.requireRun`）。

---

## 10. 测试

| 层 | 测什么 |
|---|---|
| **画布裁剪（纯函数，platform-api）** | 闭包：选叶子只含它自己；选中间节点含全部下游；未知 id 拒；全屏蔽（无非 finally 节点）拒。断点：`a→b→c` 里 b 失败 → b 是断点、**c 不是**；**点 c 被拒且提示「请从 b 开始」**；已成功节点不是断点；没有状态的节点算「没成功」；**并行断点自动合并**（两分支例子：frontier=`a2,b1`、scope=`a2,a3,b1,b2`，点 a2 与点 b1 得到同一集合）；屏蔽闭包被排除；收尾节点作为目标时 scope 只有它。裁剪：节点/边都只留集合内的，节点属性原样保留（**收尾目标那一个节点除外**：`finallyTask`/`finallyWhen` 被清掉）；子画布能通过既有渲染校验（含收尾目标这一个 case） |
| **plan 与 retry 的一致性** | 同一个 `frontier/retryScope` 实现被两个端点共用：同一运行同一输入，plan 返回的 `runNodeIds` 与实际创建出来的子画布节点集合**完全一致**；plan 只读（不建对象、不落行） |
| **重试服务** | 父运行非终态拒；父运行 `SUCCEEDED` 拒；**上游没成功的节点拒**（报具体上游）；**已成功节点拒**；**收尾节点可重试**；屏蔽节点拒；`workspace_pvc` 为空拒；有效状态 = 自身 steps ∪ 沿用集合；`attempt_no` 递增、`parent_run_id` 正确；`masked` 继承父运行；创建失败**回滚运行行**（不留 RUNNING 孤儿）；连接重新授权校验（撤销授权后重试被拒） |
| **`steps_json` 解析** | 新键（节点 id）与老键（`<runName>-<id>`）都要认 —— 与日志路径 `nodeIdOf` 同一口径 |
| **k8s-core** | `buildRun` 要求 `workspacePvc` 必填、绑定形状是 `persistentVolumeClaim`；PVC 工厂的标签/注解与 TTL 刷新；**沿用而卷不存在 → 拒绝（不补空卷）**；**渲染结果里不再出现 `volumeClaimTemplate`** |
| **`PipelineRunFinisher`** | `SUCCEEDED` → 删 PVC；`FAILED` → 不删；删 PVC 失败**不影响归档判定**；取消路径仍走 `persistLogs`（既有那条回归） |
| **Reaper** | 过期删 / 未过期留 / 非平台 PVC 不碰 / 一个集群失败不影响其余 |
| **回归** | 全量 `mvn test`（四个模块）；前端 `npm run build`（**不要用 `vue-tsc --noEmit` 当验收**，它会零报错放过真错误） |
| **迁移** | 手工重放 + `platform_pipeline_run` 六列存在；`PlatformPipelineRunMapper.xml` 四处（resultMap / Base / insert / List）都能取到新列；**4 条权限行 + 12 条角色关联**逐条命中真实 endpoint（启动期交叉校验会替我们拒） |

---

## 11. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 项 | 为什么 |
|---|---|
| **自动重试（Tekton `retries`）** | 用户本批要的是手工续跑。要加时是节点上的一个数字 + `task.setRetries`，本方案不挡路 |
| **运行中重试** | 正在跑的运行还在用那块工作区，两条运行同时挂一块 PVC 会互相踩；而且它没有「已经结束到哪一步」的确定结论 |
| **屏蔽写进定义** | 用户选择单次生效；持久屏蔽等于改定义，用编辑器停用节点更直白（本仓没有「停用」概念，要另说） |
| **成功运行的重试** | 成功即删工作区；想重跑就整条重来。要放开就得让成功运行也保留 24h（配额代价，用户 2026-10-20 选择不做） |
| **从已成功节点重跑（如点了 A1 却只想重跑 A1 之后）** | 已成功节点不是断点，没有「重试」入口。想重来就整条重跑；本批不做「任意起点」 |
| **只补一条分支** | Tekton 的停止是整条运行级的，只补一条会产出「成功但流水线没跑完」的记录；而成功即删工作区，另一条就永远补不了。两条分支本该互不影响时，用 `onError=continue` 让第一条失败不掀翻第二条，那是更强的机制 |
| **重试普通节点时顺带跑收尾节点** | 通知/清理会重复执行；要重跑某个收尾动作，直接点它自己（收尾节点可被单独重试，见 §3.2） |
| **重试时保留凭据 Secret** | 里面是可解凭据；重试重新解析连接，顺带重新校授权 |
| **工作区内容浏览 / 下载** | 要起 Pod 或 exec，是另一个面（与 S3.2 应用层也不属于同一件事） |
| **详情页预览带屏蔽的 YAML** | `/pipeline/preview` 仍渲染完整画布。触发对话框不需要 YAML；真要看以运行详情归档的 YAML 为准 |
| **删除运行记录时顺带删工作区** | 只删库行（现有语义），PVC 交给 TTL —— 免得「删父运行把子运行正在用的卷删了」 |
| **重跑成功后自动清理沿用链** | 成功即删（§6.3），链上的子运行工作区列悬空无妨（只用于显示与将来重试） |

---

## 12. 待实测（纸上推不出来，必须真跑）

- [ ] 平台自建 PVC + `persistentVolumeClaim` 绑定，在目标集群上**真能绑上并挂进 Pod**
      （`volumeClaimTemplate` 时代是 Tekton 建的，这一条是本批最大的行为变化）。
- [ ] 删掉 PipelineRun 之后，**平台建的 PVC 确实还在**（没有 ownerRef 级联）。
- [ ] 重试运行的子画布**没有上游节点时，task 立即起跑**（`runAfter` 为空）。
- [ ] 平台自建 PVC 与构建缓存 PVC 同时挂载时，`coschedule` 的那个前提仍然成立
      （两轮 TaskRun 的 workspace 列表没变，只是来源从模板变成具名 PVC —— 但仍要跑一次确认）。
- [ ] 重试**收尾节点**时，子画布里它被当作普通任务（`finallyTask` / `finallyWhen` 已清）——
      真跑确认它立即起跑、不触发渲染器那条「全是收尾节点」的拒绝。
- [ ] **两分支真跑**：`A2` 失败时 `B1` 的实际落点（被取消的 TaskRun 在 `steps` 里映射成哪个状态）——
      它必须落进「断点」判定；确认 `frontier` 算出来是 `A2, B1`，子画布是 `A2, A3, B1, B2`。
- [ ] 重试运行里沿用节点的产物确实还在工作区（同一块 PVC；没有重新克隆/重建的迹象）。
- [ ] 并行 TaskRun 同挂一块 `ReadWriteOnce` 卷的既有问题（沿用现状，不在本批解决，但要记录下来）。
- [ ] 24 小时 TTL 注解的 RFC3339 与集群/浏览器时区理解一致（回收器只解析 UTC `Z`）。

---

## 13. 部署

1. **手工重放** `V2026_10_20_1__pipeline_retry_and_mask.sql`（⚠️ 本仓没有 Flyway，启动日志里永远不会有
   `Migrating schema`；别等它）。
2. **重建三个模块**：`k8s-server`（PVC 工厂、归档分叉、回收器）、`platform-api`（裁剪、重试、端点）、
   `platform-web`。
3. **集群侧核对**：租户 RBAC 模板里 `persistentvolumeclaims` 有 create/delete（§6.6）；有默认 StorageClass。
4. **配置**（k8s-server 读）：
   ```yaml
   platform:
     pipeline:
       workspace:
         retention: ${PIPELINE_WORKSPACE_RETENTION:24h}          # 失败运行的工作区保留窗口
         reap-interval: ${PIPELINE_WORKSPACE_REAP_INTERVAL:10m}   # 回收节拍
   ```
5. **不需要重新登录**（没有新权限码；权限闭包按 code 算，四条新行用的都是既有码）。

### 13.1 部署后待验清单

- [ ] 跑一条**成功**的流水线 → 命名空间里 `platform-pipeline-ws-*` **消失**，`kubectl get pvc` 只剩缓存卷。
- [ ] 跑一条**失败**的流水线 → 工作区 PVC **还在**，注解 `retain-until` 有值；列表页「待重试工作区」计数 +1。
- [ ] `a→b→c` 里 `b` 失败：`a` 不可点（已成功）、`c` 不可点且提示「请从 b 开始」；
      点 `b` → 新运行跑 `b`、`c`；`a` 显示「已沿用（上次成功）」且没有被重新执行（看日志）。
- [ ] **两分支**：`A2` 失败、`B1` 被取消 → `A2` 与 `B1` 都是断点；点任一个，新运行跑 `A2, A3, B1, B2`，
      对话框注明「B 分支也停了，一起补完」。
- [ ] 点一个**收尾节点** → 只跑它一个，且它按普通任务执行（不再按「前面是否成功」筛）。
- [ ] 重试成功 → 工作区 PVC 被删；链上两条运行都能打开，`attempt_no` 与父运行链接正确。
- [ ] 屏蔽一个叶子节点触发 → 该节点没有 TaskRun、详情显示「本次已屏蔽」、运行整体成功；
      列表页运行历史能看到这条。
- [ ] 屏蔽一个中间节点 → 它的下游一起不跑（详情页都显示已屏蔽），运行成功。
- [ ] 把画布全部节点都屏蔽 → 前端禁止提交；绕过前端直接调接口被拒。
- [ ] 手动清一次「待重试工作区」→ PVC 消失，再点那条运行的重试 → 明确报「工作区已过期」。
- [ ] 撤销某条 git 连接的「允许在流水线里使用」后再重试 → 被拒（而不是跑起来才发现拉不到代码）。
