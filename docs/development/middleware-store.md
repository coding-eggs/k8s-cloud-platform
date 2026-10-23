# 中间件商店

> 设计：[2026-10-22-middleware-store-design.md](../superpowers/specs/2026-10-22-middleware-store-design.md)
> 计划：[批①-1](../superpowers/plans/2026-10-22-middleware-store-batch1.md)、[批①-2](../superpowers/plans/2026-10-22-middleware-store-batch2.md)
> 状态：批①-1（底座 + Redis）、批①-2（MySQL/Kafka/ES + Nacos）与**批②（PostgreSQL / RabbitMQ）**已实施；
> 2026-10-23 真集群验证：redis 单节点/哨兵、mysql 单节点、kafka 三 broker、nacos 单节点已跑通，
> PostgreSQL / RabbitMQ 见文末验证记录；ES 待集群里 51 天前的旧 ECK 资源处理后再验。

## 1. 可用的中间件

| 条目 | 类型 | 版本（chart） | 实例形态 | 凭据 |
|---|---|---|---|---|
| Redis | Operator（OT-Container-Kit） | 0.27.0 | 单节点 / 哨兵（数据节点 + 固定 3 哨兵）/ 集群分片 | 平台生成 → `<名称>-credentials` |
| MySQL | Operator（Percona PS） | 1.2.0 | 单节点 / 高可用集群（Group Replication） | Operator 生成 → `<名称>-secrets` |
| Kafka | Operator（Strimzi，KRaft） | 1.3.0 | 集群（brokers ≥3，NodePool 双角色） | 无（内网 plain 监听） |
| Elasticsearch | Operator（ECK） | 3.5.0 | 集群（nodeSet，count ≥1） | ECK 生成 → `<名称>-es-elastic-user` |
| Nacos | **Chart**（不是 Operator） | 1.2.0（appVersion 3.2.4） | 单节点 / 集群（内嵌 JRaft 存储） | chart 默认不鉴权 |
| PostgreSQL | Operator（CloudNativePG） | 0.29.1（appVersion 1.30.1） | 单实例 / 主备（instances ≥3，自动 failover） | Operator 生成 → `<名称>-app` |
| RabbitMQ | Operator（官方 Cluster Operator，Bitnami 打包） | 4.4.34（appVersion 2.16.1） | 单节点 / 集群（replicas ≥3） | Operator 生成 → `<名称>-default-user` |

> Nacos 3.x 集群模式使用内嵌 JRaft 存储，**不需要外置 MySQL**。

## 1.1 创建表单

- 命名空间、StorageClass 都是**下拉**，选项由 `POST /middleware/form-options` 现算：命名空间按租户分配表收窄，
  StorageClass 走管理端读（租户 SA 没有 `storageclasses` 权限）；读失败降级为空列表，不影响其它字段；
- 资源一律「数字 + 单位」：CPU 填核数（可 0.1 步进，提交为 `0.5`），内存/存储填数字并选 `Mi`/`Gi`，
  提交时拼成 K8s quantity；不接受手写 `500m`/`1Gi` 这类字符串；
- 版本（Kafka / Elasticsearch）是**下拉**，选项来自 `MiddlewareCatalog` 里声明的受支持清单，接口层同样校验
  （绕过前端直接传别的 tag 会被拒）；清单为空的中间件（Redis/MySQL/PG/RabbitMQ/Nacos）不展示版本字段；
- 节点数按形态区分：单节点/单实例形态**锁定为 1**（只读），标签随形态变化（`主节点数` / `数据节点数` / `Broker 数` /
  `实例数`），不再一律叫「节点数」；Redis 集群形态额外给「每个主节点的副本数」（默认 1，0 = 不配备份，
  映射到 `redisFollower.replicas` = 主节点数 × 每个主节点的副本数，值记在 CR 注解
  `platform.coding/redis-replicas-per-shard`，扩主节点时按它同步副本总数）；
- Elasticsearch 表单有「安装 IK 中文分词器」开关（默认关）：开启后用 initContainer 从
  `get.infini.cloud/elasticsearch/analysis-ik/<版本>` 下载并解压到共享 emptyDir 的 `plugins/analysis-ik`。
  ⚠️ **不要用 `bin/elasticsearch-plugin install`**：它会对 config 目录 chown，而 ECK 默认
  `runAsNonRoot` + drop ALL，容器内必然 `Operation not permitted`；下载 zip 直接解压才可行。
  该 initContainer 必须带资源（配额要求），且集群 Pod 需要能访问 get.infini.cloud（离线集群要换内网源）。
- Elasticsearch 表单有「同时部署 Kibana」开关：勾选后平台额外建一个同名 Kibana CR（`elasticsearchRef` 指向该实例）；
  Kibana 的 `status.health` 非 green 时，实例状态降级并带原因，不会因为 ES 本身绿就报「运行中」。

- 创建成功后弹「连接方式」：集群内 Service 地址 + 凭据在哪个 Secret 的哪个键（口令不回传）；
  实例列表每行也保留「连接」入口。地址按 Operator 实际创建的 Service 命名（如 Redis 哨兵
  `<名称>-master/-replica/-sentinel`、集群 `<名称>-leader/-follower`、PG `<名称>-rw/-ro`、
  RabbitMQ `<名称>`、ES `<名称>-es-http`、Kafka `<名称>-kafka-bootstrap`）。

## 1.2 实例详情 / 编辑

- 列表点名称或「详情」进 `apps/:id`：概览（台账 + 规格 + 连接方式）、资源对象、YAML（CR 原文）、操作记录；
- 「资源对象」与状态判定用同一套 selector（Pod 用 `statusSelector`、PVC 用 `pvcSelector`），
  不会出现"列表 READY、详情没对象"；
- 「资源对象」除 CR / Pod / PVC / Service 外，还给出：
  - **附属 CR**（Redis 哨兵的 `RedisSentinel`、Kafka 的 `Kafka`、ES 勾选的 `Kibana`）；
  - **工作负载**（Operator/Helm 为该实例建的 StatefulSet / Deployment，含副本与镜像）——
    归属判据是「名字是实例名或以 `<实例名>-` 开头」/「ownerReferences 指向实例 CR」/
    「带 `app.kubernetes.io/instance` 或 `platform.coding/middleware-instance` 标签」，任一成立；
  - **凭据 Secret**（只给名字、类型与键名，不回传值）；
  - **状态条件**（主 CR 的 `status.conditions`，DEGRADED 时先看这里）；
  - **事件**（CR 与其派生对象上的最近 50 条，Pod 起不来/卷绑不上时是第一现场）。
  每一块独立读，租户 SA 缺某项权限（典型是 `events`）时只在该块上方提示，其余照常展示；
- **详情页的状态是现场读的**：台账里的 `status` 只在创建时写过 `CREATING`，之后没人回写，
  详情接口会先按列表同样的口径刷新一次，否则实例已经跑起来还显示「创建中」；
- **概览有监控曲线**：CPU / 内存 / 网络 / 磁盘四张图（`POST /middleware/instance/metrics`，一次请求返回全部），
  口径是「该实例 selector 命中的全部 Pod 聚合」——Pod 名单先由 k8s-server 按与状态判定相同的 selector 读出，
  再拼成 pod 名正则查 Thanos。**这几项不需要 ServiceMonitor**：来自 kubelet/cAdvisor，集群本就在采；
  中间件自己的业务指标见 §1.4；
  > 已知环境事实：本集群 Thanos 里没有 `container_network_*`（cAdvisor 网络指标未采），网络图会恒空，
  > 与工作负载详情的网络图同一现象；
- 资源对象里的名称都可点击跳到对应资源页（工作负载 / Pod / PVC / Service / Secret）；跳转前会把
  全局资源上下文（租户/集群/命名空间）对齐到实例所在位置，否则目标页查的是别处；
- 编辑只做**副本数 + CPU/内存 + 显示名**：形态与存储不可在线改（后端 scale 语义如此）；
  Nacos 走 chart，只允许改显示名；列表行与详情页右上角都有「编辑」入口；
- 实例列表可按类型筛选（`instance/list` 的 `code` 参数），只拉这一类实例的实时状态。

## 1.3 Operator 状态

- `middleware/catalog` 里的 Operator 状态按集群缓存 **30s（Redis）**：每个 Operator 都要查
  helm release + CRD + 工作负载，不缓存时进一次页面要查 6 遍（实测 1.9s → 0.01s）；
- 「已安装版本」取工作负载的 `helm.sh/chart` 标签，与目录里钉死的版本比较：
  一致 → 按钮显示「重装」，不一致 → 「升级」；不再对所有已安装的 Operator 都显示「升级」。

## 1.4 业务指标（每款中间件自己的指标）

设计见 [2026-10-23-middleware-metrics-design.md](../superpowers/specs/2026-10-23-middleware-metrics-design.md)，
计划见 [plans](../superpowers/plans/2026-10-23-middleware-metrics.md)。

- **开关**：创建表单有「采集业务指标」，**默认关**；打开后才装/开指标源并建 Service + ServiceMonitor。
  资源用量（CPU/内存/网络/磁盘）不受影响。开关值记在 `spec_json.metricsEnabled`，详情页据此展示业务指标区。
  已有实例不能事后补开（需重建）；
- 机制：适配器给出 `MetricsSpec`（端口 / 路径 / scheme / Pod 选择器），平台建
  **`<名字>-platform-metrics` 的 Service + ServiceMonitor**（`targetPort` 用数字，不依赖 Operator 的容器端口名）；
  删除实例时一并删。集群没有 ServiceMonitor CRD 时自动跳过，**best-effort**，失败不阻断建实例；
- 命名坑：不能叫 `<名字>-metrics` —— Redis Operator 自己就给 exporter 建了同名 Service，会把平台的 label 覆盖掉；
- 查询：`MiddlewareCatalog` 按 code 声明 `MetricDef(key/label/unit/promql)`，PromQL 里三个 `%s` 是
  cluster / namespace / pod 名正则；与资源用量图同一次请求返回（`business` 字段）。

| 中间件 | 指标源 | 端口 | 指标 |
|---|---|---|---|
| PostgreSQL | instance manager 内置 | 9187 | 连接数、事务提交/秒、复制延迟、数据库大小 |
| RabbitMQ | `rabbitmq_prometheus` 插件（默认开，**免鉴权**） | 15692 | 就绪消息、未确认消息、连接数、发布/秒 |
| Redis | `redisExporter` 边车（口令从 `<名字>-credentials` 注入） | 9121 | 连接数、内存使用、命令/秒、阻塞客户端 |
| Kafka | `strimziMetricsReporter`（1.3 新增，无需 ConfigMap） | 9404 | 消息入站/秒、字节入站/秒、未充分复制分区、活跃 Controller |
| Elasticsearch | `elasticsearch-exporter` 边车（args 传 URI/TLS，`runAsUser: 65534`） | 9114 | 集群 health、JVM 堆、查询/秒、未分配分片 |
| MySQL | `mysqld_exporter` 边车（`monitor` 用户） | 9104 | 连接数、QPS、活跃线程、慢查询 |
| Nacos | chart 原生 `nacos.extraEnv` 开 actuator | 8848（`/nacos/actuator/prometheus`） | JVM 堆、活跃线程、HTTP 请求/秒、配置数（可能为空） |

## 2. 执行链

```
前端（中间件页）
  → platform-api：权限 + 形态校验 + Operator 就绪 + 租户归属 + 实例台账 + 操作记录
  → k8s-server：
      Operator 类：配额预检 + 写凭据（按需）+ 建 CR（MySQL/Kafka/ES 由各自 Operator 接管）
      Nacos：helm upgrade --install（release = 实例名）
  → 列表页按 (类型, 命名空间) 批量读实时状态（Pod 就绪；Nacos 以 helm release 是否存在判断实例是否存在）
```

- Operator 安装：helm `platform-middleware-<code>` → 命名空间 `platform-middleware-system`，chart 随 k8s-server 镜像内置；
- 判定：必需 CRD 齐全 + 本 release 清单里的 Deployment/StatefulSet 就绪（不同 chart 的标签各不相同，故按 `helm get manifest` 解析）；安装前做 **CRD 归属预检**（他人 release / 非 Helm 管理一律拒）。

## 3. 部署

- 批①-2 **无新表、无新权限行**，但新增了一条租户 RBAC 迁移 `V2026_10_22_5`（补
  `ps.percona.com` / `kafka.strimzi.io` / `elasticsearch.k8s.elastic.co` 三个 apiGroup）；
- 批②（PostgreSQL / RabbitMQ）同样**无新表、无新权限行**，只新增租户 RBAC 迁移 `V2026_10_23_1`
  （补 `postgresql.cnpg.io` / `rabbitmq.com`），重放后同样要点一次「重下发全部模板」；
- 表单可选项端点新增权限行 `perm_mw_form_options`（`/middleware/form-options` → `tenant:middleware:read`），
  见 `V2026_10_23_2`，已绑 admin / tenant_admin / tenant_member；
- 详情 / 操作记录 / 改名三个端点见 `V2026_10_23_4`（前两个 `tenant:middleware:read`，改名 `tenant:middleware:write`）；
- 详情页的「事件」块要在租户 RBAC 模板里有 core/v1 `events`（只读），见 `V2026_10_23_5`；
- 业务指标的采集资源要能建 ServiceMonitor，租户模板补 `monitoring.coreos.com/servicemonitors`，见 `V2026_10_23_7`；
- 概览监控曲线的端点见 `V2026_10_23_6`（`tenant:middleware:read`），重放后无需重下发模板；
- **重放后必须点一次「重下发全部模板」**，否则租户创建实例会 403（含采集资源）、详情页的事件也读不到；
- 重建 k8s-server + platform-api + platform-web；
- Operator 安装时平台会下发"监听全部命名空间"的 values（Percona `watchAllNamespaces`、Strimzi
  `watchAnyNamespace`）—— 这是 CR 能被 reconcile 的前提，手工安装时别漏；
- 集群需要能拉取的镜像：

| 中间件 | 镜像来源 |
|---|---|
| Redis | `quay.io/opstree/redis-operator`、`quay.io/opstree/redis`（数据）、`quay.io/opstree/redis-sentinel`（哨兵） |
| MySQL | `percona/percona-server-mysql-operator`、`percona/percona-server:8.4`、`percona/percona-xtradb-cluster-operator:1.14.1-haproxy` |
| Kafka | `quay.io/strimzi/operator`、`quay.io/strimzi/kafka` |
| Elasticsearch | `docker.elastic.co/eck/eck-operator`、`docker.elastic.co/elasticsearch/elasticsearch` |
| Nacos | `nacos/nacos-server:v3.2.4` |
| PostgreSQL | `ghcr.io/cloudnative-pg/cloudnative-pg`（Operator）、`ghcr.io/cloudnative-pg/postgresql`（实例） |
| RabbitMQ | `docker.io/bitnami/rabbitmq-cluster-operator`、`docker.io/bitnami/rabbitmq` |

> ⚠️ Percona 没有 `percona/percona-haproxy` 这个仓库；HAProxy 入口镜像在 PXC operator 的 `-haproxy` tag 里。
> 哨兵必须用 `redis-sentinel` 镜像，用 `redis` 镜像会以 standalone 起来、30s 后被探针判死循环重启。

## 4. 常见失败

| 现象 | 原因 |
|---|---|
| 卡片「未装 Operator」 | 还没装或安装失败（Operator tab 看说明） |
| 安装 Operator 报 CRD 被占用 | 集群里已有别人装的同名 CRD；先清理或改用平台安装 |
| 创建实例 403 | 租户 RBAC 模板没重下发（批①-1 部署第 2 步） |
| 「命名空间配额不足」 | ResourceQuota 的 requests/limits 不够；提示里给了差额 |
| MySQL HAProxy Pod 起不来 | 配额要求每个容器都写 requests/limits，而 Operator 追加的 `haproxy-init` / `mysql-monit` 侧车没有资源字段 —— **配了 `limits.*` 配额的命名空间还需要一个 LimitRange** 给未声明资源的容器兜底（真集群验证实锤） |
| MySQL 一直 Degraded | 组复制成员数不足或 PVC 不可调度；看 CR 的 `status` 与 Pod 事件 |
| Kafka 起不来 | 检查 NodePool 是否有 `strimzi.io/node-pools: enabled` 注解（平台已自动加） |
| ES 拒绝创建 | `version` 不在 ECK 支持范围；换一个受支持的版本 |
| Nacos 一直 Pending | 存储未配（`persistence.enabled=false` 时用 emptyDir）或镜像拉不到 |
| Nacos 第二个实例安装失败 | chart 的 Service/ConfigMap 名固定为 `nacos-cs`，同一命名空间只允许一个实例 |
| 实例一直「创建中」 | 先看 CR 的 `status.conditions`；Operator 拒绝规格（版本不支持等）时不会建 Pod |

## 5. 已知限制

- 中间件版本清单未做成可选列表（Kafka/ES 有下拉清单，MySQL/Redis 镜像固定）；
- 存储与形态不能在线修改（PVC 扩容依赖各 Operator，后端显式拒绝）。

## 6. 真集群验证记录（2026-10-23，coding-cluster）

| 条目 | 形态 | 结果 |
|---|---|---|
| Redis | 单节点 / 哨兵 2→3 数据节点 | ✅ 创建 / 状态 / 扩容 / 删除（PVC 一并清） |
| MySQL | 单节点（含 HAProxy） | ✅（需命名空间有 LimitRange，见 §4） |
| Kafka | 3 broker + Entity Operator | ✅ |
| Nacos | 单节点（内嵌存储） | ✅（`storageClassName: null` 才绑定默认 SC） |
| PostgreSQL | 单实例 / 主备 3 实例 | ✅（状态按 `cnpg.io/podRole=instance` 过滤，initdb Job 不算） |
| RabbitMQ | 单节点 / 集群 3 节点 | ✅（Pod/PVC 标签是 `app.kubernetes.io/name=<集群名>`） |
| Elasticsearch | — | 未验：集群里还有 51 天前遗留的 ECK CRD 与 `es` 集群，需先处理 |
