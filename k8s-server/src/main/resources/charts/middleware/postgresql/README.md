# PostgreSQL（中间件 code: `postgresql`）

> **这是组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/CRD 清单在 `0.29.1/README.md`。
> 使用与设计文档：`docs/development/middleware-store.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（CloudNativePG，CNPG） |
| Operator chart | `0.29.1`（appVersion 1.30.1），见 `0.29.1/`；release `platform-middleware-postgresql` → `platform-middleware-system` |
| CRD / apiGroup | `clusters.postgresql.cnpg.io`（v1） |
| 实例形态 | `SINGLE`（1 实例）/ `HA`（≥3 实例，一主多备，CNPG 自管 failover） |
| 凭据 | Operator 生成 `<实例名>-app`（user/password/host/port/dbname） |
| 业务指标 | instance manager **内置 exporter**，9187（见 §6） |
| 镜像 | Operator `ghcr.io/cloudnative-pg/cloudnative-pg`；实例镜像**不写 `imageName`**，用 Operator 自带默认 |

> 为什么不钉实例镜像：钉死会让「升级 Operator」和「实例 PostgreSQL 版本」脱钩，维护反而更麻烦。
> 要换 PG 大版本时走 Operator 升级流程，而不是给个别实例改 imageName。

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-postgresql-<8位随机>`，即 `Cluster` CR 名；
- **受管标签**：CR 带 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`；
- **Operator 监听**：CNPG 默认 cluster-wide，不需要额外 values；
- **租户 RBAC**：租户模板需要 `postgresql.cnpg.io`（`V2026_10_23_1`）+ 重下发全部模板；
- **不写平台口令**：`usesPlatformPassword=false`，凭据全由 Operator 生成（写一个没人引用的 Secret 只会误导）；
- **状态**：Pod 选择器是 `cnpg.io/cluster=<实例名>,cnpg.io/podRole=instance` —— **必须带 `podRole=instance`**，
  否则 initdb / join 的 Job Pod 也会被算进去（真集群：3 实例被数成 5 个，"永远未就绪"）。
- **默认账号有 CREATEDB**：CNPG 的 `app` 账号**不是 superuser**、默认也没有 CREATEDB，用户拿平台给的默认
  凭据 `CREATE DATABASE` 会得到 `permission denied to create database`（2026-10-27 实报）。平台在
  `bootstrap.initdb.postInitSQL` 里补一条 `ALTER ROLE app CREATEDB;`（那是 CNPG 少数几个以 superuser
  身份执行的位置），**只给 CREATEDB、不摊开 superuser**（`enableSuperuserAccess` 保持默认 false）。
  库名/属主显式写成 `app`，保证 ALTER ROLE 的对象与 operator 生成的账号一致。

## 3. 形态与 CR 的对应关系

| 形态 | CR 要点 |
|---|---|
| `SINGLE` | `instances: 1`；无容错 |
| `HA` | `instances: 3` 或 `5`；一主多备，failover 由 CNPG 自管，应用连 service 即可 |

- 存储：`spec.storage.size` + `storageClass`（留空 = 集群默认；平台按表单拼）；
- 资源：`spec.resources` requests/limits；
- **不要写 `spec.monitoring.enablePodMonitor`**：CNPG 0.29 已标 deprecated（"请自己管理 PodMonitor"），
  平台统一用 Service + ServiceMonitor。

## 4. 表单字段

命名空间、显示名、StorageClass、CPU/内存、存储、`replicas`（实例数：单实例固定 1、主备 ≥3）、
「采集业务指标」开关（默认关）。

## 5. 坑（真集群实锤，别回退）

1. **状态统计要过滤 `podRole`**（见 §2），否则 Job Pod 让实例永远判不成就绪。
2. **`monitoring.enablePodMonitor` 是 deprecated**：写了会有告警，且平台指标走的是自己的 SM。
3. **内存别小于 2Gi**：1Gi 实例在真集群验证时 OOM（与其它中间件同款问题）。
4. **删除时 PVC 的处置**：保留 PVC = 数据还在，下次同名实例可复用；不保留则 PVC 一起删。
   CNPG 的 PVC 名是 `pgdata-<实例名>-<序号>`，平台按实例标签清理。
5. 存储与形态**不能在线改**（CNPG 支持扩容 PVC，但平台没开放这个入口）；要改删实例重建。
6. **`bootstrap` 只在建库时执行一次**：`postInitSQL` 里的 CREATEDB 授权对**存量实例不生效**
   （CR 的 bootstrap 不可变）。存量实例手工补一次：
   `kubectl -n <ns> exec -it $(kubectl -n <ns> get cluster <实例名> -o jsonpath='{.status.currentPrimary}') -c postgres -- psql -U postgres -c 'ALTER ROLE app CREATEDB'`
   （`enableSuperuserAccess` 关闭时 `postgres` 仍可走本地 socket 免密登录）。

## 6. 业务指标

- 源：instance manager 内置的 exporter（容器端口名 `metrics` = **9187**），平台建
  `<实例名>-platform-metrics` Service + ServiceMonitor；
- Pod 选择器同状态判定（`cnpg.io/cluster=<实例名>,cnpg.io/podRole=instance`）；
- 指标定义在 `MiddlewareCatalog.POSTGRESQL_METRICS`（存活/连接/等待/事务提交与回滚/死锁/缓存命中率/
  最长事务/复制延迟/库大小）；
- **不需要额外边车**，所以开启指标（编辑里）不会重启 Pod。

## 7. 加一个新版本怎么做

**升级 CNPG Operator chart**：

1. `helm pull` 到 `charts/middleware/postgresql/<新版本>/chart/` + 新建 `<新版本>/README.md`；
2. 改 `MiddlewareOperatorCatalog`：版本、必需 CRD（`clusters.postgresql.cnpg.io`）、镜像；
3. 注意 CNPG 的 CRD 在 chart 的 `templates/` 里（安装即建，可随 upgrade 更新），但仍要对比字段变化
   （例如 `monitoring` 的弃用/移除）；
4. 跑测试 → 真集群装一次 → 建 SINGLE + HA 实例各一个，确认 failover 语义没变。

**升级实例 PostgreSQL 版本**：不要给个别实例写 `imageName`。CNPG 的实例版本跟随 Operator 的默认镜像；
换大版本是 Operator 升级 + 实例滚动升级，属于 DBA 级操作，平台目前不提供入口。

**加形态**：需要同时改 `MiddlewareCatalog.POSTGRESQL_MODES`、`instancesOf()`、状态判定的期望副本数。

## 用户管理（2026-10-26）

- 通道：**全声明式**（不连数据库）。用户 = `databaseroles.postgresql.cnpg.io` CR：
  `login: true` + `passwordSecret` + `inRoles`；口令存每用户一个 `kubernetes.io/basic-auth` Secret，
  CNPG 负责 reconcile 到数据库。
- 内置账号 = operator 的应用账号（`<实例名>-app`）；改它之前先验 operator 会不会回写。
- 角色：`只读` → `pg_read_all_data`；`读写` → 再加 `pg_write_all_data`。
- 删除只把 `ensure` 置 `absent`（CR 留着可回滚），同时删该用户的 Secret。
