# 应用入口灰度 设计

> 前置：应用发布（`docs/development/application-release.md`）、网关端口暴露第一期
> （`docs/superpowers/specs/2026-11-08-app-istio-exposure-design.md`，本文是其 §8 的展开）。
> 状态：**已实现（未部署）**，提交范围 `16befb3..HEAD`（main，2026-11-09）。域名方式（原 §7）另起一篇，不在本文。
>
> 实现与设计的出入：
> - 指标端点是 `POST /application/canary/metrics`（请求体 `{appId, serviceKey, window}`），不是 GET —— 与 `/application` 其他端点一致。
> - 指标不加 reporter 过滤（§7 写的「限定为网关一侧」）：本集群 reporter 只有 `source` / `waypoint`，按目标 Service 过滤已足够。
> - ROUTE 阶段只确认路由写入成功，不读 `status.parents`（§11 的开放项）。
> - 灰度配置弹窗只开放镜像、环境变量、副本数（开始时另有权重与 header）；其余字段沿用 stable。接口接受完整 desired。
> - 额外校验（设计未写）：灰度副本 1-100；header 名 `^[A-Za-z0-9][A-Za-z0-9-]{0,63}$`、值至多 256 字符。

## 1. 意图

服务上新版本时，不直接替换线上版本，而是在旧版本（stable）旁边多跑一份新版本（canary），在网关上把一部分流量分给它。
租户看着两个版本的指标，自己决定是继续放量、全量，还是回退。

- **平台只摆数据、不下结论**：不设合格线，不自动推进，不自动回退。放不放量由租户判断。
- **访问地址不变**：仍是「节点 IP:网关端口」，分流发生在应用自己的 HTTPRoute / TCPRoute 上。

## 2. 目标 / 非目标 / 反目标

**目标**
- 按权重分流（0-100%），HTTP 服务还可以按 header 直达 canary，两种可以同时用。
- canary 默认复制 stable 的配置、只换镜像，其余字段也允许改。
- 开始、调整、全量、回退，每一步都是一条发布记录，有过程日志，失败可「继续」。
- 灰度面板并排展示 stable / canary 的请求量、错误率、延迟和 Pod 状态。

**非目标（第一版不做）**
- 自动推进、按指标自动回退。
- canary 挂 HPA（副本数固定）。
- 服务之间调用的灰度（东西向流量）。需要 L7 waypoint，属于原设计第三期。
- 非网关暴露的服务（NodePort / 不暴露）灰度。
- 一个服务同时跑多个 canary。

**反目标（出现了就算做错）**
- canary 还没就绪就开始接流量。
- 全量过程中出现两个版本都缩容、容量掉底的窗口。
- 灰度期间 stable 被别的发布顺带改掉。

## 3. 已定的规则

| 规则 | 来源 |
|---|---|
| 两种分流都要，可以同时用 | 用户 |
| canary 默认复制 stable，只换镜像；env、配置文件、资源规格等也允许改 | 用户 |
| 权重手动调 | 用户 |
| 灰度中的服务锁住：stable 不能改也不能发布，只能调权重、改 header 规则、改 canary 配置、全量或回退 | 用户 |
| 同一应用的其他服务照常改、照常发布；应用级回滚和下线要先结束灰度 | 用户 |
| 平台只提供指标，不判断好坏 | 用户 |
| canary 的应用设置（链路追踪、服务网格）与 stable 一致 | 原设计 §8 |
| canary 副本数默认 1，初始权重默认 10%，header 默认 `x-canary: true` | 我定 |
| canary 不挂 HPA | 我定 |
| 权限沿用应用的发布权限，不新加码 | 我定 |
| canary 不能改端口、暴露方式、服务类型（服务 / 后台进程） | 我定：路由的端口和协议由 stable 决定 |
| 只有无状态服务（Deployment）能灰度；有状态服务（StatefulSet）不支持 | 我定：canary 的数据卷归属说不清，第一版不碰 |
| 回退 = 回到灰度开始前（全量失败时也是），不只是删 canary | 用户 |
| 探针补 TCP 类型，随灰度一起做 | 用户 |

## 4. 模型

### 4.1 灰度状态存在哪

**存在发布快照里**，不新建表：快照的 `services.<key>` 下多一个 `canary` 块。

```json
"services": {
  "api": {
    "...": "stable 的 desired（不变）",
    "canary": {
      "desired": { "...": "canary 的完整 desired（开始时从 stable 复制，替换镜像）" },
      "replicas": 1,
      "weight": 10,
      "header": { "name": "x-canary", "value": "true" }
    }
  }
}
```

- `header` 为 null 表示不开 header 直达；TCP 服务恒为 null。
- 线上快照（线上发布号指向的那条发布）里有 `canary` 块 = 该服务正在灰度。不需要单独的状态字段。
- 服务台账 `platform_app_service.desired` 始终是 stable 的期望配置。灰度期间这一行被锁（§6.1），所以不会和快照分叉。
- **待发布判定**（`AppPending.compute`）比较线上快照与台账时**忽略 `canary` 块**，否则灰度中的服务会一直显示「有改动」。

### 4.2 对象

canary 用同一个 workload chart 再渲染一次，名称加 `-canary` 后缀：

| 对象 | stable | canary |
|---|---|---|
| Deployment / StatefulSet | `<slug>-<key>` | `<slug>-<key>-canary`（标签选择器随名字分开） |
| Service | `<slug>-<key>` | `<slug>-<key>-canary` |
| HTTPRoute / TCPRoute | `<slug>-<key>`（唯一一个，后端写两个） | 不生成 |
| HPA | 照旧 | 不生成 |
| ConfigMap（env / 配置文件） | 照旧 | 各自一份，名称加后缀 |

- 应用设置（追踪、网格标签）照常注入 canary，和 stable 一样。
- 集群内其他服务调用的仍是 stable 的 Service，**canary 只接网关进来的流量**。概览和灰度面板上要写明这一点。

### 4.3 路由

HTTP（weight = w，header 开启时）：

```yaml
rules:
  - matches:
      - headers: [{ name: x-canary, value: "true" }]
    backendRefs:
      - { name: <slug>-<key>-canary, port: <port> }
  - backendRefs:
      - { name: <slug>-<key>,        port: <port>, weight: <100-w> }
      - { name: <slug>-<key>-canary, port: <port>, weight: <w> }
```

- 没开 header 就只有第二条规则。
- TCP：TCPRoute 只有第二条（按权重分连接）。
- w = 0 时 canary 仍写进 backendRefs（权重 0），header 规则照样生效，方便测试同学先单独验证。

## 5. 操作与发布类型

每个操作都是一次普通发布，走同一个执行器：快照 = 线上快照 + 这次的灰度改动，**只动这一个服务的 `canary` 块**（全量时还改 stable），其余服务原样重新渲染。

| 操作 | 发布 type | 快照变化 |
|---|---|---|
| 开始灰度 | `CANARY_START` | 加 `canary` 块 |
| 调整（权重 / header / canary 配置 / 副本数） | `CANARY_UPDATE` | 改 `canary` 块 |
| 全量 | `CANARY_PROMOTE` | stable desired := canary desired，去掉 `canary` 块 |
| 回退 | `CANARY_ABORT` | 以线上快照为基准去掉 `canary` 块；stable 台账还原为线上快照里的版本（§5.2） |

发布历史里这四类显示为「灰度开始 / 灰度调整 / 灰度全量 / 灰度回退」，带服务名。

### 5.1 执行阶段：路由放到健康之后

现有阶段：`PRECHECK → RENDER → CONFLICT → APPLY → HEALTH → PRUNE → DONE`。
改成 `… → APPLY → HEALTH → ROUTE → PRUNE → DONE`：

- **APPLY** 下发除路由（HTTPRoute / TCPRoute）以外的全部对象。
- **HEALTH** 只看非路由对象。
- **ROUTE** 下发路由，确认路由对象存在。
- **PRUNE** 不变。

这样四种操作都能满足反目标：

| 操作 | 过程 |
|---|---|
| 开始 | 建 canary → 等 canary 就绪 → 才把权重 / header 写进路由。canary 起不来（如拉镜像失败），执行停在 HEALTH 失败，路由没动，**零流量打到坏版本** |
| 调整权重 | 工作负载没变化，HEALTH 立刻通过 → ROUTE 换权重 |
| 全量 | stable 滚动到新镜像（canary 照常接自己那份流量）→ stable 就绪 → 路由切回 100% stable → PRUNE 删掉 canary |
| 回退 | 没有工作负载要就绪 → ROUTE 切回 100% stable → PRUNE 删 canary |

- 普通发布也走这个顺序：服务改成网关暴露时，路由要等 Pod 就绪后才挂上。这是顺带的改进，不改语义。
- 全量时同时写回服务台账：stable 那一行 `desired := canary desired`、version + 1。在创建发布的事务里写，发布失败也不回退台账（与普通发布一致：台账是期望，线上是快照）。

### 5.2 失败与续跑

- 开始灰度失败（FAILED 不算活动执行）：可以「继续」重跑，也可以直接回退。回退快照里没有 canary，PRUNE 会把建了一半的 canary 删掉。
- 调整失败：可以继续，或回退。
- **全量失败**（stable 滚动后一直不就绪，比如配额不够、新节点拉不到镜像、全量流量下探针超时）：执行停在 HEALTH，
  路由保持灰度权重，canary 照常接流量。可以「继续」（解决问题后接着等 stable 就绪），也可以**回退**。
- **回退 = 回到灰度开始前的状态**（用户确认）。线上发布号只在成功时才切换，所以全量失败时线上快照仍是
  「stable 旧配置 + canary 块」。回退以它为基准：
  1. 服务台账里 stable 的 desired 还原为线上快照里的 stable desired（全量创建时已被改写成 canary 的配置），version + 1；
  2. 发布去掉 canary 块的快照：stable 滚回旧版本，路由切回 100% stable，PRUNE 删掉 canary。
  不需要再做一次回滚。任何时候的回退都按这个规则走，没有全量失败时第 1 步是空操作。

## 6. 约束（服务端校验）

### 6.1 锁

线上快照里服务 X 带 `canary` 块时：

- `/application/service/update`、移除服务：拒绝，提示「灰度中，先全量或回退」。
- 普通发布勾选 X：拒绝。勾选别的服务照常，X 按线上快照原样重新渲染（包括 canary 块，所以 canary 不受影响）。
- 回滚、下线：只要应用里有任何服务在灰度就拒绝。
- 回滚目标快照里带 `canary` 块的（回滚到灰度期间的某个版本）：**剥掉 canary 块**，只还原 stable。

### 6.2 开始灰度的前提

- 服务已上线，暴露方式是网关 HTTP 或网关 TCP。
- 服务是无状态的（Deployment），不是 StatefulSet。
- 该服务当前不在灰度中，应用没有活动执行。
- canary desired 走与普通保存相同的 normalize 和校验；端口、`expose`、服务类型必须与 stable 一致。
- 镜像与 stable 相同时不拦，只在弹窗里提示「镜像没变」。
- `weight` 范围 0-100；`header` 只有 HTTP 能填。

### 6.3 探针类型（随灰度一起做）

现有的「健康检查」只有 HTTP GET（readinessProbe + livenessProbe 打服务端口的 `probePath`），默认开启。
纯 TCP 服务（如网关 TCP 暴露的非 HTTP 服务）用 HTTP 探针会一直失败。补一个探针类型：

- desired 加 `probeType`：`http`（默认，沿用 `probePath`）/ `tcp`（`tcpSocket` 探服务端口，不用路径）。
- 没写 `probeType` = `http`，旧数据不变。暴露方式选「网关 TCP」时，前端默认把类型切成 `tcp`（可改回）。
- chart 按类型渲染 `httpGet` 或 `tcpSocket`，initialDelay 与 failureThreshold 不变。
- 灰度依赖它：探针开着时「就绪」= 探针通过；探针关了时就绪只代表进程已启动，开始灰度的弹窗里提示这一点。

## 7. 指标（灰度面板）

数据来自 Istio 入口网关的指标，按目标 Service 区分两个版本。业务不用改代码，也不依赖服务网格。

| 指标 | HTTP | TCP |
|---|---|---|
| 请求量 | `sum by (destination_service_name)(rate(istio_requests_total{...}[w]))` | 新建连接数 `istio_tcp_connections_opened_total` |
| 错误率 | `response_code=~"5.."` 占比 | 不提供（L4 没有响应码）；显示收发字节 |
| 延迟 | `istio_request_duration_milliseconds_bucket` 的 P50 / P99 | 不提供 |

- 过滤条件：`cluster_name`、`destination_service_namespace=<ns>`、`destination_service_name=~"<stable>|<canary>"`，并限定 reporter 为网关一侧。具体 label 名和 reporter 取值**部署后必须在真集群核对**，见 §10。
- 时间窗默认 15 分钟，可选 5m / 15m / 1h；面板 30 秒刷新一次。
- 只做一个提示：canary 错误率高于 stable 且差值超过 1 个百分点时，canary 的数字标橙。不告警，不拦操作。
- Pod：两个版本各自的就绪副本数、重启次数，以及跳到 Pod 日志的入口（复用服务页现有 Pod 列表）。
- 查不到数据（Prometheus 没装、或还没有流量）时显示「暂无数据」，不报错。

## 8. 接口

都挂在 platform-api 的 `/application` 下。

| 端点 | 说明 |
|---|---|
| `POST /application/canary/start` | `{appId, serviceKey, desired, replicas, weight, header}` → 返回执行 |
| `POST /application/canary/update` | 同上，字段可选；只改给出的部分 |
| `POST /application/canary/promote` | `{appId, serviceKey, note}` |
| `POST /application/canary/abort` | `{appId, serviceKey, note}` |
| `GET /application/canary/metrics` | `appId, serviceKey, window` → 两个版本的指标 |

- **权限**：新端点要有权限行（不然 `PermissionCrossCheckRunner` 拒启）。四个写端点挂现有的应用发布码，metrics 挂应用查看码，用新 SQL `V2026_11_09_2` 写行。新权限行不会按 code 自动继承，所以要按「已持有对应码的角色」回填绑定，做法同 `V2026_11_08_2`。
- 审计：四个写端点进审计日志（同其他应用写接口）。
- k8s-server 不加业务端点：渲染、下发、状态都复用现有 `/application/**`。chart 只加 canary 相关的 values。

## 9. 界面

- **应用详情页服务列表**：灰度中的服务在「状态」旁加标签「灰度中 10%」；「对外端口」列不变。
- **服务页**：新增「灰度」页签。
  - 未灰度：说明 + 「开始灰度」按钮。非网关暴露的服务按钮置灰，提示原因。
  - 开始弹窗：canary 配置表单，默认复制 stable，镜像 tag 置顶；副本数、初始权重、header 开关（HTTP 才有）。
  - 灰度中：
    - 当前权重和 header 规则，权重用滑块加 0 / 10 / 25 / 50 / 100 快捷档；
    - 指标对比表（§7）、两个版本的 Pod；
    - 「修改灰度版本配置」「全量」「回退」按钮，全量和回退要二次确认；
    - 执行中的进度复用现有执行面板。
  - 服务页其他页签的保存按钮在灰度中禁用，提示原因。
- **发布对话框**：灰度中的服务不能勾选，说明原因。

## 10. 部署与验证（部署后在真集群执行）

- 先部署 k8s-server（chart 改动），再部署 platform-api；先重放 `V2026_11_09_2`，重新登录或等权限缓存 30 秒。
- 需要真跑确认的：
  1. 网关按权重分流，含 0 和 100 两个边界；header 规则优先于权重规则。
  2. 开始灰度时 canary 镜像不存在：执行停在 HEALTH 失败，canary 收不到任何流量。
  3. 全量期间持续请求不中断，没有 5xx 尖峰。
  4. §7 指标的 label 名与 reporter 取值，两个版本能分开统计。
  5. TCP 服务按权重分连接。

## 11. 留给实现者决定的

- chart 是给 canary 单独渲染一次（名称加后缀），还是在同一次渲染里多出一组对象。前提是对象名和选择器满足 §4.2。
- ROUTE 阶段对路由「就绪」的判定：至少确认路由对象存在。是否读 `status.parents` 的 Accepted 由实现定，但不能因为 Istio 顶层状态不准（ListenerSet 的 AddressNotUsable 问题）而卡住。
- 指标查询放在现有 `MetricQuery` 里新增条目，还是在灰度服务里单独拼。
