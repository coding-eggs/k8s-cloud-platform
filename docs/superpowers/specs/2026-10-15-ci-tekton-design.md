# CI（Tekton）设计 —— 第 ① 片：定义 · 运行 · 日志

> 任务台账 **S3.5**（前置 S0.2 已完成）。需求依据见 `docs/gap-analysis-2026-10-12.md` §7.4。
> 调研结论（fabric8 tekton-client / 部署方案 / 日志复用）见台账 S3.5 条目的 2026-10-14 补记。

**目标**：让租户在平台里**连出一条自由的 DAG 流水线**，**手动触发**它跑在自己的命名空间里，
跑完能看到每一步的状态与日志。典型：`git 克隆 → 构建 → 打镜像并推送`。

---

## 0. 本批在 S3.5 里的位置

S3.5 在台账里是 **XL / 13 个子任务** —— 一批 spec + 一个实施计划装不下。拆成三片，各自 spec → 计划 → 执行：

| 片 | 内容 | 为什么切在这 |
|---|---|---|
| **① 定义 · 运行 · 日志**（本文件） | 三个内置节点 + 画布 + 渲染器 + 隐式 workspace + 手动触发 + 运行历史/日志 + 权限 + 凭据 | **单独就能用**：能连、能跑、能看日志 |
| ② 条件与收尾 | 条件分支 `when` 三段式 + 跳过传播可视化 + `finally` | 在已有画布上加一个维度 |
| ③ 自定义节点 + 运行时参数 | 自定义节点（级联删除）+ 运行表单（运行时参数 vs 固定配置）+ 并发策略 | 要求 ① 的节点 schema 已稳定；顺序反了会返工 |

---

## 已定决策（brainstorming 结论，2026-10-14/15）

| 决策点 | 结论 | 理由 |
|---|---|---|
| **节点怎么实现** | **一个内置节点 = 一个类**，类里同时给 `formSchema()` 与 `render()` | 表单由 schema 驱动（加字段不用写 Vue）；「字段 → taskSpec」用 Java 写（不发明 DSL）。加节点 = 加一个类 |
| **语言差异放哪** | **预设（数据），且只是默认值** | 用户原话：「预设节点只是预设，用户组建流水线的时候可以更改，如镜像、命令等」。所以没有预设的语言改两个字段就能用，**平台不用加代码** |
| **节点有几个** | **三个**（git 克隆 / 命令 / 构建并推镜像） | 台账原话：「内置节点（git/build/test/image/push，**其实质是 task，由用户填写具体配置**）」—— build 与 test 既然是同一个形状，就合并成一个「命令」节点，跑测试只是它的一个预设 |
| **镜像怎么构建** | **每个 Pod 带一个 Buildah** | 共享构建服务会**跨租户串味**（缓存、凭据、逃逸面）。per-Pod 隔离最好；除 Tekton 外不用装别的东西 |
| **部署节点** | **不做** | 通用部署节点会让**构建 Pod 拿到集群写权限** —— 与「构建 Pod 能跑任意代码」叠加后，能定义流水线就等于能改集群 |
| **流水线归属** | **命名空间级** | 跟 Deployment / Service 一样。权限、配额、级联清理与现有资源模型一致，「跑在哪个命名空间」这个问题直接消失 |
| **流水线对象** | **不在集群里留 `Pipeline` 对象**，每次运行把画布渲染成 `PipelineSpec` **内联进 `PipelineRun`** | 见 §3.2 —— 零漂移、历史运行天然带当时那一版、命名空间里少一类要清理的对象 |
| **隐式 workspace** | **PVC**（`volumeClaimTemplate`），平台注入，用户无感 | Tekton 里 `emptyDir` workspace **不跨 TaskRun 共享**，而我们要的正是「git 克隆的源码给下游所有节点用」 |
| **拉取凭据** | **不做 imagePullSecret**，但流水线上给「Pod 拉取凭据」设置 | 用户裁定。⚠️ 后果写进 §10 |
| **git 凭据** | **平台连接逐条显式授权**（新列，默认关） | 台账 S3.5.12 的安全边界；S0.2 特意留着「租户侧没有凭据码」，CI 正面撞上了它 |
| **构建 Pod 的权限** | **零集群写权限** | 它只往 registry 推镜像；集群里的写入全由平台侧（有权限控制与审计）做 |
| **运行历史与日志** | **落库**；集群里的 `PipelineRun` 归档后**删掉**（级联删 TaskRun 与 **Pod**） | 用户 2026-10-15：Tekton 跑完会留 `Completed` 的 Pod 占资源，期望「**k8s 不保留**，执行记录与日志**进数据库**」。详见 §4 |
| **运行终结的事件从哪来** | **k8s-server 侧「按需 watch」** —— 只在有运行在跑时开，运行结束就关；空闲时零连接 | 用户 2026-10-15 两次追问后的结论。不用轮询；**不用 Tekton CloudEvents sink**（要集群→平台的入站连通，且漏了不补）；**也不用常驻 informer**（空闲时每集群占一条连接、且要管生命周期）。见 §4.1 |
| **取消与超时** | **都收进平台**（先抓日志、再取消）；**不设** Tekton 的 `spec.timeouts`；超时由 k8s-server 侧一个**只看**活动 watch 的台账**（哪些 run 在跑 + 它们的 startTime）**的定时器判定 | Tekton 在超时/取消时会自己删 Pod → 日志丢失。收进平台才能保证「所有终结路径上都抓过日志」（§4.2） |

---

## 1. 交付边界

**做**：三个内置节点 + 节点 schema 与语言预设 + 画布（自由连线、环检测、并发语义提示）+ 渲染器 +
隐式共享 workspace + 手动触发与取消 + **运行归档任务（先抓后删）** + **运行记录与日志落库** +
**保留策略** + 权限（5 个码）+ git 连接逐条授权 + 推送凭据 + capability 门控 + 审计。

**不做**：条件分支与 `finally`（片②）· 自定义节点 · 运行时参数 vs 固定配置分离 · 流水线级并发策略 ·
部署节点 · 触发器 / Webhook / 定时 · 平台级流水线模板 · **Tekton Pruner**（理由见 §4.1 / §10）·
`imagePullSecret`（用户自理）。

---

## 2. 节点模型

### 2.1 接口

```java
public interface PipelineNode {

    /** 节点的 type 标识，如 "git" / "shell" / "image"。 */
    String type();

    /**
     * 表单 schema。**前端据此渲染节点属性面板** —— 加一个字段不用写一行 Vue。
     * 同时供渲染器做校验（必填、类型）。
     */
    NodeSchema formSchema();

    /**
     * 把节点上填的值合成 Tekton 的 {@code TaskSpec}。
     * **纯函数**：入参是节点配置 + 上下文（workspace 名、节点名…），无 IO，可单测。
     */
    TaskSpec render(NodeContext ctx);
}
```

`NodeSchema` 是一串字段描述：`{key, label, type(TEXT|TEXTAREA|SELECT|IMAGE), required, placeholder, help, defaultValue, options?}`。

⚠️ **`IMAGE` 类型**：前端的镜像输入框要挂 **S3.1 的候选补全**（`useImageSuggest`）——
「不用背镜像名」正是 S3.1 存在的理由，在这里兑现。

### 2.2 三个内置节点

| 节点 | 特殊在哪 | 主要字段 |
|---|---|---|
| **git 克隆** | **真特殊**：引用一条**被授权**的平台 git 连接、注入凭据、把源码写进共享 workspace | 连接（下拉）· 仓库地址 · 分支 / commit · 目标目录（默认 = workspace 根） |
| **命令** | 通用，**所有字段都可改** | **预设**（下拉）· 镜像（`IMAGE` 类型，**带 S3.1 的候选补全**）· 命令 · 工作目录（默认 `.`） |
| **构建并推送镜像** | **真特殊**：Buildah、推送凭据、产出 digest | 目标仓库（**引用一条被授权的 registry 连接**，决定 endpoint 与推送凭据）· 仓库路径（如 `proj/app`）· tag 来源（**三选一**：`git 短 sha` / `本次运行序号` / 自定义固定值）· Dockerfile 路径 · 构建上下文 |

⚠️ **镜像地址不用模板语法**。目标镜像 = `<被授权连接的 endpoint>/<仓库路径>:<tag>`，
`tag` 由**一个下拉三选一**决定 —— 不发明 `${...}` 那套小 DSL（本仓没有这个习惯，出错面也大）。

### 2.3 语言预设是**数据**，且**只是默认值**

```java
public record BuildPreset(String key, String label,
                          String image, String command, String workDir) { }
```

**扁平的一份列表**（不是"语言 × 动作"两段式 —— 那会让字段随选择变化，而我们要的正是"字段固定、只换默认值"）：

| 预设 | 镜像 | 命令 |
|---|---|---|
| `java-build` | `maven:3.9-eclipse-temurin-21` | `mvn -B -DskipTests package` |
| `java-test` | `maven:3.9-eclipse-temurin-21` | `mvn -B test` |
| `go-build` | `golang:1.22` | `go build ./...` |
| `go-test` | `golang:1.22` | `go test ./...` |
| `node-build` | `node:20` | `npm ci && npm run build` |
| `node-test` | `node:20` | `npm test` |
| `custom` | 空 | 空 |

`workDir` 默认 `.`（相对共享 workspace 根目录）。

⚠️ **预设只预填默认值，不锁死任何字段**（用户明确要求）。所以：

- 没有预设的语言（Rust / PHP / Scala…）→ 选 `custom` 或任意预设、改掉镜像与命令即可，**零代码改动**
- 「加一种语言」= 加**一行**预设 = **加一份便利默认值**，不是加一份能力
- **字段集合不随预设切换** —— 选预设只换默认值。表单因此**不需要动态字段**，加语言也真的是纯数据

**「跳过测试」= 不连那个测试节点** —— 自由 DAG 天然支持，不做开关。

### 2.4 护栏测试

一条测试遍历所有注册的 `PipelineNode`，断言：

1. `type()` 唯一且非空
2. `formSchema()` 非空、字段 key 不重复
3. `render()` 对**最小合法配置**能产出一个非空 `TaskSpec` 且不抛

—— 与 S3.1 那条「每个 `TYPES` 值都有 dialect 实现」同形：**忘了实现立刻红**。

---

## 3. 渲染器

### 3.1 纯函数

```
画布 JSON（节点 + 连线） + 节点 schema  →  Tekton PipelineSpec
```

无 IO、可单测。与本仓既有的做法一致（`CredentialProbe`、`HarborDialect` 的纯函数、`RegistryTargetResolver`）。

**连线语义**：

- **有向无环**；检测到环 → 保存时拒绝（前端也实时提示）
- **入度为 0 的节点 = 起点**（Tekton 里就是没写 `runAfter` 的 task）
- **没连线的两个节点 = 并发** —— 这是 Tekton 的默认语义，UI 要**显式提示**，否则用户以为"没连上"是错的
- 连线 → `runAfter`

### 3.2 **不在集群里留 `Pipeline` 对象**

每次运行即时把画布渲染成 `PipelineSpec`，**直接内联进 `PipelineRun.spec.pipelineSpec`**。

三个好处：

1. **零漂移** —— 集群里没有一份可以被别人改、也可以被 `kubectl delete` 的副本。
   S3.2 台账里专门列了「漂移处理」要设计，这条路绕开了它。
2. **历史运行天然带当时那一版** —— 每个 `PipelineRun` 自己携带着"跑的时候那张画布长什么样"，
   **审计与复现免费得到**。这也顺带解决了台账 S3.5.1 说的「版本化」——**不需要另做版本表**。
3. **命名空间里少一类要清理的对象** —— 流水线删了就删了，没有孤儿 `Pipeline`。

**代价**：`kubectl get pipeline` 看不到东西。接受 —— 平台是真相源，而运行态
（`PipelineRun` / `TaskRun` / `Pod`）在集群里全都在。

### 3.3 隐式共享 workspace

平台在渲染时把**同一条 workspace 注入到所有节点**，用户不感知。

⚠️ **必须是 PVC**：Tekton 里 `emptyDir` workspace **不跨 TaskRun 共享**（每个 Task 各拿一个空的）。

**用 `volumeClaimTemplate`**（PipelineRun 自建临时 PVC，用完删）：每次运行干净、互不干扰。
代价是需要一个**能动态供应的 StorageClass**（见 §11）以及每次运行多一次绑定等待。

---

## 4. 运行 · 归档 · 日志

> **本节在 2026-10-15 被整体重写。** 用户提出：Tekton 跑完会留下 `Completed` 状态的 Pod 占资源，
> 期望「**删掉、k8s 不保留**；执行记录与日志**写进数据库**」。核实后的三条事实（出自
> `tektoncd/pipeline/docs/taskruns.md`）：
>
> | | 事实 |
> |---|---|
> | **正常完成** | Pod **保留为 `Completed`**（现在仍然如此，这就是堆积的来源） |
> | **超时** | Pod **被删**、**日志丢失** |
> | **取消** | Pod **被删**、**日志丢失**（有 `keep-pod-on-cancel` flag 可保留） |
>
> 堆积的兜底是 kubelet 的 `--terminated-pod-gc-threshold`，**默认 12500** —— 到那个数之前没人清理。

### 4.1 归档由**按需 watch** 驱动：空闲时零连接

> **本节经三次改写。** 一稿用轮询 → 用户说「不要轮询，不是有 webhook 吗」；
> 二稿用常驻 informer → 用户说「watch 迟迟不结束，会建大量连接，是不是 sink 更好」。
> 核清事实后的结论在下面，**三个选项的取舍都记着**，免得下次重走一遍。

**先更正一个事实**（因为它决定了取舍）：fabric8 的 watch 是**一个资源类型一条连接** ——
常驻 informer 是 **1 条/集群**，不是每命名空间、更不是每 run 一条。而且 kube-apiserver 有
`--min-request-timeout`（通常 180–600 秒），到点就断、客户端重连，**不是永不结束的 TCP**。
所以按连接数算，常驻 watch 比 sink 还省（sink 是每个事件一次 POST）。

**但「不结束、有状态」这层担心成立** —— 常驻 informer 要管生命周期（集群增删、重启、退避），
而且空闲时也占着连接。所以选了第三条：

```
平台触发一次运行
  → k8s-server 在**创建 PipelineRun 的那条路径上顺手开一条 watch**
    （命名空间级 + fieldSelector metadata.name=<runName>）      ← 零新端点
  → 运行终结 → 回调 platform-api：抓日志 → 落库 → 删 PipelineRun → **关掉这条 watch**
  → 空闲时 **零连接**
```

⚠️ **超时判定复用同一条 watch 的本地上下文**（里面有 `startTime`）：到了平台超时
**只看本地、不打集群**，先抓日志、再取消。见 §4.2。

### 4.1.1 ⚠️ 只碰**平台自己创建的** run

平台渲染 `PipelineRun` 时**必须打一个标签**（如 `app.kubernetes.io/managed-by: platform-system`），
**watch、归档、删除统统只针对带这个标签的**。

理由不是洁癖：集群里可能有**别人手工建的** `PipelineRun`（运维调试、别的 CI 系统）。
不带标签过滤的话，平台会去**删掉别人的运行** —— 这是本批最容易造成事故的一处。

### 4.1.2 补漏：两个**非轮询**的动作

按需 watch 的问题是「平台/k8s-server 重启时正好有运行在跑 → 没人盯它」。两个动作补上：

| 动作 | 何时 | 做什么 |
|---|---|---|
| **启动时扫一次** | k8s-server 启动 | 集群级 list 一次**带平台标签且还在 running** 的 PipelineRun → 为它们重新开 watch |
| **打开运行历史页时对一次** | 用户动作 | platform-api 顺手列该命名空间里「带平台标签、已终结、但库里没有」的 run → 补归档 |

两个都**不是轮询**：一个是启动初始化，一个由用户动作驱动。
最坏情况是「平台重启期间的运行 + 之后一直没人看」→ 那些 Pod 会留着；
但没有人会去看的运行，本来就是最不重要的一批，而且**它们不会永久消失**（下次有人看就补上）。

### 4.2 取消与超时**也收进平台** —— 这是让日志拿得到的关键

Tekton 在**超时**和**取消**两种情况会自己把 Pod 删掉 —— 那之后日志就没了。
所以平台不能让 Tekton 去终结：

- **取消** = 平台**先抓一次日志**，再 patch `spec.status` 取消
- **超时** = **不设** Tekton 的 `spec.timeouts`，改由 **k8s-server 侧一个轻量定时器**判定「跑太久了」
  → 通知平台走与取消同一条路（先抓后取消），结束时标成 `TIMEOUT` 而不是 `CANCELLED`

  ⚠️ 它**只看活动 watch 的台账**（k8s-server 侧记着哪些 run 在跑 + 它们的 `startTime`），**不打集群**；
  而且**只在真有运行中的 run 时才有事可做**，没有在跑的 run 时它是空转。
  所以它不是「轮询集群」，是**同一份 watch 缓存的二次利用** —— 唯一的替代是让 Tekton 自己超时，
  那会删掉 Pod、**那批运行的日志必然丢失**，与"日志进库"直接冲突。

于是**所有终结路径上，平台都在 Pod 还在时抓过日志** ✓

⚠️ 这条约束是硬性的：**只要 dev 用 Tekton 自己的超时，那次运行的日志就必然丢失**。

### 4.3 观察运行

运行期间平台仍直接读集群（`PipelineRun.status` 的 `conditions` / `startTime` / `completionTime` /
`childReferences` → TaskRun 状态），把状态映射到画布上的节点（按节点名）。
**归档之后**才改读库 —— 两种数据源的分界就是「这个 run 归档了没有」。

### 4.4 日志：抓取仍然白捡，只是**多一步落库**

`PipelineRun` 不起 Pod，`TaskRun` 才起；而 Tekton 的**每个 step 就是 Pod 里的一个容器**。
所以抓取仍用现有的 `GET /{podName}/logs?clusterId&namespace&container=…`（支持选容器、
`tailLines` 回看、剥时间戳）—— **不用新写日志通道**。

新增的只是：抓到之后写进 `platform_pipeline_run_log`，并且
⚠️ **单步最多 1 MB**，超出**截断**并在 UI 上标注「已截断」。
实时看日志（运行还在跑时）仍然走老路直接读 Pod —— 不落库、不落缓存。

---

### 4.5 归档的调用时序

⚠️ **它是回环调用**（k8s-server `127.0.0.1:8080` 与 platform-api `:8081` 同机），**不跨网络边界**。

**① 触发时 —— 先落库，再创建**

```
前端 → platform-api   POST /pipeline/run {pipelineId}
platform-api:
   1. 读 platform_pipeline.canvas_json → 渲染器 → PipelineSpec
   2. **先生成 run_name**（pipeline 名 + 时间戳/ULID）
   3. **先落库** platform_pipeline_run(status=RUNNING, run_name=…)
   4. 调 k8s-server 创建 PipelineRun（**用这个名字** + managed-by 标签 + **不设 timeouts**）
k8s-server:
   5. fabric8 创建 PipelineRun
   6. **同一步里顺手开 watch**（namespace + fieldSelector metadata.name=<runName>）
      → 记进「活动 watch 台账」（含 startTime —— 超时判定要用）
```

⚠️ **第 3 步必须在第 4 步之前**，这是刻意的：`run_name` 由平台先生成 → **幂等键从源头就有**，
而且不会出现「集群里有对象、库里没记录」的孤儿。反过来做的话，创建成功而落库失败的那个窗口里，
**那个运行就没人知道了**。

**② 运行结束 —— watch handler 发回调**

```
k8s-server 的 watch handler（收到 MODIFIED）:
   判断三件事：带 managed-by 标签？ condition 变成终结？ 台账里有它？
   → HTTP POST http://127.0.0.1:8081/internal/pipeline-run/finished
        X-Internal-Token: <配置的密钥>
        {clusterId, namespace, runName}
   → 2xx：关掉这条 watch、从台账移除
   → 非 2xx / 超时：**不关 watch**，把 runName 放进「待补」集合（见 ④）
```

**③ platform-api 收到之后**

```
POST /internal/pipeline-run/finished
  1. 校验 X-Internal-Token（留空 → 拒绝一切，fail-closed）
  2. 查库：这个 run_name 归档过没有？
       已归档 → **不能直接 return**（见下）→ 跳到第 5 步
  3. 调 k8s-server 取快照：读 PipelineRun 状态 → 读它的 TaskRun 列表 →
     对每个 TaskRun 的每个 step 容器读日志（现有的 /{pod}/logs?container=，1 MB 截断）
  4. **一个事务里**写：日志行（按 (run_id, node_name) 唯一）+ 更新 run 的状态与耗时
  5. 调 k8s-server **删 PipelineRun**（级联删 TaskRun 与 Pod）
  6. 返回 200
```

⚠️ **第 2 步那个坑**：幂等分支**不能直接返回**。想象「库已经写好了，但第 5 步删对象失败」——
若重投时直接 return 200，那条运行的对象就**永远留在集群里**了，正是本批要消掉的东西。
所以：**归档幂等（不重写库），但删除每次都试**（删本身幂等）。

**④ 失败与重试**

| 情况 | 处理 |
|---|---|
| 回调失败（platform-api 重启/不可达） | 进 k8s-server 的「待补」集合 |
| **谁重试** | **复用超时判定那个节拍器**（每 30s，只看台账、不打集群）—— 它每拍干两件事：① 有没有跑超时的 run ② 有没有待补的归档。**只多一个节拍器，不引入第二套定时机制** |
| 归档中途失败 | 事务回滚 + 返回 5xx → k8s-server 重投 |
| 重复投递 | `run_name` 唯一挡住重写，**但删除照做** |

**⑤ 这个端点的安全性**

- 它**不在用户 JWT 体系里**（k8s-server 没有用户身份），靠 `X-Internal-Token` 一个**共享密钥**
- **密钥留空则拒绝一切请求**（fail-closed，与凭据主密钥同一条判断）
- ⚠️ 边界要说清：platform-api 是单体，**没法只给这一个端点绑 `127.0.0.1`** ——
  它跟着 8081 一起暴露在内网。所以**密钥是唯一防线**：必须是随机长串（32+ 字节），不能是 `platform` 这种

---



```yaml
platform:
  pipeline:
    archive:
      enabled: ${PIPELINE_ARCHIVE_ENABLED:true}
      internal-token: ${PIPELINE_INTERNAL_TOKEN:}     # k8s-server → platform-api 的内部回调密钥；留空则内部端点拒绝一切请求（fail-closed）
    run:
      timeout: ${PIPELINE_RUN_TIMEOUT:30m}            # 平台自己判定的超时
      timeout-check: ${PIPELINE_TIMEOUT_CHECK:30s}    # 判定节拍 —— 只看**活动 watch 的台账**（哪些 run 在跑 + 它们的 startTime），不打集群
    log:
      max-bytes: ${PIPELINE_LOG_MAX_BYTES:1048576}    # 单步 1 MB，超出截断
      retention-days: ${PIPELINE_LOG_RETENTION_DAYS:90}
```

**保留策略**：另有一个每日任务，删 `completion_time` 早于 90 天的 `platform_pipeline_run`
及其日志行（连带，靠代码不靠外键 —— 本仓不加外键）。

⚠️ **`enabled: false` 的后果要说清**：Pod 不会被删（回到 Tekton 默认行为），
而且运行记录**只在集群里**、不落库。这个开关是给"我就是要用 pruner 自己管"的部署留的。

---

## 5. 权限

### 5.1 五个码

| 码 | 域 | 管什么 |
|---|---|---|
| `tenant:pipeline:manage` | API | 定义（新建 / 改 / 删） |
| `tenant:pipeline:run` | API | 触发 + 取消 |
| `tenant:pipeline:read` | API | 看定义与运行历史（元数据） |
| `tenant:pipeline:logs` | API | **看运行日志**（单独成码：日志是容器输出，可能含敏感内容） |
| `tenant:page:pipeline` | Page | 页面可见性 |

⚠️ 必须是 `tenant:` 前缀（`RoleService.assertScopeMatches`：TENANT 角色只能持 `tenant:` 码）。

**角色关联**（我定的，可改）：

| 角色 | 码 |
|---|---|
| `builtin_role_tenant_admin` | 全部 5 个 |
| `builtin_role_tenant_member` | `read` + `logs` + **`run`** |
| `builtin_role_admin` | 全部 5 个（平台管理员兜底） |

**为什么 member 给 `run`**：`run` 只能跑**别人定义好的**流水线；要往流水线里塞任意命令需要 `manage`。
所以「能触发」在 member 这一档是安全的 —— 这一条把「构建 Pod 能跑任意代码」的实际风险面收敛到了 `manage` 上。

### 5.2 命名空间边界：零新机制

走既有的「租户 × 集群 × 命名空间」分配表，与 workload / namespace 资源完全一致。

### 5.3 git 连接的**逐条显式授权**（本批新增一列）

S0.2 特意留着「租户侧没有凭据码」这条边界（`platform:credential:use` 只绑 admin）。CI 正面撞上了它。

**做法**：`platform_credential` 加一列 `usable_by_pipelines tinyint NOT NULL DEFAULT 0`；
凭据页加一个开关「允许在流水线里使用」（**默认关**）。

- 只有被标记的 git 连接才出现在流水线 git 节点的连接下拉里
- 新增一个**窄投影**端点（**不复用 `/credential/list`** —— 那个要 `platform:credential:read`，租户一进就 403），
  只回 `{id, name, endpoint}` 三个字段，与 S3.1 的 `/registry/connections` 同一形状
- 管理员在凭据页能看见「谁在用」—— 因为流水线的 git 节点引用的是连接 id

⚠️ **推送凭据**同理但更窄：`构建并推送镜像` 节点引用的 registry 连接**也必须被标记为可用**
（否则租户能拿平台凭据往任意仓库推）。用**同一列**。

---

## 6. 前端

**画布库：Vue Flow**（备选 LogicFlow）。理由：原生 Vue 3、TS 优先、**自定义节点就是一个 Vue 组件**
（与「一个节点一个类」的形态天然对齐）、社区与文档体量大。

| 界面 | 内容 |
|---|---|
| **流水线列表** | 命名空间级列表（沿用本仓既有列表页形态） |
| **画布页** | 左：节点面板（拖入）· 中：画布（自由连线、环检测、**并发语义提示**）· 右：属性面板（**由 `formSchema()` 渲染**，零手写） |
| **运行历史** | 列表：状态 / 触发人 / 耗时 / 起止 |
| **运行详情** | 按 TaskRun 状态着色的 DAG + 点节点看该 step 的日志（复用 `PodLogDialog`）+ **看渲染出来的 PipelineRun YAML**（复用 `YamlBlock`） |

第 4 条的 YAML 是**排查利器**，也几乎零成本；平台渲染出来的东西用户能看见，
这同时是对「纯函数渲染器」最好的自证。

---

## 7. 后端分层与端点

**平台侧不碰 fabric8** —— 沿用既有边界：`platform-api` → HTTP → `k8s-server` → fabric8（`tekton-client`）。

| 层 | 落点 |
|---|---|
| `k8s-core` | `PipelineRunOperations`（用 `io.fabric8:tekton-client:7.9.0`）+ 渲染器 + 三个 `PipelineNode` 实现 + `BuildPreset` |
| `k8s-server` | 命名空间级 `PipelineRunController`（create / get / list / delete / patch-status） |
| `platform-api` | `PipelineService` + `PipelineController` + `PipelineRunService` + 迁移 |
| `platform-data` | `platform_pipeline` 表 + `platform_credential.usable_by_pipelines` 列 + 5 个权限码 + 角色关联 |

**端点**（platform-api，全部 `POST`，与本仓惯例一致）：

```
/pipeline/list | get | create | update | delete      ← manage / read
/pipeline/node-types                                 ← read（内置节点的 formSchema + 预设）
/pipeline/git-connections                            ← read（窄投影：只回 id/name/endpoint）
/pipeline/run | run/cancel                           ← run
/pipeline/run/list | run/get                         ← read
/pipeline/preview                                    ← read（画布 → 渲染出的 YAML，给前端预览）
```

**数据模型**（三张表）：

| 表 | 关键列 |
|---|---|
| `platform_pipeline` | id · cluster_id · namespace · name · description · `canvas_json` · created_by · created_at · updated_at |
| `platform_pipeline_run` | id · pipeline_id · cluster_id · namespace · **`run_name`**（集群里的 PipelineRun 名，**唯一 → 归档幂等**）· status(`SUCCEEDED`/`FAILED`/`CANCELLED`/`TIMEOUT`) · reason · triggered_by · start_time · completion_time · duration_ms · `steps_json`（每个节点的状态与耗时）· created_at |
| `platform_pipeline_run_log` | id · run_id · node_name · `content`(MEDIUMTEXT) · truncated · size_bytes |

⚠️ 运行与日志**落库**（§4）—— 集群里的对象归档后被删掉，所以**库是运行历史的唯一来源**。

**平台侧**：

```
/internal/pipeline-run/finished        ← k8s-server → platform-api 的内部回调（密钥鉴权，不在用户权限体系里）
```

⚠️ 内部端点**必须在 `ExemptPaths` 之外自己校验密钥**（它与用户 JWT 是两套东西）：
密钥留空则**拒绝一切请求**（fail-closed），与凭据主密钥同一条判断。
调用方是 k8s-server（与 platform-api 同机，`127.0.0.1`），不经用户上下文。

**归档 / 删除 / 保留清理是平台自己的动作，不标审计**（不是用户请求）。但 `/pipeline/run` 仍标 ——
台账 S0.1.3 点名了「CI 触发」。

---

## 8. 审计

**写操作要记**（台账 S0.1.3 点名了「CI 触发」）：`create` / `update` / `delete` / `run` / `cancel`
标 `@Auditable`。`list` / `get` / `preview` / `node-types` 是纯读，不标。

⚠️ 审计的 `target` 用**流水线名**（run 用 `pipelineName`），与 `ClusterController` 取 name 的做法一致。

---

## 9. 测试

| 层 | 测什么 |
|---|---|
| **渲染器（纯函数）** | 表驱动：单节点 / 链 / 分叉再汇合 / 入度 0 / 环检测拒绝 / `runAfter` 的生成；**同一份画布渲染两次结果一致**（这是「历史运行带当时那一版」的前提） |
| **节点实现** | 三个节点各自：`formSchema()` 非空且 key 唯一；`render()` 对最小合法配置产出非空 TaskSpec；**预设只预填、字段可覆盖**（改掉镜像后渲染出来的是改后的值） |
| **护栏** | 所有注册节点两个方法都给全（§2.4） |
| **权限** | 五个码各自的端点；member 有 `run` 无 `manage`（**反面断言**：member 不能 create）；`tenant:` 前缀不变量 |
| **凭据授权** | 未被标记的连接**不出现**在 `/pipeline/git-connections` 里；强行引用未授权连接 id → 拒绝（**不静默降级**） |
| **迁移** | 手工重放 + **join 对账**（5 个码 × 3 个角色的关联逐条命中真实权限行，全表悬空扫描 = 0）—— S0.2/S3.1 的教训 |
| **capability 门控** | 没有 `tekton.dev/v1` 时端点/页面整块不可用，不是 500 |
| **归档** | 一个已终结的 run 归档后：`run_name` 落库、集群对象被删；**同一个 run 投递两次只写一条、只删一次**（幂等钉死）；**抓日志中途 Pod 没了 → 抓到多少存多少、不静默丢**；**未终结的 run 不被归档**（不能把 Running 的当完成的） |
| **不动别人的 run** | 一个**没有平台标签**的已终结 PipelineRun → watch 不理它、归档不碰它、**绝不删它**（这是最容易出事故的一处，要正面钉住）；`/pipeline/run/list` 里也不出现它 |
| **内部端点** | 密钥不对 → 拒绝；**密钥未配置 → 拒绝一切**（fail-closed）；未终结的 run 名 → 不归档 |
| **幂等分支仍要删** | 库已有该 run（模拟「归档成功但删除失败」）后重投 → **不重写库、但必须再试一次删除**（这是最容易漏的一条） |
| **不留孤儿记录** | k8s-server 创建 PipelineRun 失败 → 库里**不能留一条 RUNNING 的孤儿**（先落库再创建的代价要在这里堵住） |
| **截断** | 超过 1 MB 的单步日志 → `truncated=true` 且内容确实被截断；未超的 `truncated=false` |
| **取消的顺序** | **先抓日志、再 patch 取消** —— 断言两次调用的先后（这是「日志拿得到」的全部依据） |
| **超时判定** | 只读活动 watch 的台账、**不打集群**（断言没有出站调用）；未达时限不动；已达 → 走取消那条路且状态记 `TIMEOUT` |
| **保留** | 90 天前的 run 与其日志行**一起**被删；89 天的两条都还在 |

---

## 10. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 项 | 为什么不做 |
|---|---|
| **条件分支 / `finally`** | 片② |
| **自定义节点 / 运行时参数 / 并发策略** | 片③ |
| **部署节点** | 会让构建 Pod 拿到集群写权限（§0）。部署归 S3.2 |
| **触发器 / Webhook / 定时** | 台账已定「仅手动触发」，**不引入 Tekton Triggers** |
| **`imagePullSecret`** | 用户裁定。⚠️ **后果**：构建 Pod 的基础镜像必须来自**免认证仓库**，或用户自己在命名空间建 Secret 并在流水线的「Pod 拉取凭据」里指定。**而 kubelet 不走平台的出站代理** —— 集群节点拉不到 Docker Hub 时，基础镜像只能来自私有 Harbor |
| **平台级流水线模板** | 台账没有；第一批做会引出模板/实例的同步与版本问题 |
| **Tekton Pruner** | 独立项目（0.3.3）、要单独装。**不用它**：它删的时候平台还没抓日志 —— 两个组件各干各的，归档与清理的先后无法保证（§4.1）。平台一个动作做完，时序天然正确 |
| **构建缓存** | per-Pod Buildah 每次从零构建。加缓存要么挂 PVC（vfs 会吃掉大量空间），要么上共享构建服务（跨租户串味）—— 两条都比本批重 |
| **运行历史落库** | §7 的理由 |

### 10.1 已知风险（接受）

1. **`manage` 码 = 能让集群跑任意代码**。这不是漏洞（有权限码、有审计），但它是本平台**权限面最大**的一个码，
   与 S3.5.12 的定性一致。`run` 之所以能给 member，正是因为 `manage` 是那条真正的线。
2. **租户可以看到自己命名空间里构建 Pod 的日志** —— 日志里可能有被误打印的凭据。这也是 `logs` 单独成码的原因。
3. **基础镜像的供应链不加限制** —— 用户能填任意镜像地址。限制来源（比如只允许已配的 registry）
   在"构建 Pod 本来就要跑用户代码"的前提下收益有限，且会让 Docker Hub 公开镜像用不了。

---

## 11. 待实测 / 待查实

与代理那条一样：**纸上推不出来的，必须真跑一遍**。

- [ ] **Buildah per-Pod 的确切 `securityContext`** —— vfs 还是 fuse-overlayfs、要不要 `seccompProfile: Unconfined`、
      能不能做到**不用 privileged**。这决定 §0 那条「零集群写权限」之外还要放宽什么
- [ ] **Tekton `v1` 的取消取值** —— v1beta1 是 `spec.status: PipelineRunCancelled`，**v1 改成了什么**（查实，不猜）
- [ ] **Tekton 给 Pod 打的标签名** —— `tekton.dev/pipelineRun` / `tekton.dev/taskRun` 是否就是要用的那两个
- [ ] ⚠️ **Tekton 的默认超时** —— 平台不设 `spec.timeouts` 之后，Tekton 会不会用它的 `default-timeout`
      feature flag（我记得默认 60 分钟）兜底删 Pod。**若是，必须把集群侧的 `default-timeout` 设为 0（禁用）
      或设成远大于平台超时** —— 否则「超时收进平台」这条设计会静默失效、那批运行的日志照样丢
- [ ] **归档与 kubelet GC / 他方删除的竞态** —— 抓日志中途 Pod 消失时的行为（要「抓到多少存多少」，不是整条丢）
- [ ] **按需 watch 的开与关** —— fabric8 的 `withField("metadata.name", …).watch(...)` 是否就是要用的形态、
      `Watch.close()` 能否干净释放；以及**同时有多个运行在跑**时是每条一条 watch 还是合并
      （合并更省，但要按 run 名分派事件）
- [ ] **1 MB 截断的实际体验** —— 拿一次真实的 `mvn` 构建日志量一下（决定这个默认值要不要调）
- [ ] **`volumeClaimTemplate` 在目标集群的 StorageClass 上能否绑定**（集群有没有可动态供应的默认 SC）
- [ ] **基础镜像能不能拉到** —— kubelet 不走平台的出站代理；确认集群节点拉 `maven:3.9-eclipse-temurin-21` 之类的路径
- [ ] **`tekton-client:7.9.0` 加进 `k8s-core` 后能否与本仓的 fabric8 7.9.0 干净共存**（同版本、应无冲突，但要跑一次构建）

---

## 12. 部署

**本批有 SQL**（与 S3.1 那批不同）。

1. **先重放** `V2026_10_15_1__pipeline.sql`（手工，本仓没有 Flyway）：
   `platform_pipeline` / `platform_pipeline_run` / `platform_pipeline_run_log` 三张表 +
   `platform_credential.usable_by_pipelines` 列 + 5 个权限行 + 角色关联
2. **集群侧**：装 Tekton Pipelines（`profile: lite` 或 vanilla release.yaml）；
   **租户 SA 的 K8s RBAC 模板要加 `tekton.dev` 组**（与当年 Gateway 那个坑同形）；确认有可动态供应的 StorageClass；
   ⚠️ **若 Tekton 有默认超时，把 `default-timeout` 设为 0**（否则超时仍由 Tekton 终结 → 那批运行日志丢失，见 §11）。
   **不需要配 `config-events` 的 sink**（§4.1）。
   ⚠️ k8s-server 的启动扫描要能**集群级 list PipelineRun**（平台侧凭据已有该权限）
3. **归档需要的配置**（§4bis）：`platform.pipeline.archive.{enabled,internal-token}` ·
   `platform.pipeline.run.timeout` · `platform.pipeline.log.{max-bytes,retention-days}`。
   ⚠️ 除 `internal-token` 外都有默认值；**`internal-token` 必须配**，否则内部回调端点拒绝一切请求（fail-closed）
4. **再起新构建**
5. 重放后重新登录，或等权限闭包缓存的 30s TTL

### 12.1 部署后待验清单

- [ ] 集群没装 Tekton 时，**CI 菜单与页面整块消失**（不是报错、不是空白页）
- [ ] 租户成员建一条流水线（`git 克隆 → 命令 → 构建并推送镜像`）→ 保存 → **画布能重新打开并还原**
- [ ] 点运行 → 运行详情里 DAG 按状态着色、每步日志出得来、能看渲染出的 YAML
- [ ] 取消一个正在跑的运行 → 状态变 Cancelled，**对象还在**（历史没丢）
- [ ] **member 账号**：能跑、能看日志，**不能改流水线定义**（按钮不出现，且绕过前端直接调 `update` 也被拒）
- [ ] **未被标记为「允许在流水线里使用」的 git 连接，不出现在下拉里**；强行引用它的 id → 明确报错
- [ ] 审计页能看到 `create` / `update` / `delete` / `run` / `cancel` 的记录，**看不到** list / get
- [ ] **跑完一次之后 `kubectl get pods` 里那个 `Completed` 的 Pod 消失了**，`kubectl get pipelinerun` 也没了 ——
      而平台里的运行记录与日志都在
- [ ] **取消一次正在跑的运行** → 取消之后**日志仍然看得到**（这条验的是「先抓后取消」的顺序）
- [ ] 一条超 1 MB 的日志 → UI 上标了「已截断」
