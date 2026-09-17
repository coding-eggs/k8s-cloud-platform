# B3 · 命名空间管理增强（ResourceQuota / LimitRange / 描述 / 概览）

- **日期**：2026-09-14
- **状态**：草稿，待评审
- **覆盖条目**：原始清单 8
- **范围**：本阶段只交付规格；实现另开
- **CRD/资源**：`v1 Namespace`、`v1 ResourceQuota`（plural `resourcequotas`）、`v1 LimitRange`（plural `limitranges`）——均为 core/v1，fabric8 有类型化模型

---

## 1. 背景与目标

当前命名空间管理（`NamespaceView.vue`，属「平台管理」组、集群级）**只有删除**：无创建、无编辑、无描述字段、ResourceQuota/LimitRange 完全未建模。本批把命名空间管理补全为「创建 / 编辑 / 概览」三件套，并把资源约束（配额 + 限制范围）纳入其中。

**用户重构的关键决策**：创建/编辑命名空间 = 设置该命名空间的 ResourceQuota + LimitRange（+描述/标签）。因此**编辑器是唯一编辑面**，概览页专注查看、不做内联编辑。

| 子项 | 目标 |
|---|---|
| 8a | 新增 **ResourceQuota / LimitRange** 建模与管理（具体字段本 spec 设计） |
| 8b | 新增**创建 / 编辑命名空间**，含**描述字段**（其他可编辑字段同模式：标签） |
| 8c | 新增**命名空间概览页**（namespace 级查看，展示项本 spec 设计） |

## 2. 范围

**In scope（本批交付）**
- 后端：`ResourceQuotaDTO` / `LimitRangeDTO`（+内部类）、两个 converter、两个 operations、`ResourceType` 增枚举、factory 注册、platform-api 命名空间管理端点（create/update/quota/limitrange get+upsert）、k8s-server 访问路径。
- 前端：`NamespaceView.vue` 增强（创建按钮 + 行操作查看/编辑/删除）；新增 `NamespaceEditorView.vue`（独立编辑页：名称/描述/标签/配额/限制范围）；新增 `NamespaceDetailView.vue`（概览页，只读 + 跳编辑）；前端 quantity 后缀工具；router / api client / types。
- **命名空间级能力开关 + 工作负载级 ambient**（评审后追加，见 §11）：Calico 绑定池（ns annotation）+ Istio ambient（ns/pod label）+ 工作负载列表快捷开关（`/workload/mesh-toggle`）。引用 B6/B7。

**Out of scope（v1 不做）**
- ResourceQuota 的 `spec.scopes` / `spec.scopeSelector`、`count/*` 选择器类 quota 项——编辑器只建模常用固定 key，其余靠 fetch-overlay 原样保留不丢。
- LimitRange 的 `maxLimitRequestRatio` 之外的边角 type（如 ContainerFixed）——v1 建模 Container/Pod/PersistentVolumeClaim 三类，ContainerFixed 靠 overlay 保留。
- 命名空间的**租户分配**流程改造（现有 `AllocateNamespaceDialog` 不动）。
- quota 用量以外的**跨对象资源聚合**（如全 ns Pod requests 汇总）——用量直接读 `ResourceQuota.status.used`（K8s 已算好），不自建聚合。

## 3. 现状盘点（探查结论）

| 项 | 现状 | 对本批的意义 |
|---|---|---|
| `/namespace` 端点 | platform-api `NamespaceController`：仅 `POST /list`、`POST /delete`；**无 create/update/get-detail** | 需新增 create/update + quota/limitrange get+upsert |
| 命名空间创建 | 经 `K8sAdminClient.ensureNamespace`（k8s-server `/admin/namespace/create`，幂等、打 managed-by 标签）；UI 未暴露 | 创建流程要在此基础上补 description/labels/quota/limitrange |
| NamespaceDTO | `name/phase/creationTimestamp/labels`；**无 description** | 描述走 `metadata.annotations["description"]`（同 WorkloadDTO 模式），需扩 DTO + converter |
| ResourceQuota/LimitRange | **代码库零建模**（grep 无命中） | 从零加 DTO/converter/operations/ResourceType |
| 详情页范式 | 近期 commit 已建 `NodeDetailView`（tab 布局 + 双栏 + 监控面板） | 命名空间概览页复用该范式（tabs） |
| Quantity 约定 | 平台 DTO 层 Quantity 统一 **BigDecimal 基础单位**（cpu=核/memory=字节），工具 `QuantityUtil.toBase/fromBase`（k8s-core） | quota/limitrange 值遵循同约定；前端需新增后缀解析/格式化工具 |
| 归属 | NamespaceView 属「平台管理」组、集群级（非租户上下文） | quota/limitrange 由**平台管理员**在该命名空间上下文管理，非租户侧 |

## 4. 数据模型（platform-common）

> 值单位：cpu=核数、memory=字节，均 `BigDecimal`（基础单位）；计数类（pods/services/pvc）为 `Integer`。converter 用 `QuantityUtil.toBase/fromBase` 与 K8s Quantity 互转。

### 4.1 `ResourceQuotaDTO extends BaseResources`
| 字段 | 类型 | 说明 |
|---|---|---|
| `cpu` / `memory` | BigDecimal | `hard.cpu` / `hard.memory`（核/字节） |
| `pods` / `services` | Integer | Pod / Service 数量上限 |
| `limitsCpu` / `limitsMemory` | BigDecimal | `hard.limits.cpu` / `hard.limits.memory`（容器 limit 总和上限） |
| `requestsCpu` / `requestsMemory` | BigDecimal | `hard.requests.cpu` / `hard.requests.memory`（容器 request 总和上限） |
| `persistentVolumeClaims` | Integer | PVC 数量上限 |
| `used` | ResourceQuotaUsedDTO（只读，查询返回） | 来自 `status.used`，同构字段（cpu/memory/pods/…），基础单位 |
| `creationTime` | String | 仅查询返回 |

- `getApiPath()` → `"/resources/resourcequotas"`。
- **overlay**：update 时未建模的 hard key（如 `count/deployments`、`services.nodeports`）原样保留（fetch-overlay，同 ServiceMonitor）。

### 4.2 `LimitRangeDTO extends BaseResources`
| 字段 | 类型 | 说明 |
|---|---|---|
| `limits` | List<LimitRangeItemDTO> | 限制项列表 |
| `creationTime` | String | 仅查询返回 |

**内部类**：
- `LimitRangeItemDTO { type, max, min, default, defaultRequest, maxLimitRequestRatio }`
  - `type` ∈ `Container` / `Pod` / `PersistentVolumeClaim`（v1 建模这三类；`ContainerFixed` 靠 overlay 保留）
  - `max/min/default/defaultRequest/maxLimitRequestRatio` 均为 `ResourcePairDTO { cpu: BigDecimal, memory: BigDecimal }`（可空，未设即不写）
- `getApiPath()` → `"/resources/limitranges"`。

## 5. 后端各层改动

### 5.1 k8s-core
- **converter**：`CoreV1ResourceQuotaConverter`、`CoreV1LimitRangeConverter`（core/v1 类型化模型；hard/used/limits 的 Quantity ↔ BigDecimal 走 `QuantityUtil`）。
  - ResourceQuota `convertForUpdate` + fetch-overlay：未建模 hard key 原样保留。
  - LimitRange `convertForUpdate`：未建模 type（ContainerFixed）原样保留。
- **operations**：`ResourceQuotaOperations`、`LimitRangeOperations implements NamespacedOperations<...>`（core/v1 namespaced；list/get/create/update(SSA)/delete/yaml/checkExist）。
- **factory `KubernetesOperationsFactory.build()`**：注册两 case（单版本，直接 new，无需 capability 分派）。

### 5.2 platform-common
- 新增 `ResourceQuotaDTO`、`LimitRangeDTO`（+内部类）。
- `ResourceType` 增枚举：`RESOURCE_QUOTA(ResourceQuotaDTO.class)`、`LIMIT_RANGE(LimitRangeDTO.class)`。
- `NamespaceDTO` 增 `description`（映射 `metadata.annotations["description"]`）；对应 namespace converter 读写该 annotation。

### 5.3 platform-api（`/namespace`，命名空间管理，平台级）
在现有 `NamespaceController` 上扩展（一页一 controller 原则下，配额/限制范围属命名空间管理的内聚能力，归此 controller）：
| 端点 | 说明 |
|---|---|
| `POST /namespace/create` | 建命名空间（name + description + labels），打 managed-by 标签；幂等 |
| `POST /namespace/update` | 改 description + labels（name 不可变） |
| `POST /namespace/quota/get` | `{clusterId, namespace}` → ResourceQuotaDTO（含 used）；无则空 |
| `POST /namespace/quota/upsert` | 创建或更新该 ns 的 ResourceQuota（SSA） |
| `POST /namespace/limitrange/get` | `{clusterId, namespace}` → LimitRangeDTO；无则空 |
| `POST /namespace/limitrange/upsert` | 创建或更新该 ns 的 LimitRange（SSA） |
- 现有 `/list`、`/delete` 不变。

### 5.4 k8s-server（边界，零业务逻辑）
- ResourceQuota/LimitRange 为 core/v1 namespaced 资源：按既有**资源 client 机制**暴露（ResourceType + boundary controller），或经 admin 路径（因命名空间管理属平台级、非租户上下文）。**访问路径二选一，实现时定**（见 §10 风险）；无论哪条，k8s-server 只透传、零业务。

## 6. 前端改动汇总

### 6.1 `NamespaceView.vue`（列表增强）
- 顶部：集群选择 + 刷新 + **「创建命名空间」**（跳 `/namespaces/editor`）。
- 行操作下拉（收敛，替代裸删除按钮）：**查看**（→概览页）、**编辑**（→编辑器 `?name=`）、**删除**（保留现有约束：仅平台管理且未分配租户可删）。
- 列：名称（点击开概览）/ 状态 / 描述（新增列，截断显示）/ 创建时间 / 管理方式 / 已分配租户 / 操作。

### 6.2 `NamespaceEditorView.vue`（新增独立编辑页）★唯一编辑面
路由 `/namespaces/editor`（创建）/ `?name=<n>`（编辑）。区块：
1. **基础信息**：名称（RFC1123，编辑态禁用）、描述（FieldHelp：存于 annotation，展示在列表与概览）、标签（LabelEditor）。
2. **资源配额（ResourceQuota）**：cpu / memory / pods / services / limits.cpu / limits.memory / requests.cpu / requests.memory / PVC 上限。每字段 FieldHelp（作用 + 单位）。值用 quantity 输入（用户输 `8Gi`/`500m`，提交转基础单位；留空=不设该项）。
3. **限制范围（LimitRange）**：按 type（Container / Pod / PersistentVolumeClaim）分组，每组 max/min/default/defaultRequest × (cpu, memory)。每字段 FieldHelp。留空=不设。
- **提交**：创建 → 依次 `namespace/create` → `quota/upsert`（若填了配额项）→ `limitrange/upsert`（若填了限制项）；编辑 → `namespace/update` + 两个 upsert。至少一个约束项或描述/标签有值才提交对应对象。
- **校验**：名称必填+RFC1123（创建）；quota 各值合法 Quantity；limitrange 同 type 内 max≥min、defaultRequest≤default（若都填）。

### 6.3 `NamespaceDetailView.vue`（新增概览页，只读 + 跳编辑）★
路由 `/namespaces/detail?name=<n>`。复用 `NodeDetailView` tab 范式：
1. **概览**：基础信息（名称/状态/创建时间/描述/标签/管理方式/已分配租户）+ 资源用量摘要（若定义了 quota：cpu/memory/pods 的 used/hard + 使用率；否则提示「未设配额」）。
2. **资源配额**：usage vs hard 表（每项 used / hard / 使用率%），只读；右上「编辑配额」→ 编辑器。
3. **限制范围**：各 type 的 max/min/default/defaultRequest 表，只读；「编辑限制范围」→ 编辑器。
4. **YAML**：namespace 原始 YAML（只读）。

### 6.4 quantity 工具 + 注册点
- **前端 quantity 工具**（新增 `utils/quantity.ts`）：K8s 后缀解析/格式化（`m/k/M/Gi/Mi/Ti` ↔ 基础单位 number），供配额表单输入转换与用量展示复用。
- **router**：`/namespaces/editor`(name `namespace-editor`)、`/namespaces/detail`(name `namespace-detail`)，meta group=平台管理；现有 `/namespaces` 保留。
- **api/index.ts**：`namespaceApi` 增 `create/update/quotaGet/quotaUpsert/limitrangeGet/limitrangeUpsert`。
- **types.ts**：`K8sResourceQuota`、`K8sLimitRange`（+内部类型）；`NamespaceView` 增 `description`。

## 7. 逐子项设计要点

### 8a · ResourceQuota / LimitRange
- 建模见 §4；编辑器内联（§6.2），概览只读展示（§6.3）。
- 一个命名空间一份 quota / 一份 limitrange（K8s 允许多份但平台收敛为单份，upsert 语义）。
- 未建模 key/type 靠 fetch-overlay 保留，编辑不丢。

### 8b · 创建 / 编辑 + 描述
- 描述 → `metadata.annotations["description"]`（同 WorkloadDTO）；标签 → `metadata.labels`。
- 编辑器统一承载 name/description/labels/quota/limitrange（用户重构：创建=设定约束）。

### 8c · 概览展示项（本 spec 设计）
- 四 tab：概览（信息+用量摘要）/ 资源配额（used vs hard）/ 限制范围 / YAML。
- 用量直接读 `ResourceQuota.status.used`（K8s 已算），不自建聚合；未设 quota 时显示工作负载计数占位。

## 8. 边界与异常

| 场景 | 行为 |
|---|---|
| 创建时 namespace 建成但 quota/limitrange 失败 | 非原子：提示「命名空间已建，配额/限制设置失败，可重试编辑」；不自动回滚（v1 已知取舍） |
| 该 ns 无 ResourceQuota / LimitRange | get 返回空；概览对应 tab 显示「未配置」+ 编辑入口 |
| quota 用量读取失败 / 集群不支持 | 概览用量区降级为「—」，不阻断其他 tab |
| 编辑存量含未建模 key/type 的 quota/limitrange | fetch-overlay 原样保留，前端只回显已建模项 |
| 删除已分配租户的命名空间 | 沿用现有约束（仅平台管理且未分配可删），配额/限制随 ns 级联回收 |

## 9. 验收标准（怎么算做完）

1. 创建命名空间：填名称+描述+标签+配额+限制 → 集群生成 Namespace（含 description annotation）+ ResourceQuota + LimitRange，值单位正确（cpu 核/memory 字节）。
2. 编辑命名空间：改描述/标签/配额/限制 → 更新生效；未建模 key/type 不丢失。
3. 概览页四 tab 全通：基础信息、used vs hard（含使用率）、限制范围表、YAML 只读；「编辑」跳编辑器并正确回填。
4. quantity 后缀输入/展示正确（`8Gi`↔字节、`500m`↔核）。
5. list 新增描述列 + 行操作查看/编辑/删除收敛为下拉；上下文切换刷新正确。
6. 无 quota/limitrange 的 ns：get 空、概览显示「未配置」，不报错。

## 10. 待确认 / 风险

- **k8s-server 访问路径**（§5.4）：quota/limitrange 走「资源 client（ResourceType+boundary controller）」还是「admin 路径」——取决于 K8sResourceClient 是否支持无租户上下文的 cluster+namespace 访问。实现时确认；两条都满足 k8s-server 零逻辑。
- **创建非原子**：三对象顺序创建，中途失败不回滚（v1 取舍）；如需强一致可后续加 best-effort 回滚。
- **前端 quantity 工具**：需覆盖 K8s 全部常用后缀 + 二进制/十进制；实现时确认是否已有可复用工具。
- **单份收敛**：平台假定每 ns 一份 quota/limitrange（upsert）；若集群已有多个，编辑器取第一个、概览提示存在多份。
- **描述列宽度/截断**：列表加描述列可能挤占空间，实现时定列宽与 tooltip 全文。

## 11. 命名空间级能力开关 + 工作负载级 ambient（评审后追加）★

> **来源**：B7（Calico）评审后用户追加——把「命名空间编辑」做成**命名空间级能力聚合点**，并把 Istio ambient mesh 下沉到工作负载。本节约束引用 B6（Istio / Gateway API）与 B7（Calico）已定能力，不新增独立子系统。**平台只读写 namespace / pod template 的保留 metadata，不部署任何对象。**

### 11.1 设计模型（保留 metadata 映射表）
| 能力 | 作用域 | metadata 类型 | key | 取值 |
|---|---|---|---|---|
| Calico 绑定池 v4 | namespace | **annotation** | `cni.projectcalico.org/ipv4pools` | JSON 数组字符串 `'["pool-1","pool-2"]'`；空=默认分配 |
| Calico 绑定池 v6 | namespace | **annotation** | `cni.projectcalico.org/ipv6pools` | 同上（IPv6 pool） |
| Istio 加入 mesh | namespace / pod | **label** | `istio.io/dataplane-mode` | `ambient` / `none`；pod 优先于 ns |
| Istio L7 waypoint | namespace / pod | **label** | `istio.io/use-waypoint` | `{waypoint-name}` / `none`；pod 优先于 ns |

> Calico 侧为原生机制（CNI 读 ns annotation 圈定自动分配的 pool，分地址族）；Istio 侧为 ambient label（ztunnel L4 + waypoint L7）。另两个 ambient label 不落在本批：`istio.io/ingress-use-waypoint`（依赖控制面 flag `ENABLE_INGRESS_WAYPOINT_ROUTING`，v1 不做）、`istio.io/waypoint-for`（打在 **Gateway** 上、归 B6，非 ns/pod 级）。

### 11.2 数据模型（platform-common）
- **`NamespaceDTO`** 增 4 字段：`ipv4Pools: List<String>` / `ipv6Pools: List<String>`（⇄ 两 annotation，JSON 数组 ↔ List）、`dataplaneMode: String` / `useWaypoint: String`（⇄ 两 label）。
- **`WorkloadDTO.podTemplate.labels`**（`Map<String,String>`，已建模）——ambient 两 label 落此；item13 的 `cni.projectcalico.org/ipAddrs` 落 `podTemplate.annotations`（B7），互不冲突。

### 11.3 后端各层
- **namespace converter**：双向映射 4 个保留 key（2 annotation + 2 label）；fetch-overlay 保留其余 metadata 不丢。
- **workload converter**：pod template `labels` 的 `istio.io/dataplane-mode` / `istio.io/use-waypoint` 两 key（revert 读 → DTO，buildTemplate 写）。
- **端点**：
  - namespace `create`/`update` 已随 metadata 携带这 4 字段（无需新端点）。
  - **`POST /workload/mesh-toggle`**（新增）：body `{clusterId, namespace, name, kind, dataplaneMode?, useWaypoint?}`（`null`=不动该 label，传值=设置含 `none`）。复用工作负载 update 路径（SSA fetch-overlay，**只改 pod template 这两个 istio label**，不碰其他字段、不受 A/B/C 校验拦截）。供列表快捷开关调用。
- **capability 门禁**：`hasCalico`（B7）/ `hasIstioAmbient`（B6 ztunnel DaemonSet 活探测）。

### 11.4 前端
- **命名空间编辑器「能力开关」区块**（第四区块，紧随限制范围）：
  - *绑定地址池*：IPv4 / IPv6 两个多选下拉（从 `/admin/calico/ippool` list 按 CIDR 族过滤，`:`→v6）；集群单栈时按 `K8sCluster.ipStack` 只显对应一个；留空=默认分配。门禁 `hasCalico`。
  - *服务网格（Ambient）*：`dataplane-mode` 三态选择器（跟随集群默认 / 纳入 ambient / 排除 none）+ `use-waypoint` 下拉（该 ns 的 waypoint Gateway，0/1；无则禁用 +「先创建 waypoint Gateway」跳 B6）。门禁 `hasIstioAmbient`。
- **工作负载列表操作栏**（`WorkloadView.vue`，现有 `el-dropdown` 旁加两个**显式** el-switch，不入下拉）：
  - *Ambient 流量*：on→写 `ambient` / off→写 `none`；回显 ambient=开 / none=关 / 无=跟随（灰）。
  - *L7 流量*：**on 时弹该 ns 的 waypoint Gateway 选择**（0/1，per-ns 唯一；0 个→阻止 +「先创建」跳 B6）→ 写 name / off→写 `none`。
  - 调 `POST /workload/mesh-toggle`；开关旁提示「切换将触发工作负载滚动更新」（改 pod template label → 控制器滚新 RS，存量 pod 需重建才带新 label）。门禁 `hasIstioAmbient`（L7 另需该 ns 有 waypoint GW）。
- **工作负载编辑器**（`WorkloadEditorView.vue`）：新增「服务网格（Ambient）」`el-collapse` 模块（与 B7 item13「固定 IP」并列）+ 右侧导航项；字段同列表开关（dataplane-mode 三态 + use-waypoint 下拉，默认不填=跟随 ns）。
- **保留 key 隔离**：通用 LabelEditor / annotation 编辑**排除** `istio.io/dataplane-mode`、`istio.io/use-waypoint`、`cni.projectcalico.org/ipv4pools`、`cni.projectcalico.org/ipv6pools`，由专用区块管理（防用户自定义 label/annotation 覆盖 mesh/pool 配置）。
- **概览页**：命名空间概览显示绑定的 v4/v6 pool + mesh 状态；工作负载详情显示 ambient/L7 状态。列表页可加「网格」列（ambient / L7:gw / —）。

### 11.5 waypoint Gateway 约束（→ B6 交叉引用）
- waypoint Gateway = B6 的 Gateway API `Gateway`（带 `istio.io/waypoint-for`）。**per-ns 唯一**：B6 创建/编辑 Gateway 时，该 ns 已存在 waypoint GW → 拒绝再建。
- `use-waypoint` 下拉数据源 = 该 ns 的 waypoint GW（0/1）；0 个 → use-waypoint 禁用 + 跳 B6 创建入口。

### 11.6 边界与门禁
| 场景 | 行为 |
|---|---|
| `hasCalico=false` / `hasIstioAmbient=false` | 对应区块/开关禁用 + 说明（未装 Calico / ambient mesh） |
| L7 开启但该 ns 无 waypoint GW | 阻止 +「先在该命名空间创建 waypoint Gateway」跳 B6 |
| per-ns 已有 waypoint GW 再建 | B6 拒绝（唯一性约束） |
| 选中的 pool 已删 / waypoint GW 已删 | 编辑回显标红提示，提交前须修正或清空 |
| 切换 ambient/L7 | 改 pod template label → 自动滚动更新；开关旁提示生效需重建 pod |
| 用户自定义 label/annotation 撞保留 key | 通用编辑器排除保留 key，专用区块独占管理 |

### 11.7 验收标准（追加）
1. 命名空间编辑：设绑定池（v4/v6）→ ns annotation `cni.projectcalico.org/ipv{4,6}pools` 正确写入（JSON 数组）；留空移除。
2. 命名空间编辑：设 ambient dataplane-mode / use-waypoint → ns label 正确写入；概览回显。
3. 工作负载列表两个显式开关：Ambient on/off 一键切换 pod template `istio.io/dataplane-mode`；L7 on 弹 gateway 选择后写 `istio.io/use-waypoint`、off 移除；无 waypoint GW 时 L7 不能开。
4. 工作负载编辑器「服务网格」模块：字段与列表开关一致，默认不填=跟随 ns。
5. 保留 key 隔离：通用 label/annotation 编辑器不出现这 4 个保留 key。
6. `hasCalico` / `hasIstioAmbient=false` 时各区块/开关禁用 + 说明。
