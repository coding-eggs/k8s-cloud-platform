# 流水线源码预览（sha 预览 + 变更 diff）设计

2026-11-18。目标：**触发前**能看清这次会构建哪个 commit、相对上次成功运行改了哪些文件；**触发后**能追溯实际构建的 commit，归档之后仍然可查。

## 1. 现状与约束（核对过的事实）

- 触发入口只有列表行「运行」→ `PipelineRunDialog`（只能勾选屏蔽节点）→ `POST /pipeline/run {id, tenantId, maskedNodeIds}`（`PipelineController.run`）。平台里没有 webhook / 定时触发，所有运行都是人点的。
- repo / revision 写在画布 git 节点里（`GitCloneNode` 表单：`connectionId / repo / revision / dir`）。revision 留空 = 默认分支，**触发前平台不知道会拿到哪个 commit**。
- 克隆脚本是 `git clone --depth 1 [--branch <revision>] <repo> <dir>`，之后 `git rev-parse --short HEAD > /workspace/.git-sha`。日志里没有提交信息；表单明确写了**填 commit sha 会报 Remote branch not found**（脚本只会 `--branch`）。
- 渲染在 k8s-server 侧：platform-api 把裁剪后的画布 + 已授权解密的连接（`PipelineConnectionRef`）发过去（`K8sPipelineClient.createRun` / `PipelineCanvasRequest`），k8s-server 渲染成 PipelineRun。
- 归档即删集群侧 PipelineRun / TaskRun / Pod；运行行 `platform_pipeline_run` 只留状态快照（`stepsJson`）、画布（`canvasJson`）、归档那一刻的 YAML（`runYaml`）。
- **库里没有任何 commit / sha 字段**（`PlatformPipelineRun` 全字段、`platform-data` 全模型都 grep 过）；`runYaml` 里只有取 sha 的脚本，不是取值。
- 结论：没有“上次成功运行的 sha”可对比 → 预览/追溯都缺数据基础，**落库是前置**。

## 2. 目标 / 非目标

目标：

1. 触发对话框里显示每个 git 节点：`仓库 · 分支/标签 → 实际 sha（提交标题、作者、时间）`；有上次成功运行时给出对比基准。
2. 按需展开**文件级变更**（状态 A/M/D/R + 增删行数）。
3. 预览到的 sha 必须**钉住**到这次运行——否则预览与真正构建会漂移。
4. 每次运行都**落库** commit 信息，归档后运行详情仍可看、仍可查 diff。
5. 能力不可用时（仓库不可达、超时）**降级不阻塞**：照旧能运行，只是没有预览。

非目标：

- 不做 Webhook / 定时触发的“预检”（目前不存在这类触发）。
- 不做“必须看过 diff 才能运行”的硬门禁。
- 清单只到**文件级**（谁变了、多少行）；单个文件的 unified diff **按需取**（点开某一行才去拉），
  不整份返回 —— 一次格式化能改几千个文件，整份补丁没有上限。
- 不引入 GitLab/GitHub Compare API（多方言 + 凭据二次暴露）。

## 3. 总体设计

```
用户点「运行」
  ├─ 打开对话框 → POST /pipeline/run/preview（withDiff=false）
  │     ↳ platform-api：解析连接（复用授权校验）→ ls-remote → sha + 提交标题
  │                    → 与上次成功运行（source_json）对比
  ├─ 点「查看变更」→ POST /pipeline/run/preview（withDiff=true）
  │     ↳ JGit 浅取两个 commit（head / base）→ DiffFormatter → 文件清单
  └─ 点「运行」→ POST /pipeline/run { …, pinnedShas: {nodeId: sha} }
        ↳ 服务端仍有兜底：UI 没给 pin 时自己解析一次并落库（best-effort）
        ↳ 渲染时把 pin 交给 GitCloneNode：克隆后校验 HEAD，不一致就 fetch 该 sha 并 checkout
        ↳ source_json 写入运行行
```

两个决定：

- **解析与 diff 放在 platform-api**，用 JGit。理由：凭据明文与出站代理本来就在 platform-api（`CredentialProbeClient` 就是从这里探测 git host 的），业务判断（基线是哪次运行、哪些节点要预览）也属于业务层；k8s-server 保持“零业务”。拒绝的两个替代方案见 §11。
- **钉住（pin）是执行契约**，不是提示：运行按 pin 的 sha 构建，取不到就**失败**而不是悄悄换成别的提交。

## 4. 数据模型

`platform_pipeline_run` 新增一列：

```sql
ALTER TABLE platform_pipeline_run
  ADD COLUMN source_json TEXT NULL
  COMMENT '本次运行各 git 节点解析出的源码信息（JSON 数组），见 PipelineSourceRefDTO';
```

结构（每个 git 节点一条；将来多仓多节点天然支持）：

```json
[{
  "nodeId": "git-1",
  "repo": "https://gitlab.example.com/group/repo.git",
  "revision": "main",
  "kind": "branch",
  "sha": "3f2c...（完整 40 位）",
  "shortSha": "3f2c1ab",
  "subject": "fix: 修正登录跳转",
  "author": "张三",
  "committedAt": "2026-11-18T09:12:33+08:00",
  "baseSha": "9a01...",
  "baseKind": "last-success",
  "baseRunName": "platform-web-20261117-…",
  "fileCount": 3, "additions": 42, "deletions": 7,
  "pinned": true,
  "error": null
}]
```

- 迁移文件：`platform-data/src/main/resources/db/migration/V2026_11_18_1__pipeline_run_source.sql`（本仓无 Flyway，**手工重放**）。
  历史 diff 端点另有一条 `V2026_11_18_2__pipeline_run_diff.sql`（权限行）。
- 前端 `PipelineRun` 类型加 `sourceJson?: string | null`（原样透传字符串，沿用 `stepsJson` 的既有风格）。
- **落库的只有元数据**（repo / 分支 / sha / 基线 / 标题 / 作者 / 时间）。文件清单**与统计**都不落库：
  一次格式化能改几千个文件，体积不可控；历史详情要看时用存下来的 `sha/baseSha` 现查（§5.3）。

## 5. 接口

### 5.1 预览

`POST /pipeline/run/preview`

```json
{ "id": "<pipelineId>", "tenantId": "...", "maskedNodeIds": [], "withDiff": false }
```

返回（按 git 节点）：

```json
{ "sources": [ { …同 §4 结构…, "diff": null } ] }
```

- 授权：复用 `PipelineRunService.resolveConnections`（逐条校验“允许在流水线里使用”，**不静默降级成匿名**），只解析本次要跑的子画布（屏蔽闭包 `PipelineCanvasMask.maskedClosure` 之后）。
- `withDiff=false` 走 `ls-remote`（不下载对象，通常 <1s）。
- `withDiff=true` 走 §7 的 JGit 流程（30s 上限），单个仓库失败只在该节点上回 `warning`，不影响其它节点。
- **两档的分界线是「要不要下载对象」**：打开触发框、触发时落库都走 false 那一档；
  只有用户点「查看变更」才走 true（depth-1 取一个 commit 会把整棵快照下下来，普通仓库几秒、
  monorepo 几十秒甚至几百 MB）。所以 P1 的预览里没有提交标题，P2 点「查看变更」时一次拿到标题 + 清单。
- 权限码：沿用 `tenant:pipeline:run`（能运行的人才能预览源码）。

### 5.2 触发（扩展，向后兼容）

`RunRequest` 增加可选 `pinnedShas: Map<nodeId, sha>`：

- 校验：nodeId 必须存在于**裁剪后的**画布里且节点 type=git；sha 必须是 40 位十六进制。任一不合法 → 400，不运行。
- 服务端兜底：未给 pin 的 git 节点 → 触发时自己 `ls-remote` 一次；成功则 pin + 落库，失败则本次不 pin、`source_json` 里记 `error`（**照旧运行**，与今天行为一致）。
- 落库的 `canvasJson` 不含 pin（pin 不走画布，走 `PipelineCanvasRequest` 的新字段，见 §6）。

### 5.3 历史 diff（按需）

`POST /pipeline/run/diff`

```json
{ "runName": "...", "nodeId": "git-1", "tenantId": "..." }
```

从运行行读 `source_json` 拿 `sha/baseSha/repo`，再用当前仍然可用的连接现算 diff。凭据已被删除/未授权 → 返回该原因，前端提示“当时没有留存变更明细”。

### 5.4 运行详情

`run/get` 返回的 `sourceJson` 已含元数据；`PipelineRunDetailView` 的 git 节点卡片显示 `shortSha · subject`，展开看作者/时间/基线，「查看变更」调 5.3。

## 6. 渲染层（k8s-core / k8s-server）

- `PipelineCanvasRequest` 增加可选 `pins: Map<String, String>`（nodeId → 完整 sha）。k8s-server 原样放进 `NodeContext`（新增 `pin()` 访问器），不解释含义——它只负责“渲染时把 pin 交给节点”。
- `GitCloneNode`：
  - 有 pin 时脚本在克隆之后校验：

    ```sh
    head=$$(git -C '<dir>' rev-parse HEAD)
    if [ "$$head" != "<sha>" ]; then
      git -C '<dir>' fetch --depth 1 origin "<sha>" \
        || { echo "本次运行钉住的提交 <sha> 已不可获取（远端可能已前进或清理），请重新预览后再运行"; exit 1; }
      git -C '<dir>' checkout --detach "<sha>"
    fi
    ```

  - 末尾一律回显 `echo "commit: $$(git -C '<dir>' rev-parse HEAD)"`，日志里永远能看到实际构建的 sha（不依赖 result）。
  - `.git-sha`（短 sha，镜像 tag 用）语义不变。
- `render` / `preview`（画布校验）路径不带 pins：它们给用户看，pins 只属于“创建运行”。

## 7. platform-api 的源码解析（JGit）

新增 `platform-api` 依赖（仅此模块，不进 common / k8s-server）：

```xml
<dependency><groupId>org.eclipse.jgit</groupId><artifactId>org.eclipse.jgit</artifactId><version>7.x</version></dependency>
```

`SourcePreviewService`：

1. **解析引用**：`LsRemoteCommand`（heads + tags）。`revision` 为空 → 取 `HEAD`，`kind=default`；否则先 `refs/heads/<rev>` 再 `refs/tags/<rev>`（与 `git clone --branch` 的优先级一致），都没有 → 该节点 `error="分支/标签不存在"`。
2. **拿提交元数据**：把目标 sha `fetch --depth 1` 进临时裸仓（`+<sha>:refs/preview/head`），`RevWalk` 读 subject / author / 时间。
3. **基线**：取该流水线最近一次 `SUCCEEDED` 运行的 `source_json` 中同 nodeId 的 sha（`status=SUCCEEDED` 按 startTime 倒序取前 20 条在 Java 里过滤）；没有则取 head 提交的第一个父提交（`kind=parent`，等价于“本次提交的变更”）；再没有 → 无基线，不做 diff。
4. **diff**：再 `fetch --depth 1` 基线 sha（`+<base>:refs/preview/base`），`DiffFormatter` 扫两个 commit 的树，输出 `path / status / additions / deletions`。`diff A B` 不需要祖先对象，depth 1 够用；因此**不提供“相差 N 个提交”**（需要完整历史）。
5. **降级**：服务端拒绝按 sha fetch（`uploadpack.allowReachableSHA1InWant=false` 的自建源）→ 退成“浅克隆该分支 depth 1 后核对 sha”：一致才算成功，不一致回 `error`（不猜）。超时（30s）/ 仓库过大 → 只回 sha + 元数据，diff 留空。
6. **临时目录**：`Files.createTempDirectory("pipe-preview-")`，`finally` 递归删除；凭据以 `UsernamePasswordCredentialsProvider` 进内存，**不落盘**。
7. 配置：`platform.pipeline.preview.timeout-ms`（默认 30000）、`max-files`（默认 200，超出标 `truncated`）。

## 8. 前端交互

`PipelineRunDialog`（[PipelineListView.vue](platform-web/src/views/PipelineListView.vue) 触发）：

- 打开即调 `preview`（无 diff），在节点清单上方渲染「源码」区，每个 git 节点一行：
  `github/gitlab 图标 · repo 末段 · 分支 main → 3f2c1ab「fix: 修正登录跳转」`；右侧 badge：`与上次成功构建相同` / `较上次 +3 文件` / `首次运行`。
- 「查看变更」→ 带 `withDiff=true` 再调一次，展开文件表（状态色沿用 StatusBadge 的语义色），底部提示“另有 N 个文件未显示”。
- 「运行」把预览到的 sha 作为 `pinnedShas` 传给后端；预览失败的节点不传。
- 预览失败：黄色提示条“源码预览不可用：原因”，运行按钮照常可用。

`PipelineRunDetailView`：

- git 节点卡片副标题追加 `3f2c1ab · fix: …`（有 `sourceJson` 时）。
- 节点详情里「变更」区块：显示基线（“较上次成功运行 9a01c2f”）+ 文件表（按需调 5.3）。
- 老运行（`sourceJson` 为空）：显示“未记录（本能力上线前的运行）”。

## 9. 权限与审计

- 新端点 `/pipeline/run/preview`、`/pipeline/run/diff` 各加一条权限行，code 沿用 `tenant:pipeline:run`，角色绑定从 `/pipeline/run` 那行**复制**（同 `V2026_11_17_1` 的写法）。
- `preview` 与 `diff` 都按 `PIPELINE_RUN` 审计（它读的是私有仓库内容）。
- **部署顺序**：先重放 SQL（新列 + 权限行）→ 再部署 platform-api → 再部署 k8s-server（pins 字段）→ 最后前端。k8s-server 先于 api 部署也安全（未知字段忽略）；但 api 先于 k8s-server 部署时 pin 会被忽略 → 钉住失效，必须按顺序。

## 10. 分阶段与验收

**P1 sha 预览 + 钉住 + 落库（≈3–4 人天）**

- SQL / 实体 / `RunRequest.pinnedShas` / `PipelineRunDTO.sourcePins` → `NodeContext.pins` / `GitCloneNode` 校验脚本 / 触发时 best-effort 解析落库 / 对话框源码区（仓库 · 分支 → sha · 与上次成功运行对比）/ 运行详情显示短 sha。
- 验收（真跑）：
  - 预览显示 sha A → 运行 → 日志 `commit: A`，`source_json.sha = A`；
  - 预览后推进分支 → 仍按 A 构建（pin 生效）；A 被远端清掉 → 节点失败且报错文案可读；
  - 归档后运行详情仍显示 A；
  - 无 `tenant:pipeline:run` 的用户调 preview 403。

**P2 文件级 diff（≈2–3 人天）**

- JGit diff、`withDiff`、对话框变更表、`/pipeline/run/diff`、运行详情按需 diff；同一次拉取顺带补上**提交标题/作者/时间**（P1 为了不下载对象而省略）。
- 验收：构造 增/改/删/改名 四种变更，清单与 `git diff --name-status` 一致；超过 200 文件标 truncated；自建源禁 sha-fetch 时降级文案正确；30s 超时不影响运行。

**P3 打磨（≈1 人天）**

- 基线回退到父提交的展示、预览结果短时缓存（同一对话框反复打开不重复 ls-remote）、多 git 节点时的汇总。

## 11. 被否掉的两个替代方案

- **集群侧“预检”TaskRun**：网络与凭据最真实，但每次预览起一个 Pod，还要在运行历史 / 对账里隔离“预检运行”，并让 k8s-server 长出业务端点。等真出现“platform-api 连不上、只有集群能连”的 git host 再说。
- **UI 直连 GitLab/GitHub Compare API**：多方言、浏览器拿不到内网 git、凭据要二次暴露。

## 12. 风险

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 自建源禁止按 sha fetch | 钉住后取不到该提交 | 克隆后能核对则放行；取不到就**失败并给可读文案**，绝不静默换提交 |
| 大仓库 fetch 超时 | 预览慢/失败 | 30s 上限 + 只回 sha 的降级；不缓存对象 |
| JGit 与 git CLI 语义差异（深浅克隆、tag） | diff 结果与真构建不一致 | diff 只用于展示；构建语义仍以 GitCloneNode 的脚本为准，pin 校验一致 |
| 凭据在预览期被使用 | 权限面扩大 | 与触发同一条 `resolveConnections` 授权；预览纳入审计 |
| 老运行无 `source_json` | 历史对比缺失 | 显式显示“未记录”，首次运行后自然积累 |
| 部署顺序错（api 先于 k8s-server） | pin 被忽略、预览与执行漂移 | §9 的部署顺序写进发布说明 |

## 13. 落地记录（2026-11-18，P1 + P2 同批）

实现时才明确下来的几件事，改动任何一条都会让「看到的」与「跑的」对不上：

- **一个节点一个临时仓库**：`readCommit`（depth-1 取 head）与 `diff`（depth-1 取 base）共用同一个
  `RepoSession`，否则同一个提交会被下两遍。`close()` 递归删目录，凭据只在内存。
- **shallow 边界提交没有父提交**：depth-1 取下来的提交被 git/JGit 当作「无父」，所以
  `RevCommit.getParentCount()` 恒为 0。而「首次运行的基线 = 上一提交」正需要那个 id ——
  改为从提交对象的原始字节里读 `parent <sha>` 行（只多读几十字节，比再下一棵父树便宜）。
  这一条**只有真跑才会暴露**（本地仓库测试才发现）。
- **diff 只需要两棵树**：`CanonicalTreeParser` 各自 reset 到两个提交的树，`DiffFormatter`
  开 `setDetectRenames(true)`（否则一次 `mv` 会读成「删了一个又加了一个」），行数从 `Edit`
  的类型与长度累加，二进制文件由 `FileHeader.PatchType.BINARY` 判定。
- **历史 diff 的 connectionId 来自运行行的 `canvas_json`**，不是 `source_json`：凭据引用
  不属于源码元数据，混进去会让「哪些字段能公开」变得含混。
- **测试用本地仓库当 remote**（`file://` URL）：不联网就能把 ls-remote → 浅取 → 两棵树对比
  整条链路真跑一遍。⚠️ 测试仓库必须打开 `uploadpack.allowReachableSHA1InWant`（基线提交通常
  不是任何 ref 的尖端）—— 这同时也说明自建源不开这项时，预览会如实降级成「变更清单不可用」。
- **前端文案与清单各只有一份**：`sourceRefText.ts`（repo/分支/基线文案）与
  `PipelineSourceFiles.vue`（文件表），触发对话框与运行详情共用，避免两处口径分叉。
- **JGit 的代理只有 `create(url, proxy)` 那条路有效**（2026-11-18 真环境撞到）：`TransportHttp`
  构造时取 `ProxySelector.getDefault()` 自己算一遍，再调 `factory.create(u, proxy)`；默认选择器
  不知道我们的白名单，传进来的永远是 `DIRECT`。只实现单参数的 `create(url)` 是**死代码** ——
  症状是直连外网 git host 超时，报一句没有上下文的 `connection failed`（`SocketException`），
  日志里连"代理"两个字都不会出现。改法：在 `create(url, proxy)` 里按白名单**覆盖**它。
- **多个 git 节点必须并行解析**：超时是按**每条 JGit 命令**算的（默认 30s），串行的话
  N 个节点最坏 N × 30s，界面上就是「一直在解析仓库」。前端轻档的超时收紧到 45s
  （axios 全局是 120s，等不到任何反馈）。
- **解析失败要打日志**：失败只回给前端的话，运维在日志里看不到任何痕迹（真环境排障时
  正是这一行指出问题）；现在每个 `error` 都有一条 `WARN 解析 git 节点源码失败 …`。
- **单文件补丁走 `POST /pipeline/run/diff/file`**（2026-11-18 追加，用户要「能看改啥了」）：
  历史运行用 `runName`（sha 从运行行读），触发前预览用 `pipelineId` + 预览回来的 sha/baseSha
  （**只用于展示**，不参与执行决策）。`PathFilter` 只比那一个文件，补丁正文按
  400 行 / 512 KB 封顶并标 `truncated`。状态徽标改用单字母（A/M/D/R/C）—— 中文「修改」在窄列里
  会竖着折行。
