# 工作负载级日志设计（S2.1）

> 台账：[task-ledger-2026-10-12.md](../../task-ledger-2026-10-12.md) 的 **S2.1 工作负载级日志**
> 需求依据：[gap-analysis-2026-10-12.md](../../gap-analysis-2026-10-12.md) §T1-2 / §5.1 #1
> 状态：**设计已定**（2026-10-16 brainstorming，结论见下表）。实施计划见 `../plans/`。

---

## 0. 先说清：k8s 原生有没有"工作负载级日志"

**服务端没有。** 核心 API 里看日志的入口只有 **Pod 的 `logs` 子资源**
（`/api/v1/namespaces/{ns}/pods/{name}/log`），粒度就是**一个 Pod 的一个容器**。
Deployment / StatefulSet / DaemonSet **没有** log 子资源 —— 在真集群上问过
`/apis/apps/v1/namespaces/default/deployments/<name>/log` → **404**，
而同一时刻同一个 Pod 的 `log` 子资源能正常取到内容。

**kubectl 客户端有，而本批做的就是它的 UI 等价物。** kubectl 的三个便捷用法（都在客户端实现）：

| kubectl | 它其实做了什么 |
|---|---|
| `kubectl logs deployment/foo` | 解析出该负载的 selector → 列出 Pod → 逐个发 `pods/log` |
| `kubectl logs -l app=x --all-containers --prefix` | 同上；`--all-containers` **会自动打开 `--prefix`** |
| `kubectl logs -l app=x --max-log-requests=10` | 扇出时最多同时请求几个；**默认只有 5**，超了直接报错让你显式调高 |

kubectl 在防的，正是本 spec §3.2 那个"副本太多怎么办"——它用 `--max-log-requests` 防，我们用勾选上限防。
`--ignore-errors` 对应 §3.4 的部分失败降级；`-p/--previous` 是**同一个子资源**的参数，对应 §2。

**真正"服务端级 / 可检索"的工作负载日志，k8s 核心没有** —— 业界答案是日志采集器
（DaemonSet 收）送到 Loki / ES / ClickHouse，也就是台账里的 **S2.3**。
两者不冲突也不重叠：**本批解决"现在怎么了"**（在线排障、多副本横向比一眼），
**S2.3 解决"上周那次是怎么回事"**（历史、检索、Pod 删了还在）。

---

## 已定决策（brainstorming 结论，2026-10-16）

| 问题 | 结论 | 为什么 |
|---|---|---|
| 聚合出来的日志长什么样 | **每行带前缀 `[pod/<pod名>/<容器名>]`，混成一条流**（照抄 `kubectl logs -l app=x --prefix` 的形态） | 十个副本跑同一个应用时 90% 的行是一模一样的噪声；分段得读十遍，混着看只需扫一眼「哪个 Pod 在刷错」。这也正是"逐个 Pod 点开"这个痛点的反面 |
| 副本很多时怎么办 | **打开时先勾选** Pod（默认勾最近创建的 10 个，上限 20），不做"异常优先"的智能预选 | 用户裁定：**不做**按失败/异常排序的预选 —— 状态列就摆在那儿，谁坏了自己看得见 |
| `previous` 的作用范围 | **本批一起做**，但**只在单 Pod 那条路上**（聚合视图不做） | 用户裁定：聚合就是把日志聚起来；Pod 的失败原因"点进去看"。而"点进去"这条路现在恰好缺 `previous` |

---

## 1. 交付边界

**做**：

1. **`previous`（上一次容器实例）** —— 两个 Pod 日志端点各加一个查询参数 + 单 Pod 日志弹窗加一个开关。
2. **工作负载级聚合视图** —— 工作负载详情页新增入口：勾选若干 Pod → 一条带前缀的流，每 2 秒增量追加。

**不做**（见 §8 的完整清单）：日志后端（Loki/ES）与历史日志 · 时间范围下推 · 服务端关键字检索 ·
跨 Pod 的全局时间排序 · 聚合视图里的 `previous` · 自动纳入滚动更新中新起的 Pod。

---

## 2. `previous` —— 上一次容器实例

### 2.1 它是什么

容器崩溃重启时，**Pod 不变，换的是里面的容器实例**：新实例的日志文件从空的重新开始，
被换掉的那一代由容器运行时**保留一代**。`kubectl logs <pod> -c <c> --previous` 读的就是那一代。

救命场景是 **CrashLoopBackOff**：应用一启动就崩、kubelet 反复重启它，你点进日志看到的要么是空的、
要么只有刚起来那几行 —— **崩的那一瞬间的堆栈在上一次实例的日志里**。
Pod 状态那行只说 `Back-off restarting failed container`、`restartCount: 7`，不给原因。

**它不是历史日志**：只有**一代**。重启 7 次只给第 7 次；容器从没重启过时直接报「没有上一个终止的容器」。
要留全得靠日志后端，那是 S2.3。

### 2.2 后端：fabric8 里它叫 `terminated()`，不叫 `previous()`

⚠️ **已经反编译核实过**（`kubernetes-client-7.6.1.jar`）：
`BytesLimitTerminateTimeTailPrettyLoggable.terminated()` →
`PodOperationContext.withTerminatedStatus(true)` → 常量池里的 **`&previous=true`**。
所以 Java 侧要调的是 `.terminated()`。

⚠️ **链式上有个坑**：`terminated()` 返回 `TimeTailPrettyLoggable`，**会丢掉 `limitBytes`**
（`limitBytes` 与 `terminated` 都挂在 `BytesLimitTerminateTimeTailPrettyLoggable` 上，调了任一个就降级）。
两个日志端点本来都没用 `limitBytes`（它们靠 `transferTo` 直出流），所以无影响 ——
但**别顺手加**，加了就编译不过。

要改的两个端点（都只是加一个查询参数，k8s-server 的"零业务逻辑"不变）：

| 端点 | 位置 | 前端调用方 |
|---|---|---|
| `/pods/{name}/logs` | `PodController.logs`（命名空间域） | Pod 列表 / 工作负载详情 / Pod 详情 |
| `/nodes/{name}/pods/{ns}/{pod}/logs` | `NodeController.podLogs`（集群域） | 节点详情 |

改写形状（两个端点同构）：

```java
BytesLimitTerminateTimeTailPrettyLoggable pretty = client.pods()...inContainer(container).usingTimestamps();
// ⚠️ 必须是三元表达式这一步"降级"——terminated() 之后就不是 Bytes 那条链了
TimeTailPrettyLoggable loggable = Boolean.TRUE.equals(previous) ? pretty.terminated() : pretty;
```

### 2.3 前端

`PodLogDialog` 加一个开关「上一次」。**只在 Pod 真的重启过时才给**（否则点了必然报
「没有上一个终止的容器」）—— `K8sPod` 已有 `restarts?: number | null`（`PodDTO.restarts` 的口径是
**容器重启次数合计**，见 `PodDTO:28`），`restarts > 0` 才显示这个开关。

---

## 3. 聚合视图

### 3.1 入口与形态

工作负载详情页顶部按钮「全部 Pod 日志」→ 打开一个大弹窗（复用现有日志弹窗的外壳观感：
全屏、贴底自动跟随、Esc 退出、复制）。

### 3.2 第一步：勾选 Pod

弹窗打开时**不是一个日志流，而是一张 Pod 表**：

| 列 | 来源 |
|---|---|
| 名字（复选框） | `pod.name` |
| 状态 | `pod.phase`（异常时附 `pod.statusReason`） |
| 重启次数 | `pod.restarts` |
| 创建时间 | `pod.creationTime` |
| 调度节点 | `pod.nodeName` |

⚠️ **列到这儿为止**：`K8sPod` 上**没有**就绪数、也没有每容器的状态 ——
`containerDetails` 装的是 spec 侧的详情（env / ports / probes），不是状态。
现有工作负载详情页的 Pod 表也只有 名称 / 容器 / IP / 调度节点 / 重启，这里与它保持一致。

- **默认勾最近创建的 10 个**（纯按创建时间倒序，**不做**异常优先）
- **上限 20 个**，到顶后其余行禁用并说明
- 顶部常显一句：`这个负载共 37 个 Pod，已勾 10 个（最多 20 个）`
- 表里没有 Pod 时（`replicas=0`）直接说明「这个工作负载当前没有 Pod」，不给确定按钮

### 3.3 第二步：一条带前缀的流

- 每行前缀 **`[pod/<pod名>/<容器名>]`** —— **照抄 kubectl `--prefix` 的形态**，不自创。
  在真集群上跑过，它长这样：
  ```
  [pod/movie-deployment-976f89cbb-5l7tv/movie] 2026-10-16T19:21:48.185+08:00 DEBUG 1 --- …
  ```
  照抄的理由：排障的人多半会先想到 `kubectl logs -l … --prefix` 那条命令，
  两边长得一样，从 kubectl 过来的人不用重新认。多容器 Pod 的**每个容器都跟**，
  靠前缀分开 —— 前缀要能把 (pod, 容器) 唯一定位。
- 轮询：**每个 (pod, 容器) 各自一个 `sinceTime` 游标**（沿用既有增量能力），2 秒一轮
- 新行**在"本轮"内**按时间戳排序后再追加。**不跨轮缓存排序**：那需要缓冲"还没到齐的更早行"
  才能保证全局有序，延迟与内存都不好控，而排障时多等几秒就是副作用
- 时间戳前缀的拆解与归一化沿用既有口径（见 §4 的纯函数），显示时剥掉、只留自己加的前缀

### 3.4 部分失败

某个 (pod, 容器) 拉不到（Pod 刚被删、容器还没起、`previous` 不存在）时：
**不影响其余**，在该来源第一次失败处插一行说明（`<pod>/<容器>：暂时取不到日志（原因）`），
后续轮次照常重试；恢复后不再重复提示。

---

## 4. 前端结构

| 文件 | 职责 |
|---|---|
| `views/resource/WorkloadDetailView.vue` | 加「全部 Pod 日志」入口按钮（改现有文件） |
| `components/WorkloadLogsDialog.vue`（新） | 勾选 Pod → 一条带前缀的流。管理 N 个游标、排批内序、贴底跟随 |
| `utils/logLines.ts`（新） | **纯函数**：k8s 时间戳前缀的拆解 / 归一化 / 按时间戳排序 / 加前缀 |
| `components/PodLogDialog.vue` | 加「上一次」开关；时间戳处理改调 `utils/logLines.ts`（**只换实现来源，渲染不动**） |

**为什么不把聚合塞进 `PodLogDialog`**：那个文件已经 ~450 行，再塞进勾选表 + N 条流 + N 个游标会到 700 行。
本仓的既有判断是"文件长了就是它做多了"。

**为什么只抽纯函数**：两处的**展示形态**本来就不同（单 Pod 用 xterm，聚合是一条带前缀的流），
能共用的只是时间戳口径 —— 而这份口径**必须一致**，否则同一份日志在单 Pod 视图和聚合视图里
会显示出不一样的行。抽纯函数同时满足这两点，且不碰 `PodLogDialog` 的渲染，回归面最小。

---

## 5. 后端分层与端点

- **不新增任何端点**。聚合是前端对**既有的单 Pod 日志端点多发几次**。
- **不新增权限行** —— 每一次抓取的授权与今天完全一样（逐个 Pod 走既有的边界校验）。
- **platform-api 不动**：这两个日志端点是前端**直连 k8s-server** 的
  （`PodLogDialog.fetchLogs` 用 `fetch` 带 Bearer 打 `/api/pods/{name}/logs`），不经过 platform-api。
- **k8s-server 只加一个查询参数**，零业务逻辑的定位不变。
- **为什么不做服务端聚合**：增量游标是**每 (pod, 容器) 一个**；服务端要合并就得设计
  「游标映射进 / 新行映射出」的接口 —— 那份复杂度换来的只是省几个浏览器 → k8s-server 的往返，
  而那些本来就是直连的，且被 §3.2 的 20 个上限压住了。

---

## 6. 权限

**本批不动权限表**（见 §5）。聚合视图能看到什么，完全由"你能看到这个工作负载"+"你能读它的 Pod 日志"
这两条既有授权决定 —— 勾选表里列出的 Pod 就是列表页本来就能看到的那批。

---

## 7. 测试

**后端**（`k8s-server`，两个端点各一）：
- 带 `previous=true` → fabric8 链上确实调了 `terminated()`（用 mock 的 `PodResource` 断言）
- 不带 → **不调** `terminated()`（防"顺手一直带着"）
- `previous` 与 `tailLines` / `sinceTime` 同用时链式不报错

**前端**：
- `utils/logLines.ts` 单测：前缀拆解（制表符 / 空格两种分隔）、小数秒补零后**字典序=时间序**、
  批内排序、加前缀（含多容器）
- 浏览器冒烟（真组件 + 假 token + XHR stub）：
  1. 勾选表默认勾最近 10 个、上限 20、到顶禁用
  2. 确定后，**两个 Pod 的新行都出现在同一条流里**、前缀正确
  3. 每个来源的游标**各推进各的**（stub 记录每次请求带的 `sinceTime`）
  4. 贴底自动跟随；往上翻不被拽走（沿用既有冒烟的做法，几何断言要先钉"面板有真实高度"）
  5. 某个 Pod 拉失败 → 只多一行说明，其余照常追加
- `PodLogDialog` 的「上一次」：`restarts=0` 时**不出现**、`restarts>0` 时出现且请求带上 `previous=true`

---

## 8. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

| 不做 | 为什么 |
|---|---|
| 历史日志（Pod 删了就没有） | 要引日志后端，属 S2.3 |
| 时间范围下推、服务端关键字检索 | 同上；现在只有客户端过滤 |
| 跨 Pod 的全局时间排序 | 见 §3.3：增量轮询下要缓冲才有全局序，代价是延迟 |
| 聚合视图里的 `previous` | 用户裁定：失败原因走"点进去看"那条路（§2.3 已把它补齐） |
| 勾选表里的"异常优先"预选 | 用户裁定不做 |
| **自动纳入滚动更新中新起的 Pod** | 勾选那一刻定下的就是那批。自动加进来会让你正在读的位置被新 Pod 的日志冲掉；要看新副本就重开一次 |
| 把聚合做成工作负载详情页里的常驻区块 | 日志是"要的时候才打开"的东西，常驻会让 N 条流在后台一直轮询 |

---

## 9. 已实测的前提（2026-10-16 在本集群上问过，**不是推断**）

1. **kubelet 真保留了上一代日志** ✓ —— 拿一个重启过 1 次的 Pod 实测：
   `restartCount=1`；当前实例给的是 `2026-10-16T19:27` 的行，而 `--previous` 给的是
   `2026-10-15T18:39` 的 JVM 退出/GC 行 —— **两代内容确实不同**，不是空、也没报错。
2. **`previous` + 增量轮询没有意义，已定成"一次性快照"** ✓ ——
   `--previous --since-time=<现在>` 的输出长度是 **0**、退出码 **0**。
   道理也直白：**上一次实例是已终止的容器，不会再产出任何行**。
   所以本批的实现是：**「上一次」模式固定走 `tailLines` 回看一次，不参与增量轮询**
   （`sinceTime` 一个字都不发）。
3. **`previous` 的粒度只能到 Pod，到不了容器** —— 已查实：`K8sPod` 上没有每容器的状态
   （`containerDetails` 是 spec 侧的详情，不是状态），只有**合计**的 `restarts`。
   后果：一个多容器 Pod 里 A 重启过、B 没有时，合计 > 0 → B 上也会给「上一次」开关，
   点了会报「没有上一个终止的容器」。**接受**这个多给的开关 ——
   那个报错本身已经把话说清楚了，而为此加一套每容器状态是为一个小角落扩接口。

---

## 10. 部署

| 服务 | 为什么 |
|---|---|
| **k8s-server** | 两个 Pod 日志端点加了 `previous` 参数 |
| **platform-web** | 新组件、新入口、`PodLogDialog` 的开关 |
| platform-api | **不用重建**（本批没动它） |
| 数据库 | **无迁移**（不新增权限行、不改表） |
