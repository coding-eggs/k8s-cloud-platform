# 中间件商店

> 设计：[2026-10-22-middleware-store-design.md](../superpowers/specs/2026-10-22-middleware-store-design.md)
> 计划：[批①-1](../superpowers/plans/2026-10-22-middleware-store-batch1.md)、[批①-2](../superpowers/plans/2026-10-22-middleware-store-batch2.md)
> 状态：批①-1（底座 + Redis）与批①-2（MySQL/Kafka/ES + Nacos）已实施，**2026-10-23 真集群验证**（redis 单节点/哨兵、
> mysql 单节点、kafka 三 broker、nacos 单节点均跑通；ES 待集群里 51 天前的旧 ECK 资源处理后再验）；PostgreSQL/RabbitMQ 在批②。

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
  → 列表页按 (类型, 命名空间) 批量读实时状态（Pod 就绪；Nacos 以 helm release 是否存在判断实例是否存在）
```

- Operator 安装：helm `platform-middleware-<code>` → 命名空间 `platform-middleware-system`，chart 随 k8s-server 镜像内置；
- 判定：必需 CRD 齐全 + 本 release 清单里的 Deployment/StatefulSet 就绪（不同 chart 的标签各不相同，故按 `helm get manifest` 解析）；安装前做 **CRD 归属预检**（他人 release / 非 Helm 管理一律拒）。

## 3. 部署

- 批①-2 **无新表、无新权限行**，但新增了一条租户 RBAC 迁移 `V2026_10_22_5`（补
  `ps.percona.com` / `kafka.strimzi.io` / `elasticsearch.k8s.elastic.co` 三个 apiGroup）；
  **重放后必须点一次「重下发全部模板」**，否则租户创建这三类实例会 403；
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

- 前端暂无「详情 / 扩容」入口（后端 scale 已支持 Redis/MySQL/Kafka/ES；Nacos 显式拒绝扩容）；
- 中间件版本清单未做成可选列表（Kafka/ES 版本是文本框，MySQL/Redis 镜像固定）。
