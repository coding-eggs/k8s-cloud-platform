# 应用发布

> 设计：`docs/superpowers/specs/2026-11-01-application-release-redesign-design.md`
> 本文取代旧的「应用编排（画布）」文档：画布、整份 spec、预检/部署/操作记录/修订历史已于 Task 13 删除。

## 1. 模型

**应用 = 一组服务 + 一组依赖 + 一串不可变的发布。** 没有画布，没有整份 spec。

| 概念 | 存在哪 | 说明 |
|---|---|---|
| 应用 | `platform_application` | 只登记集群、命名空间、名称（slug）；`spec_json` 新应用恒为 null |
| 服务 | `platform_app_service` | 每个服务一行 `desired`（镜像、端口、副本、环境变量、配置文件……），带乐观锁 `version` |
| 依赖 | `platform_app_dependency` | 中间件（应用创建 / 绑定已有）或外部连接；**中间件不进发布**，增删即生效；外部连接可改（§1.2） |
| 发布 | `platform_app_release` | 一次发布的不可变快照：勾选服务的 desired + 解析结果；回滚 = 用旧快照再发一次 |
| 执行 | `platform_app_execution` | 发布/回滚的阶段机，`PENDING → … → DONE`；platform-api 正常重启 / 滚动更新时由新实例接着跑（凭据在 Redis，TTL=token 有效期）；凭据取不到（Redis 不通 / token 过期）才进入「已中断」，可继续 |
| 对象台账 | `platform_application_resource` | 平台建过的 K8s 对象；prune 差集、冲突判定、删除应用时清理 |

**待发布改动代替草稿**：服务页保存只改 `desired`；应用页列出「与线上不同」的服务，勾选后发布。
未勾选的服务不会带着自己的待发布改动上线，但仍会从线上快照重新渲染；
若它引用的服务端口或中间件实例的连接信息变了，其 configHash 随之变化，也会滚动重启。
外部连接被修改的情况例外：引用方被列为待发布，并且必须一并勾选（§1.2）。

### 1.1 变量引用代替连线

环境变量和配置文件里写 `${名称.字段}`，名称是服务 key 或依赖 key，在 platform-api 渲染前解析
（`AppEnvResolver`）。

- 环境变量可引用全部字段；凭据字段（`username` / `password`）渲染成 `secretKeyRef`，明文不进快照。
- **配置文件只能引用 `host` / `port` / `address` / `url`**：文件内容会原样进 ConfigMap，凭据请走环境变量。
  保存时校验，渲染时再拦一次。
- 引用了不存在的 key 会在发布预览里报出来，不能发布。
- **依赖可以绑定租户内其他命名空间的实例**（同集群）。地址是 `<服务>.<命名空间>.svc.cluster.local`，
  跨 ns 直接可用；但 `secretKeyRef` 不能跨命名空间，所以平台在发布 / 初始化前把实例的凭据 Secret
  复制到应用所在的命名空间（副本名 `mwdep-<应用>-<依赖名>-<源 Secret>`，带
  `platform.coding/managed-by=app-dependency-secret` 与 `platform.coding/app-id` 标签）。副本随依赖
  增删自动对齐：不再需要的副本在下次发布时删掉，删除应用时一并清掉；只认平台标签，用户自建的 Secret
  不会被覆盖或删除。

### 1.2 修改外部连接（`/application/dependency/update`）

依赖抽屉「修改连接信息」，只对外部连接：地址、端口、Secret 名、用户名键、密码键可改；依赖名和协议不可改
（要换协议就新增一个依赖）。中间件依赖没有这个入口，规格在中间件实例页改。

- **不改线上**。发布时外部连接信息取自当前依赖台账并写进快照，而发布总是把线上所有服务整份重新渲染，
  所以改完之后**任何一次发布**都会把新地址带给引用方。为了不让它们「被顺带改掉」：
  - 待发布判定（`AppPending.compute`）把「引用的外部依赖与线上快照不同」也算作改动，字段名 `依赖 <key>`；
  - 发布时勾了别的服务、没勾这些引用方 → 进 `requiredKeys`，必须一并勾选（与「引用了未发布的服务」同一个机制）。
- 去掉端口 / 键名后仍有服务引用对应字段：保存放行，发布预览按「引用不存在的字段」拦下。
- 没有乐观锁（依赖表无 version 列）：两人同时改，后保存的覆盖先保存的。
- 回滚用的是旧快照里的外部连接信息；回滚后引用方会重新显示为待发布，再发布一次即用上当前登记的值。

### 1.3 拓扑

应用页的「拓扑」页签是只读图：节点 = 服务 + 依赖，边由服务的环境变量与配置文件引用生成
（使用方 → 被使用的一方）。不存储、不可拖拽、不可连线。点服务进服务页，点依赖打开依赖抽屉。

### 1.4 下线（`/application/release/offline`）

下线 = 一条 `type=OFFLINE`、快照不含服务的发布，走同一个执行器（租约、凭据、失败可「继续」都一样）：
PRECHECK 后直接跳到 PRUNE，删掉台账 `platform_application_resource` 里本应用的**全部**对象（不分发布号、去重），
**PVC 保留**（重新上线后 StatefulSet 挂回同一个卷）；结束时线上发布号指向这条下线记录、应用状态 `OFFLINE`。
服务配置、依赖与中间件实例都不动。线上快照为空 → 所有服务显示为「新增，待发布」，**重新上线就是一次普通发布**。
下线记录不能作为回滚目标（不含服务）；回滚到下线前的某个版本也可以重新上线。

### 1.5 对外暴露（NodePort / 网关端口）

服务配置「基本」页签：有端口的服务可选「不暴露 / NodePort / 网关 HTTP / 网关 TCP」
（设计 `docs/superpowers/specs/2026-11-08-app-istio-exposure-design.md`）。

- **NodePort**：端口固定在 30000-32767。留空则**保存时由平台挑一个空闲端口写进 desired**（`AppNodePortAllocator`），
  之后发布 / 回滚都不变——不交给 K8s 随机（本集群 nodePort 范围是 1-65535，随机会落到低端口或端口池段）。
  「空闲」= 集群所有 Service 已占用的 nodePort（k8s-server `GET /application/node-ports`）∪ 同集群其他应用服务已保存的
  nodePort；自己的线上 Service / 台账行不算。填了的端口被占就在保存时拒绝。读不到集群则保存失败，不猜。
  并发两次保存挑到同一端口的极小概率由发布时 K8s 报「端口已占用」兜底。desired 存 `expose: nodeport` + `nodePort`。
- **网关 HTTP / TCP**（`expose: gateway-http | gateway-tcp`）：经 `istio-system/external-gateway` 转发，集群外用
  「节点 IP:端口」访问。端口从 Istio 组件的「应用端口池」分配（默认 20000-20999），**不进 desired**，锁在分配表
  `platform_ingress_port` 里：
  - 保存时分配并锁定，一个服务一个端口；HTTP ↔ TCP 切换只改协议、端口不变；
  - 渲染：Service 保持 ClusterIP，另生成 HTTPRoute / TCPRoute，`parentRefs` 指向命名空间 ListenerSet
    `platform-ingress` 的 listener `p<端口>`；网关 HTTP 只支持 HTTP，HTTPS 服务选网关 TCP（TLS 透传）；
  - PRECHECK：快照里网关方式的服务确保有端口（下线后重新上线、回滚到旧版本时重新分配），再把命名空间 ListenerSet
    整份同步（同命名空间多个应用共用一个）；缺 ListenerSet CRD、没开端口池在这里就失败，不会下发半截；
  - 释放：服务从未发布过时改回不暴露 / 删服务立即释放；**已上线的端口等发布成功后**（快照和期望配置都不再是网关方式）
    才摘 listener、释放，否则线上入口会在发布前就断，发布失败时旧版本的路由也会失去 listener；下线、删除应用释放全部端口并同步 ListenerSet；
  - 释放一律「先下发、后释放」：ListenerSet 同步失败时端口保持占用（listener 还在集群上）；
    发布后 / 下线时同步失败只记日志、端口保留（下次成功发布再放 / 再上线复用）；删应用 / 删未上线服务时同步失败也释放（残留 listener 由该命名空间下次同步摘掉）；
  - 保存时**不改**已有端口的协议（HTTP ↔ TCP），协议在发布 PRECHECK 按快照改，线上入口在发布前不受影响；
  - 同一 (集群, 命名空间) 的 ListenerSet 同步用 MySQL `GET_LOCK` 串行（跨实例），等锁 30 秒；
  - Istio 组件操作进行中（PENDING / RUNNING）时不分配网关端口（端口池可能正在缩）；
  - 入口 Gateway 不存在时同步按「不支持」处理（PRECHECK 失败），不会下发挂不上的 ListenerSet；
  - 释放后冷却 10 分钟才会分给别的服务；下线后再上线 / 改回网关方式时**优先拿回这个服务上次的端口**（不受冷却限制），
    只有原端口已被别的服务占了才换；
  - 一个命名空间最多 64 个网关端口（ListenerSet 上限），满了保存时就拒绝；
  - 门禁：依赖 Istio 的 `routes` 旗标（没装不让开、已开可关）；
  - 查看：「网络 → ListenerSet」只读页（租户视角看本命名空间，平台视角直接列集群全部命名空间），
    `platform-ingress` 标「平台维护」，每条 listener 显示归属的应用 / 服务；分配表里没有的标「无归属（残留）」。
  - 状态按**每条 listener** 的 Programmed / Conflicted 判，不看 ListenerSet 顶层 Programmed：顶层是父 Gateway 的
    地址判定，Istio 1.31 会把 Gateway 的 `AddressNotUsable`（"port N not found for hostname"）抄给所有 ListenerSet。
    成因（读 istiod 源码确认）：`ServiceIndex.instancesByPort` 只在 **Service 变化**时重建，EndpointSlice 的端点增量
    不触发重建；端口池扩进 Service 时端点还没就绪，索引就缺这些端口，直到集群里**任意** Service 再变一次（或 istiod
    重启）才恢复。此时各 listener 仍 Programmed=True、流量正常（2026-11-08 实测 20000 端口 200）。
  - 应用详情页服务列表有「对外端口」列：网关端口（异常时带状态）/ 线上 NodePort（概览现读 Service，自动分配的也能看到）/ 仅集群内。
- 「不暴露」不存 `expose` / `nodePort`。
- k8s-server 侧端点在 `/application/ingress-listenerset`、`/application/service-ports`（UPSTREAM 边界：调用方是租户的
  保存 / 发布，`/components/**` 要求平台侧身份会 403）。命名空间放行标签 `ingress.platform.coding/ports` 用独立
  field manager `platform-ingress-label` 维护；命名空间编辑提交的 `ingress.platform.coding/` 前缀标签会被剔除。

### 1.6 过程日志（`/application/execution/logs`）

表 `platform_app_execution_log`（迁移 `V2026_11_06_2`），执行器每个阶段追加一条：预检结论、渲染出哪些对象、
冲突检查、下发到哪个命名空间、健康检查、清理 / 删除了哪些对象、完成 / 失败 / 中断（含原因）、谁点了继续。
健康检查每 3 秒一拍，**只在内容变化时追加**（与该执行上一条相同就跳过）；内容逐个列出未就绪对象，
工作负载再下钻到 Pod（k8s-server `AppPodDiagnosis`：调度失败、拉镜像失败、CrashLoopBackOff + 上次退出码、
配置引用缺失、初始化容器未完成、探针没过；说不清时补该 Pod 最近一条 Warning 事件），最多 3 个 Pod。
第一个 Pod 原因也拼进执行的 `message`，所以超时失败的报错里能直接看到。
写日志失败只记 warn，不影响发布。前端：进度面板下方时间线（随轮询刷新）、发布记录详情抽屉里也有一份。

### 1.7 HPA / 可观测 / 服务网格（设计 `docs/superpowers/specs/2026-11-07-app-service-hpa-observability-mesh-design.md`）

都**随发布渲染**；资源页不能改应用对象（`platform.coding/managed=true` 被 `ManagedResourceGuard` 拒绝写入），
所以不在工作负载页 / Service 页配。服务页只做配置与摘要，细节跳已有页面。

**链路追踪与服务网格是应用级设置**（网格的使用说明在应用设置「服务网格」旁的「网格说明」，`AppMeshGuide.vue`，行为改了要同步）（应用详情「应用设置」，存 `platform_application.settings_json`，发布快照的 `app` 字段）：
一条链路跨多个服务，只开一部分就断，采样由入口决定（parent-based），各服务各设采样率没有意义；网格的 mTLS / L7 策略也要调用双方都在网格里。
日志采集、HPA、指标各服务确实不同，留在服务级。渲染与门禁前先用 `AppSettings.effective` 把应用设置合并进每个服务，
之后的注入（`AppServiceInjection`）和旗标判定（`AppServiceFeatures`）不区分设置来自哪一级。

| 位置 / 键 | 渲染到 | 说明 |
|---|---|---|
| 服务 `autoscalingEnabled` / `minReplicas` / `maxReplicas` / `targetCpu` / `targetMemory` | HPA | CPU 指标始终生成（`targetCpu` 缺省 60）；`targetMemory` 有值才加内存指标；只在 HPA 相关字段变化时校验 `1 ≤ min ≤ max` |
| 服务 `otel {language, logs, logMode, logFile}` | Pod 模板注解 + 容器 env（+ file 模式的 emptyDir 卷） | 与工作负载页「可观测接入」共用 `OtelInjection`；`OTEL_SERVICE_NAME` 固定为工作负载名 `<slug>-<key>`；语言与日志都没有 = 不存这个键；服务里写 `tracing` / `sampling` / `mesh` 会被拒（提示去应用设置） |
| 应用 `tracing` / `sampling` | 同上（探针注解 + 采样 env） | 只对**选了语言**的服务生效；没选语言的服务（Nginx、Go）不注入、也不需要组件 |
| 应用 `mesh {mode, waypoint}` | 见下表 | 没有 `mesh` = 跟随命名空间（不写任何标签）；L7 的 waypoint 必填，候选只列本命名空间 `waypoint-for` 为 service / all |

| `mesh.mode` | Pod 模板 `istio.io/dataplane-mode` | 有端口服务的 Service `istio.io/use-waypoint` | 得到什么 |
|---|---|---|---|
| `none` | `none` | 不写 | 显式不加入网格（盖过命名空间的 ambient） |
| `l4` | `ambient` | `none` | ztunnel：自动 mTLS、按身份 / 端口的 L4 授权、TCP 指标；显式不走 waypoint（盖过命名空间级 waypoint） |
| `l7` | `ambient` | `<waypoint>` | L4 全部 + 经 waypoint：HTTP 路由（HTTPRoute 挂 Service）、重试超时、L7 授权、请求级指标与拓扑 L7 边；后台进程没有 Service，只有 L4 |

**发布**：应用设置的期望值与线上快照 `app` 不同时，待发布列表多一项伪 key `@app`（`AppSettings.KEY`），发布对话框里单独一行。
勾选才带新值（全部服务滚动），不勾沿用线上值。回滚与服务同口径：期望 = 线上（没有未发布编辑）才同步为旧快照的值，否则保留为待发布。
下线快照保留 `app`，重新上线不会显示成改动。

**组件门禁**（`AppFeatureGate` → `ComponentFeatureGuard`，未探测 = 不可用）：

| 功能 | 旗标 |
|---|---|
| HPA | `hpa-resource-metrics` |
| 暴露指标（ServiceMonitor） | `servicemonitor` |
| 链路追踪 / 日志框架方式的日志 | `otel-collection` |
| 日志采集（两种方式） | `otel-logs` |
| ambient / 具体 waypoint | `ambient-mesh`（「跟随」和 `none` 任何时候都能设） |

- **保存**只拦「从关到开」（换成另一个 waypoint 也算开）；已开的始终能关、能保留。服务保存看合并后的生效配置：
  应用开着追踪时给服务首次选语言 = 该服务开始被追踪，同样要组件。应用设置的 waypoint 换了名才校验是否存在且能处理服务流量，
  查询本身失败（集群断开、无 RBAC）放行。
- **发布预览 / 创建**：勾选的服务（勾了 `@app` 则是全部服务）里相对线上新开启、而组件不可用的 → 拦；其余生效中但组件已不可用的 →
  只警告，照常发布（不追溯）。同一原因涉及多个服务合并成一条；waypoint 只查一次，报「应用设置：…」。
- **回滚**不调门禁：回滚是止血手段，确认框里只列警告。
- 前端：开关在组件不可用且保存值为关时置灰并给原因；服务概览只按**线上快照**显示「自动扩缩容 / 可观测 / 服务网格」三行，
  按钮跳 HPA 列表、日志查询（`?workload=`）、链路追踪（`?service=`）、网格拓扑（`?focus=<ns>/<workload>`），目标页无权限则不显示。
  HPA 行只有当前副本 / min–max / 指标目标：`HpaDTO` 没有 status（当前指标值、`ScalingActive` 等条件），要看去 HPA 页。

**HPA 开启时清单不写 `spec.replicas`**（Deployment 与 StatefulSet 都是）：发布带 SSA `forceConflicts`，写了就会每次把
HPA 扩出来的副本强制打回。配置页「副本数」此时只读。只省略还不够：字段若只有 `platform-app` 拥有（手动副本，
或 HPA 还没改过它），SSA 省略它会把字段删掉、回默认 1（StatefulSet 会被缩到 1 个序号）。所以 k8s-server
`AppApplyService` 在 apply 前，对清单里不写 replicas 且线上已存在的 Deployment / StatefulSet，先用 fieldManager
`platform-app-replicas-handover` 按**线上当前值**认领一次 `spec.replicas`（K8s SSA 文档「Transferring ownership」），
交接失败就不写清单、发布报错。真集群结论见 §5 的真跑项。

### 1.8 入口灰度（设计 `docs/superpowers/specs/2026-11-09-app-canary-release-design.md`）

**模型**：灰度状态只存在发布快照里 —— `services.<key>.canary = {desired, replicas, weight, header{name,value}|null, external}`，
不建新表。线上快照带这个块 = 该服务正在灰度；服务台账的 desired 始终是 stable。`AppCanary` 是唯一读写这个块的地方；
`AppPending` 不把它算作待发布改动。

**渲染**：灰度中的服务 `X` 多一个节点 `X-canary`（对象名 `<slug>-X-canary`），用灰度版本的 desired 渲染，
副本取 `canary.replicas`，不自动扩缩、不暴露、不带路由。stable 的路由带两个加权后端（`weight` / `100-weight`），
HTTP 且开了 header 时再加一条优先的 header 匹配规则直达 canary。`-canary` 是保留后缀，服务 key 不能以它结尾。

**四个操作**都是普通发布（`AppCanaryService`，走同一执行路径）：

| 操作 | 发布 type | 快照里的服务 | 台账 |
|---|---|---|---|
| 开始 `/application/canary/start` | `CANARY_START` | stable + 灰度块（默认副本 1、权重 10、HTTP 默认 `x-canary: true`） | 不动 |
| 调整 `/application/canary/update` | `CANARY_UPDATE` | 只改给出的字段 | 不动 |
| 全量 `/application/canary/promote` | `CANARY_PROMOTE` | = 灰度版本 desired，去掉灰度块 | 写成灰度版本 desired |
| 回退 `/application/canary/abort` | `CANARY_ABORT` | = 线上 stable，去掉灰度块 | 与线上 stable 不同时写回 |

前提：服务已上线、**应用线上设置为 L7 网格或服务网关暴露（HTTP / TCP）**（见下「内部灰度」）、无状态（`persistenceEnabled` 不为 true，
与 chart 选 StatefulSet 同一判据）、应用没有进行中的执行；灰度版本的端口、暴露方式、服务类型、协议（HTTP / TCP）必须与 stable 一致；
权重 0-100；header 只对 HTTP 服务。
live_release_no 只在执行成功时切换，所以失败的操作不改变「是否在灰度」：全量失败后可以回退，回退会把台账一并写回 stable。

**ROUTE 阶段**：`PRECHECK → RENDER → CONFLICT → APPLY → HEALTH → ROUTE → PRUNE → DONE`。APPLY 跳过
`HTTPRoute` / `TCPRoute`（`AppApplyRequestDTO.skipKinds`），HEALTH 不看路由，全部工作负载就绪后 ROUTE 只下发路由
（`onlyKinds`；k8s-server 按 `---` 切文档保留原文过滤）。所以 canary 没就绪前权重不会生效；ROUTE 只确认写入成功，
不读 Istio 的 Programmed（顶层状态在本集群不可信）。下线仍是 `PRECHECK → PRUNE`。

**锁**：灰度中的服务不能改配置、不能移除、不能在普通发布里勾选；有任何服务在灰度时不能回滚、不能下线。
回滚目标快照里若带灰度块会被剥掉（回滚只还原 stable）。

**指标** `/application/canary/metrics`（`window` 只收 5m / 15m / 1h，其余按 15m）：Istio `istio_requests_total` /
`istio_request_duration_milliseconds_bucket`（HTTP：请求/秒、5xx 错误率、P50 / P99）与 `istio_tcp_*`（TCP：新建连接/秒、
收发字节/秒），按 `cluster_name` + `destination_workload_namespace` + `destination_workload=~"<stable>|<canary>"` + `reporter`
过滤、`sum by (destination_workload)` 拆两列（canary 不是独立 Service，只能按工作负载分）。返回
`{window, protocol, internal, external}`：`internal` = `reporter="waypoint"`（集群内调用，应用为 L7 时才有），
`external` = `reporter="source"`（网关进来，服务网关暴露时才有），没有的组为 null；单项查不到为 null（前端「暂无数据」）。
前端只在 canary 错误率比 stable 高 1 个百分点以上时标橙，不拦操作。

**内部灰度**（设计 `docs/superpowers/specs/2026-11-09-app-internal-canary-design.md`）：

- 前提三分：应用线上设置为 L7 网格（`mesh.mode=l7` 且选了 waypoint，`AppSettings.isL7`）→ 集群内调用也分流，没网关暴露的服务也能灰度；
  非 L7 但网关暴露 → 只分网关流量（同入口灰度）；两者都不是 → 不能灰度。
- 网格路由：L7 时灰度中的服务多一条 GAMMA 路由 `<slug>-<key>-mesh`（HTTP 服务 HTTPRoute，TCP 服务 TCPRoute），
  parentRef 是 stable Service 本身（`group: ""`、`kind: Service`、`port`），后端权重 / header 规则与网关路由同一份。
  k8s-server 只认通用参数 `meshRoute` / `protocol`，不知道「灰度」。没在灰度的服务不渲染、调用路径不变。
- 协议：`expose=gateway-tcp` → TCP；否则 `probeType=tcp` → TCP；否则 HTTP。TCP 服务的 Service 端口名（及 ServiceMonitor 端口）
  为 `tcp`（chart `expose.portName`），`targetPort` 仍是 `http`。
- `external`（「外部流量参与灰度」，默认 true）：只在 L7 且网关暴露时可关；关掉后网关路由不带 canary 后端，网关流量全进 stable。
  非 L7 时传 false 报错；没有网关暴露时存 false。旧灰度块没有这个字段按 true 读。**一个权重**同时管内部与外部。
- header 直达对集群内调用同样生效，但需要调用方把请求头往下传（平台不注入）。
- 网格锁：有服务在灰度时，应用设置不能进出 L7、不能换 waypoint（`AppServiceConfigService.updateSettings` 与发布勾选
  「应用设置」两处都拦，「有服务在灰度（…），先全量或回退再改网格」）；跟随 / 不加入 / L4 之间、链路追踪仍可改。前端：原本 L7 → 网格整块只读；
  原本非 L7 → L7 选项置灰。

**界面**：入口在应用页服务列表的操作列 —— 未灰度显示「开启灰度」（直接弹对话框，顶部说明分流范围；未上线 / 非 L7 且无网关暴露 / 有状态时置灰并提示原因），
灰度中显示「管理灰度」（抽屉：分流范围说明、权重滑块 + 快捷档、「外部流量参与」开关（L7 且网关暴露才有）、header 规则、
指标按「内部调用」「网关进来」分两张表、两个版本的 Pod、修改灰度版本配置 / 全量 / 回退）；
服务详情页不放灰度；应用详情页状态旁「灰度中 N%」；发布对话框里灰度中的服务不可勾选；
发布记录显示四种灰度类型。

## 2. 配置文件

服务页「配置文件」页签：每条一个绝对路径 + 内容。

| 规则 | 值 |
|---|---|
| 路径 | 绝对路径，不以 `/` 结尾，每段只含 `[A-Za-z0-9._@+=,-]`，不能有 `.` / `..` / 空段，≤255 字符 |
| 冲突 | 路径不能重复、不能互相嵌套；不能落在持久化数据卷（默认 `/data`）之内 |
| 数量 / 大小 | 每服务至多 20 个，内容合计至多 512KiB |
| 引用 | 只允许 `host` / `port` / `address` / `url` |

渲染：一个服务一个 ConfigMap `<slug>-<key>-files`（键 `file-0..N`），每个文件 `subPath` **只读**挂到
它的路径，不覆盖目录里的其他文件。`subPath` 挂载**不会跟随 ConfigMap 更新**，所以文件内容计入
Pod 模板注解 `platform.coding/config-hash`：改文件 → 哈希变 → 发布时滚动。没有配置文件的服务哈希不变
（老服务升级后不会因此重启）。`-files` 与 `-env` / `-secret` / `-headless` 一样是保留后缀，服务和依赖
key 不能以它结尾。

## 3. 上线

按顺序执行，**迁移与代码必须同批**。

1. **上线前在平台库查旧应用，并用线上现有构建删掉部署过的**（设计 §11：不迁移旧应用）：
   ```sql
   SELECT id, name, namespace, status, current_revision,
          EXISTS (SELECT 1 FROM platform_application_resource r WHERE r.app_id = a.id) AS has_objects
     FROM platform_application a
    WHERE deleted_at IS NULL AND spec_json IS NOT NULL AND spec_json <> '';
   ```
   `current_revision > 0` 或 `has_objects = 1` 的是**部署过的旧画布应用**，必须在升级前用线上现有构建的页面删除
   （会 prune 它建过的对象，PVC 与中间件实例保留）。新构建已没有「旧模型只读」守卫，这类应用留到升级后会被当成
   新应用放行发布，它在资源台账里的旧对象从此无人认领。其余（从未部署过的）由清理迁移转成新模型的空应用，无需处理。
   提前和使用方确认要删的应用。
2. **手工重放迁移**（本仓没有 Flyway，启动日志不会有 Migrating schema），按文件名顺序：
   `V2026_11_02_10__application_release` → `V2026_11_02_11__application_release_permissions` →
   `V2026_11_02_12__application_release_rollback_permission` → `V2026_11_02_13__application_dependency_permissions` →
   `V2026_11_02_14__drop_application_canvas_legacy`。最后这条是画布遗留清理（§3.1），**第 0 步报
   「存在已部署的旧画布应用…」表不存在就说明第 1 步没做干净**：回到第 1 步删掉后再重放本条（它会跳过所有结构改动，
   可重复执行）。之后再重放 `V2026_11_03_1__application_dependency_update_permission`（修改外部连接的权限行）、
   `V2026_11_06_1__application_release_offline_permission`（下线的权限行）。
   main 上的 `V2026_11_01_1__object_storage_platform_page`、`V2026_11_01_2` 与本批无关，照各自说明重放。
3. **上线前在真 MySQL 上各跑一次两条条件更新**（单测只覆盖了 mock）：执行表 mapper 的 `reopen`
   （继续已中断的执行）和 `supersedeInterrupted`（新发布取代已中断的执行）。用一条手工造的
   `INTERRUPTED` 执行行跑一遍，确认语法与影响行数后删掉这行。**不要留到上线后**：这两条 SQL 平时不走，
   写错了不会在启动或普通发布时暴露，只会在第一次「继续已中断的执行」或「新发布取代已中断的执行」时
   才报错 —— 那正是用户最需要它们的时候。
4. **重建** k8s-server、platform-api、platform-web（platform-data / platform-common 作为依赖一起构建）。
   **k8s-server 必须先于 platform-api 部署，且升级窗口内不要发布**：新 platform-api 对旧 k8s-server 会写出
   未展开的 `$(_REF_n)` 值、丢掉 configFiles，并沿用旧的就绪判定。另外先让仍在进行的旧画布部署操作跑完再升级，
   它们的 worker 随升级一并删除，不会再被续跑。
5. **platform-api 只能单副本**：执行器的进度、依赖初始化的恢复都假设只有一个实例；
   多副本会出现两个实例同时推进同一个执行。
6. **「重下发全部模板」**：私有镜像拉取依赖命名空间 default SA 已挂 `platform-registry-pull`
   （命名空间分配时下发；渲染时不再注入）。没挂的命名空间发布会卡在拉镜像。
7. **重新登录**：权限行有新增，旧会话拿不到新码。

**网关端口暴露（§1.5）**：
1. 重放 **V2026_11_08_1**（`platform_ingress_port` 表；新 mapper 读它，不重放保存网关方式直接 SQL 报错）。
   若重放过 11-08 早先的版本（没有 `active_service` 生成列 / `uk_ingress_active_service`）：表里没有数据，`DROP TABLE` 后重放。
2. 重建 k8s-server → platform-api → platform-web，**k8s-server 先部署**（新 platform-api 调的
   `/application/ingress-listenerset` 等端点旧 k8s-server 没有；旧 k8s-server 的 HEALTH 也不认 HTTPRoute / TCPRoute）。
3. 到 Istio 组件「编辑」里确认「应用端口池」范围并**保存一次**：生成网关 Service 的 `pool-<端口>` 端口（nodePort = 端口）
   和 Gateway 的 `allowedListeners`。不保存则集群没有端口池，网关方式保存时报「集群未启用应用端口池」。
   保存前检查现有 NodePort Service 没有落在池段里（本集群 nodePort 范围是 1-65535，未固定的 NodePort 会随机分配）。
4. 租户 RBAC 模板不需要 `listenersets` 写权限（ListenerSet 由平台用集群管理员身份下发）。
5. ListenerSet 只读页：重放 **V2026_11_08_2**（权限行 + 租户模板加 `listenersets` 的 get/list/watch），
   然后在 RBAC 模板页点一次「重下发全部模板」，否则租户打开页面会被 K8s 403。

**HPA / 可观测 / 服务网格（§1.7）**：先重放 **V2026_11_07_1**（`platform_application` 加 `settings_json` / `settings_version`
两列 + `/application/settings/update` 权限行，绑 admin / tenant_admin；新 mapper 读这两列，不重放直接 SQL 报错），
重新登录拿新码。然后重建 k8s-server → platform-api → platform-web，
**k8s-server 先部署**：新 platform-api 发的注入参数（`podAnnotations` / `podLabels` / `serviceLabels` / `extraEnv` /
`logMountDir` / `targetMemory`）旧 k8s-server 不认，会被静默忽略 —— 发布成功但注解、标签、内存指标都不生效。

**入口灰度（§1.8）**：
1. 重放 **V2026_11_09_2**（五条权限行：四个写端点 `tenant:application:write`、指标 `tenant:application:read`；
   按已持有「创建发布」/「应用概览」的角色回填绑定）。不重放则 platform-api 被 `PermissionCrossCheckRunner` 拒启。
2. 重建 k8s-server → platform-api → platform-web，**k8s-server 先部署**：新 platform-api 发的 `skipKinds` / `onlyKinds`
   旧 k8s-server 不认，APPLY 会把路由一并下发（canary 没就绪就接流量），ROUTE 再下发一次；chart 的 canary values 旧版也没有。
3. 重新登录或等权限缓存 30 秒。

**内部灰度（§1.8「内部灰度」）**：无 SQL。重建 k8s-server → platform-api → platform-web，**k8s-server 先部署**：
新 platform-api 发的 `meshRoute` / `protocol` 旧 k8s-server 不认，会被静默忽略 —— 灰度「成功」但集群内调用不分流、TCP 服务端口名不变。
注意 TCP 服务（`probeType=tcp` 或网关 TCP）下次发布 Service 端口名会从 `http` 变成 `tcp`（滚动不影响连接，但按端口名匹配的外部配置要跟着改）。

### 3.1 画布遗留清理（V2026_11_02_14）

画布模型的遗留在代码和库里都已删除：

- 库：`platform_application.spec_json` / `current_revision` 两列，`platform_application_revision` /
  `platform_application_operation` 两张表，旧接口（`/application/update`、`/application/preview`、
  `/application/deploy`、`/application/operation/get`、`/application/revision/list`）的 5 条权限行及其角色绑定。
- 代码：对应实体与 mapper、`AppGuard` 的「旧模型应用只读」守卫、`ApplicationView` / 概览的 `legacy` 字段、
  前端的「旧模型」标签与提示条。
- **保留**：`platform_application_resource`（新发布也记账）、`platform_application_reference` 与
  `platform_middleware_instance.app_id` / `app_node_id`（新依赖也用）。

迁移对从未部署过的旧应用：状态改回 `DRAFT`、画布建的中间件实例解除归属（回到中间件商店）、画布引用行删除。
部署过的旧应用**不能**交给迁移处理，见 §3 第 1 步。

## 4. 旧模型应用

上线后不再有「旧模型应用」：部署过的已在升级前删除，没部署过的成了新模型的空应用，照常添加服务、发布。

## 5. 验收清单（未验证，部署后由使用者在真集群执行）

设计 §12 的验收在分支上**没有跑过**（未部署、实现期不连集群），上线后逐条执行：

- [ ] 重复发布不新建中间件实例，也不重跑初始化脚本。
- [ ] 只改环境变量会滚动 Pod，未勾选的服务不重启。
- [ ] 只改配置文件会滚动 Pod；容器内文件内容与路径正确、只读；同目录其他文件还在。
- [ ] 镜像拉取失败时，发布停在 HEALTH，旧 Pod 继续服务。
- [ ] 平台「发布自己」（或发布中滚动更新 platform-api）：旧 Pod 停机释放租约，新 Pod 几秒内接手并走到成功，不进「已中断」。
- [ ] Redis 不通时重启 platform-api，执行进入「已中断」，点继续能完成（顺带验证 `reopen` 的 SQL）。
- [ ] 下线：对象全部删除、PVC 还在；再发布一次（全选）后服务恢复，StatefulSet 挂回原来的卷。
- [ ] NodePort：留空时概览显示集群分配的端口且可访问；改成固定端口后发布，端口变成固定值；改回「不暴露」后 Service 变 ClusterIP。
- [ ] 已中断时直接发新版本，旧执行被取代（顺带验证 `supersedeInterrupted` 的 SQL）。
- [ ] 一个应用卡住时，其他应用仍能发布。
- [ ] 回滚后线上配置与旧快照一致。
- [ ] 口令不出现在快照、清单和接口响应中。
- [ ] 依赖初始化：MySQL / PostgreSQL / Redis / ES / Kafka 各成功一次、各故意失败一次，失败时状态为失败且日志可读。
- [ ] 跨命名空间绑定：应用 ns 里出现 `mwdep-*` 凭据副本，引用 `${依赖.password}` 的服务能启动、能连上实例；
      移除该依赖再发布后副本被清理；同名实例在另一个 ns 时不会互相覆盖。
- [ ] Harbor 选 tag、新版本提示、资源页跳转在真实 Harbor 上可用。
- [ ] 清理迁移：有部署过的旧应用时第 0 步报错且表结构不变；删掉后重放，两列两表与 5 条权限行消失，未部署的旧应用变成可发布的空应用。

网关端口暴露（§1.5）：

- [ ] 网关 HTTP：`curl http://节点IP:端口` 通；网关 TCP（如 MySQL / TLS 服务）`节点IP:端口` 通；概览显示「已生效」。
- [ ] HTTP 与 TCP 互相切换并发布：端口不变，路由类型（HTTPRoute / TCPRoute）与 listener 协议正确。
- [ ] 同一命名空间两个应用各有网关服务：ListenerSet 里两条 listener 都在；其中一个下线后另一个不受影响。
- [ ] 下线：本应用端口释放；命名空间里没有别的网关服务时 ListenerSet 删除、命名空间标签 `ingress.platform.coding/ports` 去掉。
- [ ] 下线后再发布：重新分到端口并可访问（端口可能变化）。
- [ ] Istio 组件里把端口池缩小到不含在用端口：保存被拦下，逐条列出「应用 / 服务 / 端口」；端口池与端口表重叠被拦下。
- [ ] 没装 Istio（或没有 ListenerSet CRD）的集群：网关选项置灰；发布含网关服务的旧快照在 PRECHECK 失败并提示 ListenerSet。

HPA / 可观测 / 服务网格（§1.7）：

- [ ] **真跑**：从「手动副本 3」切到 HPA（min 2）的那次发布，副本保持 3、不被重置为 1（交接 manager 生效；managedFields 里 `spec.replicas` 归 `platform-app-replicas-handover`）。StatefulSet 与 Deployment 各验一次。结论：（待回填）
- [ ] **真跑**：HPA 经 scale 子资源写的 replicas 在 managedFields 里是独立 manager，之后连续发布两次，HPA 扩出的副本不被打回。结论：（待回填）
- [ ] 没装 metrics-server / Prometheus Operator / OTel / Istio 的集群：对应开关置灰；直接调接口开启被拒；已开启的服务在组件卸载后仍能发布，预览有警告；关闭能发布。
- [ ] 应用设置开链路追踪 + 一个 Java 服务选语言、一个 Nginx 服务不选：只勾「应用设置」发布，两个服务都滚动，只有 Java 服务带探针注解；日志在 ES、链路在 Tempo，服务概览「历史日志」「链路」直接过滤到它。
- [ ] 应用设置 L4：全部服务纳管后网格拓扑能看到（只有 TCP 边）；命名空间另配了 waypoint 时，本应用服务仍不走它。
- [ ] 应用设置 L7 选 waypoint：有端口的服务出现 L7 边，后台进程不受影响；waypoint 删掉后发布预览报「应用设置：…不存在」。
- [ ] 回滚到接入前的版本，注解 / 标签随之消失。
- [ ] 卸载 OTel 后回滚到接入后的版本：确认框有警告，回滚照常完成。

入口灰度（§1.8，设计 §10）：

- [ ] 网关按权重分流，含 0 和 100 两个边界；HTTP 带 `x-canary: true` 的请求总进 canary（header 规则优先于权重）。
- [ ] 开始灰度时 canary 镜像不存在：执行停在 HEALTH 失败，canary 收不到任何流量（ROUTE 没执行）；之后可直接「回退」。
- [ ] 全量期间持续请求不中断，没有 5xx 尖峰。
- [ ] 指标 label 核对：`destination_service_name` / `destination_service_namespace` / `cluster_name` 在网关上报的序列上存在，
      两个版本能分开统计；HTTP 延迟用 `_milliseconds_bucket`。结论：（待回填）
- [ ] TCP 服务按权重分连接。
- [ ] 全量失败（如新配置起不来）后回退：服务恢复为灰度前配置，台账不残留待发布改动。

内部灰度（§1.8「内部灰度」，设计 §9）：

- [ ] waypoint 上挂 Service 的 HTTPRoute 按权重分流，含 0 / 100 边界；header 规则优先于权重；集群内另一个服务（如 web）调 stable Service 的结果符合权重。
- [ ] TCPRoute 挂 Service 在 waypoint 上是否支持。不支持则 TCP 服务前提里加一条（L7 下也不能内部灰度），HTTP 不受影响。结论：（待回填）
- [ ] `external=false` 时网关进来的请求 100% 进 stable；`external=true` 时按权重。
- [ ] 指标：`reporter`（`waypoint` / `source`）与 `destination_workload` 的实际取值，两组、两个版本能分开且不重复计数。结论：（待回填）
- [ ] 结束灰度后网格路由被删除，内部调用全部回到 stable，没有 5xx 尖峰。
- [ ] 非 L7 网格、网关暴露的服务灰度行为与入口灰度一致，且不渲染网格路由。
- [ ] TCP 服务 Service 端口名改为 `tcp` 后，网关 TCP 暴露、L4 / L7 网格下连接正常，ServiceMonitor 照常抓取。

## 6. 已知限制

- **灰度**：集群内调用分流依赖应用为 L7 网格（非 L7 时 canary 只接网关进来的流量）；内部 header 直达要调用方透传请求头；
  TCPRoute 挂 Service 在 waypoint 上未核对（见 §5）；有状态服务不能灰度；
  灰度版本不自动扩缩；指标 label 名与 reporter 取值未在真集群核对（见 §5）；灰度配置弹窗开放镜像、环境变量、配置文件、资源与命令（开始时另有副本数），
  探针、HPA、可观测、持久化等沿用 stable（接口本身接受完整 desired）。

- 对外域名、入口灰度不在本次范围（网关端口第二期）；网关 HTTP 不终止 TLS（HTTPS 服务用网关 TCP 透传）。
- 缩小端口池的拦截只看分配表里的 ACTIVE 行，不看集群上残留的 listener。
- 依赖新建不支持 Milvus，以及 ES Kibana/TLS/IK、Redis 分片副本等高级参数：先去中间件商店建，再绑定。
- ES 初始化的 `initPath` 可调该实例的任意 ES API（只作用于自己的实例，暂不收紧）；集群内 TLS 用 `curl -k`。
- 初始化排队时间计入超时；`MYSQL_PWD` 已被 MySQL 标记弃用，但客户端仍认。
- Harbor 镜像下，用户自填的 `imagePullSecrets` 列表里删不掉平台 Secret（保存时会再并入）；
  字符串形式的 `imagePullSecrets` 不合并；之前已自填列表的服务需要再保存一次才会并入平台 Secret。
- 发布列表的运行状态取台账而非实时探测。
- 只读成员除了在界面上，也能通过 API（`overview.desired`、`release/get`）看到环境变量的字面值；
  敏感值请用 Secret 引用而不是字面值。
- 一个 EXTERNAL 依赖被修改（或删除后以新地址重新添加）后回滚：会部署旧快照里的旧地址，而概览显示的是新地址；
  引用方随即显示为待发布，再发布一次即恢复为新地址。
- `${依赖.url}` 对非 HTTP 类型依赖拼出的是 `http://host:port`，只对说 HTTP 的依赖有意义
  （ES 按实例 TLS 开关取 http/https）。
- HEALTH 等待应用内**所有**服务：未勾选的服务若不健康，会挡住其他服务的发布，直到它修好；
  执行记录的消息（进行中与超时失败）会点名未就绪的对象。
- 回滚没有单独的预览接口：前端用 `release/get` 读两份快照对比。
- **快照 / 清单列的容量**：`platform_app_release` 的 `snapshot_json`、`manifest` 是 MEDIUMTEXT（16MiB）。
  配置文件每服务至多 512KiB，内容同时进快照和清单（ConfigMap），所以一次发布里配置文件大的服务
  一多（十几个服务都接近上限）就可能写不进去，发布在建记录时失败。真遇到再改 LONGTEXT 或给每次发布设总量上限。
- **`-env` / `-files` ConfigMap 在 APPLY 阶段就被覆写**，早于 HEALTH：发布失败停在 HEALTH 时旧 Pod 仍在跑
  旧配置（环境变量启动时读一次、subPath 不跟随更新），但这期间任何旧 Pod 重启（崩溃、驱逐、节点故障）
  都会读到**新**的环境变量和配置文件，配旧镜像。ConfigMap 名不带版本号，是有意的取舍（不留历史 ConfigMap）；
  发布失败后尽快修复再发，或回滚。
