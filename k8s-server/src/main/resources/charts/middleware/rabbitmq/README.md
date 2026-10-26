# RabbitMQ（中间件 code: `rabbitmq`）

> **这是组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/CRD 清单在 `4.4.34/README.md`。
> 使用与设计文档：`docs/development/middleware-store.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（官方 RabbitMQ Cluster Operator，**Bitnami 打包**） |
| Operator chart | `4.4.34`（appVersion 2.16.1），见 `4.4.34/`；release `platform-middleware-rabbitmq` → `platform-middleware-system` |
| CRD / apiGroup | `rabbitmqclusters.rabbitmq.com` |
| 实例形态 | `SINGLE`（1）/ `CLUSTER`（≥3，Quorum 队列要多数派） |
| 凭据 | Operator 生成 `<实例名>-default-user`（用户名/口令） |
| 业务指标 | `rabbitmq_prometheus` 插件（**默认开、免鉴权**），15692（见 §6） |
| 镜像 | Operator `docker.io/bitnamilegacy/rabbitmq-cluster-operator:2.16.1-debian-12-r0`；凭据更新器 `bitnamilegacy/rmq-default-credential-updater`；Broker `docker.io/library/rabbitmq:4.1.3` |

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-rabbitmq-<8位随机>`，即 `RabbitmqCluster` CR 名；
- **受管标签**：CR 带 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`；
- **Operator 内容**：只装 **cluster operator**（`msgTopologyOperator.enabled=false`）—— 拓扑 Operator 会多拉两个镜像，
  平台不提供拓扑类对象；
- **租户 RBAC**：租户模板需要 `rabbitmq.com`（`V2026_10_23_1`）+ 重下发全部模板；
- **状态**：Pod 选择器 `app.kubernetes.io/name=<实例名>,app.kubernetes.io/component=rabbitmq`
  （Cluster Operator 给 Pod/StatefulSet/PVC 打的是 `app.kubernetes.io/name=<集群名>`，
  **不是** `<名称>-rabbitmq`）；期望副本数取 `spec.replicas`。

## 3. 形态与 CR 的对应关系

| 形态 | CR 要点 |
|---|---|
| `SINGLE` | `replicas: 1`；无容错；禁止改节点数 |
| `CLUSTER` | `replicas ≥ 3`（Quorum 队列要多数派）；扩容时平台强制 `Math.max(3, replicas)` |

- 存储：`spec.persistence`（StorageClass + 容量，按表单拼）；
- 资源：`spec.resources` requests/limits；
- 不需要写任何 metrics 配置（见 §6）。

## 4. 表单字段

命名空间、显示名、StorageClass、CPU/内存、存储、`replicas`（节点数：单节点固定 1、集群 ≥3）、
「采集业务指标」开关（默认关）。

## 5. 坑（真集群实锤，别回退）

1. **镜像必须显式覆盖**：Bitnami 2025 起把历史 tag 从 `bitnami/*` 挪到 `bitnamilegacy/*`，
   chart 默认值（`bitnami/rabbitmq-cluster-operator:2.16.1-debian-12-r0`）已经 manifest unknown。
   平台在 Operator 安装 values 里把三个镜像都改成 `bitnamilegacy/*` / `library/rabbitmq`，
   并设 `global.security.allowInsecureImages: true`（否则 chart 拒绝非官方仓库的镜像）。
2. **PVC/Pod 的标签是 `<集群名>` 而不是 `<集群名>-rabbitmq`**：清理 PVC 与查状态都按
   `app.kubernetes.io/name=<实例名>`。
3. **单节点不能扩副本**：后端显式拒（"要扩容请删除后按「集群」重建"），否则会得到 2 节点的伪集群。
4. **集群形态必须 ≥3**：2 节点没有多数派，Quorum 队列会不可用。
5. 存储与形态**不能在线改**；要改删实例重建（保留 PVC = 数据/元数据保留）。

## 6. 业务指标

- `rabbitmq_prometheus` 是 Cluster Operator 的必备插件，**默认启用且免鉴权**（15692），CR 不需要额外配置；
- 平台只建 `<实例名>-platform-metrics` Service + ServiceMonitor，Pod 选择器与状态判定一致；
- 指标定义在 `MiddlewareCatalog.RABBITMQ_METRICS`（就绪/未确认消息、连接/信道、队列/消费者、
  发布/投递/重投、资源告警）；
- 开启指标（编辑里）**不会重启 Pod**（不需要边车）。

## 7. 加一个新版本怎么做

**升级 Operator chart**：

1. `helm pull` 到 `charts/middleware/rabbitmq/<新版本>/chart/` + 新建 `<新版本>/README.md`；
2. 改 `MiddlewareOperatorCatalog` 里 rabbitmq 的 values：
   - **重新核对三个镜像 tag**（`bitnamilegacy` 里未必有新版；没有就得自己镜像化）；
   - `msgTopologyOperator` 仍然关闭；
3. 改版本号与必需 CRD；
4. 跑测试 → 真集群装一次 → 建单节点/集群各一个 → 看 15692 指标出数。

**升级 Broker（RabbitMQ）版本**：`rabbitmqImage.tag`（现在是 `library/rabbitmq:4.1.3`）。
大版本升级（如 3.x → 4.x）有 Erlang/特性兼容问题，要先在测试集群按 Operator 官方步骤滚动升级，
平台不提供入口；存量实例不会自动换镜像。

## 用户管理（2026-10-26）

- 通道：Management API（`http://<实例名>.<ns>.svc:15672`），凭据取 `<实例名>-default-user`。
- 角色：`只读`（read=.* 其余空）/ `读写`（三项全 .*）/ `管理员`（再加 administrator tag）；
  vhost 固定 `/`。
- 改口令保留原有 tag（丢了内置账号会丢管理权限）。
