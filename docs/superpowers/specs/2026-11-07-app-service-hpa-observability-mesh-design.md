# 应用服务：HPA / 可观测 / 服务网格 设计

状态：待评审。建立在 `2026-11-01-application-release-redesign-design.md`（去画布重做）之上，
现行模型见 `docs/development/application-release.md`。

## 1. 为什么做

用户原话：「应用中服务好像还缺少对 hpa 和可观测的支持，还有服务网格。」

勘查结论：
- **HPA 已有一半**：服务配置「高级」里有开关 / 最小 / 最大副本 / 目标 CPU，chart `hpa.yaml` 会渲染（只有 CPU），
  但没有运行态展示，且有一个会在每次发布时把副本打回去的 bug（§3.1）。
- **指标已有**：「监控」页签（资源指标 `MetricsPanel`）+「暴露指标」（ServiceMonitor）。
- **日志只有实时**：「Pod 与日志」页签读 Pod 日志；历史日志（ES）和链路（Tempo）都要求工作负载接入 OTel。
- **OTel 接入、ambient、use-waypoint 在应用服务上用不了**：现成入口在工作负载页
  （`WorkloadService.observability`、`/workloads/{name}/mesh-toggle`），但应用渲染的对象都带
  `platform.coding/managed=true`，`ManagedResourceGuard` 判成 COMPONENT 拒写；就算写进去，
  下一次发布 SSA 也会覆盖。

## 2. 核心决定

1. **会改 Pod 模板或 Service 的配置，一律进服务的期望配置（`desired_json`），随发布渲染下发。**
   它们因此进快照、参与「待发布改动」判定、随回滚恢复，与镜像 / 环境变量是同一套语义。
   旧快照没有这些键 = 关闭 / 跟随，不会因升级而产生待发布改动。
2. **运行态与数据不在应用里重造**：服务页给汇总状态，详细查看跳到现有页面（HPA、日志查询、链路追踪、网格拓扑），
   带上预填的过滤条件。
3. **组件门禁（用户 2026-11-07 定）：集群没安装或不健康的组件，对应功能不允许开启。**
   判据统一用 `ClusterComponentService.flags(clusterId)`（`/cluster/component-flags`，租户可读）。
   - 前端：旗标不可用 → 开关置灰并显示 `reason`；**旗标未拿到（未探测 / 读失败）也按不可用**。
   - 后端：保存服务配置时，某开关**从关到开**（与库里当前 desired 比）→ 现查旗标，不可用就拒绝；
     发布预检对**本次勾选且相对线上快照新开启**的项再查一次（防绕过前端、防保存后组件被卸）。
   - **已开启的不追溯（用户 2026-11-07 确认）**：线上已开启、之后组件没了 → 发布照常，预览给警告；
     关闭始终允许。理由：发布总是整份重渲染应用内所有服务，追溯会让整个应用发不出去，连关掉都发不出去。
   - **回滚只警告、不拦**（设计者的决定）：回滚到一个开着某功能的旧版本，而组件已卸载 / waypoint 已删，
     执行器预检给警告、照常执行。回滚是止血手段，不能被门禁挡住；这些注解 / label 在组件缺席时无害。
     回滚确认对话框把这些警告列出来（复用发布预检的警告结构，不新增预览接口）。
   - **什么算「开启」**：只有让功能生效的取值算开启 —— HPA 开关、暴露指标、`otel.tracing` / `otel.logs`、
     `mesh.dataplaneMode=ambient`、`mesh.useWaypoint=<具体名字>`（包括从一个名字换成另一个名字）。
     「跟随」与 `none`（排除）任何时候都可设，不过门禁（没装 Istio 时它们无害）。

| 开关 | 旗标 | 依赖 |
|---|---|---|
| 自动扩缩容 | `hpa-resource-metrics` | metrics-server |
| 暴露指标 | `servicemonitor` | Prometheus Operator |
| 追踪 / 应用日志（agent） | `otel-collection` | OTel 采集栈 |
| 日志（应用日志或文件日志） | `otel-logs` | 采集栈健康且组件开了「应用日志」 |
| ambient 纳管 / L7 waypoint | `ambient-mesh` | Istio |

「暴露指标」原来没有门禁，本批一并加上（同一规则）。

## 3. HPA

### 3.1 必修 bug：发布把副本打回去

`deployment.yaml` 在 HPA 开启时写死 `replicas: 1`，`statefulset.yaml` 写 `workload.replicas`；SSA 带
`forceConflicts`，所以**每次发布都把 HPA 扩出来的副本强制改回 1（或配置值）**，随后 HPA 再扩回去。

修法：HPA 开启时清单**不写 `spec.replicas`**（两种工作负载都是）。
⚠️ 需要真集群验证两件事（不能只靠单测，见记忆「真跑的验证分层」）：
- 从「平台拥有 replicas」切到「不写」的那次发布，副本是否会被重置为默认 1（字段失去全部 manager 时会被删除）。
  如果会，就接受一次瞬时缩容并在文档写明，或在切换那次发布写入 `max(当前副本, minReplicas)` 后下次再省略——实现者按实测择一，记进文档。
- HPA 经 scale 子资源写的 replicas 在 managedFields 里有自己的 manager，之后的发布不再冲突。

### 3.2 配置

`desired` 现有键 `autoscalingEnabled / minReplicas / maxReplicas / targetCpu`，新增 `targetMemory`（可选，1–100 %）。
渲染：CPU 指标**始终生成**，`targetCpu` 缺省仍按 60（与现行 `AppWorkloadValues.autoscaling()` 一致，存量快照渲染结果不变）；
`targetMemory` 有值才加内存指标。

保存校验（只在 HPA 相关字段有变化时跑，与它无关的保存 —— 比如只改镜像 —— 不因此被拒）：
- `1 ≤ minReplicas ≤ maxReplicas`；
- 不校验 request：渲染时 `cpuRequest` / `memoryRequest` 缺省补 `100m` / `128Mi`，Pod 永远有 request，HPA 都能算；
- 门禁见 §2。

自定义 / 外部指标不做（需要 prometheus-adapter）；`behavior`（扩缩稳定窗口）不做。

### 3.3 页面

- 服务配置：HPA 开启时「副本数」输入框只读，提示「副本数由 HPA 在 min–max 间调整」。
- 服务概览新增「自动扩缩容」一行（仅线上开启时）：当前 / 期望副本、各指标当前值 / 目标值、
  `ScalingActive=False` / `AbleToScale=False` 时的原因（例如拿不到指标），以及「查看 HPA」跳 HPA 详情。
  数据用现有 `hpaApi.get`，名字 = 工作负载名 `<slug>-<key>`；用户没有 HPA 读权限时不显示这一行。
- 概览里的「副本数」在 HPA 开启时显示实时副本，而不是 desired 值。

## 4. 可观测

### 4.1 指标

不动（已有「监控」页签 + ServiceMonitor），只加 §2 的门禁。

### 4.2 日志与链路接入

`desired` 新增一个对象键 `otel`（不存在 = 未接入）：

| 字段 | 说明 |
|---|---|
| `language` | `java` / `python`；开追踪或应用日志时必填 |
| `tracing` | 布尔 |
| `logs` | 布尔 |
| `logMode` | `app`（日志框架，默认）/ `file`（sidecar 读文件） |
| `logFile` | `file` 模式必填：容器内具体日志文件绝对路径 |
| `sampling` | 1–100，空 = 跟随组件默认；只在 `tracing` 时生效 |

**语义与工作负载页的「可观测接入」完全一致**：同样的注解（`instrumentation.opentelemetry.io/inject-<lang>`
指向三个 Instrumentation 之一、文件日志的 sidecar 注解与文件注解）、同样的 env（`OTEL_TRACES_SAMPLER` +
`_ARG` 必须同时写）、同样的 emptyDir 日志卷。实现上把 `WorkloadService.observability` 里「请求 → 注解 / env / 卷」
的计算抽成一个纯函数类，两处共用；`WorkloadService` 行为不变（单测保持绿）。

- `OTEL_SERVICE_NAME` **固定为工作负载名 `<slug>-<key>`**，不可改：日志和链路的跳转过滤靠它。
- 开启接入时，用户环境变量里出现 `OTEL_SERVICE_NAME` / `OTEL_TRACES_SAMPLER` / `OTEL_TRACES_SAMPLER_ARG` → 拒绝保存
  （平台写的值和用户的同名变量谁生效不可预期）。
- 渲染分工（k8s-server 零业务）：platform-api 算出 Pod 模板额外注解、额外 env、日志卷挂载目录，
  经 `AppNodeDTO.params` 传下去；`AppWorkloadValues` 只透传为 `workload.podAnnotations` /
  `workload.extraEnv` / `workload.logVolume`，chart 照着渲染。
- 注解和 env 都在 Pod 模板里，改了自然滚动；`configHash` 不需要改。

### 4.3 查看

服务页（线上已接入时）给两个入口：
- **历史日志** → `/observability/logs?workload=<工作负载名>`
- **链路** → `/mesh/traces?service=<工作负载名>`

需要给 `LogsView` 补读 `workload`（可选 `pod`）query 参数、给 `TraceView` 补读 `service` 参数，进页即按其过滤；
集群 / 命名空间仍取顶栏上下文（两页都是 `context: 'full'`，跳转前顶栏就是应用所在的上下文）。
没有对应页面权限时不显示入口。「Pod 与日志」里的实时日志保持不变。

## 5. 服务网格

`desired` 新增一个对象键 `mesh`（不存在 = 全部跟随）：

| 字段 | 取值 | 写到哪 |
|---|---|---|
| `dataplaneMode` | 缺省 = 跟随命名空间；`ambient`；`none`（排除） | Pod 模板 label `istio.io/dataplane-mode`（服务与后台进程都可设） |
| `useWaypoint` | 缺省 = 跟随命名空间；waypoint 名；`none` | **Service** label `istio.io/use-waypoint`（只有有端口的服务） |

- waypoint 放在 Service 上而不是 Pod 上：平台建的 waypoint 默认 `waypoint-for: service`，Pod 级只对
  `workload/all` 生效，放 Pod 上在默认配置下必然静默不生效。候选只列 `waypoint-for` 为 `service` / `all` 的
  waypoint，复用 `GatewayService.waypointNamesOf` 与 `canHandleService` 的口径（数据来自租户可读的 `/gateways/list`）。
  当前值不在候选里时保留一条 disabled 回显项（与 Service 编辑器同口径）。
- 保存校验：`useWaypoint` 是名字时，必须是同命名空间里存在且能处理 service 流量的 waypoint。
  发布预检：本次勾选的服务所引用的 waypoint 已不存在 → 拦；未勾选的 → 警告（istio 会静默放行，不报错）。
- 命名空间级的 ambient / waypoint **不在应用里设**，那是命名空间分配 / 运维的事。
- 服务概览新增「服务网格」一行：配置值（跟随 / 纳管 / 排除；waypoint 名）+「在网格拓扑里看」
  → `/mesh/topology?focus=<命名空间>/<工作负载名>`。命名空间级真实值租户读不到，「跟随」就只显示「跟随命名空间」。
- label 进 Pod 模板会触发一次滚动；这是一次发布，可接受。

## 6. 不做

- HPA 的自定义 / 外部指标、`behavior`。
- 在应用里内嵌日志 / 链路查询界面。
- 用 HTTPRoute 做灰度或流量切分（原 spec 已列为不做）；对外域名 / 入口网关（待入口管理方式定）。
- AuthorizationPolicy、mTLS 策略、跨命名空间 waypoint（`use-waypoint-namespace`）。
- 应用「拓扑」页签叠加网格实测流量（请求量 / 错误率）：以后可做，本批不做。
- 统一工作负载页 / HPA 页等现有页面的组件门禁：另起任务（同一口径）。

## 7. 接口、权限与迁移

- 不新增接口：配置走现有 `service/create`、`service/update`；读 HPA、waypoint 候选、组件旗标都用现有读接口。
- 预计**无新权限行、无 SQL 迁移**。实现者核实：租户成员角色是否持有 HPA 读码、`tenant:gateway:list`、
  组件旗标的读码；缺哪个就在页面上隐藏对应展示（不为此新加权限）。
- `AppServiceDesired.PASSTHROUGH` 不适合嵌套对象：`otel` / `mesh` 要有自己的规范化（白名单字段、取值校验），
  未知字段照旧拒绝。

## 8. 实施顺序

1. HPA：§3.1 bug + `targetMemory` + 校验 + 门禁 + 概览状态。
2. 可观测：抽纯函数类 + `otel` 键 + chart 值 + 门禁 + 两个页面的 query 参数与跳转。
3. 网格：`mesh` 键 + chart 的 Pod / Service label + waypoint 校验 + 概览与跳转。
4. 文档：`application-release.md` 新增一节；chart `README.md` 的 values 表与产物对照表。

每步改 chart 后跑 README 要求的三种渲染（service / worker / service+persistence），`AppWorkloadChartRenderTest` 补用例。

## 9. 验收（部署后在真集群）

- 开 HPA 后连续发布两次，HPA 扩出的副本不被打回；从「手动副本」切到 HPA 那次发布的副本变化与文档一致。
- 没装 metrics-server / Prometheus Operator / OTel / Istio 的集群：对应开关置灰；直接调接口开启被拒；
  已开启的服务在组件卸载后仍能发布，预览有警告；关闭能发布。
- 接入 Java 服务：日志在 ES、链路在 Tempo，服务页两个跳转直接过滤到它。
- ambient 纳管后网格拓扑能看到它；设 waypoint 后出现 L7 边。
- 回滚到接入前的版本，注解 / label 随之消失。
- 卸载 OTel 后回滚到接入后的版本：确认框有警告，回滚照常完成。

## 10. 实现者可自行决定

纯函数类命名与位置、概览里各行的样式、HPA 状态的刷新方式、chart 值的具体键名（§4.2 列的是建议）。
