# 中间件业务指标 实施计划

> Spec：[2026-10-23-middleware-metrics-design.md](../specs/2026-10-23-middleware-metrics-design.md)
> 执行方式：本会话内联执行；每批完成后真集群验收再进入下一批。

## Global Constraints

- 不抽公共基类：每款中间件的指标源写法写在各自适配器里（沿用既有原则）。
- 平台建的采集资源必须带 `platform.coding/middleware-instance=<id>` 与 `platform.coding/managed-by=middleware` 标签。
- 集群没有 ServiceMonitor CRD 时**跳过**采集资源，不阻断建实例。
- 租户路径用租户 client（K8s RBAC 第二道闸），管理员路径用 admin client。
- 新权限/模板迁移一律手工重放（本仓无 Flyway）。

---

## 批 A：底座 + PostgreSQL + RabbitMQ（✅ 已完成并真集群验证）

### Task A1：采集资源的创建/删除与能力探测（k8s-server）

**Files**
- Modify: `k8s-server/.../middleware/MiddlewareAdapter.java`（新增 `MetricsSpec` 与默认 `buildMetricsResources`）
- Modify: `k8s-server/.../middleware/MiddlewareInstanceService.java`（创建/回滚/删除）
- Test: `k8s-server/.../middleware/MiddlewareMetricsResourceTest.java`

**接口**

```java
record MetricsSpec(int port, String path, String scheme, String podSelector) {}

/** null = 该实例不采业务指标 */
default MetricsSpec metricsSpec(String name, String kind, String instanceId) { return null; }

/** 默认：Service + ServiceMonitor；PG 覆写成空（用 CR 自带开关） */
default List<CrSpec> buildMetricsResources(MiddlewareCreateRequestDTO req, MetricsSpec spec) { ... }
```

**Steps**
1. 写失败测试：给定 `MetricsSpec(15692, "/metrics", "http", "app.kubernetes.io/name=x,app.kubernetes.io/component=rabbitmq")`，
   断言生成的 Service 名为 `<name>-metrics`、端口名 `metrics`、`targetPort=15692`、selector 解析为两个键值；
   ServiceMonitor 的 `spec.selector.matchLabels` 能选中该 Service，endpoint `port=metrics`。
2. 实现 `buildMetricsResources`（Service + ServiceMonitor 的 GenericKubernetesResource）。
3. `MiddlewareInstanceService.create`：CR 建完后，若 `metricsSpec != null` 且集群有
   `servicemonitors.monitoring.coreos.com` CRD → 建采集资源。**Ruling：采集资源 best-effort，失败只记日志、
   不进回滚** —— 监控问题不该挡住中间件本身；半套资源会被清理。核心 CR 的失败仍走原回滚。
4. `delete`：静默删 `<name>-metrics`（Service 与 ServiceMonitor）；`keepPvc` 不影响。
5. 跑 `mvn -pl k8s-server test`。

**验收**：单测绿；创建/删除路径都覆盖。

### Task A2：PostgreSQL 采集规格

**Files**：`PostgresqlMiddlewareAdapter.java`、`MiddlewareMetricsResourcesTest.java`

- `metricsSpec`：9187 / `/metrics` / `http` / `cnpg.io/cluster=<name>,cnpg.io/podRole=instance`；
- CR **不写** `spec.monitoring.enablePodMonitor`（CNPG 0.29 已 deprecated，统一走 ServiceMonitor）。

**验收**：CR 无 `monitoring` 字段；真集群 Service(9187) 抓取 UP。

### Task A3：RabbitMQ 采集规格

**Files**：`RabbitmqMiddlewareAdapter.java` 实现 `metricsSpec`：15692 / `/metrics` / `http` /
`app.kubernetes.io/name=<name>,app.kubernetes.io/component=rabbitmq`；测试。

**验收**：单测断言端口与 selector。

### Task A4：租户 RBAC 模板补 ServiceMonitor

**Files**：`platform-data/.../V2026_10_23_7__tenant_rbac_servicemonitors.sql`

```sql
UPDATE platform_rbac_template
SET rules = JSON_ARRAY_APPEND(rules, '$.rules',
        CAST('{"apiGroups":["monitoring.coreos.com"],"resources":["servicemonitors"],"verbs":["get","list","watch","create","update","patch","delete"]}' AS JSON))
WHERE built_in = 1 AND deleted_at IS NULL AND rules NOT LIKE '%monitoring.coreos.com%';
```

**验收**：重放后租户建实例能创建 ServiceMonitor（重下发模板后）。

### Task A5：platform-api 指标清单与响应

**Files**
- Modify: `MiddlewareCatalog.java`：`Item` 增加 `List<MetricDef> metrics`；`MetricDef(key,label,unit,query)`；
  `postgresql` / `rabbitmq` 各声明 4 条（其余先空）。
- Modify: `MiddlewareMetricsResponse`：增加 `List<BusinessMetric> business` 与 `withBusiness`。
- Modify: `MetricsService`：新增 `businessMetric(clusterId, namespace, podRegex, query, unit, start, end)`。
- Modify: `MiddlewareService.metrics`：按实例 code 取声明，逐条查询，组装进响应。
- Test: `MiddlewareServiceTest` 增加「按 code 取声明并发查询」。

**验收**：`POST /middleware/instance/metrics` 返回 `business`；未知 code 返回空数组。

### Task A6：前端业务指标区块

**Files**
- `components/middleware/MiddlewareMetricsPanel.vue`：`data.business` 非空且至少一条有数据时，渲染「业务指标」小节
  （每指标一个 `MetricChart`，单位来自 `data`，2 列网格）。
- `types.ts`：`MiddlewareMetrics.business?: { key: string; label: string; data: MetricSeriesResponse }[]`。

**验收**：`npm run build` 通过；stub 数据下区块渲染正确。

### Task A7：真集群验收（批 A）—— 结果

1. 临时命名空间 `mw-metrics-verify`（已删）；
2. RabbitMQ 单节点：15692 **免鉴权**（HTTP 200，217 个 `rabbitmq_*` 指标），平台形态的 Service + ServiceMonitor 抓取 `up=1`；
3. PostgreSQL 单实例：`spec.monitoring.enablePodMonitor` 已 **deprecated** → 改为平台 Service(9187) + ServiceMonitor，
   实测 `up=1`、`cnpg_backends_total` 有值；CR 里不再写该字段；
4. 目录里声明的 8 条 PromQL（PG 4 + Rabbit 4）全部在 Thanos 返回数据；
5. **命名坑**：采集资源不能叫 `<名字>-metrics` —— Redis Operator 自己给 exporter 建了同名 Service，
   我们的 label 会被它覆盖（ServiceMonitor 什么都选不到）。统一改为 `<名字>-platform-metrics`。

---

## 批 B：Redis（✅ 已完成并真集群验证）

- CR 加 `redisExporter: {enabled: true, image: quay.io/opstree/redis-exporter:v1.44.0, port: 9121}`
  + `env REDIS_PASSWORD`（secretKeyRef 到平台写的 `<名字>-credentials`）——**不带口令时 exporter 的 INFO 失败、指标全空**；
  哨兵 CR 上移除该字段（哨兵 Pod 没有 exporter）；
- `metricsSpec`：9121 / `/metrics` / `http` / `app=<name>`（只选数据 Pod）；
- 实测：Pod 2/2（redis + exporter），`redis_up 1`，185 个 `redis_*` 指标；
  平台 Service + ServiceMonitor `up=1`；4 条 PromQL 全部有数据。
- 坑：见批 A 的 `-platform-metrics` 命名。

## 批 C：Kafka（✅ 已完成并真集群验证）

- CR 加 `spec.kafka.metricsConfig: {type: strimziMetricsReporter}`（不用 ConfigMap、无额外边车）；
- `metricsSpec`：9404 / `/metrics` / `http` / `strimzi.io/cluster=<name>,strimzi.io/name=<name>-kafka`；
  容器端口名是 `tcp-prometheus`，但平台走数字 targetPort，不依赖它；
- 实测：1 broker → 6491 个 `kafka_*` 指标，平台 ServiceMonitor `up=1`；
  **reporter 的指标名带 `persec`**（`kafka_server_brokertopicmetrics_messagesinpersec_total`），
  不是 JMX exporter 的 `messagesin_total` —— 目录里按实测名字写。

## 批 D：Elasticsearch + MySQL（✅ 已完成并真集群验证）

- ES：`nodeSets[0].podTemplate.spec.containers` 追加 `elasticsearch-exporter:v1.7.0`（50m/64Mi、100m/128Mi），
  **地址/TLS 必须走 args**（实测镜像不认 `ES_URI`/`ES_SKIP_TLS_VERIFY`）：`--es.uri=https://localhost:9200 --es.ssl-skip-verify`；
  凭据走 env `ES_USERNAME/ES_PASSWORD`（secretKeyRef 到 `<name>-es-elastic-user`）；
  **ECK 在 Pod 级设了 `runAsNonRoot`**，镜像用户是 nobody（非数字）→ 必须给 `securityContext.runAsUser: 65534`，
  否则 `CreateContainerConfigError`；
- MySQL：`spec.mysql.sidecars` 追加 `mysqld-exporter:v0.15.1`，用 `monitor` 用户（`<name>-secrets` 的 monitor 键）；
  selector 必须带 `component=database`（Percona 数据 Pod 的 component 不是 mysql，HAProxy 也没有 9104）；
- 实测：ES green + `elasticsearch_cluster_health_status{color="green"}=1`、JVM 堆 483MB、查询速率、未分配分片；
  MySQL `mysql_up=1`、连接数/活跃线程/QPS/慢查询；两条 ServiceMonitor `up=1`，8 条 PromQL 全部有数据；
- 环境噪声：1Gi 的 MySQL/Nacos 会 OOMKilled（验证时调到 2Gi/3Gi 才稳定），与 sidecar 无关。

## 批 E：Nacos（✅ 已完成并真集群验证）

- chart 原生支持 `nacos.extraEnv`，**不用改模板**：开关打开时下发
  `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=prometheus`；
- 采集：Service(8848) + ServiceMonitor，path `/nacos/actuator/prometheus`，selector `app.kubernetes.io/instance=<release>`；
- 实测：端点 200；ServiceMonitor `up=1`；稳定指标 JVM 堆 / 活跃线程 / HTTP 请求秒均返回数据；
  **Nacos 业务 gauge（configCount 等）注册依赖模块初始化，重启后一段时间内不出现** → 只保留一条并接受可能为空。
