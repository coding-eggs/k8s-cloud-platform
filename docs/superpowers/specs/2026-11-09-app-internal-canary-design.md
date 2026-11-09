# 应用服务内部灰度 设计

> 状态：设计稿，待审。在「应用入口灰度」（`2026-11-09-app-canary-release-design.md`，下称「入口灰度」）之上扩展，
> 那篇的模型、执行阶段、四个操作、锁、界面入口都沿用；本文只写变化。

## 1. 意图

灰度主要用在后端服务，而后端的流量大多来自集群内其他服务的调用（例如 web 调 api），不经过网关。
入口灰度只在网关路由上分流，内部调用全进 stable，对后端几乎没用。本次让**集群内调用也按权重分流**，
网关进来的流量可选择参与或不参与。

## 2. 目标 / 非目标 / 反目标

**目标**
- 灰度中的服务：集群内其他服务调用它时，按权重分到 stable / canary；HTTP 还可按请求头直达 canary。
- 对外暴露（网关 HTTP / TCP）的服务，网关进来的流量可开可关地参与灰度，用**同一个权重**。
- 不对外暴露的服务也能灰度。
- 指标按来源（内部调用 / 网关进来）分开看，两边不重复计数。

**非目标**
- 不做「内部、外部各一个权重」。
- 不做没开网格时的替代方案（如按副本比例让 stable Service 同时选中 canary Pod）。
- 不做按调用方（哪个服务调过来的）分流；不做自动调权重。

**反目标**
- 因为加内部分流，导致**没有灰度的服务**的调用路径发生变化（多走一跳、换 Service、重启）。
- 关掉「外部参与」后网关流量仍有一部分进了 canary。

## 3. 已定的规则（用户决定）

1. **必须开网格才能灰度**：应用级网格设置为 L7（有 waypoint）的服务才能开启灰度。网格为跟随 / 不启用 / L4 都不行。
   这同时收紧了入口灰度：之前「只要网关暴露就能灰度」，现在网关暴露但没开 L7 网格的服务也不能灰度。
2. **一个权重**：同一个权重同时作用于内部调用和（参与时的）网关流量。
3. **「外部流量参与灰度」开关**：只在服务有网关暴露时出现，默认开。关掉 = 网关进来的请求固定进 stable，权重只作用于内部调用。
4. header 直达对内部调用同样生效，但需要调用链把这个请求头往下传；界面写明。

## 4. 模型

### 4.1 快照里的灰度块

`services.<key>.canary` 增加一个字段：

```
canary = { desired, replicas, weight, header{name,value}|null, external: true|false }
```

- `external` 只对有网关暴露的服务有意义；没有网关暴露时存 `false`，忽略。开始灰度不传时：有网关暴露 = `true`。
- 仍由 `AppCanary` 唯一读写；`AppPending` 照旧不把灰度块算作待发布改动。
- 开发环境，无存量数据兼容问题；读到没有 `external` 的旧块按 `true` 处理（与入口灰度行为一致）。

### 4.2 对象

在入口灰度的 canary 节点（`<slug>-<key>-canary`：Deployment、Service）之外，灰度中再多一个**网格路由**：

| 对象 | 名字 | 说明 |
|---|---|---|
| HTTPRoute 或 TCPRoute | `<slug>-<key>-mesh` | `parentRefs` = stable 的 Service（`group: ""`、`kind: Service`、`name: <slug>-<key>`、`port`）；规则与网关路由同形：可选的 header 规则在前，然后 stable `100-weight` / canary `weight` 两个后端 |

- 只在灰度中渲染（没有灰度块的服务不渲染这个对象），所以**没灰度的服务不受任何影响**（反目标 1）。
  结束灰度（全量 / 回退）后它不在清单里，PRUNE 按台账删掉。
- 属于 stable 节点（与网关路由同一个渲染单元），归入 `ROUTE_KINDS`，在 ROUTE 阶段下发。
- 生效机制：stable Service 已带 `istio.io/use-waypoint=<waypoint>`（L7 网格），集群内发往它的请求经 waypoint；
  waypoint 按挂在这个 Service 上的路由（Gateway API 的 GAMMA 用法）分到两个后端。canary Service 也带同样的标签，不额外处理。
- 网关进来的流量默认不经 waypoint（不打 `istio.io/ingress-use-waypoint`），所以网关那一路仍由网关路由决定，
  「外部参与」开关才能独立生效（见 4.3）。

### 4.3 网关路由

| 情况 | 网关路由 |
|---|---|
| 无网关暴露 | 没有网关路由（不变） |
| 有网关暴露、`external=true` | 与入口灰度相同：header 规则 + 两个加权后端 |
| 有网关暴露、`external=false` | 与没灰度时相同：单一后端 stable（无 header 规则、无权重） |

### 4.4 协议判定与 Service 端口名

内部灰度要知道服务是 HTTP 还是 TCP（决定路由 kind、header 能否用、看哪组指标）。没有网关暴露的服务以前不需要这个信息。

- 判定：`expose = gateway-tcp` → TCP；否则 `probeType = tcp` → TCP；否则 HTTP。前后端同一个函数口径。
- **顺带修一个现存问题**：chart 的 Service 端口名固定是 `http`，Istio 会据此把它当 HTTP 解析。TCP 服务一旦进 L7 网格，
  waypoint 按 HTTP 解析 TCP 流量会出错。改为：TCP 服务的 Service 端口名为 `tcp`（`targetPort` 仍引用容器端口名不变）。
  这会让现有 TCP 服务的 Service 在下次发布时变一次端口名（不重启 Pod）；开发环境，可接受。

## 5. 前提与锁

### 5.1 开始灰度的前提（替换入口灰度 §6.2 的「网关暴露」一条）

- 服务已上线、有端口、无状态（`persistenceEnabled` 不为 true）、应用没有进行中的执行（不变）。
- **线上快照**的应用设置里网格为 L7、有 waypoint（新）。不再要求网关暴露。
- 灰度版本端口、暴露方式、服务类型与 stable 一致（不变）；**协议**（4.4）也须一致（新）。
- 权重 0-100；header 只对 HTTP（不变）。`external` 只在有网关暴露时可为 `true`。

### 5.2 新增的锁

- 有任何服务在灰度时，应用设置里的**网格**不能改（模式、waypoint 都不能改；追踪等其他设置照常可改）。
  理由：关网格或换 waypoint 后网格路由失效，内部流量会静默全进 stable 或行为不可预期。
- 其余锁沿用入口灰度（灰度中服务不能改配置 / 移除 / 在普通发布里勾选；回滚、下线须先结束灰度）。

## 6. 指标

面板分两组，按来源区分：**内部调用**（总是有）与**网关进来**（有网关暴露时才显示；`external=false` 时 canary 列恒为空，照样显示 stable）。

- 内部调用：`reporter="waypoint"`。网关进来：`reporter="source"` 且 `source_workload` 为平台入口网关（具体取值实现时按真集群定）。
  本集群 reporter 只有 `source` / `waypoint`（入口灰度实现时已确认）。
- 两个版本改用 **`destination_workload`** 区分（`<slug>-<key>` / `<slug>-<key>-canary`），不再用 `destination_service_name`：
  waypoint 按路由选了后端后，上报的目标 Service 可能是 stable Service 本身，按 Service 名分不开两个版本。
  这条**必须在真集群核对**（§9 第 4 条）；若 `destination_workload` 也分不开，回退到按 `destination_service_name`。
- 其余（HTTP：请求/秒、5xx 错误率、P50 / P99；TCP：新建连接/秒、收发字节/秒；时间窗；错误率标橙规则；「暂无数据」）不变，每组各算一遍。

## 7. 接口

- `POST /application/canary/start`、`/update`：请求体加 `external`（可选布尔）。start 不传按 4.1 的默认；update 不传 = 不改。
- `POST /application/canary/metrics`：返回结构改为 `{internal: {stable, canary}, external: {stable, canary} | null}`。
- 不加新端点，不加权限行，无 SQL。审计沿用。

## 8. 界面

- **服务列表「开启灰度」**：置灰原因改为「应用未开启 L7 网格（在应用设置里开启并选择 waypoint）」等；不再提示「只有网关暴露的服务能灰度」。
- **开始灰度对话框**：有网关暴露时多一个开关「外部流量参与灰度」（默认开）。header 直达的说明加一句「内部调用需调用方把这个请求头往下传」。
- **管理灰度抽屉**：
  - 流量区：一个权重滑块（不变）；有网关暴露时显示「外部流量参与灰度」开关，与权重、header 一起「应用」。
  - 顶部提示从「集群内其他服务调用的仍是 stable」改为说明当前分流范围（「内部调用 + 网关进来」或「仅内部调用」）。
  - 指标对比分「内部调用」「网关进来」两组。
- **应用设置**：灰度中时网格选项禁用，提示「有服务在灰度中，先全量或回退」。
- 状态标签「灰度中 N%」不变。

## 9. 部署与验证（部署后在真集群执行）

部署：先 k8s-server（chart：网格路由模板、TCP 端口名），再 platform-api，再 platform-web。无 SQL。

需要真跑确认：
1. **waypoint 上挂在 Service 的 HTTPRoute 按权重分流**，含 0 / 100 两个边界；header 规则优先于权重；
   集群内另一个服务（如 web）调 stable Service 的结果符合权重。
2. **TCPRoute 挂 Service 在 waypoint 上是否支持**。不支持则：TCP 服务不能灰度（前提里加一条，提示原因），HTTP 不受影响。
3. `external=false` 时网关进来的请求 100% 进 stable；`external=true` 时按权重。
4. 指标：`reporter` / `source_workload` / `destination_workload` 的实际取值，两组、两个版本能分开且不重复计数。结论回填本文。
5. 结束灰度后网格路由被删除，内部调用全部回到 stable，没有 5xx 尖峰。
6. TCP 服务 Service 端口名改为 `tcp` 后，网关 TCP 暴露、L4 / L7 网格下连接正常。

## 10. 留给实现者决定的

- 网格路由放在现有 `route.yaml` 里多一个文档，还是新模板文件；values 结构自定（`route.mesh` 或 `canary.mesh` 之类）。
- 「网关进来」的 `source_workload` 匹配方式（精确名还是前缀），以 §9 第 4 条的实测为准。
- 应用设置网格锁的检查位置（设置更新服务里查线上快照与进行中执行，口径同入口灰度的配置锁）。
