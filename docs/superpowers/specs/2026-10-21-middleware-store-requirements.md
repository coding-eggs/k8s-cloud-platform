# 中间件商店与实例管理 · 需求整理（2026-10-21）

> 设计定稿见 [2026-10-22-middleware-store-design.md](2026-10-22-middleware-store-design.md)；两者冲突以设计文档为准。

> 本文件是**需求整理稿**，不是设计终稿。来源：2026-10-21 用户口述需求 + 当场调研结论。
> 相关设计：[2026-10-21-app-store-and-operator-design.md](2026-10-21-app-store-and-operator-design.md)（其中"通用 chart 商店"部分作废）。

---

## 0. 一句话

应用商店不做通用 chart 货架，改成**固定清单**：能用 Operator 的中间件一律用 Operator 部署（平台内部维护 Operator，用户不能自己装），只有 Nacos 用 chart；所有实例在一张列表里可查；平台/商店创建的资源在工作负载等页面禁止修改。

---

## 1. 范围与清单

### 1.1 商店固定条目（V1）

| 类型 | 条目 | 实现 | 实例形态 |
|---|---|---|---|
| Operator | MySQL | **Percona Operator for MySQL（PS，非 PXC）** | 单节点 / 主从 |
| Operator | Redis | **OT-Container-Kit redis-operator** | 单节点 / 哨兵 / 集群 |
| Operator | Kafka | **Strimzi** | KRaft 集群（N 节点） |
| Operator | Elasticsearch | **ECK** | N 节点 + 可选 Kibana |
| Operator | PostgreSQL | **CloudNativePG** | 单实例 / 主备 |
| Operator | RabbitMQ | **RabbitMQ Cluster Operator** | 单节点 / 集群 |
| Operator | 对象存储 | **SeaweedFS（operator）** | 集群级一套 + 桶分配（见 §1.3） |
| Chart | Nacos | 官方 chart（`nacos-k8s`） | 单机 / 集群（集群依赖外置 MySQL） |

不在本版：通用 chart 上架、Artifact Hub / Harbor 来源搜索、任意 chart 安装、Operator CR 的通用 YAML 编辑。

### 1.2 明确不做

- 用户（含租户管理员）自己安装 Operator；
- 通用 chart 安装向导（计划①的"手动安装 chart"入口下线）；
- 备份/恢复、跨集群灾备；
- 中间件版本的多版本并存选择（每款钉一个版本，升级由平台统一做）。

---

## 2. 关键流程

### 2.1 Operator 生命周期（平台内部维护）

- Operator 由平台按固定版本、固定 values 安装，**只有管理员**能操作；
- 未安装 Operator 的集群，对应商店卡片处于**禁用**状态，并给出明确提示：
  「该中间件需要先安装 MySQL Operator（管理员操作）」；
- Operator 安装状态按集群记录（见 §4），并可用 CRD 存在性做事实校验；
- Operator 升级只走平台侧，不开放给用户。

### 2.2 商店卡片

- 卡片 = 固定清单里的一项（图标 + 名称 + 一句话说明 + 状态）；
- 卡片状态三种：**可用**（Operator 已装）/ **需要先装 Operator**（禁用）/ **实例已存在 N 个**（可继续创建）；
- 点卡片 → 创建实例表单（手写字段，不做通用 schema 表单）。

### 2.3 实例创建与查看

- 实例 = Operator 的 CR（如 `RedisCluster`、`Kafka`、`InnoDBCluster`）或 Nacos 的 Helm release；
- **所有实例都能创建**：管理员平台视图与租户视图都可创建；租户只能在**分配表内的命名空间**创建，管理员不受限；
- 表单字段按中间件手写：命名空间、副本数/模式、CPU/内存、存储大小与 StorageClass、版本（固定）、账号密码（Secret）；
- 实例状态来自 CR 的 `status`（就绪副本、阶段、条件）；
- 删除实例 = 删除 CR（Operator 负责级联）；数据卷是否保留按中间件能力单独说明。

**"租户也能建实例"的连带要求**：

1. 租户 SA 的 K8s RBAC 模板要补各中间件 CRD 的 apiGroup（与 `tenant-rbac-template-crd-groups` 同一类改动，需"重下发全部模板"才生效）；
2. 实例台账（`platform_app` 复用）要记录 `tenant_id`，列表/详情按分配表收窄；
3. 命名空间的 ResourceQuota 会约束实例（副本 × requests），表单要给默认 requests/limits 并明确提示；
4. 实例的删除权限归创建者所在租户；但**实例创建出来的工作负载等资源仍受 §3 保护规则约束**（删实例走实例页，不走资源页）。

### 2.4 统一实例列表

- 一个列表展示本集群全部中间件实例：类型、名称、命名空间、模式（单节点/哨兵/集群）、版本、状态、创建人/时间、操作；
- Operator 实例与 Nacos chart 实例同表展示，用类型列区分。

---

## 3. 资源保护规则（工作负载与其他资源）

目标：**平台组件创建的资源**与**商店实例创建的资源**，在工作负载等通用资源页面**禁止修改**（只读）。

| 来源 | 判定依据（建议） | 受限操作 |
|---|---|---|
| CR 实例（现状已限制） | 对象属于已注册的 CRD kind | 追加到同一套规则里 |
| 平台组件（S3.6）安装的 release | `app.kubernetes.io/managed-by=Helm` + `meta.helm.sh/release-namespace` 属于平台组件命名空间/已知 release | 编辑、伸缩、回滚、删除 |
| 商店实例（chart 或 Operator 创建） | label `platform.coding/managed-by` ∈ {component, middleware} 或 owner 链指向受管 CR | 编辑、伸缩、回滚、删除 |

- **前端**：列表行内操作置灰 + tooltip 说明"由平台/实例管理，请到对应页面操作"；
- **后端**：k8s-server 在 update/delete 前校验，返回明确错误码（如 `RESOURCE_MANAGED_BY_PLATFORM`），不能只靠前端置灰；
- "其他资源"包括 Service / ConfigMap / Secret / Ingress / PVC 等，判定与限制同上。

---

## 4. 数据模型草案

| 表 | 用途 | 关键列 |
|---|---|---|
| `platform_middleware_operator` | 每集群每款 Operator 的安装状态 | cluster_id, code, version, status, installed_at |
| 复用 `platform_app`（+ 3 列） | 实例台账（Operator 实例与 Nacos chart 实例同表） | 加 `middleware_code`、`mode`、`cr_name`；原有 chart_ref/version/values/status 复用 |
| `platform_app_operation` | 安装/升级/删除操作记录 | 复用 |

保护规则不落表：以对象 label / owner 链为准，避免两套真相。

---

## 5. 选型调研结论（2026-10-21，GitHub API 实测）

### 5.1 Redis

| 候选 | 状态 | 结论 |
|---|---|---|
| spotahome/redis-operator | **已归档**（2024-07 停更，最后 release 2022-12） | 不用 |
| **OT-CONTAINER-KIT/redis-operator** | 活跃：v0.27.0（2026-10-09），Apache-2.0，1.4k stars | **采用** |
| Opstree redis-operator | 仓库已不可达（404） | 不用 |

OT-Container-Kit 正好覆盖三种形态：`Redis`（单节点）、`RedisFailover`（主从+哨兵）、`RedisCluster`（集群模式）。

**许可**：Redis 服务端自身经历了三次变化 —— 7.4 之前 BSD；7.4 起 RSALv2/SSPLv1；**8.0 起三许可，含 OSI 认可的 AGPLv3**。建议：
- 用 **Redis 8.x（AGPLv3）**，或
- 用 **7.2.x（BSD-3）** 规避许可讨论；
- 对 AGPL 敏感的话可评估 **Valkey**（Linux 基金会，BSD）。

### 5.2 MySQL

| 候选 | 复制模型 | 形态 | 备份/路由 | 许可 | 活跃度 | 结论 |
|---|---|---|---|---|---|---|
| **percona/percona-server-mysql-operator** | 半同步主从（基于 Percona Server，兼容原生 MySQL） | 1~N 副本，单节点=1 | XtraBackup + PITR；可选 ProxySQL/Haproxy | Apache-2.0 | v1.2.0（2026-07），活跃 | **推荐** |
| percona/percona-xtradb-cluster-operator | Galera 同步多主 | 生产需 ≥3 节点（奇数） | XtraBackup | Apache-2.0 | v1.20.0，活跃 | 需要多主/强一致时再上 |
| mysql/mysql-operator（Oracle） | InnoDB Cluster（Group Replication） | 1、3、5… | 官方备份 + Router | GPLv2 | 有提交，迭代偏慢 | 要官方背书时选 |
| cybozu-go/moco | GTID 半同步主从 | 1~N | moco-backup（独立组件） | Apache-2.0 | v0.37.0，活跃 | 最简实现，缺路由/备份一体化 |
| bitpoke/mysql-operator | 半同步 | 1~N | — | Apache-2.0 | 2023 后停更 | 排除 |
| vitessio/vitess | 分片 | 复杂 | Vitess 生态 | Apache-2.0 | v24，活跃 | 分片场景，太重 |

**结论修正**：平台要的是**单节点 / 主从**，不是 Galera 多主。Percona 的 **PS Operator**（半同步，和 Moco 同类）比 PXC 更贴场景，且自带备份与读写路由，所以选它；PXC 作为"需要多主"的备选。

**PS Operator 的能力边界（官方文档，2026-10-22 核对）**：

| 能力 | 支持情况 |
|---|---|
| 单节点 | ✅ |
| **经典主从（异步复制）** | ⚠️ **tech preview，官方明确不推荐生产** |
| 高可用集群 | ✅ Group Replication（GA，**单主写入**，最多 9 实例） |
| 多主 | ❌ 两种模式都只写主库 |
| 备份 / 读写分离 | ✅ XtraBackup + PITR；HAProxy（两模式）/ MySQL Router（仅 GR） |

因此按"要暴露的实例形态"重新对照选型：

| 平台要的形态 | 选型 |
|---|---|
| 单节点 + 高可用集群（单主、强一致） | **PS Operator**（Group Replication，GA） |
| 单节点 + 经典主从（2~N、低延迟、读写分离） | **Moco**（半同步 GA；无路由） |
| **多主** | **PXC**（Galera，≥3 节点奇数） |
| 一套覆盖单节点/主从/集群/多主 | 没有单一 Operator：要么 PXC（没有优雅的单节点主从），要么两套并存（重） |

### 5.3 其余

| 中间件 | 选用 | 状态 |
|---|---|---|
| Kafka | Strimzi 1.3.0 | 活跃；1.x 已移除 ZooKeeper，纯 KRaft |
| Elasticsearch | ECK | 活跃；许可为 Elastic License 2.0 / SSPL（内部使用可接受，注意不可再分发） |
| PostgreSQL | CloudNativePG v1.30.1 | 活跃；中间件里最成熟的 Operator |
| RabbitMQ | 官方 Cluster Operator v2.23.1 | 活跃；MPL-2.0；CR 只有 `RabbitmqCluster`，实现成本最低 |
| Nacos | 官方 chart（nacos-k8s） | 活跃；注意**集群模式依赖外置 MySQL**，单机模式用内置 Derby |

### 5.4 MinIO —— 必须改

**不是"成熟了不用维护"，是商业闭源。** 时间线：

| 时间 | 事件 |
|---|---|
| 2025-05 | 社区版 Web UI 移除大部分管理功能，LDAP/OIDC 外置登录移入企业版 |
| 2025-10 | **停止发布社区版 Docker 镜像与预编译二进制**（CVE 修复只能自己编译） |
| 2025-12-03 | 仓库 README 宣布 maintenance mode |
| 2026-02 | GitHub 仓库首次 archived（短暂恢复后再次归档） |
| 2026-04-25 | 仓库再次 archived；官方引导用户转向商业产品 **AIStor** |

- 结论：**新平台不应把 MinIO 作为默认对象存储**；已部署的能继续跑，但没有新特性、没有兼容性更新、**没有安全补丁保证**。
- 替代（均活跃）：

| 替代 | 许可 | 部署方式 | 评价 |
|---|---|---|---|
| **外部 S3**（阿里云 OSS / AWS S3 / R2 / 公司现有存储） | — | 平台只存 endpoint + AK/SK | **最省事**：零运维、零归档风险 |
| **SeaweedFS** | Apache-2.0 | seaweedfs-operator 0.1.43 | 最接近 MinIO 的 drop-in；海量小文件强；自建首选 |
| Rook / Ceph（`CephObjectStore`） | Apache-2.0 | Operator v1.21.0 | 最成熟、块/文件/对象齐全；运维重、要专用盘 |
| CubeFS | Apache-2.0 | Helm（无 operator），v3.6.0 | CNCF 项目，POSIX + S3 |
| Apache Ozone | Apache-2.0 | 官方 Helm（**k8s operator 仍未交付**，JIRA HDDS-14062） | 适合已有 Hadoop 生态 |
| Garage | AGPLv3 | 无 k8s operator | 面向多地理位置的轻量存储 |
| RustFS | Apache-2.0 | rustfs/operator 0.0.8 | 主打 MinIO 迁移，仍是 alpha |
| MinIO AIStor Free | 商业闭源 | AIStor Operator | 2025-12 起有 Free/Enterprise Lite/Enterprise 三档；要 MinIO 行为且接受许可时可选 |
| MinIO 社区 fork | AGPL | — | 不建议生产 |

**建议（2026-10-22 用户确认要做）**：对象存储进 V1（批②），选 **SeaweedFS**。

对象存储与 MySQL 这类"每租户一个实例"不同，采用**服务 + 桶**模型：

- 管理员装一套 SeaweedFS（`Seaweed` CR：master / volume / filer / s3 网关，起步可全 1 副本，后续扩 master=3、s3=2）；
- 租户与应用拿 **endpoint + bucket + AK/SK**，不各装一套（避免容量碎片化和 S3 endpoint 分散）；
- 平台提供：endpoint 展示、bucket 创建/删除、AK/SK 发放（SeaweedFS 内置 IAM，通过 `s3.config` secret 下发）；
- 平台自身的 Thanos / Tempo 也用同一套 endpoint（现在 recipes 里是 `${tempo.minio.bucket}` 之类占位符）。

---

## 6. 我的意见（按重要性）

1. **MinIO 必须换或缓**（§5.4，有归档证据）。
2. **V1 体量偏大**：7 个 Operator + 7 张实例表单 = 至少 7 份 CR 操作 + 7 张表单。建议分两批：
   - 批①：MySQL、Redis、Kafka、ES（你原定的四个）+ Nacos chart；
   - 批②：PostgreSQL、RabbitMQ、对象存储（替代品）。
3. **MySQL 选型建议改成 Moco**（理由见 §5.2），Percona 作为备选。
4. **保护规则要后端兜底**，不能只靠前端置灰；并且要一次把"哪些 label 算受管"定义成唯一真相。
5. **CRD 升级不可回退**：Operator 版本必须钉死并记录；升级前要评估 CRD 的破坏性变更。
6. **资源与存储**：每款中间件要有默认 requests/limits 与 StorageClass 选择，否则实例会占满节点。
7. **Nacos 的隐藏依赖**：集群模式必须有 MySQL；如果平台同时提供 MySQL 实例，安装向导应能选"用哪个 MySQL 实例"，否则用户装完起不来。

---

## 7. 已定 / 待定

**已定（2026-10-22）**

1. **实例谁都能创建**：管理员与租户管理员都可创建，租户限分配表内的命名空间（连带要求见 §2.3）。
2. **删除也禁**：平台组件与商店实例创建的工作负载及其他资源，编辑与**删除**都禁用。
3. **MySQL 选 Percona PS Operator**，形态只承诺**单节点 / 高可用集群（Group Replication，单主）**；PS 的异步主从是 tech preview，不作为生产形态。需要"经典主从"时改用 Moco；需要"多主"时改用 PXC——具体取哪个见 §5.2 的形态对照。
4. **对象存储进 V1（批②）**：选 **SeaweedFS operator**，采用"集群级一套服务 + 桶/凭据分配"的模型，不做每租户一个实例。

**仍待定**

1. 分批边界是否按 §6.2（批① MySQL/Redis/Kafka/ES + Nacos；批② PostgreSQL/RabbitMQ/对象存储）。
2. Nacos 集群模式依赖外置 MySQL：安装向导是否允许直接选平台里的 MySQL 实例。
