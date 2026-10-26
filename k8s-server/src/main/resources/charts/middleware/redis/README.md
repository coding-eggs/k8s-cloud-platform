# Redis（中间件 code: `redis`）

> **这是组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体某个 chart 版本的来源/digest/CRD 清单在版本目录里：`<version>/README.md`。
> 使用与设计文档：`docs/development/middleware-store.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（OT-Container-Kit redis-operator） |
| Operator chart | `0.27.0`（见 `0.27.0/`），release `platform-middleware-redis` → `platform-middleware-system` |
| CRD / apiGroup | `redis` / `redisreplications` / `redissentinels` / `redisclusters`，组 `redis.redis.opstreelabs.in` |
| 实例形态 | `STANDALONE`（单节点）/ `SENTINEL`（主从 + 固定 3 哨兵）/ `CLUSTER`（分片，≥3 主） |
| 凭据 | 平台生成密码 → Secret `<实例名>-credentials`（键 `password`） |
| 业务指标 | `redisExporter` 边车，9121（见 §6） |
| 镜像 | 数据 `quay.io/opstree/redis:v7.0.15`；哨兵 `quay.io/opstree/redis-sentinel:v7.0.15`；exporter `quay.io/opstree/redis-exporter:v1.44.0` |

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-redis-<8位随机>`（台账 id 是 `mwi_…`）；实例名就是 CR 名。
- **受管标签**：平台建的 CR 与资源都带 `platform.coding/managed-by=middleware` +
  `platform.coding/middleware-instance=<实例 id>`。资源页因此只读（`ManagedResourceGuard`），
  要改请回中间件页（编辑/删除）。
- **Operator**：`MiddlewareOperatorCatalog` 固定 release 名 / chart 版本 / 必需 CRD；
  安装前做 **CRD 归属预检**（别人 release 管的、非 Helm 管的都拒）。CRD 在 chart 的 `crds/` 目录里，
  **`helm upgrade` 不会更新 CRD**。
- **租户**：租户 SA 需要 `redis.redis.opstreelabs.in` 这个 apiGroup 的规则（`V2026_10_22_5`）；
  改完迁移必须点一次「重下发全部模板」，否则租户建实例 403。
- **状态**：列表/详情都**现读集群**（`CREATING/READY/DEGRADED/FAILED/DELETING` 以台账为起点，
  非删除中一律先刷新）。判定口径见 `RedisMiddlewareAdapter#status`：按平台实例标签找 Pod +
  CR 的 `status`；**哨兵 Pod 的 app 标签是 `<实例名>-sentinel`，不要按 `app=<实例名>` 查**。
- **配额**：创建前做 ResourceQuota 预检（admin 读），差额会写进报错。

## 3. 形态与 CR 的对应关系

| 形态 | 建的 CR | 要点 |
|---|---|---|
| `STANDALONE` | `Redis` | 单 Pod；`replicas` 固定 1 |
| `SENTINEL` | `RedisReplication` + `RedisSentinel` | 数据节点 `clusterSize ≥ 2`；哨兵固定 **3**，`redisSentinelConfig.redisReplicationName` 指向同一个 RedisReplication |
| `CLUSTER` | `RedisCluster` | 主分片 `clusterSize ≥ 3`；`redisFollower.replicas` 是**从节点总数**（不是每分片），平台按 `分片数 × 每分片副本数` 计算并把每分片数记在注解 `platform.coding/redis-replicas-per-shard`，扩容时用它重算 |

## 4. 表单字段

命名空间、显示名、StorageClass、CPU/内存、存储大小、`replicas`（标签随形态变：主节点数 / 数据节点数）、
集群形态额外有「每个主节点的副本数」（默认 1，0 = 不配备份）、「采集业务指标」开关（默认关）。

## 5. 坑（真集群实锤，别回退）

1. **哨兵必须用 `redis-sentinel` 镜像**：用 `redis` 镜像会以 standalone 起来，30s 后被探针判死循环重启。
   镜像通过 `kubernetesConfig.image` 下发（数据节点仍用 `redis` 镜像）。
2. **哨兵 Pod 上没有 exporter**：构造 RedisSentinel 的 spec 时必须删掉 `redisExporter`，
   否则 Operator 会往哨兵 Pod 里塞一个连不上数据的 exporter。
3. **exporter 必须注入 `REDIS_PASSWORD`**（secretKeyRef 到 `<实例名>-credentials` 的 `password`），
   否则 `redis_up=0`，指标全空。
4. **采集资源的 Service 不能叫 `<实例名>-metrics`**：Operator 自己就建了同名 Service，
   会把平台的 label 覆盖掉 → ServiceMonitor 选不到。统一用 `<实例名>-platform-metrics`。
5. **fabric8 的 `withLabelSelector`/`withLabel` 语义坑**：集合式 selector 只命中一部分；
   单参 `withLabel` 是「key 存在」不是「等于」。按实例找 Pod 一律用平台标签 `platform.coding/middleware-instance`。
6. 扩容走 SSA：先清 `metadata.managedFields`，否则 400 `must be nil`。
7. 存储与形态**不能在线改**（PVC 扩容依赖 Operator 能力），要改请删实例重建（可保留数据卷）。

## 6. 业务指标

- 源：`redisExporter` 边车（9121），平台建 Service（`<实例名>-platform-metrics`，端口名 `metrics`）+
  ServiceMonitor（selector `platform.coding/middleware-monitor=<service 名>`）；
- `MetricsSpec` 的 Pod 选择器用 `app=<实例名>`（数据 Pod），**哨兵 Pod 不采**；
- 指标定义（PromQL）在 `platform-api` 的 `MiddlewareCatalog.REDIS_METRICS`；
  形态按语义分 `CHART`（命中率/命令/过期/驱逐）/ `STAT`（连接/内存/键/阻塞）/ `STATE`（存活/持久化）；
- 集群没有 ServiceMonitor CRD 时自动跳过（best-effort，不阻断建实例）。

## 7. 加一个新版本怎么做

**升级 Operator chart**：

1. `helm pull` 新版本到 `charts/middleware/redis/<新版本>/chart/`（原样入库），新建 `<新版本>/README.md`
   记录来源/digest/CRD 清单（照抄同目录现有文件的结构）；
2. 更新 `k8s-server` 的 `MiddlewareOperatorCatalog`：版本号、必需 CRD 名单、监听 values（若有变化）；
3. 对比新旧 chart 的 `crds/` —— **Helm 不会升级 CRD**，破坏性变更要单独写迁移步骤；
4. 跑 `mvn test`（`ComponentRecipeCatalogTest` 那条跨模块校验会检查 chart 目录存在）→ 真集群装一次 →
5. 更新本文件的「镜像」「CRD」行与版本目录。

**升级实例镜像（数据版本）**：

1. 镜像常量在 `RedisMiddlewareAdapter`（`DEFAULT_IMAGE` / `SENTINEL_IMAGE` / `EXPORTER_IMAGE`）；
2. 换版本后**存量实例不会自动升级**（`applyScale` 不碰 image）—— 需要删实例重建，README 里要提醒数据迁移；
3. 真集群至少验证一次：单节点 + 哨兵 + 集群三种形态各建一个、看指标出数、删掉。

**加新形态**：需要同时改 `MiddlewareCatalog` 的 modes、adapter 的 CR 构造与 status 判定、
`MiddlewareScaleRequestDTO` 的校验，以及本文件 §3 的表格。

## 用户管理（2026-10-26）

- **只支持改口令**（opstree 不管理 ACL 用户）。
- 改 `<实例名>-credentials` 的 `password` → 按平台标签/名字前缀滚动重启 STS。
- ⚠️ 真集群要验 operator 会不会把 Secret 回写成旧值；回写就把 redis 从支持清单去掉。
