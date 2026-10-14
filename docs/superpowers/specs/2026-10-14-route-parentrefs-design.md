# Route 父引用（parentRefs）跨 kind 支持 · 设计（2026-10-14）

- **目标读者**：本批实施者（本文件是 spec；实施计划见后续 `writing-plans` 产出的 plan）
- **上游讨论**：2026-10-14 会话内评审（父类型范围 / sectionName 与 port 的取舍 / 列表页展示，逐项确认）
- **相关**：B6 服务网格（`docs/superpowers/specs/2026-09-15-service-mesh-gateway-api-design.md`、`docs/superpowers/plans/2026-10-08-b6-service-mesh-gateway-api.md`）、`docs/development/backend-layering.md`、`docs/development/frontend-permission-conventions.md`
- **上游文档**：Gateway API `ParentReference`（本次会话内两份 spec 引用已逐句核对：`sectionName` 为 **Support: Core**，`port` 为 **Support: Extended**）

## 0. 一句话

Route 的 `spec.parentRefs[]` 现在只能指向 Gateway，且**写盘时会把 Service 父引用必需的 `group: ""` 抹掉**（结果是"打开一次、保存一次，路由静默脱挂"）。本批把父资源补成 **Gateway / Service** 两种，把编辑器里的父类型、命名空间、名称、段名做成随 kind 联动的选择器，并修掉那个静默损坏存量数据的写盘 bug。

## 1. 目标 / 非目标

**目标**

1. 修掉写盘 bug：`group = null` 且 `kind = Service` 时必须显式写出 `group: ""`，使平台**能建**、也**不会改坏**已有的 Service 父引用。
2. 编辑器父引用块支持两种父资源：kind 选择器联动候选来源与 `sectionName` 语义。
3. 命名空间、名称、段名三处从纯手输变成「候选 + 可手输」；kind / 命名空间变化时清掉必然失效的旧值。
4. 列表页 / 详情页的父引用展示不再预设「父 Gateway」。

**非目标（本批不做）**

- `port`（PortNumber）**不做输入框**：Gateway 侧 spec 原话是"不推荐设置"；Service 侧那段整段在 `gateway:experimental:description` 里，而它要表达的"挑一个端口"对命名端口已被 `sectionName` 覆盖。保持透传（§5.3）。
- `backendRefs` / RequestMirror 的 `namespace` 不动（另一类引用、默认值语义相反，§4.3 有防误改提醒）。
- 其他父资源 kind（ServiceImport / ServiceEntry…）不做建模：编辑器原样透传，YAML tab 可编辑。
- Gateway 的 `allowedRoutes`、ReferenceGrant 的 CRUD（本平台没有 ReferenceGrant 资源）。

## 2. 上游语义（本批依据）

Gateway API `ParentReference`：

- **`group`**：省略时**默认 `gateway.networking.k8s.io`**；要指向 core 组的资源（Service）**必须显式写 `""`**。**`kind`**：省略时默认 `Gateway`。
- 两种 Core 父资源：**Gateway**（Gateway conformance profile）、**Service**（Mesh conformance profile，限 ClusterIP）。
- **`sectionName`（Support: Core）**：Gateway = **监听器名**；Service = **端口名**。未指定（空串）= 引用整个资源（Gateway 的全部监听器 / Service 的全部端口）。该句里出现的 `(experimental)` 标的是 **Port**，不是 `sectionName`——这一条曾误读，spec 里记下以免再犯。
- **`port`（Support: Extended）**：Gateway = 该端口上的 listener；Service = 该 Service 的某个端口（experimental 措辞）。**`sectionName` 与 `port` 同时给出时二者都必须匹配**（AND，不是 OR）：所以"两个字段各给一个输入框"等于把一对必须自洽的值拆成两个可能互相矛盾的框（`sectionName: http` + `port: 9090` 会照收但什么都挂不上、且不报错）。

## 3. 现状（要改的地方）

- **写盘**：`GatewaySpecUtil.toParentRefMaps` 用 `putStr` 写 `group`，而 `putStr` 丢弃空白串 → `""` 丢失；`HttpRouteConverter.convertForUpdate` 对 `parentRefs` 是**整列表替换**，丢掉的 key 由 CRD 默认值补成 `gateway.networking.k8s.io` → 父引用变成"gateway 组里的 Service"＝不存在的资源 → **路由静默脱挂、界面不报错**。`ParentRefDTO` 的类注释早已写明"指向 Service 时须显式空串"，是写盘侧没落实。
- **编辑页 ×5**（Http / Grpc / Tcp / Tls / Udp）：`Gateway` 已是候选下拉，但候选只有"本命名空间"的 Gateway（`loadGateways()` 写死 `ctx3`，5 份重复）；`命名空间` 是纯手输；`监听器（sectionName）` 是纯手输；`group/kind/port` 标注为"UI 不编辑，仅透传"。
- **列表页 / 详情页 ×5**：列头与字段名写死"父 Gateway"，摘要 `ns/name[:sectionName]`。
- **死代码**：`GatewaySpecUtil.sameParent` 按未归一的 group 比较，当前**零调用方**。

## 4. 后端设计

### 4.1 `group` 的写盘规则（唯一改动点）

`GatewaySpecUtil.toParentRefMaps`：

| DTO 的 group | 写盘 |
|---|---|
| 非 null（**含 `""`**） | 原样写出 |
| null 且 `kind == "Service"` | 写 `group: ""` |
| null 且其他（kind 空 / `Gateway` / 未建模 kind） | 省略，交 CRD 默认 |

- **规则窄到 Service**：其他 core 组 kind 本批不建模，规则不扩大；将来建模时一并扩，util 里留注释。
- **为什么放写盘侧、而不是让前端显式送空串**：一个闸门对所有调用方生效且**幂等**（前端送 `null` 或 `""` 结果相同）。放前端等于依赖"空串能安全穿过 FE→api→k8s-server 三跳"这个当下的巧合（走查过：Jackson 用默认 `ALWAYS`，能穿过；但那是配置默认值，不是契约）。
- **幂等性**：`convert`（create）与 `convertForUpdate`（update）都经此函数，因此"打开存量 Service 父引用路由 → 保存"不再改动父引用。

### 4.2 `sameParent` 归一

`sameParent` 改为按**归一后**的 group 比较（与 §4.1 共用同一个判定函数），避免留一个"将来有人用它就是错的"的死代码。**不删**（删除属无关重构），加注释说明它当前零调用方。

### 4.3 防误改注释（重要）

`backendRefs` 的 group **不能**照 §4.1 办理：`BackendObjectReference.group` 的默认值**相反**——省略或空串即 **core 组（Service）**，所以那边省略就是对的、今天也没有这个 bug。两处默认值不对称，须在 `GatewaySpecUtil`（backendRefs 写盘处）留一句注释，免得后人"顺手统一"。

### 4.4 DTO 语义注释

`ParentRefDTO.group` 注释补明：`null = 缺省（CRD 默认 gateway.networking.k8s.io）`、`"" = 显式 core 组（Service）`，且两者由写盘侧归一（§4.1）。

### 4.5 不改

5 个 converter 本体、k8s-server、platform-api、DTO 结构、权限行、迁移 —— 全不动。

## 5. 前端设计

### 5.1 候选加载收口（新增 composable）

新增 `platform-web/src/composables/useParentCandidates.ts`（模块级单例，仿 `composables/useNodeAddresses.ts`），按 **`kind|namespace`** 缓存候选：

- Gateway → `gatewayApi.list({ tenantId, clusterId, namespace })`
- Service → `serviceApi.list({ tenantId, clusterId, namespace })`，过滤掉 `type === 'ExternalName'` 与 `clusterIp === 'None'`（Mesh profile 只认 ClusterIP）
- 其他 kind → 不拉候选
- 失败 / 无权限 → 空候选 + `failed` 标志，页面**不抛不阻断**（沿用 `ServiceMonitorEditorView` 对同类候选的 `.catch(() => [])` 口径）
- 缓存键里的 `''` = 路由自身所在命名空间
- 5 个编辑器里现有的 5 份 `loadGateways()` 删除

### 5.2 父引用块（5 个编辑器同形改）

| 字段 | 改法 |
|---|---|
| **父资源类型** | 新增 `el-select`，**严格两项**：`Gateway（缺省）`（值 `''` → 提交 `null`）与 `Service`（值 `Service`）。**不启用 allow-create**——打错 kind 是静默失效且从界面上看不出来。placeholder 与 `''` 选项**同文案**（EP 对值为 `''` 的选中项会退化成 placeholder，本仓既有约定）。kind `@change` → 清空该行 `name` / `sectionName`（候选与语义都换了），保留 `namespace` |
| **名称** | 候选随 kind + 命名空间变（§5.1）；保留 `filterable allow-create default-first-option`（候选拉不到时仍可手输，与全站候选项一致）。未建模 kind → 无候选、纯手输 |
| **命名空间** | `el-select` `filterable allow-create`：选项 `本命名空间（留空）`（值 `''`，placeholder 同文案）+ `useResourceContext().namespacesOfCluster`（本租户在当前集群的命名空间，零新接口）。`@change` → 清空该行 `name` / `sectionName` |
| **监听器 / 段名** | 行名随 kind 变。Gateway =「监听器（sectionName）」，候选 = 所选 Gateway 的 `listeners[].name`，留空提示"留空 = 全部匹配的监听器"；Service =「端口名（sectionName）」，候选 = 所选 Service 的 `ports[].name`（**未命名的端口不进候选**；该服务端口全无名时 `no-data-text` 提示"该服务的端口没有命名，留空即作用于全部端口"），留空提示"留空 = 该服务全部端口"。手输值按 spec 的 SectionName pattern（`^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$`，≤253）在 `validate()` 里软校验 |
| **waypoint 标注** | Gateway 的 `labels['istio.io/waypoint-for']` 存在时，选项文案追加 ` · waypoint`（值仍是名字）。同命名空间里 waypoint 也是 Gateway，不标注用户分不出该挂哪个 |

端口候选项的展示格式沿用 `ServiceMonitorEditorView` 的既有格式：`http（80/TCP）`。

**实现警戒（必读）**：上面两处"清空旧值"必须**挂在用户交互上**（`el-select` 的 `@change`），**不能写成 `watch(modelValue)`** —— 回填（`fromParent` 赋值）也会改变 modelValue，用 watch 就会把刚加载进来的 `name`/`sectionName` 抹掉。本批的全部意义就是"别把用户看不见的数据弄丢"，这里写错方向会当场制造一个同类 bug。回填后不受清空逻辑影响，见 §7 前端断言 11。

### 5.3 未建模 kind 与 port 的只读提示

- 加载到的父引用 kind 不在 {缺省, Service} → kind 选择器显示原值（el-select 对无匹配 option 的值直接显示原值），并在该行给一条 muted 小字：`kind=<x> 未建模，本页不改它（可在 YAML 里改）`；名称 / 段名退化为手输，保存时 kind / group 原样透传。
- 加载到的父引用带 `port` → 在段名行右侧给 muted 小字 `port=<n>（本页不改）`；保存原样透传。

两条都是本仓对"未建模但必须透传"字段的既有做法（`HttpRouteEditorView` 顶部对未建模 filter type 的提示）。**这条很要紧**：本批修的正是"平台把用户看不见的字段抹掉"，所以对仍然不编辑的字段要显式说明它存在。

### 5.4 提交载荷

`parentRefs[]`：`kind` 由选择器决定（`''` → `null`，`Service` → `"Service"`）；`group` 仍是透传（只有未建模 kind 才带值，其余 `null`，由后端 §4.1 归一）；`port` 透传不变；`name` / `namespace` / `sectionName` 空串一律转 `null`。
`validate()` 文案："至少需要一个父 Gateway（parentRefs）" → **"至少需要一个父资源（parentRefs）"**。

### 5.5 列表页 / 详情页（5 个 View）

- 列头与 `el-descriptions` 字段名："父 Gateway" → **"父资源"**；列宽 180 → 200。
- 摘要函数 `parentText` 改为 kind 感知：`<kind> <ns>/<name>[:<sectionName|port>]`，其中 `kind = p.kind || 'Gateway'`，限定符优先 `sectionName`、无则 `port`。这样 Service 父引用不再显示成 Gateway，带 `port` 的也看得见。
- 状态区（`status.parents[].parentRef`）复用同一个 `parentText`，自动跟随。

## 6. 权限 / 接口 / 部署

- **无新端点、无新权限行、无迁移**。名称候选复用既有的 `tenant:gateway:list` 与 `tenant:service:list`；租户角色若无 Service 读权限，候选为空、退化为手输（已由 §5.1 的 catch 覆盖）。
- 部署：k8s-core 的改动随 **k8s-server** 构建，前端随前端构建，两者**同批上线**（只上前端的话 Service 父引用仍写不进去）。无 SQL 需要重放。

## 7. 测试与验收

**后端（k8s-core 单测）**

1. `HttpRouteConverterTest`：`kind=Service` + `group=null` → 输出的 parentRef **含 `group: ""`**（且 kind 在）；`group=""` 显式给出 → 原样写出；缺省 Gateway 父引用仍**不含** group / kind（既有断言保持）。
2. `TcpRouteConverterTest`：同 §7.1 的断言（证明共享路径对 5 类 Route 一致）。
3. `convertForUpdate`：live 是一条 Service 父引用（`group: ""`），DTO 同值提交 → 输出仍为 `""`（**"编辑不损坏"回归**，正是今天会坏的场景）。

**前端**（本仓无前端单测框架 → `npm run build` + 浏览器实测，Approach B 直挂 SFC）

1. 默认 kind 显示「Gateway（缺省）」，候选 = 本命名空间 Gateway。
2. 切 kind → Service：名称候选换成 Service（ExternalName / headless 不在内）、段名候选变成端口名，旧 name / sectionName 被清空。
3. 切命名空间：候选随之改变；手输命名空间仍可用。
4. Gateway 下段名候选 = 监听器名；Service 下端口全无名时给提示；留空保存后 parentRef 无 `sectionName`。
5. **Service 父引用建出来，YAML tab 里能看到 `group: ""`**（本批核心验收）。
6. **存量 Service 父引用路由：打开 → 保存 → YAML tab 里父引用不变**。
7. 未建模 kind：kind 显示原值 + 只读提示，名称手输，保存后 kind / group 不变。
8. `port` 有值时显示只读提示，保存后不变。
9. 列表页 / 详情页摘要：Gateway 与 Service 各自正确。
10. waypoint Gateway 的选项带 ` · waypoint`。
11. **回填不被清空逻辑误伤**：加载一条已有 Gateway 父引用（带 name + sectionName）的路由，页面应原样显示两者；加载一条 Service 父引用，kind 应显示 `Service` 且 name 不被清空。

## 8. 风险与取舍

- **窄规则**：`group: ""` 只对 `kind=Service` 补写。将来若建模其他 core 组 kind，须一并扩规则（util 注释已写）。
- **kind 严格两项**：将来支持 ServiceImport 等需改 UI；当前靠 YAML + 透传兜底。
- **运行时语义不在本批验证范围**：本批只保证**平台侧落盘正确**（`group: ""`、`kind: Service`、sectionName 原样写出）。Istio 是否按"端口名"解释 Service 父引用的 sectionName、是否要求 `port`，**无集群可验**，不做断言、不在 FieldHelp 里承诺。
- **已知反例**：`backendRefs` 的 group 默认值相反，不随本批改动（§4.3）。

## 9. 实施分期（建议，供 plan 采用）

1. **T1 后端写盘修复**（`GatewaySpecUtil` 的 group 规则 + `sameParent` 归一 + 防误改注释 + `ParentRefDTO` 注释）+ 三条单测。独立可验（只跑单测）。
2. **T2 前端候选收口**：`useParentCandidates.ts` + 5 个编辑器删 `loadGateways()`。
3. **T3 编辑器父引用块**（kind / 命名空间 / 名称 / 段名 / waypoint 标注 / 只读提示 / validate 文案）。**依赖 T1** 才能端到端验 §7.5、§7.6。
4. **T4 列表页 / 详情页展示**（5 个 View 的 `parentText` 与字段名）。
5. **T5 浏览器实测**：§7 前端十条断言。

## 10. 待确认（评审时否决即可，否则按此实施）

1. 列表 / 详情摘要显示**完整 kind 前缀**（`Gateway infra/gw:http`、`Service team-a/api:http`）——略长但无歧义；嫌长可改 `gw:` / `svc:` 缩写。
2. 未建模 kind 与 `port` 的**只读小字提示**保留（默认保留，理由见 §5.3）。
3. waypoint 标注文案 ` · waypoint`（若要更明确可写 ` · waypoint（L7 流量入口）`）。
