# 流水线节点重试与节点屏蔽设计

> 需求（用户 2026-10-20 原话）：「流水线功能我希望支持节点重试功能，还有就是希望支持可以选择节点屏蔽功能，
> 比如一个节点后接三个节点，我希望只运行两个」。
> 前置：`docs/superpowers/specs/2026-10-15-ci-tekton-design.md`（片① 定义·运行·日志，片② onError + finally）。
>
> 本批是 **S3.5 的增补**，不改变片①/片② 已定的边界（见 `2026-10-15-ci-tekton-design.md` §10 那张表）。

---

## 0. 两句话

- **节点屏蔽**：触发运行时可勾选「本次不跑」的节点，规则是**连同它的全部下游一起不跑**；定义本身不动。
- **节点重试**：一次运行终结后，从某个**未成功的节点**处继续 —— 新建一条只跑「这个节点 + 它上次没成功的下游」
  的 PipelineRun，**沿用父运行的工作区**（源码与已成功的构建产物都在里面）。

---

## 1. 已定决策（brainstorming 结论，2026-10-20）

| 决策点 | 结论 | 理由 |
|---|---|---|
| **「节点重试」指哪种** | **运行结束后手工从失败处继续**（不是定义期的自动重试） | 用户明确选择。自动重试（Tekton `retries`）本批不做 |
| **重试能不能省掉重跑** | **必须保留共享工作区** | 节点之间**没有任何 `$(tasks.X.results)` 引用**（2026-10-20 核实：三个内置节点都不写 results），数据全走共享 workspace。所以只要工作区还在，「只重跑失败节点及其下游」在语义上成立 |
| **工作区保留多久** | **24 小时**（可配 `retention`） | 用户选择「保留但窗口很短」。平衡配额占用与「隔天还能续跑」 |
| **屏蔽在哪一层生效** | **每次运行时选（单次生效）** | 用户选择。定义（`canvas_json`）不动，运行行上留存「本次屏蔽了谁」 |
| **屏蔽怎么落到 Tekton** | **渲染前把画布裁剪成子画布**，不引入 `when` | 见 §5：剪枝不依赖任何未实测的 Tekton 行为，且与「上游被移除 = 依赖已满足」的重试语义天然一致 |
| **剪枝规则写在哪** | **platform-api**（`PipelineCanvasRefs` 旁新增纯函数类） | 那里本来就在解析画布 JSON；渲染器因此**零改动**；闭包规则只有一个落点 |
| **重试要不要跑 finally** | **不跑** | 收尾节点在原运行里已按当时的聚合状态跑过；重试再跑会让通知/清理类动作重复执行 |
| **重试与屏蔽的关系** | 重试**继承**父运行的屏蔽集合 | 被屏蔽的节点在父运行里没有状态，本就不该出现在重试范围里 |
| **失败/取消/超时运行怎么处理工作区** | **保留**到 TTL；**成功立即删** | 只有「没跑成」的运行才需要续跑 |
| **本批的权限点** | **不新增**，屏蔽与重试都复用 `tenant:pipeline:run` | 它们都是「触发」的一种 |

---

## 2. 交付边界

**做**：触发时勾选屏蔽节点（含闭包展开与前端级联）· 运行行记录本次屏蔽集合 · 从失败/跳过节点重试
（子图重建、工作区沿用、血缘）· 工作区 PVC 的平台自建与 TTL 回收 · 列表页的「待重试工作区」查看与清理 ·
运行详情新增三种节点态 · 迁移、审计、测试。

**不做**：自动重试（Tekton `retries`）· 运行中重试 · 屏蔽的持久化（写进定义）· 工作区内容浏览/下载 ·
重试 finally 节点 · 老运行（本批之前，没有 `workspace_pvc`）的重试。

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

### 3.2 重试：跑什么、沿用什么

对象是**一条已终结运行里的某个未成功节点**：

- 能重试的运行状态：`FAILED` / `TIMEOUT` / `CANCELLED`（`RUNNING` 不行；`SUCCEEDED` 不行 ——
  它的工作区已经删了，而且「成功」的运行没有「失败处」）。
- 能重试的节点状态：`FAILED` 或 `SKIPPED`（来自父运行的 `steps_json`）。
- 不能重试的节点：`SUCCEEDED` 的、在父运行屏蔽集合里的、`finallyTask` 的。

**重跑集合**：

```
toRun = {所选节点} ∪ { d ∈ 所选节点的传递下游 | 父运行里 d 的状态 ≠ SUCCEEDED }
        − finally 节点 − 屏蔽闭包
```

即：上次已经成功的下游**沿用**，不重跑。这条对「勾了失败也继续（`onError=continue`）的节点」尤其重要 ——
它失败后下游可能已经跑过，重试时不该把它们再跑一遍（镜像节点重跑还会产出一个新 tag）。

**沿用集合**：父运行里状态为 `SUCCEEDED` 的节点（不含屏蔽的）。它们在新运行里不产生 TaskRun，
详情页按「已沿用（上次成功）」着色。

**其余节点**：既不在 `toRun`、也不在沿用集合里的（父运行里也没成功、但不是所选节点的下游）——
新运行里不跑，详情页按「上次未成功，本次未重试」显示。它们**不影响**新运行的成败。

**新运行不含 finally 节点**（见 §1 决策）。

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
| `retry_from_node` | `varchar(64) NULL` | 从哪个节点开始重试（画布节点 id） |
| `masked_nodes_json` | `mediumtext NULL` | 本次**实际不跑**的完整节点集合（JSON 数组，闭包展开后的） |
| `reused_nodes_json` | `mediumtext NULL` | 重试时沿用的节点（JSON 数组） |
| `workspace_pvc` | `varchar(128) NULL` | 本次运行（链）使用的共享工作区 PVC 名 |

**没有新的权限行** —— 复用 `tenant:pipeline:run`，所以不需要权限 join 对账那一套。

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
```

⚠️ **名字由 platform-api 定，k8s-server 只负责「没有就建、有就刷新 TTL」** ——
这样「PVC 叫什么」只有一个落点，两个模块不需要各自拼一遍前缀（与 `credsSecretName` 那种
「两边必须算出同一个名字」的约定相反，这里刻意不那样做）。platform-api 也因此能在**创建之前**
就把名字落进运行行，集群创建失败回滚时两边状态一致。

⚠️ 不发「屏蔽集合」「重跑集合」给 k8s-server：它们在 §5 的**画布裁剪**里已经体现，
k8s-server 只收到一份「本次要跑的子画布」。少一个契约，就少一处两边对不齐的地方。

**端点**

| 端点 | 入参 | 权限 | 说明 |
|---|---|---|---|
| `POST /pipeline/run` | `PipelineRunRequest(pipelineId, tenantId, maskedNodeIds)` | `tenant:pipeline:run` | **入参从 `PipelineDTO` 改为新 record**；`maskedNodeIds` 为空/缺省 = 不屏蔽 |
| `POST /pipeline/run/retry` | `{runName, nodeId, tenantId}` | `tenant:pipeline:run` | 返回新 runName；标 `@Auditable(action = PIPELINE_RUN, target = "#request.runName")` |
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
重试：toRun = 所选节点 ∪ 未成功的下游  − finally − 屏蔽闭包
裁剪：只保留 toRun 里的节点；只保留两端都在 toRun 里的边
```

三个后果：

1. **`k8s-core` 渲染器零改动**。它照旧把「节点 + 边」翻成 task / `runAfter`。
   「上游被移除」天然等于「这个依赖已满足」—— 这正是重试要的语义，也是屏蔽闭包保证安全的原因。
2. **闭包与子图规则只有一个落点**：新增 `platform-api/.../services/PipelineCanvasMask.java`
   （包私有，与 `PipelineCanvasRefs` 同处），提供三个纯函数：
   - `maskedClosure(canvasJson, selectedIds)` —— 展开闭包；未知 id → 拒；
   - `retryNodes(canvasJson, parentSteps, nodeId, masked)` —— 算 `toRun`；
   - `trim(canvasJson, keepIds)` —— 产出子画布 JSON。
3. **落库的画布与发送的画布从此不同**：`platform_pipeline_run.canvas_json` 存**完整画布**
   （详情页要画全图），`PipelineRunDTO.pipelineCanvasJson` 发**子画布**。
   这条要写进 `PipelineRunService.run/retry` 的注释里 —— 以后有人看到两处不一致，不会以为写错了。

**实现口径**：直接在 Jackson 树上操作（`tools.jackson`，与 `PipelineCanvasRefs` 一致），
不要反序列化成 `PipelineCanvas` —— 那个 record 在 `k8s-core`，platform-api 依赖不到。
节点对象**原样保留**（含 `position` / `name` / `onError` / `finallyTask`），只做筛选。
`finallyTask` 从 JSON 里读（`node.path("finallyTask").asBoolean(false)`）。

**校验（服务端）**：
- 勾选的节点 id 必须都在画布上（未知 id 直接拒，不静默忽略）；
- 闭包展开后必须还剩至少一个非 finally 节点；
- 重试时 `nodeId` 必须存在、状态 ∈ {FAILED, SKIPPED}、不是 finally、不在屏蔽集合里；
- 重试时父运行必须已终结（`status` 不是 `RUNNING`）且 `workspace_pvc` 非空。

---

## 6. 工作区生命周期（本批唯一动到核心的地方）

### 6.1 为什么必须改

今天是 `volumeClaimTemplate`（每次运行现开一块临时 PVC，[PipelineRunOperations.java](../../../k8s-core/src/main/java/com/coding/k8score/operations/pipelinerun/PipelineRunOperations.java) 的 `bindWorkspaces`）。
Tekton 建的 PVC 带 PipelineRun 的 ownerReference —— **删 PipelineRun 就级联没了**，
而归档那条路一结束就删 PipelineRun（`PipelineRunFinisher.finish`）。所以「从失败处继续」必须先拥有一块
平台自己管、生命周期独立于 PipelineRun 的卷。

### 6.2 创建与绑定

- PVC 名由 platform-api 定（§4.2）：首跑 `platform-pipeline-ws-<runName>`，重试沿用父运行的。
- k8s-server 收到 `workspacePvc` 后：
  - **不存在 → 建**：`1Gi`、`ReadWriteOnce`（**与今天一致**，不借机改访问模式）、默认 StorageClass；
    标签 `app.kubernetes.io/managed-by=platform-system` + `platform.pipeline/role=workspace`
    + `platform.pipeline/run=<runName>`；注解 `platform.pipeline/retain-until=<RFC3339, now + retention>`。
  - **已存在 → 不重建**，只刷新 `retain-until`（重试路径）。
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
  2. 父运行状态 ∈ {FAILED, TIMEOUT, CANCELLED}，否则拒
  3. 解析父 steps_json（节点 id → 状态；⚠️ 兼容 2026-10-16 之前 `<runName>-` 前缀的老键）
  4. nodeId 存在、状态 ∈ {FAILED, SKIPPED}、非 finally、不在父 masked 集合里
  5. 读父 canvas_json → PipelineCanvasMask.retryNodes(...) 得 toRun / reused / masked
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
| **运行详情**（`PipelineRunDetailView`） | ① 三种新节点态（已屏蔽 / 已沿用 / 本次未重试），复用现有灰色「已跳过」样式，`skipWhy` 加三条分支；② 未成功节点卡片加「重试」按钮（终态 + 非 finally + 非屏蔽 + 有 `run` 权限 + 有 `workspace_pvc`），确认框列出「将重跑 / 沿用的节点」，确认后跳新运行；③ 顶部显示「第 N 次尝试 · 源自运行 xxx」（可点） |
| **流水线列表**（`PipelineListView`） | 构建缓存那一行下面加「待重试工作区：N 块 / X Gi」+「全部清理」；为 0 时显示「无」 |

⚠️ 详情页判断三类「没跑」**只读运行行上的两份集合**，不自己重算闭包 —— 闭包规则在服务端（§5），
前端重算一次就多一处会走偏的判据。

---

## 9. 权限与审计

- 屏蔽：属于触发，`tenant:pipeline:run`。
- 重试：属于触发，`tenant:pipeline:run`；`@Auditable(action = PIPELINE_RUN, target = "#request.runName")`。
- 「清理待重试工作区」：`tenant:pipeline:run`；新审计动作 `PIPELINE_RETRY_WORKSPACE_CLEAR = "pipeline:retry-workspace-clear"`。
- 查看工作区列表：`tenant:pipeline:read`。
- 命名空间边界：全部走既有的「租户 × 集群 × 命名空间」分配表（`NamespaceAccessGuard` / `PipelineRunService.requireRun`）。

---

## 10. 测试

| 层 | 测什么 |
|---|---|
| **画布裁剪（纯函数，platform-api）** | 闭包：选叶子只含它自己；选中间节点含全部下游；未知 id 拒；全屏蔽（无非 finally 节点）拒。重试集合：已成功的下游**不在** toRun 里；finally 不在；屏蔽的不在；父运行里没状态的节点按「未成功」处理。裁剪：节点/边都只留集合内的，节点属性原样保留；结果画布能通过既有渲染校验 |
| **重试服务** | 非终态拒；`SUCCEEDED` 节点拒；`finally` 节点拒；屏蔽节点拒；`workspace_pvc` 为空拒；`attempt_no` 递增、`parent_run_id` 正确；`masked` 继承父运行；创建失败**回滚运行行**（不留 RUNNING 孤儿）；连接重新授权校验（撤销授权后重试被拒） |
| **`steps_json` 解析** | 新键（节点 id）与老键（`<runName>-<id>`）都要认 —— 与日志路径 `nodeIdOf` 同一口径 |
| **k8s-core** | `buildRun` 要求 `workspacePvc` 必填、绑定形状是 `persistentVolumeClaim`；PVC 工厂的标签/注解与 TTL 刷新；**渲染结果里不再出现 `volumeClaimTemplate`** |
| **`PipelineRunFinisher`** | `SUCCEEDED` → 删 PVC；`FAILED` → 不删；删 PVC 失败**不影响归档判定**；取消路径仍走 `persistLogs`（既有那条回归） |
| **Reaper** | 过期删 / 未过期留 / 非平台 PVC 不碰 / 一个集群失败不影响其余 |
| **回归** | 全量 `mvn test`（四个模块）；前端 `npm run build`（**不要用 `vue-tsc --noEmit` 当验收**，它会零报错放过真错误） |
| **迁移** | 手工重放 + `platform_pipeline_run` 六列存在；`PlatformPipelineRunMapper.xml` 四处（resultMap / Base / insert / List）都能取到新列 |

---

## 11. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 项 | 为什么 |
|---|---|
| **自动重试（Tekton `retries`）** | 用户本批要的是手工续跑。要加时是节点上的一个数字 + `task.setRetries`，本方案不挡路 |
| **运行中重试** | 正在跑的运行没有「失败处」 |
| **屏蔽写进定义** | 用户选择单次生效；持久屏蔽等于改定义，用编辑器停用节点更直白（本仓没有「停用」概念，要另说） |
| **重试 finally 节点** | 收尾节点没有「下游」，且会让通知/清理重复执行 |
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
5. **不需要重新登录**（没有新权限码）。

### 13.1 部署后待验清单

- [ ] 跑一条**成功**的流水线 → 命名空间里 `platform-pipeline-ws-*` **消失**，`kubectl get pvc` 只剩缓存卷。
- [ ] 跑一条**失败**的流水线 → 工作区 PVC **还在**，注解 `retain-until` 有值；列表页「待重试工作区」计数 +1。
- [ ] 从失败节点点「重试」→ 新运行只跑该节点及其未成功的下游；沿用节点显示「已沿用（上次成功）」；
      父运行的缓存/源码没被重新拉一遍（看日志里 git 节点没有出现在新运行里）。
- [ ] 重试成功 → 工作区 PVC 被删；链上两条运行都能打开。
- [ ] 屏蔽一个叶子节点触发 → 该节点没有 TaskRun、详情显示「本次已屏蔽」、运行整体成功；
      列表页运行历史能看到这条。
- [ ] 屏蔽一个中间节点 → 它的下游一起不跑（详情页都显示已屏蔽），运行成功。
- [ ] 把画布全部节点都屏蔽 → 前端禁止提交；绕过前端直接调接口被拒。
- [ ] 手动清一次「待重试工作区」→ PVC 消失，再点那条运行的重试 → 明确报「工作区已过期」。
- [ ] 撤销某条 git 连接的「允许在流水线里使用」后再重试 → 被拒（而不是跑起来才发现拉不到代码）。
