# Kafka（中间件 code: `kafka`）

> **这是组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/CRD 清单在 `1.3.0/README.md`。
> 使用与设计文档：`docs/development/middleware-store.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（Strimzi，**1.x 纯 KRaft，没有 ZooKeeper**） |
| Operator chart | `1.3.0`（见 `1.3.0/`），release `platform-middleware-kafka` → `platform-middleware-system` |
| CRD / apiGroup | `kafkas` / `kafkanodepools`，组 `kafka.strimzi.io` |
| 实例形态 | 只有 `CLUSTER`（Broker ≥3） |
| 版本 | Strimzi 1.3.0 支持到 Kafka **4.3.1**；平台可选 `4.3.1 / 4.3.0 / 4.2.2 / 4.2.1 / 4.2.0`（第一个是默认） |
| 凭据 | 无 —— 监听器是集群内 plain（9092），未开认证 |
| 业务指标 | `strimziMetricsReporter`，9404（见 §6） |
| 镜像 | Operator `quay.io/strimzi/operator`；Broker 由 Strimzi 按版本拉 `quay.io/strimzi/kafka` |

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-kafka-<8位随机>`；一个实例 = **两个 CR**（`KafkaNodePool` + `Kafka`）；
- **受管标签**：两个 CR 都带 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`；
- **Operator 监听**：必须下发 `watchAnyNamespace: true`（chart 默认 `watchNamespaces: []` + `watchAnyNamespace: false`），
  否则租户 ns 的 CR 没人 reconcile；
- **租户 RBAC**：租户模板需要 `kafka.strimzi.io`（`V2026_10_22_5`）+ 重下发全部模板；
- **状态**：Pod 数 vs 期望数 + CR 信号（只看现存 Pod 全就绪会把"3 broker 只起 2 个"判成 READY）；
  查状态时必须带 `strimzi.io/name=<实例名>-kafka`，否则会把 Entity Operator 的 Pod 算进来。

## 3. 形态与 CR 的对应关系

只有一个形态 `CLUSTER`（brokers ≥3；2 节点没有容错）。平台固定建：

- `KafkaNodePool`（双角色 controller+broker），**必须带注解 `strimzi.io/node-pools: enabled`**（平台已自动加）；
- `Kafka`：`spec.kafka` 里 `listeners` 为集群内 plain（`9092`）、`replicas` = Broker 数；
- 存储：卷的存储类字段叫 **`class`**（不是 `storageClassName`）。

## 4. 表单字段

命名空间、显示名、StorageClass、CPU/内存、存储、`replicas`（Broker 数 ≥3）、版本下拉、「采集业务指标」开关（默认关）。

## 5. 坑（真集群实锤，别回退）

1. **Kafka 版本必须落在 Strimzi 支持的清单里**：Strimzi 1.3.0 只到 Kafka 4.3.1，
   写 4.4.0 会直接 `UnsupportedKafkaVersionException`。平台在 `MiddlewareCatalog.KAFKA_VERSIONS` 里声明，
   接口层也校验（绕过前端也会被拒）。
2. **`KafkaNodePool` 少一个注解/标签就不工作**：`strimzi.io/node-pools: enabled`（Kafka 上）、
   `strimzi.io/cluster=<实例名>`（NodePool 上）。
3. **别用老式 `Kafka.spec.zookeeper`**：1.x 已移除；KRaft 下 controller 由 NodePool 的双角色承担。
4. 存储类字段名是 `class`，写成 `storageClassName` 会被忽略（Pod 永远 Pending）。
5. 扩容走 SSA，先清 `metadata.managedFields`。
6. 状态查询要排除 Entity Operator（按 `strimzi.io/name=<实例名>-kafka` 过滤）。

## 6. 业务指标

- `spec.kafka.metricsConfig.type: strimziMetricsReporter`（1.3 新增，不需要额外 ConfigMap），端口 **9404**；
- ServiceMonitor 的 Pod 选择器用 `strimzi.io/cluster=<实例名>,strimzi.io/name=<实例名>-kafka`；
- **指标名带 `persec`**：`kafka_server_brokertopicmetrics_messagesinpersec_total`，
  不是 JMX exporter 的 `messagesin_total` —— 写 PromQL 前先 `curl :9404/metrics` 对一遍名字；
- 指标定义在 `MiddlewareCatalog.KAFKA_METRICS`（消息/字节出入、未充分复制/离线分区、Controller、
  分区/主题数、日志量、Broker 堆）。

## 7. 加一个新版本怎么做

分两种"版本"，别混：

**A. Strimzi Operator chart 升级**（`charts/middleware/kafka/<新版本>/`）

1. `helm pull` 新 chart 原样入库 + 新建 `<新版本>/README.md`；
2. 改 `MiddlewareOperatorCatalog`（版本、CRD、监听 values）；
3. **核对新 Strimzi 支持的 Kafka 版本清单**，同步 `MiddlewareCatalog.KAFKA_VERSIONS`；
4. 跑测试 → 真集群装一次 → 建一个 3 broker 实例 → 看指标出数。

**B. 只是加一个 Kafka 版本**（Operator 不变）

1. 在 `MiddlewareCatalog.KAFKA_VERSIONS` 加版本（顺序即展示顺序，第一项是默认）；
2. 真集群建一个该版本的实例（确认 Strimzi 真的支持，报错就是版本不支持）；
3. 更新本文件 §1 的版本行。

> ⚠️ 存量实例的 Kafka 版本不会因为清单变化而自动升级；改 `spec.kafka.version` 属于滚动升级，
> 平台目前不提供这个入口（删实例重建）。

## 用户管理（2026-10-26）

- CR 里新增第二个 listener `scram:9093`（SCRAM-SHA-512）；`plain:9092` 原样保留，存量客户端不受影响。
- **有意不开 `kafka.authorization`**：一开授权，走 PLAINTEXT 的客户端（匿名用户）会被全拒；
  所以角色只有一个 `client`，UI 上写明"实例未启用授权，所有用户全权"。
- 用户 = `kafkausers.kafka.strimzi.io` CR（`strimzi.io/cluster` 标签必须打，否则 operator 不生成凭据）；
  口令由 operator 生成，适配器把它**回传**给平台写入 `<实例名>-users`。
- 改口令 = 删 CR 重建（Strimzi 只在创建时生成 SCRAM 凭据）。
- 迁移 `V2026_10_26_2` 给租户模板补 `kafkausers`（只读），跑完要**重下发全部模板**；存量实例重放一次才有 9093。
- **没有 9093 的实例，平台直接拒绝建用户**（`requireScramListener` 读 Kafka CR 校验 listener）——
  operator 在那种实例上仍会生成凭据，但端口不存在，弹给用户的口令连不上。
