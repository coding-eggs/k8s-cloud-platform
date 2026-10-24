# MySQL（中间件 code: `mysql`）

> **这是组件级 README（跨版本）**：平台怎么建实例、约定是什么、踩过哪些坑、加新版本要改哪里。
> 具体 chart 版本的来源/CRD 清单在版本目录里：`1.2.0/README.md`。
> 使用与设计文档：`docs/development/middleware-store.md`。

## 1. 概览

| 项 | 值 |
|---|---|
| 部署方式 | Operator（Percona Operator for MySQL，**PS，不是 PXC**） |
| Operator chart | `1.2.0`（见 `1.2.0/`），release `platform-middleware-mysql` → `platform-middleware-system` |
| CRD / apiGroup | `perconaservermysqls.ps.percona.com` |
| 实例形态 | `SINGLE`（单节点 + HAProxy）/ `CLUSTER`（Group Replication，≥3，单主写入） |
| 凭据 | Operator 生成 → Secret `<实例名>-secrets`（含 `root` / `monitor` 等键） |
| 业务指标 | `mysqld_exporter` 边车，9104（见 §6） |
| 镜像 | 数据 `percona/percona-server:8.4`；入口 `percona/percona-xtradb-cluster-operator:1.14.1-haproxy`；exporter `prom/mysqld-exporter:v0.15.1` |

> 明确不提供：**异步主从**（PS 的 `clusterType: async` 是 tech preview）、多主。
> 需要经典主从改 Moco、需要多主改 PXC —— 那是换 Operator，不是加一个形态。

## 2. 平台约定（所有版本都适用）

- **实例名**：`mw-mysql-<8位随机>`，即 CR 名；
- **受管标签**：CR 带 `platform.coding/managed-by=middleware` + `platform.coding/middleware-instance=<id>`，
  资源页只读；
- **Operator**：release/命名空间固定；**必须下发 `watchAllNamespaces: true`** —— chart 默认只 watch 自身
  命名空间，不下发这行租户 ns 的 CR 没人 reconcile；
- **租户 RBAC**：租户模板需要 `ps.percona.com` 这个 apiGroup（`V2026_10_22_5`）+ 重下发全部模板；
- **状态**：Pod 就绪 + CR `status`；`Degraded` 通常是组复制成员不够或 PVC 不可调度，先看 CR conditions。

## 3. 形态与 CR 的对应关系

| 形态 | CR 要点 |
|---|---|
| `SINGLE` | `mysql.size: 1`；**必须** `unsafeFlags.mysqlSize/proxySize: true`（单成员组复制在 Percona 眼里是"不安全"的，不写会拒）；`proxy.haproxy.size: 1` |
| `CLUSTER` | Group Replication，`mysql.size ≥ 3`（建议 3/5），单主写入；扩副本时**必须同时扩 `proxy.haproxy.size`** |

- `proxy.router.enabled: false`（与 HAProxy 互斥，平台只用 HAProxy）；
- HAProxy **没有** `percona/percona-haproxy` 这个仓库 —— 入口镜像用 PXC Operator 的 `-haproxy` tag
  （`percona/percona-xtradb-cluster-operator:1.14.1-haproxy`），且必须显式给 `image`（CRD 校验要求）。

## 4. 表单字段

命名空间、显示名、StorageClass、CPU/内存、存储、`replicas`（单实例固定 1、集群 ≥3）、
「采集业务指标」开关（默认关）。单节点形态**禁止扩容副本**（后端显式拒）。

## 5. 坑（真集群实锤，别回退）

1. **配额 + LimitRange**：Operator 给 HAProxy Pod 追加 `haproxy-init` / `mysql-monit` 侧车，这些容器
   **没有 resources 字段**；命名空间里配了 `limits.*` 的 ResourceQuota 时，Pod 会一直 `FailedCreate`。
   解决办法是命名空间必须有 default LimitRange（平台新建 ns 会自动补，手工建的别忘了）。
2. **HAProxy 镜像**：见 §3，没有 `percona/percona-haproxy`。
3. **单节点不是"缩到 1 的集群"**：要写 `unsafeFlags`，否则 CR 被 Operator 拒。
4. **扩副本要一起扩 proxy**，否则入口只连到部分节点。
5. 组复制只写主库：应用连接串走 HAProxy，不要在应用侧写死 Pod 名。
6. **存储与形态不能在线改**，要改请删实例重建（保留 PVC）。
7. exporter 用 `monitor` 用户（`<实例名>-secrets` 的 `monitor` 键），密码进 `MYSQLD_EXPORTER_PASSWORD`。

## 6. 业务指标

- 源：`spec.mysql.sidecars` 里追加 `mysqld_exporter`（9104），参数 `--mysqld.address=127.0.0.1:3306` /
  `--mysqld.username=monitor`；密码从 `<实例名>-secrets` 的 `monitor` 键注入；
- **ServiceMonitor 的 Pod 选择器是 `app.kubernetes.io/instance=<实例名>,app.kubernetes.io/component=database`**
  —— Percona 数据 Pod 的 `component` 是 `database`（不是 `mysql`，真集群验证确认），HAProxy 也没开 9104；
- 状态判定的 selector 只用 `app.kubernetes.io/instance=<实例名>`（数据 Pod + HAProxy 都算实例的一部分：
  HAProxy 挂了入口就不通）；
- 指标定义在 `MiddlewareCatalog.MYSQL_METRICS`（连接/使用率/QPS/慢查询/缓冲池命中/锁等待/中断/组复制流控）。

## 7. 加一个新版本怎么做

**升级 Operator chart**：

1. `helm pull` 到 `charts/middleware/mysql/<新版本>/chart/`，新建 `<新版本>/README.md`（来源/digest/CRD）；
2. 改 `MiddlewareOperatorCatalog`：版本、必需 CRD、`watchAllNamespaces`（若 chart 默认变了）；
3. 核对 chart 的 `crds/` 差异（Helm 不升级 CRD），破坏性变更要写迁移步骤；
4. 跑 `mvn test` → 真集群装一次 → 建一个单节点 + 一个集群实例验证。

**升级数据镜像**：`MysqlMiddlewareAdapter` 的 `IMAGE` / `HAPROXY_IMAGE` / `EXPORTER_IMAGE`。
存量实例不会自动升级镜像，删了重建才换版本，要提醒用户备份。

**加形态**（如多主 / 异步主从）：不是加 mode 就行 —— PS 的异步主从是 tech preview 明确不进表单；
真要做要换 Operator（Moco / PXC），等于新增一个中间件 code。
