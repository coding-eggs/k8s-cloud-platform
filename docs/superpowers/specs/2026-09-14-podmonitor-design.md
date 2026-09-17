# B1 · PodMonitor 功能设计

- **日期**：2026-09-14
- **状态**：草稿，待评审
- **CRD**：`monitoring.coreos.com/v1 PodMonitor`（plural `podmonitors`，Namespaced）
- **参照对象**：ServiceMonitor（全栈镜像，仅改 CRD 差异点）
- **范围**：本阶段只交付规格；实现另开

---

## 1. 背景与目标

Prometheus Operator 提供两种抓取发现 CRD：`ServiceMonitor`（按 Service 选目标）与 `PodMonitor`（按 Pod 直接选目标，无需 Service）。平台已有完整的 ServiceMonitor 支持（后端各层 + 前端列表/编辑页 + Prometheus discovery 候选）。本批新增 **PodMonitor**，用于抓取「没有 Service 的 Pod」（StatefulSet、Job、sidecar 容器等）。

目标：PodMonitor 的功能与交互**与 ServiceMonitor 保持一致**，用户零学习成本；仅在 CRD 语义差异处做对应调整。

## 2. 范围

**In scope（本批交付）**
- 后端：DTO / Operations / Converter(fetch-overlay) / ResourceType / platform-api Controller+Service / k8s-server 边界 controller。
- 前端：列表页 + 独立编辑页（含 relabeling/metricRelabeling 结构化编辑器、Prometheus discovery 候选、FieldHelp 全覆盖）。
- 注册点：router / 侧边菜单 / api client / TS types。

**Out of scope（v1 不做，converter fetch-overlay 会原样保留不丢）**
- PodMonitor endpoint 的高级字段：`authorization`、`oauth2`、`proxyUrl`/`noProxy`/`proxyFromEnvironment`/`proxyConnectHeader`、`honorTimestamps`、`enableHttp2`、`filterRunning`、`followRedirects`、`trackTimestampsStaleness`、`targetPort` 对象。
- spec 级高级字段：`scrapeClass`、`scrapeProtocols`、`fallbackScrapeProtocol`、`convertClassicHistogramsToNHCB`、`scrapeClassicHistograms`/`scrapeNativeHistograms`、`nativeHistogram*`、`labelNameLengthLimit`/`labelValueLengthLimit`、`keepDroppedTargets`、`selectorMechanism`。
- `status` 子资源读写（只读展示即可，不建模）。

> 原则：v1 建模「常用 + 与 ServiceMonitor 对齐」的字段；其余靠 fetch-overlay 保留，后续按需补。

## 3. CRD 事实（权威来源 = 平台 Apifox OAS `createMonitoringCoreosComV1NamespacedPodMonitor`）

**spec 顶层字段**（`selector` 为 schema 唯一 required；`podMetricsEndpoints` 功能上必填）：

| 字段 | 类型 | v1 建模 | 说明 |
|---|---|---|---|
| `selector` | object | ✅ | `{matchLabels, matchExpressions}`，选 **Pod**（required） |
| `podMetricsEndpoints` | array | ✅ | 抓取端点列表（**注意：非 ServiceMonitor 的 `endpoints`**） |
| `namespaceSelector` | object | ✅ | `{any, matchNames}`，缺省=仅本命名空间 |
| `attachMetadata` | object | ✅ | **仅 `{node:boolean}`**（本 OAS 无 pod/namespace） |
| `jobLabel` | string | ✅ | Prometheus job 名，缺省 `<ns>-<name>` |
| `podTargetLabels` | array<string> | ✅ | 从目标 Pod 标签透传到目标的 label 列表 |
| `sampleLimit` / `targetLimit` / `labelLimit` | int | ✅ | 各限流上限 |
| `bodySizeLimit` | string | ✅ | 响应体大小上限，如 `50MB` |
| 其余高级字段 | — | ⛔ | 见 Out of scope |

**endpoint（`podMetricsEndpoints[]`）字段**：

| 字段 | 类型 | v1 建模 | 说明 |
|---|---|---|---|
| `port` | string | ✅* | 命名端口（名字） |
| `portNumber` | int | ✅* | 数字端口。**与 `port` 二选一**（见 §4.2 映射规则） |
| `path` | string | ✅ | 抓取路径，缺省 `/metrics` |
| `interval` / `scrapeTimeout` | string | ✅ | 间隔/超时（如 `30s`；timeout < interval） |
| `scheme` | enum http/https | ✅ | 抓取协议 |
| `params` | map<string>[]string | ✅ | URL 附加参数 |
| `basicAuth` | object | ✅ | `{username, password}` 各引用 Secret |
| `bearerTokenSecret` | object | ✅ | `{name, key}` 引用 Secret |
| `tlsConfig` | object | ✅ | v1 建模 `{insecureSkipVerify, serverName}`（ca/cert/keySecret 靠 overlay 保留） |
| `honorLabels` | boolean | ✅ | **PodMonitor 常用**：目标标签与指标标签冲突时以指标为准。v1 新增（ServiceMonitor 未建模，此处补上） |
| `relabelings` / `metricRelabelings` | array | ✅ | 结构化 relabeling（同 ServiceMonitor） |
| `bearerTokenFile` | — | ⛔ | **本 OAS 未见**（ServiceMonitor 有）。按 OAS 不建模；若部署的 CRD 支持则后续补（overlay 保留） |
| 其余（authorization/oauth2/proxy*/honorTimestamps/enableHttp2/filterRunning/followRedirects/trackTimestampsStaleness/targetPort） | — | ⛔ | Out of scope |

> `*` port/portNumber：DTO 层收敛为**单一 `port` 字符串**，CRD 边界拆分（§4.2）。

## 4. 数据模型（platform-common）

### 4.1 `PodMonitorDTO extends BaseResources`（镜像 `ServiceMonitorDTO`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `matchLabels` | Map<String,String> | 目标 Pod 选择器 matchLabels；由后端据 `podRef` 解析填充；查询原样返回 |
| `matchExpressions` | List<MatchExpression> | 与 matchLabels ANDed（补全，支持按表达式选 Pod） |
| `podRef` | {name, namespace} | **平台侧**：前端选中的目标 Pod；据此反查 labels 生成 matchLabels；转发 k8s-server 前剥离、不落库（对应 SM 的 `serviceRef`） |
| `namespaceSelector` | {any, matchNames} | 缺省=仅本命名空间 |
| `jobLabel` | String | Prometheus job 名 |
| `podTargetLabels` | List<String> | 透传 label 列表 |
| `sampleLimit` / `targetLimit` / `labelLimit` | Integer | 限流 |
| `bodySizeLimit` | String | 如 `50MB` |
| `attachMetadata` | {node} | node=true 附加 node 名标签 |
| `podMetricsEndpoints` | List<PodMonitorEndpointDTO> | **字段名 = podMetricsEndpoints**（必填，空也给空数组） |
| `creationTime` | String | 仅查询返回 |

- 内部类：`MatchExpression{key,operator,values}`、`NamespaceSelector{any,matchNames}`、`PodRef{name,namespace}`、`AttachMetadata{node}` —— 与 SM 同名同构。
- `getApiPath()` → `"/resources/podmonitors"`。

### 4.2 `PodMonitorEndpointDTO`（镜像 `ServiceMonitorEndpointDTO`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `port` | String | **单一端口字段**：命名端口或数字端口（前端一个输入框）。CRD 边界拆分见下 |
| `path` / `interval` / `scrapeTimeout` / `scheme` | String | 同 SM |
| `params` | Map<String,List<String>> | URL 参数 |
| `basicAuth` | {username:SecretRef, password:SecretRef} | Basic 鉴权 |
| `bearerTokenSecret` | SecretRef{name,key} | Bearer Token |
| `tlsConfig` | {insecureSkipVerify, serverName} | TLS 常用项 |
| `honorLabels` | Boolean | **新增**（SM 无） |
| `relabelings` / `metricRelabelings` | List<Relabeling> | 结构化 relabeling |

- 内部类 `SecretRef{name,key}`、`BasicAuth{username,password}`、`TlsConfig{insecureSkipVerify,serverName}`、`Relabeling{sourceLabels,targetLabel,regex,replacement,separator,modulus,action}` —— 与 SM 完全同构（可考虑抽公共父类，但 v1 为降低耦合直接镜像）。
- **port 拆分规则（converter 内）**：
  - `toEndpointMap`：`port` 值若全为数字（`^\d+$`）→ 写 CRD `portNumber`(int)；否则 → 写 CRD `port`(string)。二者只写其一。
  - `fromEndpointMap`：优先读 CRD `port`；无则读 `String(portNumber)`；写入 DTO.port。
  - **update overlay**：DTO.port 为空时，从 live endpoint **同时移除** `port` 与 `portNumber`（避免残留）。
  - 边界：命名端口恰为纯数字（极罕见）会按数字处理——接受，文档标注。

## 5. 后端各层改动

### 5.1 k8s-core
- **`PodMonitorOperations implements NamespacedOperations<PodMonitorDTO>`**（镜像 `ServiceMonitorOperations`）：
  - CRD context：group=`monitoring.coreos.com`, version=`v1`, kind=`PodMonitor`, plural=`podmonitors`, scope=`Namespaced`。
  - `list/get/create/update/delete/yaml/checkExist` 逻辑同 SM；update 走 SSA fetch-overlay（`converter.convertForUpdate(dto, live)` + `ServerSideApply.FIELD_MANAGER` force）。
- **`PodMonitorConverter`**（镜像 `ServiceMonitorConverter`）：
  - `convert`：kind=`PodMonitor`；spec 端点 key = **`podMetricsEndpoints`**；port 拆分（§4.2）；`attachMetadata.node`；含 `honorLabels`；无 bearerTokenFile。
  - `revert`：读回上述字段。
  - `convertForUpdate` + `overlayEndpoint`：**MODELED_ENDPOINT_KEYS = [port, portNumber, path, interval, scrapeTimeout, scheme, params, basicAuth, bearerTokenSecret, tlsConfig, honorLabels, relabelings, metricRelabelings]**；tlsConfig 深合并保 ca/cert/keySecret；未建模 key（authorization/oauth2/proxy*/…）原样保留。
- **`KubernetesOperationsFactory`**：注册 `PodMonitorOperations`（同 SM 的注册方式）。

### 5.2 platform-common
- 新增 `PodMonitorDTO`、`PodMonitorEndpointDTO`。
- `ResourceType` 增枚举：`POD_MONITOR(PodMonitorDTO.class)`。

### 5.3 platform-api
- **`PodMonitorController`**（`/resource/podmonitors`，镜像 `ServiceMonitorController`）：
  - `POST /list`、`GET /{name}`、`GET /{name}/yaml`、`POST`(create)、`PUT /{name}`(update)、`DELETE /{name}` —— list/get/yaml/delete 直连 `K8sResourceClient`；create/update 走 `PodMonitorService`。
  - `POST /relabel-labels`、`POST /metric-names` —— discovery 候选（见 §6）。
- **`PodMonitorService`**（镜像 `ServiceMonitorService`）：`resolveSelector` + `relabelLabels`/`metricNames`。
- **`PodMonitorKeyRequest`**{clusterId, namespace, name}（镜像 `ServiceMonitorKeyRequest`）。

### 5.4 k8s-server（边界，零业务逻辑）
- **`namespace/PodMonitorController extends AbstractNamespacedResourceController<PodMonitorDTO>`**：`@RequestMapping("/resources/podmonitors")`，`resourceType()=POD_MONITOR`。双模访问、边界=分配表，同 SM。

## 6. Service 层业务逻辑

### 6.1 resolveSelector（镜像 SM 的 serviceRef→labels）
- 有 `podRef{name,namespace}` → 经 `K8sResourceClient.get(PodDTO)` 取该 Pod 的 `metadata.labels` → 覆盖 `body.matchLabels`；无 labels → 报错「Pod「x」无 labels，无法生成选择器」；Pod 不存在 → 报错。
- 无论是否解析都剥离 `podRef`（绝不下发 k8s-server / 不落库）。
- 无 `podRef` → matchLabels 原样保留（编辑回填/未改）。
- **语义说明**：选择器反映「创建时所选 Pod 的 labels」。同一工作负载的 Pod 共享模板 labels，故通常可泛化到该工作负载全部 Pod；此为 Option A 的已知取舍。

### 6.2 discovery 候选（镜像 SM，仅前缀不同）
- `relabelLabels` / `metricNames` 复用 `PromDiscoveryClient`，scrape pool 前缀 = **`podMonitor/{namespace}/{name}/`**（SM 为 `serviceMonitor/...`）。
- Prometheus 不可达/无 target → 返回空组，前端下拉仍可手输；仅记 warn。

## 7. 前端

### 7.1 `PodMonitorView.vue`（列表页，镜像 `ServiceMonitorView.vue`）
- 列：名称（点击开详情抽屉）/ 端点数（`podMetricsEndpoints.length`）/ 创建时间 / 操作下拉（编辑、删除）。
- 顶部：刷新 + 「创建 PodMonitor」（跳独立编辑页）。
- 详情抽屉：概览 tab（选择标签 chips + 抓取端点表 port/path/interval）+ YAML 只读 tab。
- 空态文案：说明 PodMonitor 由 Prometheus Operator 消费、集群未装时操作报错。
- 上下文（租户/集群/命名空间）变化时刷新；CRD 404 时保留空列表。

### 7.2 `PodMonitorEditorView.vue`（独立编辑页，镜像 `ServiceMonitorEditorView.vue`）
区块与 SM 一致，差异标注：

1. **基础信息**：名称（RFC1123，编辑态禁用）、标签（LabelEditor）、jobLabel。每字段 FieldHelp。
2. **目标选择（selector）**：
   - **目标 Pod 下拉**（必填）：按命名空间集合 `podApi.list` 拉取 Pod；选中后后端反查 labels 生成 matchLabels。**端口下拉来自所选 Pod 的容器端口**（名字优先，否则端口号）。
   - 选择器（只读 chips）：由所选 Pod labels 决定，不可手改。
   - 选择器表达式（matchExpressions）：key/operator(In/NotIn/Exists/DoesNotExist)/values，可加多行。
   - 命名空间范围（namespaceSelector.matchNames）：多选，缺省=仅本命名空间；据此加载 Pod 列表。
3. **抓取端点（podMetricsEndpoints）**：每端点
   - 主区：scheme / port*（下拉+手输，来自所选 Pod 容器端口）/ path / interval(s) / scrapeTimeout(s)。
   - 折叠高级：URL 参数 params；鉴权/TLS（basicAuth、bearerTokenSecret、tlsConfig.insecureSkipVerify/serverName）——**无 bearerTokenFile**；**honorLabels 开关**（新增，FieldHelp 说明冲突时以指标标签为准）；Relabeling（结构化，sourceLabels 候选来自 discovery）；MetricRelabeling（结构化，regex 的 `__name__` 候选来自 discovery）。
   - 可加/删多端点。
4. **其他配置**：podTargetLabels、sampleLimit、targetLimit、labelLimit、bodySizeLimit(MB)、attachMetadata.node 开关。
- 提交校验：名称必填+RFC1123；必选目标 Pod（或有 matchExpressions）；每端点 port 必填；至少一端点。组装 body（update 为整对象替换）。
- FieldHelp 覆盖所有字段（文案按 Pod 语义改写，如「目标 Pod」「从所选 Pod 的容器端口选择」）。

### 7.3 注册点
- **router**：`/resources/podmonitors`(name `podmonitors`) + `/resources/podmonitors/editor`(name `podmonitor-editor`)，meta group=资源管理、context=full。
- **MainLayout 侧边菜单**：资源管理组内、ServiceMonitor 附近加「PodMonitor」。
- **api/index.ts**：`podMonitorApi = makeResourceApi<K8sPodMonitor>('podmonitors')`；`podMonitorRelabelApi = { labels: POST /resource/podmonitors/relabel-labels, metricNames: POST /resource/podmonitors/metric-names }`。
- **types.ts**：`K8sPodMonitor`、`K8sPmEndpoint`、`K8sPmMatchExpression`、`K8sPmRelabeling`（镜像 SM 对应类型；endpoint 含 `honorLabels`）。

## 8. 边界与异常

| 场景 | 行为 |
|---|---|
| 集群未装 Prometheus Operator | CRD 404，透传错误；列表保留空、拦截器提示（同 SM） |
| Prometheus 不可达 / 无活跃 target | discovery 候选返回空；下拉仍可手输；仅 warn |
| resolveSelector：Pod 不存在 / 无 labels | 明确报错，阻止创建/更新 |
| 端点 port 为空 / 无端点 / 名称非法 | 前端本地校验拦截 |
| update 未建模字段 | fetch-overlay 原样保留（authorization/oauth2/proxy*/tlsConfig.ca 等） |

## 9. 验收标准（怎么算做完）

1. UI 创建 PodMonitor → 集群生成正确 CRD：`spec.selector.matchLabels` 来自所选 Pod labels、`spec.podMetricsEndpoints[]` 字段正确。
2. port 名字/数字映射正确（命名端口→`port`，纯数字→`portNumber`）。
3. 编辑保存后未建模字段不丢失（fetch-overlay 生效）——用带 authorization/proxyUrl 的存量 PodMonitor 验证。
4. list / 详情抽屉 / YAML 只读 / delete 全通。
5. Prometheus 可达时 relabeling sourceLabels、metricRelabeling `__name__` 候选正确填充；不可达时优雅降级为空。
6. honorLabels 开关读写正确。
7. 交互与 ServiceMonitor 一致（列表/编辑页结构、FieldHelp、discovery 候选）。

## 10. 待确认 / 风险

- **PodDTO 是否暴露容器端口 + labels**：前端 Pod 下拉的端口列表依赖 `PodDTO` 含 container ports；实现时确认，若缺则补 DTO/converter。
- **bearerTokenFile**：本 OAS 未列于 PodMonitor endpoint；确认部署 CRD 是否支持，支持则补建模。
- **attachMetadata 仅 `{node}`**：与 SM 一致；若目标 Prometheus Operator 版本支持 `pod`/`namespace`，后续可加。
- **Pod 易逝性**：选择器基于所选 Pod 的 labels（Option A 已知取舍），文档已标注。
