# 中间件商店（批①-1：底座 + Redis）

> 设计：[2026-10-22-middleware-store-design.md](../superpowers/specs/2026-10-22-middleware-store-design.md)
> 计划：[2026-10-22-middleware-store-batch1.md](../superpowers/plans/2026-10-22-middleware-store-batch1.md)
> 本批只有 Redis 走通；MySQL / Kafka / ES / Nacos 在批①-2，PostgreSQL / RabbitMQ 在批②。

## 1. 范围

| 能力 | 状态 |
|---|---|
| 固定清单（7 个条目） | ✅（其余条目显示「即将支持」） |
| Operator 安装/升级（管理员，helm，chart 内置） | ✅ Redis |
| 实例创建 / 列表 / 删除 / 实时状态 | ✅ Redis（单节点 / 哨兵 / 集群） |
| 扩容（节点数/资源） | 后端已支持，前端暂未开放入口 |
| 租户创建实例 | ✅（需先重放 RBAC 模板迁移并「重下发全部模板」） |

## 2. 执行链

```
前端（中间件页）
  → platform-api：权限 + 形态校验 + Operator 就绪 + 租户归属 + 实例台账 + 操作记录
  → k8s-server：分配表/身份（ResourceAccessResolver）+ 配额预检 + 写凭据 Secret + 建 CR
  → Operator 接管 CR，拉起 StatefulSet/Pod
  → 列表页逐条读 k8s 实时状态（读失败保留台账值）
```

- **Operator 安装**：`helm upgrade --install platform-middleware-<code> -n platform-middleware-system`，
  chart 从 k8s-server 内置的 `charts/middleware/<code>/<chart-version>/chart` 取，离线可用；
- **判定**：必需 CRD 齐全 + 该 release 的 Operator Deployment 就绪；
- **凭据**：平台生成随机密码，写进 `<实例名>-credentials` Secret；台账与审计都不含密码。

## 3. Redis 三种形态（OT-Container-Kit v0.27.0）

| 形态 | CR | 说明 |
|---|---|---|
| 单节点 | `Redis` | 开发/测试 |
| 哨兵主从 | `RedisReplication` + `RedisSentinel` | 数据节点数由用户选，**哨兵固定 3** |
| 集群分片 | `RedisCluster` | `clusterSize` = 主分片数，≥3 |

## 4. 部署

1. **重放迁移** `V2026_10_22_1__middleware_store.sql`：三张表 + 8 条权限行 + 角色绑定 + Redis CRD 的租户 RBAC 模板规则；
2. **重下发全部模板**：`POST /rbacTemplate/sync-all`（或 RBAC 模板页按钮）—— 不做这一步，租户创建实例会 403；
3. 重建 **platform-data → platform-common → k8s-server → platform-api → platform-web**；
4. 管理员进入「中间件」→ Redis 卡片 → **安装 Operator**（第一次会拉取 `quay.io/opstree/redis-operator`，集群需能访问 quay.io 或配置镜像代理）；
5. 卡片变为「可用」后创建实例。

## 5. 常见失败

| 现象 | 原因 |
|---|---|
| 卡片「未装 Operator」 | 还没装，或 install 失败（看 Operator tab 的说明） |
| 安装 Operator 报镜像拉取失败 | 集群拉不到 quay.io；配镜像代理或 `imagePullSecrets` |
| 创建实例 403 | 租户 RBAC 模板没重下发（见部署第 2 步） |
| 「命名空间配额不足」 | ResourceQuota 的 `requests.cpu/memory` 不够；提示里给了差额 |
| 实例一直「创建中」 | CR 已建但 Pod 未就绪；看命名空间里的 Pod 事件 |
| 删除后 PVC 还在 | 删除时勾了「保留数据卷」（默认勾选） |

## 6. 已知限制

- 前端暂未提供扩容入口（后端 `/middleware/instance/scale` 已可用）；
- 实例状态实时读取是逐条调用，实例很多时会线性变慢；
- Operator 升级只做 chart 升级；CRD 变更需人工评估（Helm 不更新 `crds/` 目录）。
