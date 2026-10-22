# 中间件商店与实例管理 · 设计（2026-10-22）

> 需求整理：[2026-10-21-middleware-store-requirements.md](2026-10-21-middleware-store-requirements.md)（本文件在其基础上定稿，冲突以本文件为准）。
> 取代：[2026-10-21-app-store-and-operator-design.md](2026-10-21-app-store-and-operator-design.md) 中的"通用 chart 商店 + 目录 + 来源搜索"部分；Operator 视图并入本模块。
> 复用：S3.6 的 Helm 执行链（`HelmRunner` / `ChartFetcher` / `ManifestInspector`）与组件操作记录形态。

---

## 0. 一句话

应用商店不再是通用 chart 货架，改成**固定清单**：6 款中间件用 Operator 部署、Nacos 用 chart；Operator 由平台内部维护（管理员安装），实例由管理员和租户按需创建；平台与实例产出的资源在资源页面只读。

---

## 1. 范围

### 1.1 固定清单（V1 全部）

| code | 名称 | 部署方式 | Operator / Chart | 实例形态 |
|---|---|---|---|---|
| `mysql` | MySQL | Operator | Percona Operator for MySQL（PS，`ps.percona.com/v1`） | `SINGLE` / `CLUSTER`（Group Replication） |
| `redis` | Redis | Operator | OT-Container-Kit redis-operator | `STANDALONE` / `SENTINEL` / `CLUSTER` |
| `kafka` | Kafka | Operator | Strimzi（KRaft） | `CLUSTER`（brokers ≥ 3） |
| `elasticsearch` | Elasticsearch | Operator | ECK | `CLUSTER`（nodes ≥ 1，可选 Kibana） |
| `postgresql` | PostgreSQL | Operator | CloudNativePG | `SINGLE` / `HA`（instances ≥ 3） |
| `rabbitmq` | RabbitMQ | Operator | RabbitMQ Cluster Operator | `SINGLE` / `CLUSTER` |
| `nacos` | Nacos | Chart | 官方 `nacos` chart | `STANDALONE` / `CLUSTER`（**依赖一个平台 MySQL 实例**） |

> 上游版本在实现时钉死并记入表格；调研快照（2026-10-22）：Percona PS 1.2.0、OT-Container-Kit v0.27.0、Strimzi 1.3.0、CloudNativePG v1.30.1、RabbitMQ cluster-operator v2.23.1。

### 1.2 不做

- 对象存储（用户自行调研，后续单独立项）；
- 分库分表 / 分布式数据库（Vitess、TiDB、Polaroid-X 等）；
- 通用 chart 上架、来源搜索、任意 chart 安装；
- **MySQL 异步主从**（Percona PS 的 `clusterType: async` 是 tech preview，不进表单）；
- 备份/恢复界面、多版本并存、跨集群实例迁移；
- 租户安装 Operator。

### 1.3 与现有模块的边界

| 模块 | 关系 |
|---|---|
| S3.6 组件 | 基础设施组件（Prometheus/Thanos/Istio/Tekton 等）继续走组件 tab，不进商店 |
| 计划①的应用商店代码 | **下线**：`/app/catalog/**`、上架/下架、卡片网格、通用安装向导；`/chart/**` 执行链保留（Operator chart 与 Nacos 还要用） |
| CR 浏览页 | 保持只读；本模块创建的 CR 在 CR 页仍可查看，但不可编辑 |

---

## 2. 概念与状态

### 2.1 Operator（集群级，平台内部维护）

- 每个集群、每款中间件对应一条 Operator 记录；安装走 helm（chart 随 k8s-server 内置，离线可用）；
- **只有管理员**可安装/升级；租户看不到安装入口；
- 安装后判定（两者都满足才算已安装）：
  1. helm release 存在（`platform-middleware-<code>`，命名空间 `platform-middleware-system`）；
  2. 该 Operator 的关键 CRD 存在且 Established。
- 状态：`NOT_INSTALLED` / `INSTALLING` / `READY` / `FAILED` / `DEGRADED`（operator Deployment 不就绪时读时判定）。

### 2.2 实例

- 实例 = 一个 CR（Operator 类）或一个 helm release（Nacos）；
- 归属：`cluster_id + namespace + tenant_id`；命名空间必须在租户分配表内（管理员不受限）；
- 名称由平台生成：`mw-<code>-<8 hex>`（K8s 对象名）；显示名用户可填；
- 状态来自 CR 的 `status`（就绪副本/阶段/条件），Nacos 来自 helm release 状态 + Pod 就绪数。

### 2.3 形态（mode）

| code | mode | 语义 | 节点约束 |
|---|---|---|---|
| mysql | `SINGLE` | 单节点，无高可用 | 1 |
| mysql | `CLUSTER` | Group Replication，**单主强一致，全量复制** | 3 / 5（上限 9） |
| redis | `STANDALONE` | 单节点 | 1 |
| redis | `SENTINEL` | 主从 + 哨兵 | 主从各 ≥ 1，哨兵 ≥ 3 |
| redis | `CLUSTER` | Redis Cluster 分片 | ≥ 3 主，可配副本 |
| kafka | `CLUSTER` | KRaft | brokers ≥ 3 |
| elasticsearch | `CLUSTER` | 多节点 | nodes ≥ 1，生产 ≥ 3 |
| postgresql | `SINGLE` | 单实例 | 1 |
| postgresql | `HA` | 主备 | ≥ 3 |
| rabbitmq | `SINGLE` / `CLUSTER` | 单节点 / 多副本 | 1 / ≥ 3 |
| nacos | `STANDALONE` / `CLUSTER` | 单机 / 集群 | 1 / ≥ 3（集群必须选 MySQL 实例） |

> 平台不暴露"2 节点高可用"：GR 与 Galera 类的多数派在 2 节点下没有容错意义。

---

## 3. 数据模型

迁移：`V2026_10_22_1__middleware_store.sql`（幂等；本仓无 Flyway，手工重放）。

### 3.1 `platform_middleware_operator`

| 列 | 说明 |
|---|---|
| id | `mwo_<16 hex>` |
| cluster_id | 集群 |
| code | 中间件 code |
| chart_version | 安装时钉死的 chart 版本 |
| status | NOT_INSTALLED / INSTALLING / READY / FAILED / DEGRADED |
| message | 最近一次失败原因 / 判定说明 |
| installed_at / updated_at | |

唯一键：`(cluster_id, code)`。

### 3.2 `platform_middleware_instance`

| 列 | 说明 |
|---|---|
| id | `mwi_<16 hex>` |
| cluster_id / namespace | 位置 |
| code / mode | 中间件与形态（§2.3） |
| name | K8s 对象名（平台生成） |
| display_name | 用户填写 |
| version | 中间件版本（Operator 支持的版本，见 §4.3） |
| spec_json | 表单产出的规格（节点数、资源、存储、版本等，**不含明文密码**） |
| status | CREATING / READY / DEGRADED / FAILED / DELETING |
| message | 状态说明 |
| tenant_id | 租户（管理员创建时为 NULL） |
| created_by / created_at / updated_at / deleted_at | |

唯一键：`(cluster_id, namespace, name)`；索引 `(cluster_id, code, deleted_at)`、`(tenant_id)`。

### 3.3 `platform_middleware_operation`

| 列 | 说明 |
|---|---|
| id | bigint 自增 |
| cluster_id / instance_id | |
| action | CREATE / DELETE / SCALE / UPGRADE |
| status | PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN |
| message / log_tail | 失败原因与 helm/CR 日志尾 |
| operator / started_at / finished_at | |
| running_lock | 生成列：仅 PENDING/RUNNING 时为 `cluster_id|instance_id`，唯一键防并发 |

### 3.4 计划①的表

`platform_app` / `platform_app_operation` / `platform_app_catalog` **不删除**（避免误删数据），代码与端点下线，表中数据保留；后续单独清理。

---

## 4. Operator 安装链

### 4.1 形态

- chart 随 k8s-server 镜像内置：`charts/middleware/<code>/<chart-version>/`，与 S3.6 的 `charts/components/` 同构；
- 安装命名空间：`platform-middleware-system`（一次性创建，属平台命名空间）；
- release 名：`platform-middleware-<code>`；安装参数沿用 S3.6：`--rollback-on-failure --force-conflicts --timeout 10m`；
- 管理员路径 `strict=false`（Operator 需要 CRD/ClusterRole 等集群级对象，本来就会被静态检查标 WARN，不 BLOCK）。

### 4.2 安装前检查

- 目标集群可达；
- K8s 版本满足 Operator 要求（每款在目录里声明最低版本，如 Strimzi 1.x 要求较新 K8s）；
- 所需 CRD 不冲突（已存在且 `managed-by=Helm` 且 release 名不一致 → 拒绝，避免接管别人的 CRD）。

### 4.3 中间件版本

每款中间件在目录里声明**一个默认版本**与可选版本列表（如 MySQL 8.4、Redis 8.0 / 7.2、Kafka 4.x、ES 8.x、PG 16/17、RabbitMQ 4.x），实例表单只允许从列表里选。版本清单随代码走（`MiddlewareCatalog`），不落库。

---

## 5. 实例链

### 5.1 创建

```
前端（表单）
  → platform-api：权限 + 分配表 + Operator 已装 + 配额预检 + 名称生成
  → 写 platform_middleware_instance(CREATING) + operation(PENDING→RUNNING)
  → k8s-server：POST /middleware/{code}/instances（创建 CR / helm install）
  → 轮询/回读 CR status → 实例状态 READY / FAILED
```

- **密码/凭据**：由平台生成强随机值，写入同命名空间的 Secret（`<name>-credentials`），**不进 `spec_json`、不进审计、不回显**；详情页只显示"凭据 Secret 名称"。
- **资源**：表单必填 requests/limits（给默认值）；StorageClass 从集群现有列表选。
- **配额预检**：读命名空间 ResourceQuota，估算 `副本数 × requests/limits` 是否超限，超限直接拒绝并说明差多少（避免创建后 Pending）。
  实现位置在 **k8s-server**（唯一有集群访问的一侧）；且用 **admin client 读配额** —— 租户 SA 的 RBAC 模板没有 `resourcequotas`。
- **租户路径**：k8s-server 以租户身份创建 CR（现有租户 SA + RBAC），需要补各 CRD 的 apiGroup（见 §9.2）。

### 5.2 变更

V1 只开放两类变更：

| 操作 | 说明 |
|---|---|
| 扩容（SCALE） | 节点数、CPU/内存、存储容量（存储只增不减，依赖各 CRD 能力） |
| 删除（DELETE） | 删 CR / helm uninstall；提示 PVC 与数据是否保留（按中间件能力不同） |

不做版本升级按钮（Operator 版本升级时再评估 CR 兼容性）。

### 5.3 状态同步

- 列表页按需读取（不缓存超过 30s）；
- k8s-server 提供 `POST /middleware/{code}/status`（按实例名批量），api 侧汇总；
- 操作进行中的实例由操作记录驱动，完成即刷新。

### 5.4 统一实例列表

`POST /middleware/instance/list`：本集群全部实例（Operator 类 + Nacos），字段：类型、名称、命名空间、形态、版本、状态、创建人、创建时间。租户视图按分配表与 `tenant_id` 收窄。

---

## 6. 端点与权限

### 6.1 k8s-server（`/middleware/**`，PLATFORM 边界）

| 端点 | 说明 |
|---|---|
| `POST /middleware/operators/status` | 批量判定 Operator 安装状态（**UPSTREAM 边界**：集群级只读事实，租户也要看） |
| `POST /middleware/operators/install` | 安装/升级 Operator（helm） |
| `POST /middleware/{code}/instances` | 创建实例（CR 或 Nacos helm install） |
| `POST /middleware/{code}/instances/scale` | 扩容 |
| `POST /middleware/{code}/instances/delete` | 删除 |
| `POST /middleware/{code}/status` | 批量读 CR status |

每个 code 一份适配器（`MysqlMiddlewareOps`、`RedisMiddlewareOps`…），**不抽公共基类**（既有架构原则），适配器只做"表单规格 → CR 字段"的翻译与 status 映射。

### 6.2 platform-api（对前端）

| 端点 | 权限 |
|---|---|
| `POST /middleware/catalog` | `tenant:middleware:read`（清单 + 每集群 Operator 状态） |
| `POST /middleware/instance/list` / `/get` | `tenant:middleware:read` |
| `POST /middleware/instance/create` / `/scale` / `/delete` | `tenant:middleware:write` |
| `POST /middleware/operation/get` | `tenant:middleware:read` |
| `POST /middleware/operator/install` | `platform:middleware:manage`（管理员专属） |

- 共用能力用 `tenant:` 码并**显式绑定 admin**（既有不变量）；
- 页面码复用 `tenant:page:app`（路由 `/apps` 不变，标题与菜单改为"中间件"），避免为了改名再做一次页面码迁移；
- 审计：创建/扩容/删除/安装 Operator 全部 `@Audit`，记录实例 id、code、namespace（**不记凭据**）。

---

## 7. 资源保护规则

### 7.1 受管标记

| 来源 | 标记 |
|---|---|
| 本模块实例创建的 CR 及下级资源 | label `platform.coding/managed-by=middleware`、`platform.coding/middleware-instance=<instanceId>` |
| S3.6 组件安装的 release 资源 | label `app.kubernetes.io/managed-by=Helm` + `meta.helm.sh/release-namespace=platform-system`（组件 release 清单白名单由 api 侧下发） |
| Nacos（chart 实例） | 同"本模块实例"标记 |

判定顺序：先看 `platform.coding/managed-by`，再看 ownerReferences 是否指向受管 CR，最后看组件 release 白名单。

### 7.2 受限操作

- **编辑 / 伸缩 / 回滚 / 删除** 全部禁止；
- 覆盖的工作负载：Deployment / StatefulSet / DaemonSet / Job / CronJob / ReplicaSet / Pod；
- 覆盖的其他资源：Service / ConfigMap / Secret / Ingress / PVC / HPA / PDB；
- 前端：行内操作置灰 + tooltip「由平台组件/中间件实例管理，请到对应页面操作」；
- 后端：k8s-server 在 update/delete/patch/scale 前校验，返回 `RESOURCE_MANAGED_BY_PLATFORM`（前端统一提示），**不能只靠前端**。

### 7.3 例外

- 实例本身的删除走中间件页（§5.2），不受此限制；
- 平台自身（S3.6 组件页、中间件页）的写入路径直接调用 k8s 客户端，不经过保护校验。

---

## 8. 前端

路由 `/apps`（菜单名"中间件"）：

- **商店**：7 张固定卡片，图标/名称/一句话说明/形态徽标；
  - Operator 未安装 → 卡片禁用，提示「需要管理员先安装 X Operator」；管理员看到「安装」按钮；
  - 已安装 → 「创建实例」。
- **实例**：统一列表（§5.4），行操作：详情 / 扩容 / 删除。
- **Operator（仅管理员）**：各 code 的安装状态、版本、安装/升级按钮。
- 创建表单：按 `code + mode` 手写字段（不做通用 schema 表单）；密码字段不展示（平台生成）。
- 删除：二次确认，写明 PVC/数据处置方式。
- 下线：通用 chart 安装向导、目录上架/下架、来源搜索。

---

## 9. 部署与前提

### 9.1 迁移

先重放 `V2026_10_22_1__middleware_store.sql`，再起新构建（新端点无权限行会被 `PermissionCrossCheckRunner` 拒启）。

### 9.2 租户 RBAC

- 租户 SA 需要各中间件 CRD 的 apiGroup 权限（`ps.percona.com`、`redis.redis.opstreelabs.in` 或对应 group、`kafka.strimzi.io`、`elasticsearch.k8s.elastic.co`、`postgresql.cnpg.io`、`rabbitmq.com`）；
- 与既有 CRD 授权同一处修改，**必须"重下发全部模板"**才生效。

### 9.3 镜像与网络

- Operator 与中间件镜像来自 docker.io / quay.io / ghcr.io / registry.k8s.io；
- 集群需要镜像拉取通道（内网 registry 或镜像代理），`imagePullSecrets` 由安装表单/平台配置提供；
- chart 随镜像内置，安装本身不需要外网。

### 9.4 chart 体积

7 套 chart 进 k8s-server 镜像，预计增加 20~40MB；构建时校验总大小。

---

## 10. 测试

**单测（platform-api）**
- 目录与形态校验（不支持的 mode / 节点数组合 → 拒）；
- 分配表校验（租户越界 → 拒）；
- 配额预检估算（超限 → 拒并给出差额）；
- Operator 未安装 → 创建被拒；
- Nacos 集群未选 MySQL 实例 → 拒；
- 凭据不进 `spec_json`/审计；
- 权限码存在性（`PermissionCrossCheckRunner` 覆盖）。

**单测（k8s-server）**
- 每个适配器：表单规格 → CR 字段映射（表驱动）；
- status 映射（Ready/Progressing/Degraded → READY/DEGRADED/FAILED）；
- 保护规则判定（label / owner 链 / 组件白名单三种来源，表驱动）；
- 删除语义（PVC 保留与否按 code）。

**真集群（coding-cluster）**
1. 管理员装 MySQL Operator → 卡片可用 → 建单节点实例 → 建 3 节点集群实例；
2. 租户创建自己命名空间的 Redis 实例（验证 RBAC 与分配表）；
3. 删除实例，确认 PVC 处置符合提示；
4. 保护规则：实例产生的 StatefulSet / Service / PVC 在资源页只读，API 直接调用返回 `RESOURCE_MANAGED_BY_PLATFORM`；
5. Nacos 集群：选平台 MySQL 实例安装并验证启动。

---

## 11. 风险

| 风险 | 处理 |
|---|---|
| Operator 版本与集群 K8s 版本不兼容 | 目录声明最低 K8s 版本，安装前检查 |
| CRD 升级不可回退 | Operator 版本钉死；升级前评估 CRD 变更 |
| 中间件吃资源（ES/Kafka） | 表单强制 requests/limits + 配额预检 |
| 租户创建实例越界 | 分配表 + RBAC 双闸；实例台账记 tenant_id |
| Nacos 集群依赖 MySQL | 安装向导强制选一个平台 MySQL 实例；无实例时禁用并提示 |
| 保护规则误伤 | 标记由平台写入；判定顺序明确；错误码可识别 |
| 镜像拉不到 | 安装前做镜像可达性预检（逐个 image 探测 registry） |

---

## 12. 分批

| 批次 | 内容 |
|---|---|
| **批①** | MySQL / Redis / Kafka / ES + Nacos；Operator 安装链、实例链、统一列表、保护规则 |
| **批②** | PostgreSQL / RabbitMQ（复用批①的框架，只加适配器与表单） |

批①完成并真集群验证后再开批②。
