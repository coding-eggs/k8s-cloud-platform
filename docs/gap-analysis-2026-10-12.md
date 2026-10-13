# 平台功能缺口分析（2026-10-12）

> **拆好的任务台账（working checklist）见 [task-ledger-2026-10-12.md](task-ledger-2026-10-12.md)** ——
> 本文是需求依据与方案细节，台账是「有哪些批、每批有哪些任务、谁挡着谁」的索引。
> 本文 §4 已被台账 §3 取代（保留作决策过程的记录）。

> 阶段：主体功能开发完毕，进入补全/优化期。
> 方法：全仓盘点（platform-api / k8s-server / k8s-core / platform-data / platform-web + 历史 spec），
> 逐条给出**现状证据路径**，区分「有意不做」与「真缺失」。
> 本文只做盘点与排序，不含实施计划。

---

## 一、能力地图（已建成部分）

| 域 | 已交付能力 | 主要落点 |
|---|---|---|
| 集群管理 | kubeconfig 加密入库、连通性探测、K8s 侧开通、启停、软删除、capability 探测与持久化、集群概览（聚合+指标）、资源明细/Top-N | `ClusterService`、`K8sProvisioningService`、`ClusterDetailView.vue` |
| 租户与分配 | 租户 CRUD、命名空间分配/回收、成员增删与租户内角色授予 | `TenantService`、`TenantMemberService`、`NamespaceAllocationService` |
| 平台 RBAC | 用户/角色/权限点/RBAC 模板四张管理页；权限点目录 + 热加载；角色可分配集合由后端下发；权限闭包请求期现算（移出 token） | `PermissionController`、`RoleController`、`UserController`、`PermissionClosureResolver`、`V2026_10_07_1..V2026_10_09_1` |
| 命名空间 | 增删改查、ResourceQuota、LimitRange、ns 级能力开关（Calico 绑定池 / Istio ambient）、命名空间概览 | `NamespaceService`、`NamespaceEditorView.vue`、`NamespaceDetailView.vue` |
| 节点 | 列表/详情/YAML、cordon/uncordon/drain、标签与 Taint、Conditions、事件、节点上 Pod、容器日志、当前指标 | `NodeService`、`NodeController`、`NodeDetailView.vue` |
| 工作负载 | Deployment/StatefulSet/DaemonSet 合一；完整编辑器；伸缩、暂停、镜像更新、历史版本、回滚、mesh 快捷开关 | `WorkloadService`、`WorkloadView.vue`、`WorkloadDetailView.vue` |
| Pod | 列表（含跨命名空间 list-all）、详情、容器详情、YAML、删除、流式日志（xterm）、exec 终端、四类指标 | `PodService`、`PodExecRelayHandler`、`PodTerminal.vue`、`PodLogDialog.vue` |
| 配置 | ConfigMap / Secret 完整 CRUD（含二进制预览下载、类型化表单） | `ConfigMapService`、`SecretService` + 两个 Editor |
| 服务发现 | Service 完整 CRUD、NodePort 直链（从 node 地址选一个打开） | `ServiceService`、`ServiceView.vue` |
| 存储 | PVC 创建/删除/详情（dataSourceRef）、PV 只读、StorageClass 只读 | `PvcController`、`PvcView.vue` |
| 弹性 | HPA v1/v2 完整编辑器、metrics-server / prometheus-adapter 门禁、工作负载↔HPA 联动 | `HpaService`、`HpaEditorView.vue` |
| 监控接入 | ServiceMonitor / PodMonitor 完整 CRUD、relabel 与 metric-names 候选查询 | `ServiceMonitorService`、`PodMonitorService` |
| 服务网格 | GatewayClass（平台级）+ Gateway/HTTPRoute/GRPCRoute/TCPRoute/TLSRoute/UDPRoute（租户级）全套 CRUD；ambient 活探测；waypoint | `MeshService`、`GatewayService` 等 6 个 service |
| 网络 Calico | IPPool 完整 CRUD + 删除守卫、IP 分配浏览、IPReservation、BGP Configuration/Peer/Filter 全套 CRUD | `CalicoService`、`IppoolOperations`、`Bgp*Operations` |
| 指标 | Thanos 查询、Prometheus discovery、集群/节点/工作负载/Pod/命名空间多维曲线 | `metrics/`、`ClusterMetricsPanel.vue` |

**成熟度判断**：管理面（CRUD + 权限 + 上下文）扎实；**排障面与治理面薄弱**。

---

## 二、有意不做（勿重复上报）

| 项 | 依据 |
|---|---|
| Ingress | `docs/superpowers/specs/2026-08-27-platform-web-redesign-design.md:17` 明确「Ingress / Job / CronJob 不做」，统一走 Gateway API |
| Job / CronJob | 同上（**但见 T2-2 的复议理由**） |
| 每个命名空间必须有配额 | 用户 2026-10-10 提出、有意延后；届时配额列「—」需从「合法未配置」改判为异常 |
| Prom discovery 四个端点豁免租户边界 | 有意为之（纯候选值查询） |
| Calico IPPool 前缀下钻树 | 有意不做，改为点查 + nextFreeBlocks 分页 |
| FelixConfiguration | 有意先不做 |

---

## 三、缺口清单

### T1 · 排障闭环（最高优先：能看状态，查不了原因）

#### T1-1 事件中心完全缺失
- **现状**：全平台仅 `NodeController#events`（`/nodes/{name}/events`）。Pod / 工作负载 / 命名空间 / 集群级 Event 零暴露，k8s-core 无 Event 相关 operations，前端无任何 `Event` 类型（除 `NodeEvent`）。
- **影响**：Pod 起不来（ImagePullBackOff / FailedScheduling / OOMKilled）时，唯一能给出原因的对象看不到。排障必须回 kubectl。
- **改动面**：k8s-core 新增 `CoreV1EventOperations`（含 `involvedObject` 过滤、按时间倒序、按 type 分组）；k8s-server 薄透传（命名空间级 + 集群级两档）；platform-api 三个端点（pod / workload / namespace / cluster）；前端在 Pod 详情、工作负载详情、命名空间概览加「事件」tab，并考虑做独立的全局事件页。
- **成本**：中。适配器范式完全现成（照 `CoreV1PodOperations` 写），主要是 4 处前端挂载点。

#### T1-2 日志能力不足
- **现状**：`PodLogDialog.vue` = 单 Pod 单容器 + `tailLines` 回看 + `sinceTime` 增量轮询 + **前端**关键字高亮。`grep -rn previous` 在三个后端模块与前端**零命中**。
- **缺口**：
  1. **不支持 `previous: true`** → CrashLoopBackOff 场景看不到上一次容器为什么死的（这是最刚性的一个）。
  2. 无跨容器/跨 Pod 聚合：看一个 Deployment 的日志要逐个 Pod 点开。
  3. 无服务端检索：无时间范围、无关键字下推、无历史日志（未接 Loki/ES），Pod 删了日志就没了。
- **建议**：先做 1（后端加一个 `previous` 查询参数 + 前端切换按钮，极小）；再做 2（工作负载详情按 labelSelector 聚合多 Pod 日志）；3 属独立批次（引入日志后端）。

#### T1-3 告警为零
- **现状**：`grep -rni alert` 在 platform-api / k8s-core / platform-web 只命中 Element Plus 的 `<el-alert>` UI 组件。无 PrometheusRule、无 Alertmanager、无告警规则/告警列表/静默/通知渠道。侧边栏「监控告警」组下只有 ServiceMonitor / PodMonitor 两页。
- **影响**：平台有完整的指标链路（remote-write + Thanos），但没有「主动告诉人」的能力。故障只能靠人盯。
- **改动面**：PrometheusRule CRD 的 CRUD（走已成熟的通用 CRD 范式）→ Alertmanager API 代理（告警列表 / 静默）→ 通知渠道（见 T3-3）。三块可分批。

#### T1-4 Service 看不到后端
- **现状**：`ServiceView.vue` 详情仅展示 selector chips（`selectorChips()`），无 EndpointSlice / Endpoint / 匹配 Pod 列表。
- **影响**：「Service 不通」的第一判据（有没有后端、后端 Pod 是否 Ready）无法在页面上回答。
- **成本**：低。EndpointSlice 是普通 list，或直接按 selector 复用一个 list 查 Pod。适合作为快速补口。

---

### T2 · 资源覆盖（K8s 对象面）

#### T2-1 NetworkPolicy（缺口最刺眼）
- **现状**：`grep -rn NetworkPolicy` 三模块 + 前端 **0 命中**。Calico 侧做了 IPPool / IPReservation / BGP 全套，唯独没有网络策略（K8s 原生 NetworkPolicy 与 Calico NetworkPolicy 都没有）。
- **影响**：多租户平台里，租户无法自助做命名空间内/间的流量隔离；平台也没有任何网络访问控制的表达面。
- **成本**：中。K8s 原生 NetworkPolicy 结构简单（podSelector + ingress/egress rules），照 ConfigMap 范式即可；Calico `projectcalico.org/v3 NetworkPolicy` 走通用 CRD，范式已由 BGP* 验证。

#### T2-2 Job / CronJob（复议）
- **现状**：无 `batch/v1` 任何代码；`CronJob` 仅在两处注释/DTO 清单里被提及。
- **复议理由**：原排除理由推测是「本期 8 类资源」的范围控制。但定时任务（备份、清理、报表）是交付侧高频需求，且 Job/CronJob 是唯二无法用 Deployment 替代的对象。配合 T1-2（查看已退出 Pod 日志）价值更高。

#### T2-3 PodDisruptionBudget
- **现状**：0 命中。而 drain 已上线（`POST /nodes/drain`）。
- **影响**：drain / 节点故障时，业务会不会被打断，页面上完全不可见，也没有配置入口。
- **成本**：低（对象结构简单，且天然属于「集群运维」组）。

#### T2-4 VolumeSnapshot / VolumeSnapshotClass
- **现状**：无操作层。`PvcView.vue` 已支持从已有数据源（`dataSourceRef`）创建 PVC —— 说明快照是设计预期内的路径，但快照本身不能创建/查看/恢复。
- **成本**：中（CSI 依赖 `snapshot.storage.k8s.io` group，需先并入 capability 探测）。

#### T2-5 K8s 原生 RBAC 未暴露
- **现状**：k8s-server **已有** `namespace/RoleBindingController`、`namespace/ServiceAccountController`、`cluster/ClusterRoleController`（供 `K8sProvisioningService.ensureRoleBinding` 内部使用），但 platform-api 无对应 controller，前端无路由/页面。`platform-api` 里的 `RoleController` 是**平台自己的角色**，不是 K8s 的 Role。
- **影响**：租户看不到自己命名空间里的 SA / RoleBinding，也不能给应用建 SA（Workload 编辑器的 `serviceAccountName` 是自由文本输入）。
- **成本**：低-中。**适配器已在，缺的是 platform-api 端点 + 页面 + 权限行**。

#### T2-6 CRD 通用查看器
- **现状**：所有 CRD 都是「一类一写」（Calico/Gateway/Monitoring 各自 operations）。集群里其他 CRD（Istio VirtualService/DestinationRule/AuthorizationPolicy、cert-manager 等）完全不可见。
- **影响**：ambient 的 `AuthorizationPolicy`（waypoint 强制走流量的前置）在 UI 里无处可配 —— `GatewayEditorView.vue:485` 的说明文案本身就让人「另配 AuthorizationPolicy」，但没有地方配。
- **成本**：中-高。建议先做一个**只读**的「CRD 浏览」（列举 CRD → 选 CRD → 列表/详情/YAML），不做写入。

#### T2-7 Ingress 只读兼容
- **现状**：无 `networking.k8s.io` 任何代码。Gateway API 是有意替代。
- **说明**：不算遗漏，但存量集群里的 Ingress 对象在平台上不可见，会造成「资源明明在集群里，平台上看不到」的困惑。只读列表成本极低，可作为兼容性补充。

---

### T3 · 平台治理（多租户合规刚需，目前零基础）

#### T3-1 审计日志完全缺失 ★
- **现状**：DB 表清单（`k8s_cloud_platform.sql` + 全部迁移）中没有任何 audit / operation_log 表；代码里 `audit` 仅命中三处测试注释。写操作零留痕。
- **影响**：多租户平台的合规与追责刚需。「谁在什么时候、哪个集群/命名空间、改了什么、成功还是失败」目前答不出来。出现误删（命名空间删除、PV 回收、节点 drain）无法定位责任人。
- **改动面**：新表 + 写入点（可挂 `PermissionAuthorizationManager` 之后的统一拦截或在各 Service 写路径埋点）+ 查询页（平台管理员）。**注意**：新表需遵守「不加外键」既有约束。
- **成本**：中-高（表设计 + 埋点覆盖面是关键，漏埋点的审计不如没有）。

#### T3-2 用户自助与登录安全
- **现状**：`UserController` 只有 `me` / `my-tenants` / `create` / `list` / `get` / `platformRole/*`。**无改密码、无改资料、无找回密码**；用户只能由管理员创建（`UserCreateRequest` 携带密码）。无登录失败锁定、无 MFA、无在线会话列表/强制下线。
- **影响**：任何用户改密码都要找管理员；账号安全策略无法表达。

#### T3-3 通知渠道
- **现状**：无邮件 / Webhook / IM 通知。是 T1-3（告警）和潜在审批流的共同基础设施。
- **建议**：与 T1-3 合并设计，先做「通知渠道配置 + 测试发送」，再做告警订阅。

#### T3-4 系统设置页
- **现状**：所有参数在 yml / `@Value`（TTL、缓存、AES key、命名空间保护名单等）。无可视化配置面。
- **说明**：优先级低于前三条；但 `NamespaceProtectionProperties` 这类「运营策略」参数值得下放到 DB + 页面。

---

### T4 · 体验与交付

#### T4-1 YAML 全程只读（缺兜底出口）
- **现状**：27 处 YAML 展示统一走 `YamlBlock.vue`（只读 + 复制）。无任何写入路径，无 `kubectl apply` 等价物。
- **影响**：spec 里「先做基础表单，后续按需把高级字段转表单」的策略缺少兜底 —— 表单没覆盖的字段，用户在平台上完全无解，只能回 kubectl（而 kubectl 绕过了平台权限与边界校验）。
- **建议**：给已建模的资源加「YAML 编辑」（走既有的 SSA 通道），并显式接受「YAML 编辑会绕过表单校验」这一后果（需在 UI 明示）。

#### T4-2 租户视角总览缺失
- **现状**：`OverviewView.vue` 是管理员形状（集群/租户/分配三个统计 + 4 个平台快捷入口），并用 **API 域码门控**跳过无权限的调用 —— 租户成员进来看到的是三个 0，只剩「工作负载」一个入口。而 `/overview` 又是路由守卫的**无权回落目标**，所有租户成员都会落到这里。
- **建议**：按视图上下文分叉：管理员总览（现状）/ 租户总览（我的命名空间：配额水位、工作负载健康、异常 Pod 数、最近事件——依赖 T1-1）。

#### T4-3 列表能力弱
- **现状**：
  - `labelSelector` **后端已支持**（`ListCtx.labelSelector`，`api/index.ts:276`），但**前端零入口**（`grep -rn labelSelector --include=*.vue` 只命中 `ProjectedSourcesEditor` 里的无关用法）。
  - 搜索全是**客户端**按名称模糊过滤（如 `PodView.vue:24-29`），无状态筛选（HPA 页除外）、无分页、无导出（CSV/Excel）。
- **影响**：大集群（数百 Pod）下列表实用性急剧下降。
- **成本**：低-中。label 过滤几乎是「接一个输入框」，是性价比最高的一项。

#### T4-4 PVC 扩容缺失
- **现状**：controller 有 `PUT /pvcs/{name}`，但 `PvcView.vue:269` 明确写「PVC spec 创建后不可变，调整容量请删除重建」。
- **说明**：K8s 在 StorageClass 开启 `allowVolumeExpansion` 时支持在线扩容（不可缩容）。当前口径过严。

#### T4-5 应用级交付与发布抽象缺失
- **现状**：所有资源逐个创建，无「一组资源一起建」的模板/chart（`Helm` 在代码里仅作为「这是 Helm 管理的对象，别动」的判据出现）。无「发布」概念：金丝雀只能手工拼 Gateway 权重路由 + 多副本，无版本对比、无一键切流、无一键回退到某个发布版本（工作负载回滚只回滚 Pod 模板，不涉及路由）。
- **说明**：这是从「K8s 管理台」走向「应用交付平台」的分水岭。当前阶段可作为方向性缺口记录，不建议立即开工。

#### T4-6 无多集群汇总
- **现状**：所有资源页必须选定单一 `clusterId`（`ResourceContext` 级联）。跨命名空间 list-all 已做，但**跨集群**没有入口。
- **建议**：先做只读的「资源总览：某类资源在全部集群/命名空间的分布」，不做写。

#### T4-7 无全局搜索
- **现状**：无跨资源、跨命名空间的统一搜索（按名称找「这个命名空间里的某个资源」）。
- **说明**：体验项，非刚性。

---

### T5 · 集群运维深度

| 缺口 | 现状 |
|---|---|
| 控制面组件健康 | 集群详情只有节点/资源/工作负载，无 etcd / APIServer / Scheduler / ControllerManager 状态 |
| 证书到期时间 | 无（kubelet 客户端证书、APIServer 证书） |
| 集群与节点升级 | 无（无版本漂移视图，无升级编排） |
| 备份恢复 | 无 Velero / etcd 备份接入 |
| 镜像与仓库 | 无「镜像仓库」概念（Secret 可手工建 dockerconfigjson，但无 registry 凭据管理、无镜像列表） |
| 资源回收 | 无孤儿资源 / 未使用 PVC / 悬空 PV 的清理视图 |
| 既有失败用例 | `WorkloadConverterStaticIpTest` 在 HEAD 即 NPE（非本批引入，但属待修） |
| 疑似既有 bug | Pod / Workload / Namespace 三条网络 **TX** 指标带 `device=~"/dev/dm-.*"`（网络指标只有 `interface` 标签）→ 三条曲线很可能恒空，未实跑确证 |

---

## 四、批次编排（已按 §5 / §7 的裁定重排，2026-10-12）

> ⚠️ 本节已重写。上一版（G1–G7）写于裁定之前，含已被否掉的项，不再使用。
> 「不做」清单见 §5.2。下面是**已确认要做**的项及其依赖关系。

### 4.1 依赖关系（决定顺序，不是偏好）

```
审计日志 ──────────────┐（"谁用了哪个凭据"要靠它留痕）
                       ▼
凭据 / 连接管理 ──┬──▶ 镜像仓库 + autocomplete ──▶ CI 的产物去向
（横切，前置）     ├──▶ 应用级交付（自研薄应用层）──▶ Helm 商店（Harbor OCI / Artifact Hub）
                  └──▶ CI（Tekton + 拖拉拽）
集群组件展示 ─────────▶ Operator 管理（可安装可升级）
```

### 4.2 批次

| 批 | 主题 | 条目 | 说明 |
|---|---|---|---|
| **P1** | 独立小项（先跑通节奏） | 用户自助（含清掉 `register.html` / `forgot-password.html` 两个死占位）· 集群组件展示（含 OTel Operator）· 平台总览 | 互不依赖、低风险 |
| **P2** | 治理地基 | **审计日志**（唯一「不做就补不回来」的）· **凭据 / 连接管理**（§7.6，是 P3 四块的共同前置） | 审计要早，因为它要给后续所有写操作留痕 |
| **P3** | 观测闭环 | 工作负载级日志（+ `previous`）· 告警（PrometheusRule + 平台接收端点）· 日志采集（形态待定）· 服务网格可视化（自研拓扑 + Jaeger/Tempo） | 互相独立，可并行 |
| **P4** | 交付能力 | 应用级交付（自研薄应用层）→ Helm 商店 → Operator 管理 → CI（Tekton + 拖拉拽） | **本阶段主体，量大**；Operator 管理依赖 P1 的集群组件展示 |
| **P5** | 收尾 | 镜像仓库 + autocomplete（可在 P2 后就做）· PVC 扩容 · VolumeSnapshot · Istio 表单补偿 | |

### 4.3 三个先决问题（动手前必须定）

1. **release 状态存哪**（§7.1）—— 决定 Operator 管理与 Helm 商店的升级/回滚怎么做；
2. **日志采集器形态**（§6.6）—— OTel webhook 注入（零冲突）vs 自建 filebeat sidecar（与模板编辑/回滚/重下发冲突）；
3. **拖拉拽设计器的自由度档位**（§7.4.2）—— 阶段式（A，建议起步）vs 自由 DAG（B，成本大一个数量级）。

---

## 五、决策记录（2026-10-12 用户逐条裁定）

### 5.1 明确要做

| # | 项 | 原编号 | 备注 |
|---|---|---|---|
| 1 | 工作负载级日志（按 labelSelector 聚合多 Pod） | T1-2 第 2 项 | 前提是 T1-2 的 `previous` 支持，建议同批 |
| 2 | **日志采集功能** | 新增 | 不在原清单内。见 §5.4 耦合提醒 |
| 3 | **OpenTelemetry sidecar 采集** | 新增 | 不在原清单内。见 §5.4 |
| 4 | 告警：PrometheusRule 管理 + 平台侧告警接收落库 | T1-3 部分 | **Alertmanager 已部署（用户手工）、平台不做其管理面、不对外暴露**；集群侧 receiver 指向平台，平台接收端落库；**后续如何发送待定** |
| 5 | 审计日志 | T3-1 | |
| 6 | 用户自助（改密码 / 改资料） | T3-2 | |
| 7 | 集群组件展示 | T5 首项 | 落点在**集群详情页**内（原为「集群运维深度」整块，其余仍待定）。首批内容含 **OpenTelemetry Operator**（用户已部署） |
| 8 | 平台总览 | T4-2 对侧 | 租户概览已在做；本次确认**平台总览**要做 |

### 5.2 明确不做

| 项 | 原编号 | 用户理由 |
|---|---|---|
| 事件中心 | T1-1 | 暂不做 |
| Service 后端（Endpoints）视图 | T1-4 | 没啥用 |
| **NetworkPolicy** | T2-1 | **危险较高**（写错 = 断网），暂时不做 |
| Job / CronJob | T2-2 | 不做 |
| PodDisruptionBudget | T2-3 | 不做 |
| K8s 原生 RBAC 暴露 | T2-5 | 多此一举 |
| CRD 查看器 | T2-6 | 暂不做 |
| Ingress | T2-7 | 不对外使用 Ingress，有 Istio（判断见 §6.2） |
| **系统设置** | T3-4 | **暂时不做**（设计见 §6.3，留档备用） |
| 列表能力（label 过滤 / 状态筛选 / 分页 / 导出） | T4-3 | 没必要 |
| 集群运维深度（证书 / 升级 / 备份 / 镜像 / 资源回收） | T5 其余 | 暂不做，待定 |
| 指标 TX 疑似 bug | T5 末项 | 用户自行定夺，不再上报 |

### 5.3 待办 / 已定方案

| 项 | 状态 |
|---|---|
| **应用级交付选型** | ✅ **已定：自研薄应用层**（用户接受投入更大）。调研与推荐见 §6.4 |
| **VolumeSnapshot** | 方案见 §6.1，**排最后做** |
| **Istio 表单补偿** | 方案见 §6.2 |
| **PVC 扩容** | 方案已给。**记入待做，暂不开工** |
| **日志采集器形态** | 📝 两案均记入文档、**做时再定**：① OTel 自动注入 sidecar；② 自建 filebeat sidecar。见 §6.6 ⚠️ |
| **OTel sidecar 形态**（探针注入 vs collector 容器 vs 节点级） | 📝 先记文档，做时再定。见 §6.5、§6.6 ⚠️ |

### 5.4 关键澄清（2026-10-12 第二轮，化解上一轮两处误判）

用户答复澄清了三件事，其中两条**化解**了上一轮提出的「平台要开始往集群装东西」这一担心：

1. **分工边界：中间件与独立组件由用户手工部署，平台不做安装；但平台会以「注入 sidecar」的方式参与业务 Pod 的构成。**
   - OpenTelemetry Operator：**用户已自行部署**，平台只需把它**当作集群组件展示**在集群详情里。
   - Elasticsearch：**用户将在 `platform-system` 命名空间自行创建**（日志存储）。
   - Alertmanager：**已部署**，平台不做其管理面、不对外暴露。
   - 日志采集器：**两案待定，做时再定** —— ① 用已部署的 OTel Operator 自动注入 sidecar；② 自建 filebeat sidecar（见 §5.3、§6.6）。
   - ⇒ 界线应表述为：**平台不安装中间件与独立 Agent 组件**（OTel Operator / ES / Alertmanager 都由用户装）；
     但**会通过 sidecar 注入改写业务 Pod 的构成** —— 这是本次新增的一个能力，
     **它与「工作负载模板编辑 / 回滚 / 重下发」会互相干扰，必须在动手前处理**（见 §6.6）。
2. **告警链路**：Alertmanager 已存在，因此「PrometheusRule 只是规则定义、求值/路由靠别的组件」这一环**是通的**。
   平台的准确职责是：**不做 Alertmanager 的管理面（不暴露、不配置）**，只在平台侧实现一个
   **按 Alertmanager `/api/v2/alerts` 契约的接收端点**，把告警落库。（集群侧需把 receiver 指向该端点。）
3. **日志存储已定**：ES（用户在 `platform-system` 建）。仍待定的是**「谁把日志送进 ES」**：
   - 若由用户手工部署采集器 → 平台只做「ES 端点配置 + 查询 UI」，无新增基础设施责任；
   - 若由平台下发采集器 → 触发上面第 1 条被打破的情形，需先立「集群 Agent 生命周期」。
   **这一句需用户确认后才好定日志采集的功能范围。**

---

## 六、方案草案（用户点名要的几份）

### 6.1 VolumeSnapshot 方案（排最后做）

**范围**：命名空间级快照的创建 / 查看 / 删除 + **从快照恢复 PVC**。后半条已有基础 —— `PvcView` 已支持 `dataSourceRef`，恢复路径是通的，缺的是「造快照」这前半条。

**前置门控（决定这功能能不能落地，不是页面工作量）**：
- 探测 `snapshot.storage.k8s.io` 是否在集群 capability 里（`snapshot-controller` 是否部署）；
- 探测是否存在可用的 `VolumeSnapshotClass`。
- 两者缺一 → 整块隐藏（沿用现有 capability 门控范式，同 Gateway API / metrics-server）。

**对象模型**：三个 CRD 走**已有的通用 CRD 范式**（同 Calico / Gateway，`ResourceOperations` + converter + factory），不引入代码生成：
| CRD | 作用域 | 平台是否写 |
|---|---|---|
| `VolumeSnapshotClass` | 集群级 | 只读（驱动决定，平台不建） |
| `VolumeSnapshot` | 命名空间级 | **CRUD**（主体） |
| `VolumeSnapshotContent` | 集群级 | 只读（controller 创建） |

**状态判定**（决定 UI 好不好用）：
- `status.readyToUse=false` + `status.error` 空 → **进行中**，不是失败；
- `status.error` 非空 → 失败，把 `error.message` 原样展示（这是唯一能告诉用户为什么失败的地方）；
- `readyToUse=true` → 可用；`restoreSize` 用于恢复时校验目标容量。

**落点**：`PvcView` 行操作加「创建快照」；快照列表作为命名空间级资源页（新增页面，或 `PvcView` 内加 tab —— 建议独立页，因为它有独立的生命周期与删除语义）。

**三个必须写进 UI 的语义**：
1. 快照**不等于备份** —— 同集群、同存储后端，防不了存储本身故障；
2. `VolumeSnapshotClass.deletionPolicy`（`Retain` / `Delete`）决定删快照时底层数据是否一起删，要在创建时就展示；
3. 从快照恢复出的 PVC **容量不能小于 `restoreSize`**。

**权限**：按「资源域细粒度权限」既有范式新增 code 行（resource + action），并**显式绑角色**（不按 code 继承 —— 这是踩过的坑）。

**成本**：中。主要不确定性在集群侧是否装了 snapshot-controller。

---

### 6.2 Istio 对 Ingress 的表单补偿方案

**判断**：API 能力上 Gateway API 完全取代 Ingress（Ingress 已 feature-frozen，Gateway API 是官方继任者，且 Istio 入口网关本身就是 Envoy + Gateway API）。**唯一的落差是用户心智**：Ingress 一个对象 + 几个注解搞定 host/path/TLS，Gateway API 是三层对象。**这层复杂度该由平台吃掉** —— 下面是吃法。

**落差在哪（逐项对照）**：

| 场景 | Ingress 写法 | Gateway API 写法 | 落差 |
|---|---|---|---|
| 域名+路径指向服务 | 一个对象，`rules[].host` + `paths[]` | Gateway（含 listener）+ HTTPRoute（含 parentRef + rules） | **两个对象 vs 一个** |
| TLS | `spec.tls[{hosts, secretName}]` 一行 | listener 的 `tls.mode` + `certificateRefs`（+ 可能的 cert-manager `Certificate`） | 证书要另建 |
| 换入口（灰度/迁移） | 改 Ingress 的注解 | 改 HTTPRoute 的 `parentRefs` | 更清晰（这是优势） |

**方案：加一档「快速路由」，一次填完建两个对象**

1. **简单档（新增）**：一个表单收集 `域名 + 路径 + 后端 Service + 端口 + TLS（选已有 Secret 或留空）`，平台内部**自动拆成 Gateway + HTTPRoute**：
   - Gateway 侧：若该命名空间/入口已有匹配的 Gateway 则复用，否则建一个（listener hostname = 域名，protocol = HTTP/HTTPS）；
   - HTTPRoute 侧：parentRef 指向它，`rules[0].matches` = 路径，`backendRefs` = 目标 Service。
2. **高级档（保留）**：现有完整编辑器不动，供需要 header/方法匹配、权重、多后端、跨命名空间引用的场景。
3. **后端需要一次「编排式创建」**：Gateway + HTTPRoute 是两个对象，**失败一半会留下孤儿 Gateway**。
   需要一个后端编排端点（同 `NamespaceAllocationService` 的形态）：先建 Gateway → 建 HTTPRoute → 失败则回滚已建对象。
   前端不要自己发两次请求。
4. **意图化展示（比表单更关键）**：让用户看到的是「一个入口」，而不是两个对象 ——
   - Gateway 列表/详情里列出**它承载的 Route**；
   - HTTPRoute 列表里把 `parentRefs` 解析成 **Gateway 名 + 域名/路径摘要**，而不是让用户看引用数组。
5. **冲突检测（Gateway API 有而 Ingress 没有的坑）**：listener 的 hostname 在同一 Gateway 内必须唯一；
   HTTPRoute 之间的 hostname/path 重叠不报错、按优先级匹配，容易出现「改了不生效」。
   快速路由表单应在提交前检查这两项并明确提示。

**成本**：中-低。以「前端新表单 + 一个后端编排端点」为主，不需要新适配器。

---

### 6.3 系统设置设计（**暂不做**，此处留档）

关键约束（用户）：**平台有很多内部参数，需要一个管理员专属的收敛面，不能暴露出去。**

核心是**参数分两级**：
- **可在线改**（落 DB、写审计、标注生效方式）：保护命名空间列表（`platform.namespace.protected-namespaces`，**最该下放的一项目前硬编码在 yaml**）、权限闭包缓存 TTL（`platform.permissions.cache-ttl-seconds`，默认 30s）、集群聚合与 Calico 派生查询 TTL（45s，现硬编码在 `ClusterService`）、Thanos Query 端点、k8s-server 地址、告警 webhook 配置、日志(ES)/OTel 端点、默认配额与 LimitRange 模板。
- **只读展示且不回显明文**（来自配置文件）：kubeconfig AES key（只显示指纹/是否已配置）、MySQL/Redis 连接、OAuth issuer 与 jwk-set-uri。

模型：`platform_setting(setting_key, value, value_type, scope, category, description, editable, restart_required, updated_at, updated_by)`；`scope` 预留 `CLUSTER`。
三条纪律：每个参数标注**生效方式**（避免「改了没反应」）；影响面大的二次确认；**不做任意 key-value 自由编辑**（否则退化成配置中心、绕过类型校验）。

---

### 6.4 应用级交付：开源 Operator 调研（回答「有没有 op」）→ **选型已定：自研**

> **✅ 2026-10-12 用户裁定：走自研路线（自研薄应用层，接受投入更大）。**
> 下表保留作为选型依据 —— 若日后要开 chart 生态，模板来源从「平台内置」扩到「外部 repo」即可，
> 不必现在就把 Argo CD / KubeVela 请进来。

**结论：有，但没有一个能既开箱即用又保住你们的权限/边界模型。**逐项：

| 项目 | 类型 | 与本平台的关系 |
|---|---|---|
| **KubeVela** | CNCF incubating，OAM `Application` CRD + vela-core operator。2026 仍有 roadmap（2026.09 / 2026.12），活跃 | **最对口的「应用交付 operator」**。`Application = Components + Traits` 正是「一组资源一起建」。但它**自带完整平台**（VelaUX 前端、自有权限与租户体系）→ 与你们重叠最大，引进来等于两个管理面 |
| **Argo CD** | 有官方 `argocd-operator`；GitOps 事实标准 | **范式冲突**：主张「Git 是唯一真相源」，而你们是 DB + 表单驱动。且它是**第二个写入方**（持 cluster-admin 直写集群），绕过 `k8s-server` 边界校验 |
| **Flux** | 有 `flux-operator`；GitOps | 同 Argo CD 的范式冲突 |
| **Carvel kapp-controller** | `Package` / `App` CRD，只做「安装一组资源 + 持续调和」 | **API 最小、最轻**，最接近「只要一个执行器」；但绑定 Tanzu/Broadcom 生态 |
| **OpenKruise Rollout** | **不是应用交付**，是**发布编排**：金丝雀 / 蓝绿 / A-B，原生支持按 **Gateway API** 或 Ingress 切流量，带分批、暂停、审批 | **与你们 Gateway API 直接协同，纯增量**。正好补上「发布抽象」那一块，且不是第二写入方（只调和已有 Deployment + 改权重） |
| Crossplane / kro | 基础设施编排（用 K8s API 描述云资源） | 不对口 |
| Helm Operator（fluxcd） | **已废弃**，功能并入 Flux | 不考虑 |

**推荐组合**：
1. **应用交付——自研薄应用层**：`应用 = 平台模板 + values` → 渲染 → **复用现有 SSA apply 通道**。
   边界与权限天然继承、零新组件、与「一类一 controller」同构；**渲染引擎内部用 Helm**（`helm template`），
   即「产品形态自研、模板引擎借开源」。将来要开 chart 生态，只需把模板来源从「平台内置」扩到「外部 repo」。
2. **若坚持用现成 operator**：`kapp-controller` 最轻（但功能也最薄）；`KubeVela` 最对口（但重叠最大）。
3. **发布/灰度单独引入 `OpenKruise Rollout`** —— 这条与上面两条不冲突，是叠加项，建议一并评估。

> 来源：[KubeVela Roadmap 2026.09](https://kubevela.io/docs/roadmap/2026-09-roadmap/) · [KubeVela Roadmap 2026.12](https://kubevela.website.cncfstack.com/docs/roadmap/2026-12-roadmap/) · [OpenKruise Rollout 简介](https://openkruise.io/rollouts/introduction) · [Kruise Rollout 蓝绿发布](https://openkruise.io/zh/rollouts/user-manuals/strategy-bluegreen-update) · [ACK: 用 Kruise Rollout 做金丝雀与 A/B](https://help.aliyun.com/en/ack/ack-managed-and-ack-dedicated/user-guide/use-kruise-rollout-to-perform-canary-releases-and-a-b-testing)

---

### 6.5 OTel sidecar 形态（**待定，先记录**）

用户要求：先写进文档，做的时候再定夺。

| 形态 | 优点 | 代价 |
|---|---|---|
| **每 Pod 一个 collector sidecar** | 隔离性好；配置随 Pod 走；单 Pod 粒度可控 | **每个 Pod 多一个容器** —— 资源开销、侵入性、镜像与升级面全部放大；多一次容器启停影响 Pod 就绪 |
| **节点级 DaemonSet collector**（+ 应用侧 SDK 自动注入） | 每节点一个，资源固定；升级只动 DaemonSet | 节点级故障影响该节点全部应用；精细配置能力弱于 sidecar |

**补充事实**：**OpenTelemetry Operator 已由用户部署**（因此平台**不需要**安装它），
其 `Instrumentation` CRD 做的是「给 Pod 自动注入探针/agent」，`OpenTelemetryCollector` CRD 才是 collector 本体
（可设 `mode: sidecar` / `daemonset` / `deployment`）。**形态选择最终落在 `OpenTelemetryCollector.mode` 上**，
而不是两套不同实现 —— 这一点降低了该决策的成本。

---

### 6.6 ⚠️ sidecar 注入 vs 模板编辑：两条路技术性质不同（「做时再定」的决策依据）

日志采集器的两案**不是同一方案的两种实现**，对平台的侵入面完全不同：

| 对比项 | ① OTel 自动注入 sidecar | ② 自建 filebeat sidecar |
|---|---|---|
| 注入机制 | **MutatingWebhook**（OTel Operator 提供），作用于 **Pod 对象** | 写进 / 下发时改写 **Pod 模板**（`spec.template`） |
| 是否落到 Pod 模板 | **否** —— 模板对象不变，只影响实际创建的 Pod | **是** —— 模板里真的多一个容器 |
| 与工作负载编辑器的关系 | 无冲突：用户看到的模板就是他自己写的 | **冲突**：编辑器里会看到注入的容器，用户可能改坏或删掉 |
| 与回滚的关系 | 无冲突 | **冲突**：回滚到历史版本会**冲掉注入的 sidecar** |
| 与「重下发全部模板」(STS/DS) 的关系 | 无冲突 | **冲突**：重下发可能重复注入或丢注入 |
| 与既有判据的关系 | 无 | 关联「有 ownerReferences 的对象不写」（Operator/Helm 管理的对象）这条判据 —— 注入后要重新界定「谁拥有这个模板」 |

**结论**：
- ① 与平台现有能力**近乎零冲突**，基本只是「创建一个 CR + 打注解」；
- ② 需要平台**自建一层「sidecar 注入」**（提交/下发前 patch Pod 模板），并从编辑、回滚、重下发三条路径上保持一致，工作量与风险明显更大。

**建议**：做时优先评估 ①。若选 ②，必须先把「注入层 × 模板编辑/回滚/重下发」的交互设计清楚，否则会出现「回滚一次日志就没了」这类问题。

**顺带澄清 OTel 侧的两种 CR**（都叫「自动注入 sidecar」，但不是一回事）：
- `Instrumentation` → 注入**应用侧 SDK 探针**（Java/Node/Python/.NET），采 traces/metrics（logs 需应用配合）；
- `OpenTelemetryCollector` + `mode: sidecar` → 注入**一个 collector 容器**，从共享卷/文件采集并转发到 ES。

要「收集容器 stdout 日志送 ES」，对应的是后者（或 ② filebeat），**不是** `Instrumentation`。

---

## 七、新增需求（2026-10-12 第二轮追加，用户提出）

> **✅ 2026-10-12 用户裁定**：① Operator 管理**一定要有**，可安装可升级；**Helm 只是部署方式**，
> 「纯 Helm 商店」不等于 Operator 管理 —— **两者是两个模块**，不是一件事。② Helm 商店来源
> **本地 Harbor 与 Artifact Hub 都接**。③ 服务网格可视化**自研**。④ CI 走 **Tekton + 全流程**，
> 界面要**可视化拖拉拽做流程定制**。⑤ **要有专门的密钥/配置管理工具集**。⑥ Docker Hub
> autocomplete 按本文建议接（PAT + 缓存 + 静默降级）。

### 7.1 中间件管理（Operator 管理）— 独立模块，可安装可升级

**用户明确：这是独立模块，不要用「Helm 商店」替代它。**区别在于**产品面不同**：

| | Operator 管理 | Helm 商店 |
|---|---|---|
| 面向 | **Operator 实体**：名称、版本、命名空间、健康、它提供的 CRD、CRD 的实例数与状态、**升级** | **chart**：搜索、values 表单、安装 |
| Helm 的位置 | **只是其中一种部署方式**（Operator 也常用自己的 manifest bundle / OLM） | 是主体 |

**因此 Operator 管理的产品面至少要有**：
1. 已装 Operator 列表：按 CRD group + controller Deployment 判定，展示版本 / 命名空间 / 健康；
2. **它提供了哪些 CRD、这些 CRD 有多少实例**（这是 Operator 的价值所在 —— 用户要从 Operator 找到它管理的东西）；
3. **安装 / 升级**：安装来源可以是 Helm chart、也可以是 manifest bundle；升级要能看当前版本与目标版本；
4. 与「集群组件展示」（§5.1 #7）的关系：**组件展示是这一块的只读地基**，先做展示再做安装。

**⚠️ 升级/回滚的 release 状态存哪（沿用上一版结论，仍然是必须先定的点）**：

| 路线 | 优点 | 代价 |
|---|---|---|
| (a) 平台自存状态（DB 记 values + 渲染历史） | 纯平台侧、零新依赖、边界模型不变 | 与「血统」分离 —— 若有人用 helm CLI 动过同一个 release，平台不知道，会覆盖 |
| (b) 真正走 `helm install/upgrade`（Helm SDK/CLI） | 生态兼容、release 状态在集群里、平台读它 | **Helm 成为第二个写入方**（范围比 Argo CD 小，可限制其只能写受管对象） |

### 7.2 Helm Chart 商店（可直接执行部署）

**与已定的「自研薄应用层」同一条路**（§6.4）：商店只是把**模板来源**从「平台内置」扩到「外部来源」，
渲染与 apply 通道不变。

- **来源（用户裁定：两者都接）**：
  - **本地 Harbor（OCI chart）** —— Harbor 原生支持托管 Helm chart（见 [Harbor: Working with OCI Helm Charts](https://goharbor.io/docs/2.14.0/working-with-projects/working-with-oci/working-with-helm-oci-charts/)）。**一个 Harbor 同时当镜像仓库和 chart 仓库**，凭据与 §7.5 共用（见 §7.6）。
  - **Artifact Hub 公共索引**（公开 API）—— 用于发现/搜索，安装仍走平台渲染 + apply。
- **安装流程**：选 chart → 填 values（表单 / YAML）→ 渲染 → 走平台 apply 通道。
- **必须解决**：§7.1 的 release 状态问题（与 Operator 管理共用同一答案）。
- **租户可见性**：商店对租户开放到什么程度（能否装任意 chart 到自己命名空间、怎么算配额、怎么限制危险 chart）—— 需产品定义。

### 7.3 服务网格可视化

**先纠正一个前提**：Istio 可视化**不是「Loki + 某组件」**。Loki 是日志存储（Grafana 系），
它和你们已定的 **ES 是二选一关系**，不是叠加关系 —— 日志已定 ES，就**不需要为了网格可视化再引入 Loki**。

Istio 官方配套的观测栈是四件：

| 关注点 | 组件 | 你们现状 |
|---|---|---|
| **拓扑/流量图** | **Kiali**（Istio 官方配套） | 无 |
| 指标 | Prometheus | ✅ 已有 Thanos（各集群 Prometheus remote-write 汇入、打 `cluster_name` 标签） |
| 链路追踪 | **Jaeger** 或 **Grafana Tempo** | 无 |
| 日志 | Kiali 的日志视图可直连 **Loki 或 Elasticsearch** | ES（用户将建）→ **Kiali 可直接接 ES** |

参考：[Kiali + Tempo 追踪集成](https://v2-26.kiali.io/docs/configuration/p8s-jaeger-grafana/tracing/tempo/)

**真正的选型问题：引入 Kiali 还是自研拓扑？**

| | 引入 Kiali | 自研拓扑 |
|---|---|---|
| 功能深度 | 开箱即用；配置校验、流量图、tracing 联动、日志视图 | 起步只做「拓扑 + 关键指标」 |
| 与平台的关系 | **自带完整 UI 与权限模型**（自己的登录、自己的命名空间可见性）→ 又一个「平台」（同 Argo CD / KubeVela 的问题），与你们的租户/权限点体系是两套 | 与「统一门户 + 统一权限」一致 |
| 数据源 | 新增一个组件与运维面 | **数据已在你们手里**：Thanos 里的 `istio_requests_total` / `istio_request_duration_milliseconds`（Envoy 侧自动产出），拓扑的边（谁调谁）可直接由指标的 source/destination 标签推出；节点信息你们已有 Service / Gateway / HTTPRoute 全套 |

**建议**：分两步 —— ① **自研流量拓扑 + 关键指标**（数据源 = 已有 Thanos 的 `istio_*` 指标，投入可控、
不引入新组件、权限天然继承）；② 链路追踪单独接（Jaeger / Tempo，嵌入或跳转）。

**待确认**：你们的网格是 **ambient 模式**（ztunnel + waypoint）。引入 Kiali 前需确认其对 **ambient** 的支持程度；
自研路线不受此限制（指标照样产出）。

### 7.4 CI（平台缺 CI 能力）

**选型前的两个 gating 问题（比引擎选型更靠前）**：
1. **代码托管在哪**（GitLab / Gitea / GitHub / 其他）—— CI 必须由 SCM 事件触发，这决定「是否根本不需要引擎」。
2. **范围**：只要「源码 → 镜像 → push 到 Harbor」，还是要完整流水线（单测 / 扫描 / 部署）？

| 引擎 | 形态 | 与平台的关系 |
|---|---|---|
| **Tekton** | K8s 原生 CRD（Task / Pipeline / TaskRun / PipelineRun / Triggers） | **无自有用户与权限体系**，Dashboard 很轻 → 平台自研 UI + 平台权限点正好补上；可复用现有通用 CRD 范式。**推荐** |
| **Jenkins** | 独立应用；K8s 上只是「agent 跑成 Pod」 | 插件生态最大、团队熟悉度可能最高；但是**独立的第二个平台**：自有用户/凭据/Job 体系、有状态 controller、插件维护负担 |
| **Argo Workflows** | CNCF 项目，K8s CRD（Workflow / CronWorkflow） | DAG 表达力强于 Tekton，更适合通用工作流编排；CI 生态（缓存、构建步骤库）较薄；自带 Argo UI（第二个界面） |
| **Woodpecker / Drone** | 轻量、配置文件驱动、容器原生 | 生态小 |
| **GitLab CI** | SCM 自带 | **若代码在 GitLab，成本最低**（只需注册 Runner）—— 不要为「平台里有个 CI 页面」而引入引擎 |

**推荐理由（与 §6.4 同一条逻辑）**：**平台拥有产品形态（流水线定义 / 权限 / 历史），开源只当执行器**。
Tekton 无自有权限体系、不构成第二个平台，选它最一致。

#### 7.4.1 已裁定：Tekton + 全流程

代码托管位置**未知**，因此需要一层 **SCM 连接抽象**（与 §7.5 的 registry connection 同构）：
连接类型可配（GitLab / Gitea / GitHub / 通用 Git），凭据统一走 §7.6 的凭据库。
这样「代码托管在哪」就不必现在回答 —— 它是配置项，不是设计约束。

**与已定项的连接**：CI 的产物去向是 §7.5 的镜像仓库，终点是「应用级交付」。
边界：**平台负责定义 / 触发 / 展示，构建执行交给 Tekton**。

#### 7.4.2 流水线可视化设计器（需求已细化，2026-10-12）

**用户给定的需求**：
1. **内置节点**：git / build / test / image / push 等 —— 本质是 **Task**，**具体配置由用户填写**；
2. **自定义节点**：可以自定义，但**不在系统永久保留，只在流水线中存在**，流水线删除即删除该节点；
3. **节点连线由用户自由** → **即 §7.4.2 旧版的 B 档（自由 DAG），A 档（阶段式）被否**。

**映射到 Tekton**：Tekton `Pipeline` 的 `tasks[]` 带 `runAfter` 依赖图，自由连线就是这张图；
`Pipeline` 另可挂 `finally[]`（无论成败都跑）。

##### 四个技术后果（前两个会改需求，需确认）

**(1) ⚠️「没连线」= 并行执行，不是「不执行」**
Tekton 里没有 `runAfter` 关系的 task **会并发跑**。用户画一张「几个节点并排、没连起来」的图，
意图可能是"先不启用"，实际是**全部并行执行**。画布必须显式表达这一点（例如未连线的孤立节点给出强提示 /
明确画出并行分组），否则这是最容易出事故的一处误解。

**(2) ⚠️ 连线只表达「依赖顺序」，表达不了「条件分支」→ ✅ 用户裁定：条件分支要做**
依赖图与控制条件是**正交的两个维度**，连线画不出条件。Tekton 的实现机制是 task 的 **`when`**
（`input` + `operator`(in/notin) + `values[]`，多个 `when` 之间是 AND）。

- **UI 形态建议**：节点属性里加「执行条件」，用**「字段 + 操作符 + 值」三段式下拉**生成，
  不要让用户写表达式 —— `in` / `notin` 覆盖绝大多数场景。
- **⚠️ 必须让用户理解的语义**：Tekton 里 `when` 为 false 时，**该 task 及其所有下游 task 都会被「跳过」**
  （不是"走 else 分支"）。所以「if/else 两条路」的画法是**两条并行链各带互斥的 `when`**，
  而不是从同一个节点拉出两条线。画布若不表达"跳过会传播"，用户会画出「以为还在跑、其实已被跳过」的图。
- **`finally` 同步暴露**：「无论成败都执行」的收尾节点（通知 / 清理），与条件分支是紧密相关的一环。

**(3) 连线是控制流，不是数据流**
「build 产出的镜像 tag 给 push 用」在 Tekton 里是**参数引用** `$(tasks.build.results.IMAGE_TAG)`，
不是一条连线。**建议**：连线只做 `runAfter`；节点配置里凡是能引用上游产出的字段，
给一个**「上游节点」下拉**（候选 = 画布上连到本节点的那些节点），由下拉生成引用串 ——
既不用引入数据流图语义，又不用让用户手写 `$(...)`。

**(4) ⚠️ Workspaces（共享卷）是 Tekton 特有、且必须由平台吃掉的坑**
git 节点 clone 出来的代码怎么交给 build 节点？Tekton 靠 **workspace**（流水线声明 + 任务声明挂载点 +
PipelineRun 绑定卷）。这是 GitLab CI / Jenkins 用户**完全不会遇到**的概念，直接暴露会卡住所有人。
**建议：平台隐式注入一个共享 workspace 到所有节点**，用户完全不感知；自定义节点可选择声明额外挂载。

##### 「自定义节点只活在流水线里」有现成的技术对应

Tekton 的 `tasks[]` 支持 **`taskSpec`（内联 Task 定义）**，不需要独立 Task 对象 ——
**天然随流水线生灭**，与用户要的语义完全一致。而「内置节点永久保留」= 平台侧维护的**节点模板**。

**推荐架构：两边都内联，平台渲染时展开**

| | 存哪 | 理由 |
|---|---|---|
| 内置节点模板 | **平台 DB**（代码内置或 DB，版本化） | 「系统永久保留」的语义 |
| 自定义节点 | **平台 DB，隶属于该流水线条记录**（级联删除） | 「只在流水线中存在」的语义 |
| 渲染产物 | Tekton `Pipeline`，`tasks[].taskSpec` **全部内联** | 见下 |

内联方案顺带消掉了三个麻烦：**跨命名空间 Task 引用**（Tekton 的 `taskRef` 默认只能引同命名空间的 Task）、
**ClusterTask 已被官方废弃**、以及**内置节点版本化**（流水线记「用了 git 节点 v3」，渲染时展开 v3 的 spec；
若用 `taskRef` 引用共享 Task，改模板会**静默改变所有已有流水线**的行为）。

**代价**：与 Tekton Hub / bundles 生态解耦。可另开一条「从 Hub 导入为平台节点模板」的路来补。

##### 其他必须定的点

- **节点配置表单需要 schema**：内置节点要带字段 schema（类型 / 校验 / 默认值 / 中文说明 / 是否可引用上游）。
  否则「配置由用户填写」会退化成让用户手写 YAML。
- **画布 JSON 要带版本号**，将来加字段才能迁移。
- **前端画布选型**（Vue 3 + Element Plus 栈内）：**LogicFlow**（滴滴开源，中文文档、国内使用广，
  对有向流程 + 自定义节点更顺手）或 **Vue Flow**（React Flow 的 Vue 3 版，与 Vue 生态更贴）。
- **真相源与漂移**：流水线定义的真相源在**平台 DB**，Tekton 侧的 `Pipeline` 是**派生物**。
  需明确「平台不读回 Tekton 侧」，并给渲染出的对象打标签；检测到被手改则告警
  （与 §7.1 的 release 状态问题同构）。
- **⚠️ 安全**：自定义节点能填任意镜像与命令 → 见 §7.4.3，这是平台权限最高的一条路径。
- **流水线的触发方式 → ✅ 用户裁定：只做手动触发**

  流水线建好后，**必须由用户点击**才创建 `PipelineRun`。**不做 webhook、不做定时。**

  **这一刀砍掉了不少东西**（连带砍掉了一整块安全面）：
  | 被砍掉 | 说明 |
  |---|---|
  | Git 侧 webhook 配置 | 不需要往 Git 服务里写回调 |
  | 平台侧回调端点 | **平台不需要对外暴露任何入口**（与"平台功能不能暴露出去"的偏好一致） |
  | 签名校验 | 没有入站回调，就没有伪造触发的问题 |
  | SCM payload 归一化层 | 不用适配 GitLab/Gitea/GitHub 三种事件格式（"代码托管在哪未知"的影响进一步收窄） |
  | Tekton Triggers | 整块不需要（`EventListener` / `TriggerBinding` / `TriggerTemplate` / `Interceptor`） |

  **注意**：§7.6 的 **git 连接仍然需要** —— 但用途收窄为「git 节点 clone 代码时的地址与凭据」，
  不再涉及事件投递。

  **手动触发模式下反而更要设计好的两点**：
  1. **「运行」表单**：每次运行要让用户填什么？需要在流水线定义里区分
     **「每次运行问用户的参数」**（分支 / tag / commit / 镜像 tag）与**「固定的配置」**，
     运行时只弹被标记为「运行时输入」的那些（Tekton 侧对应 Pipeline `params` → PipelineRun 传入）。
  2. **重复触发与并发**：用户手点容易连点。需定「同一流水线有运行中实例时」是禁止再触发、
     排队、还是允许并行 —— 平台层控制比让 Tekton 兜底更清楚。


#### 7.4.3 ⚠️ CI 是平台权限最高的一条路径

构建 Pod **能执行任意代码**。因此：**谁能在平台里定义流水线，谁就能在集群里跑任意容器**。
这条链路的权限设计不能沿用「资源 CRUD」的粒度，至少要定清三件事：
1. **独立权限点**（定义流水线 / 触发流水线 / 查看构建日志，分开授权）；CI 的日志可能含敏感信息；
2. **谁能把流水线跑在哪个命名空间**（沿用分配表边界，还是限定到专用构建命名空间）；
3. **镜像来源限制**（构建步骤的镜像是否允许任意 tag / 是否只允许来自已配置的 registry —— 与 §7.5 联动）。

> 这条与「中间件由用户手工装、平台不装组件」的分工**不冲突但不同性质**：平台不装东西，但 CI 让**平台用户**
> 能在集群里跑任意容器 —— 风险面从「平台自己做什么」变成了「租户能做什么」。审计（§5.1 #5）在此尤其重要。

### 7.5 镜像仓库 + 镜像 autocomplete

**设计骨架**：`registry connection`（地址 + 类型 + 凭据）→ 统一的 tags 查询接口 → 工作负载「更新镜像」处 autocomplete。

**凭据安全**：复用 kubeconfig 的 `AESUtils` 加密存储先例；**独立权限点**；**只读展示不回显密码**；变更写审计（与审计同批）。

**三档 repository 实现**（不能一套打通，各实现差异很大）：

| 类型 | 走什么接口 | 注意 |
|---|---|---|
| **Harbor** | **Harbor 自己的 REST API v2.0**（`/api/v2.0/projects/{p}/repositories/{r}/artifacts`，及全局 search） | **不要走 Docker Registry V2 的 `_catalog`** —— Harbor 默认关闭 catalog，且权限模型在它自己的 API 里 |
| 通用 registry v2 | `/v2/<name>/tags/list` | 各实现都支持；`_catalog` 普遍被禁，别依赖 |
| **Docker Hub** | 见下 | **可以接，但有三个前提** |

**Docker Hub 是否可以 autocomplete —— 可以，但要当「尽力而为的便利功能」，不能当可靠依赖**：

1. **认证**：匿名按 IP 限流、极易 429 → 应支持配置 **Personal Access Token**（官方 API 见
   [Docker Hub API](https://docs.docker.com/reference/api/hub/latest/)，需 PAT）。
2. **接口选择**：社区事实通用的是 `hub.docker.com/v2/repositories/{ns}/{repo}/tags?page_size=…`
   （registry 站点自身用的接口，**无官方 SLA 承诺**）；官方 API 的 tag 读取能力有限，
   且历史上出过问题（[docker/hub-feedback #2484](https://github.com/docker/hub-feedback/issues/2484)）。
3. **必须缓存 + 静默降级**：取不到就退回自由文本输入，**不弹错、不阻塞** —— autocomplete 不能成为
   「能不能填镜像」的硬依赖。

**自然的延伸（建议一并做）**：配好 registry 凭据后，**自动为命名空间生成 imagePullSecret** ——
否则工作负载拉私有镜像还要手工建 Secret。写 Secret 在现有边界模型内，不需要新能力。

### 7.6 密钥 / 配置管理工具集（横切能力）

用户要求「有专门工具集管理存密钥、配置等」。

> **⚠️ 2026-10-12 复审修正**：上一版把消费方列为 5 处、定性为「全平台地基」，**框架过大**。
> 逐条核对后**实际只有两类**：

| 消费方 | 是否真需要 | 说明 |
|---|---|---|
| **CI**（§7.4） | ✅ | git 凭据、构建用变量与密钥 |
| **镜像仓库**（§7.5） | ✅ | Harbor / Docker Hub / 通用 registry 的账号或 PAT |
| **Helm 商店**（§7.2） | ✅（**与上一条是同一连接**） | chart repo / Harbor OCI —— 就是那个 Harbor，不另存一份 |
| 应用级交付（§6.4） | ❌ **不需要** | 目标集群 kubeconfig **已加密存在 `k8s_cluster` 表**（`AESUtils`）。除非将来部署到未纳管的外部环境 |
| 告警（§5.1 #4） | ❌ **不需要** | 平台侧是**入站** webhook（Alertmanager 打过来），只需校验密钥 = 配置项；出站发送渠道**待定** |

**实际范围 = 两类连接：git 连接 + registry/chart-repo 连接（后者同一条 Harbor 复用）。**

**它仍然不适合塞进 CI 的 spec**，理由是**顺序**而非概念：
**镜像仓库（S3.1）排在 CI（S3.5）之前**，若凭据模型写在 CI spec 里，S3.1 就要引用一份尚未产出的 spec。
若要合并，**该合的是镜像仓库**（顺序相邻，且 registry 凭据本就是同一件事）。

**它不是「抽公共基类」**（那是 2026-10-09 明确否决过的形态）：凭据管理是**有表、有页面、有权限点、
有独立生命周期的实体模块**，与「集群管理」「用户管理」同级，不是被多个资源继承的基类。

**必须一次定清的三条口径**（这正是它不宜散在多个 spec 里各定一次的原因）：
1. **加密与主密钥来源**：复用 kubeconfig 的 `AESUtils` 先例；主密钥仍来自配置（**不进 DB、不上页面**，同 §6.3 的只读级）。
2. **三段权限 + 永不回显**：读 / 写 / 使用 分开授权；列表与详情只返回「是否已配置 + 指纹」。
3. **使用留痕**：每次「使用」落审计（谁在什么时候用哪个凭据做了什么）—— 审计（§5.1 #5）要靠它当事件源。

**另需定**：**投递能力**（凭据要能注入构建 Pod、生成命名空间 Secret / imagePullSecret），
并明确「谁能读明文、投递到哪」。

### 7.7 服务网格可视化（已裁定：自研）

按 §7.3 的结论执行：**自研流量拓扑 + 关键指标**，数据源 = 已有 Thanos 的 `istio_requests_total` /
`istio_request_duration_milliseconds`（Envoy 侧自动产出），拓扑的边由指标的 source/destination 标签推出；
节点信息复用已有的 Service / Gateway / HTTPRoute 数据。**链路追踪**单独接（Jaeger / Grafana Tempo），
嵌入或跳转皆可。**不引入 Kiali**（自带完整 UI 与权限模型，与统一门户/统一权限冲突），
**也不引入 Loki**（日志已定 ES）。

---

## 八、附：本文的判断边界

- 「缺失」以**平台自身代码**为准。集群里可能已装有 Grafana / Loki / Velero / cert-manager，但平台没有接入面 —— 本文把它们记为**接入缺口**而非「平台没有」。
- 未做**运行时实测**（无活后端），判断来自静态盘点。T5 里两条既有问题沿用既有记录，未重新确证。
- 未评估**外部依赖可达性**（如 Alertmanager 是否部署、CSI 是否支持快照），实施前需按集群 capability 探测。
