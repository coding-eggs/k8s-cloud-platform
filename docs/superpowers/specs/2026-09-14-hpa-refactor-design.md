# B2 · HPA 重构 + 工作负载联动 + metrics-server/prometheus-adapter 门禁

- **日期**：2026-09-14
- **状态**：草稿，待评审
- **覆盖条目**：原始清单 2–7
- **范围**：本阶段只交付规格；实现另开
- **前置**：复用 B1 已建立的「列表页 + 独立编辑页」范式与 `FieldHelp` / `makeResourceApi` / capability 机制

---

## 1. 背景与目标

当前 HPA 是**单页 + el-dialog**（`HpaView.vue`）：目标工作负载用「类型下拉 + 名称自由文本」输入、**全表单零 FieldHelp**、behavior 区块带一条静态告警「v1 集群不支持 behavior，填写会在保存时报错」。本批把它重构成与 ServiceMonitor/PodMonitor 一致的形态，并补齐六项能力：

| 条目 | 目标 |
|---|---|
| 2 | HPA 页面重构：工作负载改**下拉选择**；**每个字段加 FieldHelp**（作用 + 用法）；新增/编辑改**独立页面**（非 dialog） |
| 3 | **同一工作负载只能绑一个 HPA**：下拉禁选已绑定的 + 后端校验拒绝重复绑定 |
| 4 | 工作负载列表对**已有 HPA** 的加标识（badge） |
| 5 | 工作负载列表加操作「**添加 HPA**」，自动跳转新增 HPA 页并预选该工作负载 |
| 6 | **集群有 metrics-server / prometheus-adapter 才能展示/创建**：external 需 prometheus-adapter，其他需 metrics-server（按指标类型精确门禁） |
| 7 | **v2 集群应允许填写 behavior**：修复当前被误导告警「锁住」的问题，behavior 区块按集群实际版本能力动态启用 |

## 2. 范围

**In scope（本批交付）**
- 后端：新增 `HpaService`（一负载一 HPA 校验）；新增**集群 capability 读取端点**（条目 6/7 共用）；`ResourceType.HPA` 已存在无需改。
- 前端：`HpaView.vue` 重构为纯列表页（badge、行操作收敛、跳独立编辑页）；新增 `HpaEditorView.vue` 独立编辑页（工作负载下拉 + FieldHelp 全覆盖 + 指标类型门禁 + behavior 动态启用）；`WorkloadView.vue` 加 HPA badge + 「添加 HPA」操作；router / api client / types 注册。

**Out of scope（v1 不做）**
- HPA `status` 子资源的完整建模（当前仅回读 `currentReplicas`，保持）。
- metrics-server / prometheus-adapter 的**安装/部署**能力（平台只**检测**其 API group 是否存在，不负责装）。
- capability 探测本身的改造（复用现有 `getApiGroups` 全量快照 + `k8s_cluster.capability` 列，不新增探测逻辑）。

## 3. 现状盘点（探查结论）

| 项 | 现状 | 对本批的意义 |
|---|---|---|
| capability 机制 | `ClusterService.doRefresh` → k8s-server `/admin/cluster/capability/refresh`（`getApiGroups` **全量** discovery 快照）→ JSON（`group→versions[]`）写 `k8s_cluster.capability`；已有手动「刷新能力」按钮 + create/enable/kubeconfig 变化时 best-effort 自动刷新 | **条目 6/7 直接读这列即可**，无需扩展探测。全量快照已含 `metrics.k8s.io` / `custom.metrics.k8s.io` / `external.metrics.k8s.io` / `autoscaling` |
| capability 消费方 | 目前**仅 k8s-core 内部**（`KubernetesOperationsFactory.readApiVersions` 给 HPA 选 V1/V2），前端拿不到 | 需**新增一个读端点**把 capability 暴露给前端 |
| HPA 后端 | `platform-api/HpaController` = **纯透传**（仅 `K8sResourceClient`，无 service 层）；k8s-server `HpaController` 边界零逻辑；factory 按 capability 分派 V1/V2 converter | 条目 3 的**后端校验**需新增 `HpaService`（当前无 service 层） |
| HPA converter | `HpaV2Converter`：五类 metrics + behavior 全保真，value/averageValue 走 `QuantityUtil` BigDecimal 基础单位；`HpaV1Converter`：仅 CPU 利用率，**遇 behavior 显式抛 `HPA_V1_ONLY_CPU`** | v2 下 behavior 端到端本就能保存（见条目 7） |
| HPA 前端 | 单页 + el-dialog；targetKind(下拉 Deployment/StatefulSet) + targetName(**自由文本**)；零 FieldHelp；behavior 区块可编辑但带误导告警；详情抽屉(概览/YAML) | 重构为独立编辑页 + 工作负载下拉 + FieldHelp + behavior 动态启用 |
| 工作负载前端 | `WorkloadView.vue`：类型过滤 + 搜索 + 行操作下拉(编辑/暂停恢复/伸缩/Yaml/删除)；`isOpManaged`=ownerReferences 非空 | 加 HPA badge（条目 4）+「添加 HPA」操作（条目 5） |
| WorkloadDTO | `kind`(deployment/statefulset/daemonset)+`name`+`namespace` | HPA `scaleTargetRef{kind,name}` 可按 **kind+name** 交叉引用判断「已绑定」，纯前端可做 |

## 4. capability 读取端点（条目 6 + 7 共用）

### 4.1 后端
- **platform-api `ClusterController`** 新增：`POST /cluster/capability/get`（body `{clusterId}`，复用现成 `ClusterKeyRequest`；与既有 `POST /cluster/capability/refresh` 成对）。〔勘误 2026-09-24：原文写 `GET /resource/clusters/{clusterId}/capability`，与 ClusterController 实际 `@RequestMapping("/cluster")` 全 POST 约定不符〕
  - 从 `K8sCluster.capability` 列读 JSON，解析为 `Map<String, List<String>>`（group→versions）返回；列为空/无行 → 返回空 map `{}`（前端据此判定「未探测」）。
  - **零业务**：只读列 + 反序列化，不做任何推导（推导放前端，见 §4.2），符合「platform-api 薄 / k8s-server 零逻辑」。
- **k8s-server**：无需改（capability 已存 DB 列，不经 k8s-server）。

### 4.2 前端派生（`HpaEditorView` / `HpaView` 共用一个 composable，如 `useClusterCapability(clusterId)`）
拿到 group→versions map 后派生布尔：

| 派生量 | 判定 | 用途 |
|---|---|---|
| `hasMetricsServer` | `'metrics.k8s.io' in cap` | Resource / ContainerResource 指标可用 |
| `hasCustomMetrics` | `'custom.metrics.k8s.io' in cap` | Pods / Object 指标可用 |
| `hasExternalMetrics` | `'external.metrics.k8s.io' in cap` | External 指标可用 |
| `hpaSupportsBehavior` | `(cap['autoscaling'] ?? []).includes('v2')`，**capability 为空时视为 true**（对齐 factory 默认 V2） | behavior 区块启用/禁用（条目 7） |
| `probed` | map 非空 | 未探测时展示软提示而非硬阻断 |

> **api client**：`platform-web/src/api/index.ts` 在 `clusterApi` 增 `getCapability: (clusterId) => http.post('/cluster/capability/get', { clusterId })`（不走 `makeResourceApi`）。〔勘误 2026-09-24：原文的独立 `clusterCapabilityApi` + GET 路径按实现修正〕
> **types**：`K8sClusterCapability = Record<string, string[]>`。

## 5. 后端业务改动

### 5.1 `HpaService`（新增，条目 3 后端校验）
镜像 `ServiceMonitorService` 的落位（platform-api service 层承载业务规则）。`HpaController` 的 create/update 由「纯透传」改为经 `HpaService`：

- **`validateSingleBinding(body, excludeName?)`**：
  - 取 `body.scaleTargetRef{kind,name}` + `namespace`。
  - 经 `K8sResourceClient.list(HpaDTO)` 拉该命名空间全部 HPA，过滤出 `scaleTargetRef.kind==kind && scaleTargetRef.name==name` 且（update 时）`name != excludeName` 的既有 HPA。
  - 命中 ≥1 → 抛业务异常「工作负载「{kind}/{name}」已绑定 HPA「{existingName}」，一个工作负载只能绑定一个 HPA」（新增明确 `EnumResponseType`，如 `HPA_TARGET_ALREADY_BOUND`）。
- create 传 `excludeName=null`；update 传自身 name（允许改自己、拦别人）。
- list/get/yaml/delete 仍直连 `K8sResourceClient`（无需 service）。

> 与前端下拉禁用是**双保险**：下拉禁用是 UX，后端校验是硬约束（防直接调 API 绕过）。用户已确认「前端禁用 + 后端校验」。

## 6. 逐条目设计

### 条目 2 · HPA 页面重构
- **独立编辑页 `HpaEditorView.vue`**：替代 el-dialog。路由 `/resources/hpas/editor`（新建）/ `?name=<n>`（编辑），meta group=资源管理、context=full，与 ServiceMonitor/PodMonitor 编辑器同构。
- **工作负载下拉**（替代 targetKind + targetName 自由文本）：
  - 数据源：`workloadApi.list(ctxParams)` → 过滤 `kind ∈ {deployment, statefulset}`（DaemonSet 无 Scale 子资源，不可被 HPA 伸缩）。
  - 选项显示：`name`（+ kind tag）；**已绑定 HPA 的禁用**（条目 3，见下）。
  - 选中 → 填 `scaleTargetRef{kind, name}`。删除原「目标类型」「目标名称」两个字段，合并为单一「目标工作负载」下拉。
- **FieldHelp 全覆盖**：编辑页**每个字段**都挂 `FieldHelp`（作用 + 用法）。覆盖清单与文案示例见 §7.2。

### 条目 3 · 一负载一 HPA
- **前端**：编辑器加载时并行 `hpaApi.list(ctxParams)`，构建 `Set<`${kind}/${name}`>`；工作负载下拉中命中集合的选项 `:disabled`（tooltip「已绑定 HPA」）。编辑态下当前这条自身不拦。
- **后端**：§5.1 `HpaService.validateSingleBinding` 硬校验。

### 条目 4 · 工作负载列表 HPA badge
- `WorkloadView.vue` 加载时并行 `hpaApi.list(ctxParams)`，构建 `Map<`${kind}/${name}`, hpaName>`。
- 名称列：命中则加 `<el-tag type="success" size="small">HPA</el-tag>`（tooltip 显示绑定的 HPA 名）。与现有 `op` / `已暂停` tag 并列。

### 条目 5 · 「添加 HPA」操作
- `WorkloadView.vue` 行操作下拉新增「**添加 HPA**」（置于「伸缩」附近）：
  - `router.push('/resources/hpas/editor?targetKind=<kind>&targetName=<name>')`，编辑器读 query 预选目标工作负载。
  - **仅对可伸缩且未绑定的行显示**：`kind === 'daemonset'` → 不显示；已绑定 HPA（条目 4 的 map 命中）→ 不显示（该行已有 HPA badge，用户已知其状态，隐藏保持行操作简洁）。
- 编辑器收到预选后：目标下拉**预填该项、仍可切换**（更灵活；切换时其余选项仍受条目 3 禁用约束）。

### 条目 6 · metrics-server / prometheus-adapter 门禁
按指标类型精确映射到其真实依赖的 API group（比原话「external→adapter、其他→metrics-server」更准，覆盖 Pods/Object）：

| 指标类型 | 依赖 API group | 由谁提供 |
|---|---|---|
| Resource | `metrics.k8s.io` | metrics-server |
| ContainerResource | `metrics.k8s.io` | metrics-server |
| Pods | `custom.metrics.k8s.io` | prometheus-adapter（custom） |
| Object | `custom.metrics.k8s.io` | prometheus-adapter（custom） |
| External | `external.metrics.k8s.io` | prometheus-adapter（external） |

**门禁行为**：
- **页面级**（`HpaView` 列表页）：若 `hasMetricsServer && hasCustomMetrics && hasExternalMetrics` 全为 false → 「创建 HPA」按钮禁用 + 顶部/空态提示「该集群未安装 metrics-server / prometheus-adapter，HPA 暂不可用」。
  - **决策**：既有 HPA 列表**照常展示**（它们客观存在、metrics 恢复后仍会伸缩），只门禁「创建」；不隐藏存量数据。
- **指标类型级**（编辑器）：五类 metric type 下拉中，依赖 group 缺失的类型 `:disabled` + tooltip「需安装 metrics-server / prometheus-adapter」。已有该类型的存量 HPA 编辑时照常回显（不禁用已选值）。
- **未探测**（`probed === false`）：不硬阻断，展示软提示「集群 API 能力尚未探测，指标可用性未知；可点顶栏『刷新能力』」，五类默认全部可选。

### 条目 7 · v2 允许填写 behavior（修复）
**根因**：behavior 端到端在 v2 本就能保存（前端 `submit` 携带、`HpaV2Converter` 落 `spec.behavior`）；「不让填」的体感来自那条**静态误导告警**「v1 集群不支持 behavior，填写会在保存时报错」——它在 v2 下纯属吓人。

**修复**：behavior 区块按 `hpaSupportsBehavior`（§4.2）**动态启用**：
- **v2（含 capability 未探测的默认）**：区块正常可用，创建与编辑都允许填写；**移除**那条「v1 会报错」的阻断性告警，改为信息性说明（如「留空则不设置 behavior；用于控制扩缩容速率与稳定期」）。
- **v1**（capability `autoscaling` 仅含 v1）：区块禁用/折叠 + 明确说明「v1 HPA 不支持 behavior，仅支持 CPU 利用率」。
- 其余逻辑（`hasBehavior()` / `ruleToK8s` / V2 converter）不变。

## 7. 前端改动汇总

### 7.1 `HpaView.vue`（重构为纯列表页）
- 移除 el-dialog 创建/编辑表单 → 全部迁到 `HpaEditorView.vue`。
- 顶部：刷新 + 「创建 HPA」（跳 `/resources/hpas/editor`，受条目 6 页面级门禁禁用）。
- 列表列：名称（点击开详情抽屉）/ 目标工作负载 / 副本(min–max，当前) / 指标摘要 / 创建时间 / 操作下拉（查看、编辑→跳编辑器、删除）。行操作收敛为下拉（与其他页一致）。
- 详情抽屉保留（概览 + YAML 只读）。
- 上下文变化刷新；条目 6 页面级门禁提示。

### 7.2 `HpaEditorView.vue`（新增独立编辑页）
区块结构（每字段挂 `FieldHelp`，文案按 HPA 语义）：
1. **基础信息**：名称（RFC1123，编辑态禁用）、命名空间（只读，来自上下文）、目标工作负载（下拉，条目 2/3）。
   - FieldHelp 示例：
     - 名称：「HPA 对象名，需符合 RFC1123（小写字母/数字/-，字母或数字开头结尾），命名空间内唯一。」
     - 目标工作负载：「选择要自动扩缩容的 Deployment / StatefulSet。一个工作负载只能绑定一个 HPA，已绑定的不可选。」
2. **副本范围**：最小副本、最大副本。
   - 「最小副本：缩容下限（1 ~ 最大副本数），留空默认 1。」「最大副本：扩容上限，需 ≥ 1。」
3. **指标（metrics）**：每指标块 = 类型下拉 + 类型专属字段 + target。可加/删多指标。
   - 类型 FieldHelp：「Resource=按 Pod 整体资源（cpu/memory）利用率；ContainerResource=按指定容器的资源；Pods=按命名空间内一组 Pod 的自定义指标聚合；Object=按单个 K8s 对象（如 Ingress）的自定义指标；External=按集群外部指标源。可用类型取决于集群是否安装 metrics-server / prometheus-adapter。」
   - name/container/metricName/describedObject/target(type/averageUtilization/value/averageValue) 各挂 FieldHelp，说明取值与单位（value/averageValue 为 Quantity，如 `500m`/`1Gi`；后端存基础单位）。
   - 类型下拉按条目 6 门禁禁用不可用项。
4. **扩缩容行为（behavior）**：scaleUp / scaleDown 各 = stabilizationWindowSeconds + selectPolicy(Max/Min/Disabled) + policies[](type Percent/Pods, value, periodSeconds)。
   - 区块整体按条目 7 `hpaSupportsBehavior` 动态启用/禁用。
   - FieldHelp：「控制扩缩容速率与稳定性，避免抖动。留空则用集群默认。仅 autoscaling/v2 支持。」
- **提交校验**：名称必填 + RFC1123；必选目标工作负载；最大副本 ≥ 1、最小副本在范围内；每指标按类型必填项（资源名/容器名/指标名/Object kind+name/target 值）；至少一个指标。组装 body（update 为整对象替换）。
- **预选**：读 `?targetKind&targetName`（条目 5）或 `?name`（编辑）。

### 7.3 `WorkloadView.vue`
- 加 HPA badge（条目 4）+「添加 HPA」行操作（条目 5），数据源并行 `hpaApi.list`。

### 7.4 注册点
- **router**：`/resources/hpas`(name `hpas`) + `/resources/hpas/editor`(name `hpa-editor`)，meta group=资源管理、context=full。
- **api/index.ts**：`clusterCapabilityApi`（§4.2）；`hpaApi` / `workloadApi` 已存在复用。
- **types.ts**：`K8sClusterCapability`；现有 `K8sHpa`/`K8sHpaMetric`/`K8sHpaBehavior` 复用（编辑器沿用其形状）。

## 8. 边界与异常

| 场景 | 行为 |
|---|---|
| capability 未探测（列为空） | 不硬阻断；软提示「能力未探测」；五类指标默认可选；behavior 默认启用（对齐 factory 默认 V2） |
| 集群无任何 metrics API | 「创建 HPA」禁用 + 提示；存量列表照常展示 |
| 某指标类型依赖的 API 缺失 | 该类型下拉项禁用 + tooltip；已选存量值编辑时不拦 |
| 一负载多 HPA（绕过前端） | 后端 `HpaService` 拒绝，报「已绑定 HPA「x」」 |
| v1 集群填 behavior | 区块禁用/说明；即便绕过，`HpaV1Converter` 仍抛 `HPA_V1_ONLY_CPU` 兜底 |
| 目标工作负载为 DaemonSet | 下拉不含；「添加 HPA」操作不显示 |

## 9. 验收标准（怎么算做完）

1. 新增/编辑走独立页（非 dialog），字段全有 FieldHelp。
2. 目标工作负载为下拉，列出命名空间内 Deployment/StatefulSet；已绑 HPA 的禁用。
3. 创建指向已绑定工作负载 → 前端禁选 + 后端拒绝（双保险）。
4. 工作负载列表对已绑 HPA 的行显示 badge；「添加 HPA」操作跳转编辑器并预选该工作负载；DaemonSet / 已绑定行不显示该操作。
5. 无 metrics API 的集群：创建禁用 + 提示；有则按类型精确门禁（Resource/Container 需 metrics-server，Pods/Object 需 custom.metrics.k8s.io，External 需 external.metrics.k8s.io）。
6. v2 集群 behavior 可正常填写并保存（`spec.behavior` 落库）；v1 集群 behavior 禁用且有说明。
7. list / 详情抽屉 / YAML 只读 / delete 全通；上下文切换刷新正确。

## 10. 待确认 / 风险

- **capability 探测时效**：门禁依赖 `k8s_cluster.capability` 列的新鲜度。若集群刚装/卸 metrics-server 而能力未刷新，门禁会滞后——靠现有「刷新能力」按钮 + create/enable 时自动刷新缓解；是否要在 HPA 页加一个「刷新能力」快捷入口（调现有 refresh 端点）待实现时定。〔裁决 2026-09-24：**做**——HPA 列表页门禁 banner 内置「刷新能力」按钮（调 `/cluster/capability/refresh` 成功后重读 capability 并刷新列表）〕
- **`EnumResponseType.HPA_TARGET_ALREADY_BOUND`**：新增错误码，需按现有枚举规范补 message。
- **预选是否锁定**：条目 5 现定为「预填、可切换」；若希望「锁定不可改」更贴合动作意图，实现时可再议（不影响后端）。〔裁决 2026-09-24：**维持「预填、可切换」**〕
- **DaemonSet 排除依据**：以「无 Scale 子资源 / 不适合 HPA」为由排除，与现有 `TARGET_KINDS=['Deployment','StatefulSet']` 一致；如未来支持其他可伸缩 kind 需同步放开。
