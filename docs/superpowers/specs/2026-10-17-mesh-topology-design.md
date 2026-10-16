# 服务网格拓扑可视化（S2.4）设计

> 数据前提已于 2026-10-17 在 coding-cluster 实测打通（怎么接的：`docs/development/cluster-prerequisites.md` §1.7）。

## 1. 要做什么

在「服务网格」菜单下加一页：**把集群里真实发生的调用关系画成一张有向图**。

- **节点** = 工作负载（指标里的 `source_workload` / `destination_workload`）
- **边** = 「谁调谁」
- **边上**：粗细 = QPS，颜色 = 错误率，点开看 P95

**本次只做 S2.4.1（数据）+ S2.4.2（图）**。S2.4.4 已实测回答（见 §7）；S2.4.3（链路追踪）本次不做，理由见 §6。

## 2. 数据从哪来（**都是实测的，不是照文档抄的**）

主指标 `istio_requests_total`，一条序列 = 「源 → 目标 + 响应码」的累计计数。

| 要什么 | 查询 |
|---|---|
| 边 + QPS | `sum by (source_workload, destination_workload) (rate(istio_requests_total{…}[$w]))` |
| 错误率 | 同上加 `response_code=~"5.."`，两条相除 |
| P95 | `histogram_quantile(0.95, sum by (le, source_workload, destination_workload) (rate(istio_request_duration_milliseconds_bucket{…}[$w])))` |

⚠️ 四条**实测出来的**约束，全部写进代码注释：

1. 延迟桶名是 `istio_request_duration_milliseconds_bucket` —— 本集群里**没有** `_seconds` 版，也没有不带后缀的裸名。
2. `reporter` 当前只有 `source`（网关记的）与 `waypoint`（waypoint 记的）两种取值。
   **别照抄网上那些带 `reporter="destination"` 的查询** —— 在这个集群里查出来是空的。
   将来上了 sidecar，同一请求会被源侧与目的侧各记一次，**那时才需要它去重**（Kiali 的做法）。
3. 命名空间过滤用 `source_workload_namespace` / `destination_workload_namespace`。
4. **「外部来客」没有真实源标识**：从集群外进来的流量，`source_workload` 就是网关自己
   （`external-gateway-istio`）。真实来源只能靠 XFF / 网关日志，指标里没有。

## 3. 后端

**DTO**（放 `platform-api/.../metrics/dto/`，与既有 metrics DTO 同处）：

```java
record MeshTopologyResponse(List<MeshNode> nodes, List<MeshEdge> edges,
                           int windowSeconds, boolean l7Available)
record MeshNode(String id, String namespace, String workload, boolean gateway)
record MeshEdge(String source, String target, double rps, double errorRate,
                Double p95Ms, boolean hasL7)
```

⚠️ **`MeshNode.id` 的约定：`namespace + "/" + workload`**（如 `default/movie-deployment`）。
命名空间必须进 id —— 同名 workload 在多个命名空间里很常见，只用 workload 名会**把两条边粘成一个节点**。
`MeshEdge.source/target` 存的就是这个 id。

**查询**：`MetricQuery` 枚举加三个常量（与现有"一个枚举常量 = 一条 PromQL 模板 + 一个 label 类型"的风格一致）。

**服务**：`MetricsService.meshTopology(clusterId, namespace, windowSeconds)`
- 三条查询**并发**发出（现有的 `queryCluster` 是一条条打的，拓扑页每 30 秒一刷，串行会慢）
- 边的 QPS 与错误率按 `(source, target)` 合并；延迟独立聚合，缺就是 `p95Ms = null`
- 节点集合 = 出现在边两端的 workload（v1 **不补**"在网格里但没流量"的节点，见 §6）
- `l7Available`：主查询**一条序列都没有**时为 `false` → 前端据此显示"没接采集"，而不是画空图
- `gateway` 标记：`source_workload` 属于 `istio-system` 的（外部网关）标出来，前端画得跟业务节点不一样

**端点**：`POST /mesh/topology`，挂在既有的 `MeshController` 上。

**权限**：加一行 `perm_mesh_topology`（`API` / `/mesh/topology` / `POST`），**复用 `tenant:gateway:list`**
—— 与既有 metrics 端点复用 `tenant:workload:get` 同一套路（动词级 code，不为每个只读查询再造码）。
迁移文件放在 `V2026_10_17_x`，**并且显式绑角色**（本仓规矩：新权限行不按 code 继承）。

**缓存**：不做。30 秒一刷、每次三条查询，量很小；真要加也走现有的 `RedisJsonCache`。

## 4. 前端

新路由 `/mesh/topology`，菜单挂在「服务网格」组下（与 Gateway / GatewayClass 同组）。
菜单项的显隐**用同一个码** `tenant:gateway:list` —— 与后端那一行权限是同一个判据，
不另造页面码（前端只管显隐，权威判定在后端）。

**画布：ECharts 的 `graph` 系列（力导向布局）。**（2026-10-17 定；用户不参与技术选型，由我拍板。）

理由：`echarts` 已经是依赖，力导向布局、hover、tooltip、缩放拖拽全都现成，代码量最小。
**不用 VueFlow + dagre 的原因**：要新增 dagre 依赖，而且网格流量常是双向的，
分层布局（DAG 语义）不一定比力导向好读 —— 流水线画布那边用 VueFlow 是因为**用户要拖拽摆位**
（编辑态），这里是纯查看态，需求不同，不必强行统一。

- **边**：`lineStyle.width ∝ log(rps)`；颜色按错误率分档（0 → 绿，>0 → 黄，>5% → 红）
- **节点**：标签 = workload 名；按命名空间分组着色；网关节点换个图标/颜色（它是"外部入口"）
- **点击节点或边** → 右侧抽屉显示三个数 + 该边对应的 PromQL（用户可以拿去 Grafana 自己复核）
- **顶部**：命名空间选择（默认取顶栏的上下文）、时间窗（5m / 1h / 24h）、手动刷新 + 30 秒自动

⚠️ **空态必须说人话**：没有数据时显示「这个集群没在采集网格指标 —— 怎么接见集群前提 §1.7」，
而不是画一张空图（那会让人以为"集群里没有流量"）。

⚠️ **页面上要写明覆盖范围的限制**（就是"看不见什么"）：

> 图里只有：走**外部网关**的南北向，和**进了网格且挂了 waypoint** 的东西向。
> 没被纳入网格的负载互相调用、以及网关之外的集群外来源，**一条都不在这里** —— 这是 Istio 的机制决定的，
> 不是平台少画了。

## 5. 验收

1. 在 coding-cluster 上打开，应看到那 5 条边（minio / movie-web / thanos-query / prometheus / movie-service）。
2. **摘掉** `movie-service` 的 `istio.io/use-waypoint` → 那条边应变成"无 L7"状态（`hasL7=false`，
   三个数变灰/显示"—"）—— 这条同时验证了 §2 第 1 条限制是真的。
3. 在一个**没接采集**的集群上打开 → 明确提示，不是空图。
4. 从图上应能一眼看出：`movie-web → movie-service` 的错误率是 **100%**（那 30 次请求全是 404）。

## 6. 本次不做（附理由）

- **S2.4.3 链路追踪**：**本次不做，但用户 2026-10-17 已明确「后期会做」—— 不是砍掉。**
  现在做不了的原因：集群里**没有任何追踪后端** —— jaeger / tempo / zipkin 都没有；
  otel collector 虽然在跑（`default/collector`），但**没配 traces 管道**。
  和 S2.4 本身当初"没米下锅"是同一类问题：要做得先部署一个后端，**并且**让业务注入/透传 trace header。
  真要开工时，起点是：① 选后端（Tempo 比 Jaeger 更贴这套 thanos/otel 的栈）；
  ② 部署 + 接 otel collector 的 traces exporter；③ 让被测应用透传 `traceparent`
  （Istio 只负责透传，不负责生成 —— 应用不发 header 就没有 span）。
- **时间轴回放 / 历史对比**：`query_range` 代价高不少，v1 没这个需求，YAGNI。
- **补全"在网格里但没流量"的节点**：指标里没有它们，要另外查 pod/service 再合并，v1 不做；
  这也正是 §4 那条"覆盖范围"说明要写在页面上的原因。
- **网格配置拓扑**（Gateway → Route → Service 的引用关系）：那是**另一件事**，
  而且它更该跟 S4.2 的「意图化展示」一起做（用户裁定 S4.2 待定，到时优先它）。

## 7. 已实测回答的台账项

**S2.4.4（ambient 下指标是否齐备）**：✅ 已答，三句话：

- **ztunnel 不吐流量指标** —— 它的 `/metrics` 只有 xDS 与进程内部指标（12 条），没有 `istio_requests_total`。
- **L7 只有 Envoy 类代理产出** —— 外部网关（南北向）与 waypoint（东西向）。
- **waypoint 没接过流量时连统计命名空间都不存在** —— 实测它的 `/stats/prometheus` 里
  `envoy_http_*` 一条都没有，`istio_requests_total` 也没有。

**结论**：ambient 下要 L7，**目标服务必须挂 waypoint**；只靠 ztunnel 只有 L4（`istio_tcp_*`，没有请求数与响应码）。
所以拓扑图里「这条边有没有 L7」本身就是要显示的状态 —— 不能默认每条边都算得出错误率。
