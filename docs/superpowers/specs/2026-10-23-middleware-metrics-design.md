# 中间件业务指标（每款自己的指标）

> 日期：2026-10-23
> 状态：方案已评审通过，按批次实施（A → E）
> 相关：[中间件商店](../development/middleware-store.md)

## 1. 背景与目标

中间件详情页目前只有**资源用量**四张图（CPU/内存/网络/磁盘），来自 kubelet/cAdvisor，任何工作负载都有。
每款中间件还有**它自己关心的指标**（Redis 连接数、RabbitMQ 积压、PG 复制延迟、Kafka 未充分复制分区……）。

目标是让详情页出现「业务指标」，并且采集链路由平台负责，用户零配置：

- 指标源：优先用 Operator/镜像自带的 exporter 或插件；没有的（ES/MySQL）由平台加边车；
- 采集：平台随实例创建 Service + ServiceMonitor（PG 例外，用 Operator 自带开关）；
- 展示：按中间件 code 声明指标清单，前端按声明渲染。

## 2. 关键决定

### 2.1 用 Service + ServiceMonitor，不用 PodMonitor

本集群 `PodMonitor` CRD **没有 `endpoints[].targetPort`**（已核实），只能引用容器端口名；而各 Operator
的容器端口命名不归我们管。Service 的端口名是我们起的（`metrics`），`targetPort` 可写数字，对所有中间件成立。

> PostgreSQL 原本可以用 CR 的 `spec.monitoring.enablePodMonitor: true` 让 Operator 建 PodMonitor，
> 但真集群验证时 CNPG 0.29 打印了 **deprecated 警告**（"请自己管理 PodMonitor"）。因此平台统一走
> Service + ServiceMonitor：目标端口 9187、selector 复用 `cnpg.io/cluster=<name>,cnpg.io/podRole=instance`，
> 已实测抓取正常，CR 里不再写这个字段。

### 2.2 指标查询复用「Pod 名正则」

exporter 都跑在实例自己的 Pod 里（边车或内置），所以业务指标与资源用量用同一个过滤：
`cluster_name + namespace + pod=~"^(pod1|pod2)$"`。Pod 名单由 k8s-server 按与状态判定相同的 selector 给出，
一个实例多个工作负载（ES+Kibana、Redis 哨兵、Kafka 的 StrimziPodSet）都能覆盖。

### 2.3 业务指标是**开关**，默认关

创建表单有「采集业务指标」开关（默认关）。开关决定：

- 是否装/打开指标源：Redis `redisExporter` 边车、Kafka `metricsConfig`、ES/MySQL 的 exporter 边车、Nacos 的 actuator 端点；
- 是否建 Service + ServiceMonitor。

资源用量（CPU/内存/网络/磁盘）不受开关影响。开关值记在台账 `spec_json.metricsEnabled`，
详情页据此决定是否展示业务指标区、接口据此决定是否查业务指标。**已有实例不能事后补开**（要重建）。

### 2.4 指标清单集中声明

`MiddlewareCatalog` 每款声明一组指标：`key / label / unit / PromQL`（模板里有三个 `%s`：cluster、namespace、pod 正则）。
前端只按声明渲染；新增中间件 = 新增声明。

## 3. 每款的指标源

| 中间件 | 指标源怎么开 | 采集资源 | 参考指标 |
|---|---|---|---|
| PostgreSQL | exporter 内置（instance manager，9187），CR 不用改 | Service(9187) + ServiceMonitor（不用 deprecated 的 `enablePodMonitor`） | `cnpg_backends_total`、`rate(cnpg_pg_stat_database_xact_commit[2m])`、`cnpg_pg_replication_lag`、`cnpg_pg_database_size_bytes` |
| RabbitMQ | `rabbitmq_prometheus` 插件默认启用（15692），CR 不用改 | Service(15692) + ServiceMonitor | `rabbitmq_queue_messages_ready/unacked`、`rabbitmq_connections`、`rate(rabbitmq_queue_messages_published_total[2m])` |
| Redis | CR `redisExporter.enabled + image` | Service(9121) + ServiceMonitor（selector 排除哨兵 Pod） | `redis_connected_clients`、`redis_memory_used_bytes`、`rate(redis_commands_processed_total[2m])`、命中率 |
| Kafka | CR `spec.kafka.metricsConfig.type: strimziMetricsReporter`（1.3 新增，无需 ConfigMap） | Service(9404) + ServiceMonitor | 消息速率、`underreplicatedpartitions`、`activecontrollercount` |
| Elasticsearch | 平台加 `elasticsearch-exporter` 边车（ES 9 已无 `/_prometheus/metrics`）。地址/TLS 走 args（`--es.uri` / `--es.ssl-skip-verify`），凭据走 env；ECK 要求 `runAsUser` 为数字 | Service(9114) + ServiceMonitor | 集群 health、JVM 堆、查询速率、未分配分片 |
| MySQL（Percona PS） | 平台加 `mysqld_exporter` 边车（`spec.mysql.sidecars`，用 Operator 的 `monitor` 用户） | Service(9104) + ServiceMonitor（selector 带 `component=database`） | 连接数、QPS、活跃线程、慢查询 |
| Nacos | chart 原生 `nacos.extraEnv` 下发 `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=prometheus`（不改模板） | Service(8848) + ServiceMonitor，path `/nacos/actuator/prometheus` | JVM 堆、活跃线程、HTTP 请求/秒、配置数（`nacos_monitor`，可能为空） |

## 4. 统一资源与生命周期

实例创建时，在 CR 之后创建（都由平台打 `platform.coding/middleware-instance=<id>` 标签）：

- `Service` `<名字>-metrics`：`selector` = 适配器给的指标 Pod 选择器，端口名 `metrics`，`targetPort` = 数字；
- `ServiceMonitor` `<名字>-metrics`：`selector.matchLabels` 选中上面的 Service，`endpoints[0].port=metrics`，
  `path`/`scheme` 来自适配器。

规则：

- 集群里**没有 ServiceMonitor CRD** → 跳过采集资源，**不阻断**建实例（隔离环境可能没装 Prometheus Operator）；
- 采集资源是 **best-effort**：建失败只记日志、不回滚实例 —— 监控问题不该把一个刚建好的数据库撤掉；
  半套（Service 建了、ServiceMonitor 没建）会被清掉，详情页业务指标无数据即体现；
- 删除实例时一并删这两个对象（`keepPvc` 不影响）。

## 5. 查询与展示

- 端点仍是 `POST /middleware/instance/metrics`，响应增加 `business: [{key, label, data}]`，与四张资源图一次返回；
- `data` 是标准 `MetricSeriesResponse`（unit + series），单位取声明的 `unit`；
- 前端在「概览」的资源用量图下面渲染「业务指标」区块（2 列小图），无声明或无数据时不显示该区块；
- 权限沿用 `tenant:middleware:read`（不需要新权限行）。

## 6. 权限

租户创建 ServiceMonitor 需要租户 RBAC 模板补 `monitoring.coreos.com/servicemonitors`
（`get/list/watch/create/update/patch/delete`）。**新迁移 + 点一次「重下发全部模板」**，否则租户建实例会 403。
Service 已在模板里的 `services` 全权限内。

## 7. 分批与验收

| 批次 | 内容 |
|---|---|
| A | 通用底座（资源生命周期 + 指标清单/查询/展示）+ PostgreSQL + RabbitMQ |
| B | Redis |
| C | Kafka |
| D | Elasticsearch + MySQL（边车 exporter） |
| E | Nacos（内置 chart 开 actuator） |

每批验收口径一致：**新建实例（勾选采集）→ Prometheus 里 target UP → 详情页业务指标有数**。
旧实例不会自动改造（重建或手工 patch CR）。**A–E 全部已完成并真集群验证（2026-10-23）。**

## 8. 风险与待实测

- RabbitMQ 15692 是否默认免鉴权；Kafka reporter 实际端口/指标名；Redis exporter 边车端口与哨兵 Pod 的排除；
  Percona `monitor` 用户权限是否够 mysqld_exporter；Nacos actuator 的真实指标名 —— 全部在对应批次真集群验证。
- 边车会给每个 Pod 增加一个容器（ES 3 节点 = 3 个），按 50m/64Mi 请求、100m/128Mi 上限写进 CR（过 limits 配额）。
- 业务指标依赖新端点，需重启 platform-api / k8s-server。
