# 任务台账（2026-10-12）

> 本轮「补全期」的工作台账：把已裁定的需求拆成 **spec 批**与**任务**，作为后续逐批 spec 的索引与进度表。
>
> - 需求依据、裁定理据、方案细节 → [gap-analysis-2026-10-12.md](gap-analysis-2026-10-12.md)（下称「分析文档」）
> - 各批的详细设计 → `docs/superpowers/specs/`（逐批产出）
> - 各批的实施计划 → `docs/superpowers/plans/`（spec 之后产出）
>
> **「不做」清单见分析文档 §5.2**（事件中心 / NetworkPolicy / Job·CronJob / PDB / K8s 原生 RBAC /
> CRD 查看器 / Ingress / 系统设置 / 列表增强 / 集群运维深度 等）—— **不在本台账中，勿重复上报**。

---

## 0. 拆分原则

**两级粒度**：

| 级别 | 判据 | 产物 |
|---|---|---|
| **Spec 批**（S 编号） | 一个**可独立上线**、有独立产品面的模块 —— 不需要等别的批一起才可用 | 一份 spec 文档 |
| **任务**（S#.n） | 一个**能独立出实施计划、能被单独评审**的单元；拆到「评审者可以只否掉它而放过相邻任务」为止 | spec 里的一个章节 |
| （更细）步骤 | TDD 步骤、具体文件、代码块 | 每份 spec 的 `plans/` 实施计划 |

**依赖用「→」表示**（`S0.2 → S3.1` 读作「S0.2 是 S3.1 的前置」）。
**规模用 S/M/L 表示**（相对量级，不是时间承诺）。

---

## 1. 全局先决决策（动手前必须定，会同时影响多批）

| # | 决策 | 影响 | 现状 |
|---|---|---|---|
| **D1** | **release 状态存哪** —— 平台自己存（DB 记 values + 渲染历史），还是真走 `helm install/upgrade`（Helm 成为第二写入方） | S3.2 / S3.3 / S3.4 | ⬜ 未定（分析文档 §7.1） |
| **D2** | **日志采集器形态** —— OTel webhook 注入（与模板编辑零冲突）vs 自建 filebeat sidecar（与模板编辑/回滚/重下发冲突） | S2.3 | ⬜ 未定（§6.6） |
| **D3** | **「日志采集」与「OTel 采集」是同一件事还是两件事** —— OTel sidecar 采日志本身就是一种日志采集方式；若两者是一件事，S2.3 合并为一批 | S2.3 | ⬜ **需求歧义，需澄清** |
| **D4** | **审计的埋点机制** —— 统一拦截（`PermissionAuthorizationManager` 之后）vs 各 Service 写路径埋点 | S0.1 | ⬜ 未定（漏埋点的审计不如没有） |
| **D5** | **平台总览的口径** —— 平台级统计 + 健康摘要分别取哪些数 | S1.3 | ⬜ 未定 |
| **D6** | **租户可见性**：Helm 商店 / Operator 管理对租户开放到什么程度 | S3.3 / S3.4 | ⬜ 未定 |

---

## 2. 台账正文

### S0 · 平台治理地基（无前置）

#### S0.1 审计日志 ｜ 规模 L
> 唯一「不做就补不回来」的一项 —— 越早埋点覆盖越全。分析文档 §5.1 #5 / §7.4.3。

- [ ] S0.1.1 **D4 决策** + 表设计（`platform_audit_log`：who / when / cluster / namespace / 资源 / 动作 / 结果 / 来源 IP），遵守「不加外键」约束
- [ ] S0.1.2 迁移脚本（日期序号接 `V2026_10_12_1` 之后）
- [ ] S0.1.3 埋点实现（按 D4 定的机制）+ 覆盖清点：资源写操作 / 权限与角色变更 / 命名空间分配 / 集群纳管 / **凭据使用**（S0.2）/ **CI 触发**（S3.5）/ **Operator 安装**（S3.4）
- [ ] S0.1.4 查询页（平台管理员）+ 权限点 `platform:page:audit` / `platform:audit:read` + **显式绑角色**（不按 code 继承 —— 踩过的坑）
- [ ] S0.1.5 保留策略（多久、谁清理、是否分区）

> **✅ 实施完成（2026-10-13，10 个提交）**：机制=注解 + AOP 管写操作、复用已有 `accessDeniedHandler` 管 403；
> 表 `platform_audit_log`（17 列 4 索引）+ 3 条权限行（只绑 admin）。
> 首批已标注 5 个高危动作：`workload:delete` · `workload:rollback` · `namespace:delete` · `node:drain` · `cluster:delete`。
> 其余动作按 spec §10 后续批次补（**加注解不需要动迁移、不需要动权限表**）。
>
> **执行期发现并订正的既有事实错误**（spec 与 plan 已同步）：
> 1. **本仓没有引入 Flyway** —— `db/migration/*.sql` 一直是**手工重放**的，应用不会自动执行它；
>    原「看 Migrating schema 日志」的验收判据不成立。
> 2. 计划里测试命令的 `-DfailIfNoTests=false` 无效（`-am` 连带跑兄弟模块时要用
>    `-Dsurefire.failIfNoSpecifiedTests=false`）。
> 3. 计划原稿的 `AuditAspect` 把成功路径的 `record` 放在业务 `try` 内，会让「审计失败不影响业务」
>    完全寄存在 `AuditService` 的 try/catch 里 —— TDD 已抓出并改为 `recordQuietly()` 本地兜底。
>
> **未验证（留给部署后）**：真集群端到端（删工作负载 / 触发 403 后查审计）、审计页的筛选与 purge、
> 以及「`REQUIRES_NEW` 抗业务回滚」—— 后者目前只由一条反射钉子守着（详见 spec §5.1 与 plan Task 9）。
>
> **既有失败（非本批引入）**：`k8s-core` 的 `WorkloadConverterStaticIpTest` 在 @HEAD 即 NPE，与本批无关。

#### S0.2 凭据与连接管理 ｜ 规模 M（原估 L，2026-10-12 复审下调）
> **实际消费方只有两个：CI（git 凭据）与镜像仓库 / Helm 商店（同一条 registry/chart-repo 连接）。**
> 应用级交付与告警**不需要**（kubeconfig 已加密存在 `k8s_cluster`；告警是入站 webhook，只要校验密钥）。
> 详见分析文档 §7.6 的复审修正。
>
> **为什么仍独立成批**：镜像仓库（S3.1）排在 CI（S3.5）**之前** —— 若把凭据模型写进 CI spec，
> S3.1 就要引用一份尚未产出的 spec，依赖方向与执行顺序打架。
> （它不是「抽公共基类」——那是否决过的形态；这是有表/页面/权限点/生命周期的实体模块。）
>
> **✅ 2026-10-12 用户裁定：保持独立成批**（不并入镜像仓库）。理由取「三条口径一次定清 +
> 将来加连接类型只动一处」这一面。**此决定不再复议。**

- [ ] S0.2.1 连接模型与表（`connection(type, endpoint, credential, scope)`）—— **先只落两类：`git`、`registry`**
      （chart-repo 与 registry 共用一条 Harbor 连接），模型留扩展位但**不预先实现**其余类型
- [ ] S0.2.2 凭据加密存储（复用 kubeconfig 的 `AESUtils` 先例；主密钥来自配置，**不进 DB、不上页面**）
- [ ] S0.2.3 管理页 + 权限点（**读 / 写 / 使用** 三段独立）+ 迁移
- [ ] S0.2.4 **永不回显**：列表与详情只返回「是否已配置 + 指纹」
- [ ] S0.2.5 **投递能力**：注入构建 Pod（CI，→ S3.5.13）/ 生成命名空间 Secret / imagePullSecret（→ S3.1.3）——
      并定清「谁能读明文、投递到哪」
- [ ] S0.2.6 使用审计（→ S0.1.3）

---

### S1 · 平台侧小项（无前置，适合先跑通节奏）

#### S1.1 用户自助 ｜ 规模 S
- [ ] S1.1.1 改密码（`platform-auth` 端点 + 前端页面）
- [ ] S1.1.2 改资料（displayName / email）
- [ ] S1.1.3 清掉 `platform-auth` `ignore-urls` 里两个**不存在的静态页**（`/register.html`、`/forgot-password.html`）
- [ ] S1.1.4（可选，待定）登录失败锁定 / 在线会话与强制下线

#### S1.2 集群组件展示 ｜ 规模 M
> **是 S3.4 的地基**（先能看见，才谈装）。分析文档 §5.1 #7 / §7.1。

- [ ] S1.2.1 组件清单模型（名称 / 是否部署 / 版本 / 健康 / 命名空间）
- [ ] S1.2.2 探测实现：**收敛现有零散探测**（capability API group 探测、ambient 的 ztunnel 活探测）+ 新增首批内容（**OpenTelemetry Operator**、ES、Alertmanager、snapshot-controller 等）
- [ ] S1.2.3 集群详情页展示（现为 4 tab，新增一个 tab 或并入概览）
- [ ] S1.2.4 顺带成为各功能**门控的统一数据源**（capability 判据单一出处）

#### S1.3 平台总览 ｜ 规模 M
> 租户概览已在做；本项是**平台视角**的总览（分析文档 T4-2 对侧）。

- [ ] S1.3.1 **D5 决策**（口径）
- [ ] S1.3.2 指标与健康摘要的数据来源（含聚类：集群 / 租户 / 分配）
- [ ] S1.3.3 前端改造（现 `OverviewView` 是管理员形状的静态统计 + 快捷入口）

---

### S2 · 排障与观测闭环

#### S2.1 工作负载级日志 ｜ 规模 M
- [ ] S2.1.1 **`previous` 容器日志**（后端查询参数 + 前端切换）—— 解 CrashLoopBackOff 最高频场景，改动极小，建议先做
- [ ] S2.1.2 工作负载详情按 labelSelector **聚合多 Pod 日志**
- [ ] S2.1.3（可选）多容器选择 / 时间范围下推

#### S2.2 告警 ｜ 规模 L
> 已定：PrometheusRule 做；**Alertmanager 已部署、平台不做其管理面、不对外暴露**；webhook 落库；发送渠道待定。

- [ ] S2.2.1 `PrometheusRule` CRUD（通用 CRD 范式，同 Calico/Gateway）
- [ ] S2.2.2 平台侧**按 Alertmanager `/api/v2/alerts` 契约**的接收端点（集群侧 receiver 指向它）
- [ ] S2.2.3 告警落库 + 列表页 + 权限点
- [ ] S2.2.4（待定）发送渠道 —— 用户明确「后续如何发送待定」，不要提前做

#### S2.3 日志采集 + OTel 采集 ｜ 规模 L（范围待 D3 澄清）
> 依赖：端点与凭据走 S0.2；存储为 ES（用户在 `platform-system` 自建）。

- [ ] S2.3.1 **D2 / D3 决策**
- [ ] S2.3.2 采集配置（ES 端点、索引策略）—— 走 S0.2 的连接管理
- [ ] S2.3.3 注入/下发实现（按 D2 定）
- [ ] S2.3.4 日志查询 UI（对接 ES）+ 权限点
- [ ] S2.3.5 ⚠️ **若选 filebeat sidecar**：注入层 × 模板编辑 / 回滚 / 重下发的交互设计（分析文档 §6.6 —— 否则「回滚一次日志就没了」）

#### S2.4 服务网格可视化 ｜ 规模 M-L
> 已定：**自研**（不引 Kiali、不引 Loki）。分析文档 §7.7。

- [ ] S2.4.1 拓扑数据查询（Thanos 的 `istio_requests_total` / `istio_request_duration_milliseconds` → 节点与边）
- [ ] S2.4.2 拓扑图前端（节点 = workload/service，边 = 速率 / 错误率 / 延迟）
- [ ] S2.4.3 链路追踪接入（Jaeger 或 Grafana Tempo；嵌入或跳转）
- [ ] S2.4.4 ⚠️ 确认 ambient 模式下指标是否齐备（ztunnel / waypoint 的指标产出与命名）

---

### S3 · 交付能力（本阶段主体，量大）

#### S3.1 镜像仓库 ｜ 规模 M ｜ 前置：S0.2
- [ ] S3.1.1 registry connection 接入 + **三档实现**（Harbor 自己的 REST API v2.0 / 通用 registry v2 `/v2/<name>/tags/list` / Docker Hub 走 PAT）
- [ ] S3.1.2 tags autocomplete（**缓存 + 静默降级**）接到工作负载「更新镜像」
- [ ] S3.1.3 imagePullSecret 自动生成（→ S0.2.5）

#### S3.2 应用级交付（自研薄应用层）｜ 规模 L ｜ 前置：**D1**（**不需要** S0.2 —— kubeconfig 已在 `k8s_cluster` 表）
> 已定：自研（分析文档 §6.4）。产品形态自研、渲染引擎内部用 Helm。

- [ ] S3.2.1 **D1 决策** + 应用模型与表（应用 = 模板 + values + 渲染历史）
- [ ] S3.2.2 渲染引擎（`helm template`）
- [ ] S3.2.3 复用现有 SSA apply 通道（不新建写入路径）
- [ ] S3.2.4 应用列表 / 详情 / 升级 / 回滚页 + 权限点 + 迁移
- [ ] S3.2.5 漂移处理（真相源在平台侧；检测到被外部改动时的策略）

#### S3.3 Helm Chart 商店 ｜ 规模 M ｜ 前置：S3.2
- [ ] S3.3.1 来源接入：**本地 Harbor（OCI chart）** + **Artifact Hub 公共索引**（发现/搜索）
- [ ] S3.3.2 chart 搜索 / 浏览页
- [ ] S3.3.3 values 表单（schema → 表单）或 YAML
- [ ] S3.3.4 安装流程（复用 S3.2 渲染 + apply）
- [ ] S3.3.5 **D6 决策**（租户可见性：能否装任意 chart、配额怎么算、危险 chart 怎么限）

#### S3.4 Operator 管理 ｜ 规模 L ｜ 前置：S1.2、**D1**
> 用户明确：**独立模块，不能用 Helm 商店替代**；Helm 只是它的部署方式之一。支持安装与升级。

- [ ] S3.4.1 已装 Operator 探测（CRD group + controller Deployment）
- [ ] S3.4.2 Operator 详情：**它提供了哪些 CRD、各有多少实例**（Operator 的价值所在）
- [ ] S3.4.3 安装（来源：Helm chart 或 manifest bundle）
- [ ] S3.4.4 升级（当前版本 vs 目标版本）
- [ ] S3.4.5 **D6 决策**（租户可见性）

#### S3.5 CI（Tekton）｜ 规模 XL ｜ 前置：S0.2
> 已定全部关键需求：自由 DAG、内置/自定义节点、条件分支 + finally、**只手动触发**。
> 详见分析文档 §7.4.1–§7.4.3。

- [ ] S3.5.1 节点模型：**内置节点模板 + 字段 schema**（类型 / 校验 / 默认值 / 中文说明 / 是否可引用上游）+ 版本化
- [ ] S3.5.2 **自定义节点**（隶属流水线、级联删除）
- [ ] S3.5.3 渲染器：画布 JSON → Tekton `Pipeline`，**`tasks[].taskSpec` 全部内联**（消掉跨命名空间引用与 ClusterTask 废弃问题）
- [ ] S3.5.4 画布前端（LogicFlow 或 Vue Flow）：自由连线 + 环检测 + **并行语义提示**（没连线 = 并发）
- [ ] S3.5.5 **条件分支**（`when` 三段式：字段 + in/notin + 值）+ **跳过会传播到下游**的可视化
- [ ] S3.5.6 `finally` 收尾节点
- [ ] S3.5.7 **隐式共享 workspace**（平台注入到所有节点，用户无感 —— Tekton 特有的坑）
- [ ] S3.5.8 运行表单（**运行时参数 vs 固定配置**分离）
- [ ] S3.5.9 触发（**仅手动**）：平台创建 `PipelineRun`；**不引入 Tekton Triggers**
- [ ] S3.5.10 运行历史 / 日志页 + 权限点
- [ ] S3.5.11 并发与重复触发策略（禁止 / 排队 / 允许并行）
- [ ] S3.5.12 ⚠️ **权限与安全**：构建 Pod 能跑任意代码 —— 定义/触发/看日志分开授权、能跑在哪个命名空间、构建步骤镜像来源是否限制
- [ ] S3.5.13 git 节点接 S0.2 的 git 连接（地址 + 凭据）

---

### S4 · 收尾

#### S4.1 PVC 扩容 ｜ 规模 S ｜ 方案已出
- [ ] S4.1.1 `StorageClass.allowVolumeExpansion` 门控
- [ ] S4.1.2 只增不减的前后端双重校验 + 权限点 + 迁移
- [ ] S4.1.3 中间态展示（`Resizing` / `FileSystemResizePending` **不是异常**）
- [ ] S4.1.4 STS `volumeClaimTemplate` 的特例处理（改 template + 手工对齐，或只读）

#### S4.2 Istio 表单补偿 ｜ 规模 M ｜ 方案已出
- [ ] S4.2.1 「快速路由」表单（域名 + 路径 + 后端 Service + TLS → 自动拆 Gateway + HTTPRoute，已有 Gateway 则复用）
- [ ] S4.2.2 后端**编排式创建**端点（两个对象，失败回滚已建对象）
- [ ] S4.2.3 意图化展示（Gateway 详情列出承载的 Route；HTTPRoute 列表解析 `parentRefs` 成域名/路径摘要）
- [ ] S4.2.4 hostname 冲突检测（listener 唯一性 + HTTPRoute 重叠不报错的提示）

#### S4.3 VolumeSnapshot ｜ 规模 M ｜ 用户要求排最后
- [ ] S4.3.1 capability 门控（`snapshot.storage.k8s.io` + 存在可用的 `VolumeSnapshotClass`；缺一整块隐藏）
- [ ] S4.3.2 三 CRD 操作层（通用 CRD 范式；只写 `VolumeSnapshot`，另两个只读）
- [ ] S4.3.3 快照列表 / 创建 / 删除页 + 权限点 + 迁移
- [ ] S4.3.4 从快照恢复 PVC（`dataSourceRef` 已支持，补 UI 入口）
- [ ] S4.3.5 三条语义写进 UI：快照 ≠ 备份 / `deletionPolicy` 的后果 / `restoreSize` 下限

---

## 3. 进度表

| 批 | 模块 | 前置 | 规模 | 状态 | Spec |
|---|---|---|---|---|---|
| **S0.1** | 审计日志 | — | L | ✅ **已实施待部署** | [spec](superpowers/specs/2026-10-13-audit-log-design.md) · [plan](superpowers/plans/2026-10-13-audit-log.md) |
| **S0.2** | 凭据与连接管理 | — | M | ⬜ 未开始 | — |
| **S1.1** | 用户自助 | — | S | ⬜ 未开始 | — |
| **S1.2** | 集群组件展示 | — | M | ⬜ 未开始 | — |
| **S1.3** | 平台总览 | — | M | ⬜ 未开始 | — |
| **S2.1** | 工作负载级日志 | — | M | ⬜ 未开始 | — |
| **S2.2** | 告警 | — | L | ⬜ 未开始 | — |
| **S2.3** | 日志采集 + OTel | S0.2、D2/D3 | L | ⬜ 未开始 | — |
| **S2.4** | 服务网格可视化 | — | M-L | ⬜ 未开始 | — |
| **S3.1** | 镜像仓库 | S0.2 | M | ⬜ 未开始 | — |
| **S3.2** | 应用级交付 | D1 | L | ⬜ 未开始 | — |
| **S3.3** | Helm Chart 商店 | S3.2、S0.2 | M | ⬜ 未开始 | — |
| **S3.4** | Operator 管理 | S1.2、D1 | L | ⬜ 未开始 | — |
| **S3.5** | CI（Tekton） | S0.2 | XL | ⬜ 未开始 | — |
| **S4.1** | PVC 扩容 | — | S | ⬜ 未开始 | — |
| **S4.2** | Istio 表单补偿 | — | M | ⬜ 未开始 | — |
| **S4.3** | VolumeSnapshot | — | M | ⬜ 未开始 | — |

### 依赖图

```
S0.1 审计 ──────────────┐（凭据的「使用」要落审计）
                        ▼
S0.2 凭据/连接（git + registry 两类）
        ├──▶ S3.1 镜像仓库（同一条 registry 连接）──▶ (S3.5 CI 的产物去向)
        ├──▶ S3.3 Helm 商店（复用同一个 Harbor 连接）
        └──▶ S3.5 CI（git 凭据 + 注入构建 Pod）
S3.2 应用级交付 ──▶ S3.3 Helm 商店
S1.2 集群组件展示 ──▶ S3.4 Operator 管理（另需 D1）
（其余各批互不依赖）
```

### 无依赖、可立即开工的批

**S0.1 审计 · S0.2 凭据 · S1.1 用户自助 · S1.2 集群组件展示 · S1.3 平台总览 ·
S2.1 工作负载级日志 · S2.2 告警 · S2.4 服务网格可视化 · S4.1 PVC 扩容 · S4.2 Istio 表单补偿**

> **S0.1（审计）建议最优先** —— 它是唯一「不做就补不回来」的，而且凭据使用、CI 触发、Operator 安装
> 这些高危动作都指望它留痕。
> **S0.2（凭据）排在 S3.1/S3.5 之前即可**，不必抢在最前 —— 复审后它的消费方只有两个
> （git 连接 → CI；registry 连接 → 镜像仓库/商店），单独先做会有一部分闲着。
