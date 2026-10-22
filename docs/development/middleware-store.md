# 中间件商店

> 设计：[2026-10-22-middleware-store-design.md](../superpowers/specs/2026-10-22-middleware-store-design.md)
> 计划：[批①-1](../superpowers/plans/2026-10-22-middleware-store-batch1.md)、[批①-2](../superpowers/plans/2026-10-22-middleware-store-batch2.md)
> 状态：批①-1（底座 + Redis）与批①-2（MySQL/Kafka/ES + Nacos）已实施；PostgreSQL/RabbitMQ 在批②。

## 1. 可用的中间件

| 条目 | 类型 | 版本（chart） | 实例形态 | 凭据 |
|---|---|---|---|---|
| Redis | Operator（OT-Container-Kit） | 0.27.0 | 单节点 / 哨兵（数据节点 + 固定 3 哨兵）/ 集群分片 | 平台生成 → `<名称>-credentials` |
| MySQL | Operator（Percona PS） | 1.2.0 | 单节点 / 高可用集群（Group Replication） | Operator 生成 → `<名称>-secrets` |
| Kafka | Operator（Strimzi，KRaft） | 1.3.0 | 集群（brokers ≥3，NodePool 双角色） | 无（内网 plain 监听） |
| Elasticsearch | Operator（ECK） | 3.5.0 | 集群（nodeSet，count ≥1） | ECK 生成 → `<名称>-es-elastic-user` |
| Nacos | **Chart**（不是 Operator） | 1.2.0（appVersion 3.2.4） | 单节点 / 集群（内嵌 JRaft 存储） | chart 默认不鉴权 |
| PostgreSQL / RabbitMQ | — | — | 即将支持（批②） | — |

> Nacos 3.x 集群模式使用内嵌 JRaft 存储，**不需要外置 MySQL**。

## 2. 执行链

```
前端（中间件页）
  → platform-api：权限 + 形态校验 + Operator 就绪 + 租户归属 + 实例台账 + 操作记录
  → k8s-server：
      Operator 类：配额预检 + 写凭据（按需）+ 建 CR（MySQL/Kafka/ES 由各自 Operator 接管）
      Nacos：helm upgrade --install（release = 实例名）
  → 列表页逐条读实时状态（Pod 就绪；Nacos 以 Service 是否存在判断实例是否存在）
```

- Operator 安装：helm `platform-middleware-<code>` → 命名空间 `platform-middleware-system`，chart 随 k8s-server 镜像内置；
- 判定：必需 CRD 齐全 + Operator Deployment 就绪；安装前做 **CRD 归属预检**（他人 release / 非 Helm 管理一律拒）。

## 3. 部署

- 批①-2 **无新表、无新权限行**：只需重建 k8s-server + platform-api + platform-web；
- 批①-1 的迁移（`V2026_10_22_1`）与页面码绑定（`V2026_10_22_4`）仍是一次性的；
- 集群需要能拉取的镜像：

| 中间件 | 镜像来源 |
|---|---|
| Redis | `quay.io/opstree/redis-operator`、`quay.io/opstree/redis` |
| MySQL | `percona/percona-server-mysql-operator`、`percona/percona-server:8.4`、`percona/percona-haproxy` |
| Kafka | `quay.io/strimzi/operator`、`quay.io/strimzi/kafka` |
| Elasticsearch | `docker.elastic.co/eck/eck-operator`、`docker.elastic.co/elasticsearch/elasticsearch` |
| Nacos | `nacos/nacos-server:v3.2.4` |

## 4. 常见失败

| 现象 | 原因 |
|---|---|
| 卡片「未装 Operator」 | 还没装或安装失败（Operator tab 看说明） |
| 安装 Operator 报 CRD 被占用 | 集群里已有别人装的同名 CRD；先清理或改用平台安装 |
| 创建实例 403 | 租户 RBAC 模板没重下发（批①-1 部署第 2 步） |
| 「命名空间配额不足」 | ResourceQuota 的 requests/limits 不够；提示里给了差额 |
| MySQL 一直 Degraded | 组复制成员数不足或 PVC 不可调度；看 CR 的 `status` 与 Pod 事件 |
| Kafka 起不来 | 检查 NodePool 是否有 `strimzi.io/node-pools: enabled` 注解（平台已自动加） |
| ES 拒绝创建 | `version` 不在 ECK 支持范围；换一个受支持的版本 |
| Nacos 一直 Pending | 存储未配（`persistence.enabled=false` 时用 emptyDir）或镜像拉不到 |

## 5. 已知限制

- 实例列表逐条读实时状态（多实例线性变慢）；
- 前端暂无「详情 / 扩容」入口（后端 scale 已支持 Redis/MySQL/Kafka/ES；Nacos 显式拒绝扩容）；
- 中间件版本清单未做成可选列表（Kafka/ES 版本是文本框，MySQL/Redis 镜像固定）。
