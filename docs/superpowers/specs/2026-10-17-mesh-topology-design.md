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
| 边 + QPS（L7） | `sum by (source_workload, destination_workload, …ns) (rate(istio_requests_total{cluster_name="…",…}[$w]))` |
| 错误率（L7） | 同上加 `response_code=~"5.."`，两条相除（分母是**总量**，不是 rps+errors） |
| P95（L7） | `histogram_quantile(0.95, sum by (le, …, 两端 ns) (rate(istio_request_duration_milliseconds_bucket{cluster_name="…",…}[$w])))` |
| 边 + 字节（L4） | `sum by (…同上) (rate(istio_tcp_sent_bytes_total{cluster_name="…",…}[$w]))` |
| 哨兵 | `count(istio_build{cluster_name="…"})` —— 判「在不在采集」 |

**两层怎么合并**：同一对端点同时有 L4 与 L7 时**只出一条 L7 边**，字节数附在它上面；
只有 L4 的边标 `level=L4`（没有错误率、没有请求延迟）。
⚠️ L4 用**字节/秒**而不是连接数：`istio_tcp_connections_opened_total` 对长连接（mysql/redis）
几乎恒为 0，实测没有区分度（mysql 7.9KB/s、es 1.5KB/s 才有）。
⚠️ `destination_workload="unknown"`（Istio 解析不到目标）的行**丢弃**，别把它画成一个叫 unknown 的节点。

⚠️ 五条**实测出来的**约束，全部写进代码注释：

1. 延迟桶名是 `istio_request_duration_milliseconds_bucket` —— 本集群里**没有** `_seconds` 版，也没有不带后缀的裸名。
   窗口内没请求时 `histogram_quantile` 返回字符串 `"NaN"`（不是空序列），要当 null 处理。
2. `reporter` 当前只有 `source`（网关记的）与 `waypoint`（waypoint 记的）两种取值。
   **别照抄网上那些带 `reporter="destination"` 的查询** —— 在这个集群里查出来是空的。
   将来上了 sidecar，同一请求会被源侧与目的侧各记一次，**那时才需要它去重**（Kiali 的做法）。
3. 命名空间过滤用 `source_workload_namespace` / `destination_workload_namespace`，**两端都滤**
   （只滤一端会漏掉"从别处进来"的边）。
4. **所有查询都必须带 `cluster_name`**：Thanos 是全局单端点，所有远程写入的指标都统一打了这个标签，
   不滤就会把**别的集群**的边画进来；哨兵不滤则「B 集群没采集、A 集群在采集」会被误判成已采集。
   （2026-10-17 实跑确认 `istio_requests_total` 与 `istio_build` 都带它。）
5. **「外部来客」没有真实源标识**：从集群外进来的流量，`source_workload` 就是网关自己
   （`external-gateway-istio`）。真实来源只能靠 XFF / 网关日志，指标里没有。

## 3. 后端

**DTO**（放 `platform-api/.../metrics/dto/`，与既有 metrics DTO 同处）：

```java
record MeshTopologyResponse(List<MeshNode> nodes, List<MeshEdge> edges,
                           int windowSeconds, boolean meshMetricsAvailable)
record MeshNode(String id, String namespace, String workload, boolean gateway)
record MeshEdge(String source, String target, String level, double rps, double errorRate,
                Double p95Ms, Double bytesPerSec)
```

⚠️ **`MeshNode.id` 的约定：`namespace + "/" + workload`**（如 `default/movie-deployment`）。
命名空间必须进 id —— 同名 workload 在多个命名空间里很常见，只用 workload 名会**把两条边粘成一个节点**。
`MeshEdge.source/target` 存的就是这个 id。

⚠️ **层级由 `level` 表达，不是 `hasL7`**：`L7` 的边有请求数/响应码/延迟；`L4` 的边只有
`bytesPerSec`，`rps`/`errorRate`/`p95Ms` 一律是「没有意义」而不是 0 —— 前端要显示「—」。
同一对端点两层都有时只出一条 `L7` 边，字节数一并带上。

**查询**：`MetricQuery` 枚举加三个常量（与现有"一个枚举常量 = 一条 PromQL 模板 + 一个 label 类型"的风格一致）。

**服务**：`MetricsService.meshTopology(clusterId, namespace, windowSeconds)`
- 三条业务查询 + 一条哨兵**并发**发出（现有的 `queryCluster` 是一条条打的，拓扑页每 30 秒一刷，串行会慢）
- 边的 QPS 与错误率按 `(source, target)` 合并；延迟独立聚合，缺就是 `p95Ms = null`
- **延迟那条失败不算整体失败**（只让 `p95Ms` 为 null）—— 少一个数不该让整张图打不开
- 节点集合 = 出现在边两端的 workload（v1 **不补**"在网格里但没流量"的节点，见 §6）
- **`meshMetricsAvailable`（原叫 `l7Available`，2026-10-17 改名）**：判据是**哨兵指标**
  `count(istio_build{cluster_name="…"}) > 0` —— 它由每个代理（istiod / ztunnel / waypoint / 网关）产出，
  **与流量无关**，所以能区分两件长得一模一样的事：
  「**没接采集**」和「接了、但这段时间没调用」—— 两者的主查询**都是空的**。
  ⚠️ 哨兵**必须按集群过滤**，否则「B 集群没采集、A 集群在采集」会被误判成已采集。
  ⚠️ 名字刻意不叫 `l7Available`：哨兵只能证明"**在采集**"，**证明不了"有 L7 能力"**
  （只抓了 ztunnel 而没挂任何 waypoint 时，也永远不会有 `istio_requests_total`）——
  许一个兑现不了的诺，正是这个项目里反复吃过亏的那类注释。
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

**画布：ECharts 的 `graph` 系列 + 自己算的分层布局（`layout: 'none'`）。**
（2026-10-17 先用力导向，用户反馈「一进来就晃、挤成一团」后改成静态分层。）

- **为什么不用力导向**：模拟会动画（每次刷新重放一遍 → 晃），而且找到的平衡点常常一团乱麻。
  静态分层是算出来的：断环 → 最长路径定层 → 层内重心排序减交叉 → 摆坐标，
  结果确定、无动画、天然表达"从左到右"的流向。
- **为什么不用 dagre/VueFlow**：要新增依赖；节点规模（几十个）用这百来行足够，
  且这里是纯查看态 —— 流水线画布用 VueFlow 是因为**用户要拖拽摆位**（编辑态），需求不同。
- **拖动**：节点仍可拖（临时微调），位置只记在内存里，换集群/换时间窗就清掉。
- 标签放在节点**下方**并截断（`width:150, overflow:'truncate'`）—— 左→右流动时右侧标签会顶到下一列。

- **边**：L7 = **实线**，`lineStyle.width ∝ log(rps)`、颜色按错误率分档（0 → 绿，>0 → 黄，>5% → 红）；
  L4 = **虚线 + 中性色**，宽度按字节/秒（与 QPS 不同量纲，两根线的宽度不可直接比较）
- **节点**：标签 = workload 名；填充 = 健康色（见下），描边 = 命名空间色；网关节点画菱形（"外部入口"）
- **点击节点或边** → 右侧抽屉显示三个数 + 该边对应的 PromQL（用户可以拿去 Grafana 自己复核）
- **顶部**：时间窗（5m / 1h / 24h）、手动刷新 + 30 秒自动；
  **集群与命名空间来自顶栏级联**（本路由 `context: 'full'`，与其它资源页一致），
  页内**不再放一套**下拉。只留一个「仅当前命名空间」开关 —— 拓扑本质是集群级视图，
  默认看全量（否则网关那些边会因为源在 `istio-system` 而整片消失）。

⚠️ **空态分两种，说人话**：
- `meshMetricsAvailable=false` → 「这个集群**没在采集**网格指标 —— 怎么接见集群前提 §1.7」（别画空图，
  那会让人以为"集群里没有流量"）；
- `meshMetricsAvailable=true` 但 `edges` 为空 → 「在采集，但这段时间没有 L7 调用」，
  并把页面上那段"看不见什么"的说明顶到显眼处（可能是没流量，也可能是网格里没有负载 / 没挂 waypoint）。

⚠️ **页面上要写明覆盖范围的限制**（就是"看不见什么"）：

> 图里只有：走**外部网关**的南北向，和**进了网格且挂了 waypoint** 的东西向。
> 没被纳入网格的负载互相调用、以及网关之外的集群外来源，**一条都不在这里** —— 这是 Istio 的机制决定的，
> 不是平台少画了。

## 5. 验收

1. 在 coding-cluster 上打开，应看到那 5 条边（minio / movie-web / thanos-query / prometheus / movie-service）。
   ✅ 2026-10-17 对真 Thanos 跑过服务方法：`meshMetricsAvailable=true`、6 个节点、5 条边。
2. **摘掉** `movie-service` 的 `istio.io/use-waypoint` → 那条边**从实线变成虚线**（`level` 由 L7 变 L4，
   三个数变「—」，只剩字节）—— 这正是识别「有没有 L7」的用法，比自己消失更有信息量。
   顺带：ztunnel 只提供 L4，所以这一条同时验证了 §7 的结论。
3. 在一个**没接采集**的集群上打开 → 明确提示「没在采集」，不是空图。✅
4. 从图上应能一眼看出 `movie-web → movie-service` 的错误率高：2026-10-17 实测计数器
   `404=30 / 200=6` → 错误率 **83.3%**（早先那次读数是 100%，因为当时 200 的样本还没出现）。
   颜色分档 >5% 即红，所以这条边一眼是红的。✅

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
