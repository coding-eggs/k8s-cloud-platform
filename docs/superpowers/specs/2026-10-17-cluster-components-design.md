# 集群组件展示设计（S1.2）

> 需求出处：`docs/gap-analysis-2026-10-12.md` §5.1 #7（T5 首项）。
> 台账裁决（2026-10-14）：不新开「组件管理」模块；把「组件↔平台功能依赖映射」并进来；
> 按装错后果分两档门控；执行复用 S3.3/S3.4；三条风险见台账 §S1.2。
> brainstorming 结论：**2026-10-17**（本文档「已定决策」一节）。

**规模**：M–L。比台账原估的 M 大 —— 因为用户裁定「门控也全改走新源」（见 §5），
比只做展示多了 4 个消费方的改写。

---

## 已定决策（brainstorming 结论，2026-10-17）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **首批纳管：平台功能依赖的 + 用户已装的关键中间件**（11 条） | 用户选定。集群自带的 coredns/kube-proxy/CNI 不列，避免把清单变成"集群上有什么"的罗列；另外四条由用户单独裁定不展示（见 §2.1） |
| 2 | **探测机制 = 目录 + 一次活探测 + 短 TTL 缓存** | 用户选定方案 A。"健康"天生是时刻值，存快照没有意义；版本与健康是同一次读取的产物 |
| 3 | **门控也全改走新源** | 用户选定。本批把 CI / 网格 / 网络 / HPA 的判据收到同一份派生实现 |
| 4 | **按镜像仓库名匹配工作负载，不按名字** | 现场勘查：一半组件的工作负载名带 Helm release 前缀（`ps-` / `th-` / `nfs-client-`），换 release 名就找不到。镜像仓库路径跨安装稳定 |
| 5 | **给租户开一个特殊的、受约束的探测接口** | 用户裁定。租户进 CI / 网格页就会触发探测，只有租户可达才能即时要真值（§4 详述约束） |
| 6 | **集群详情页新开一个 tab** | 用户选定（台账原话"新增一个 tab 或并入概览"） |
| 7 | **`k8s_cluster.capability` 列保持不动** | `KubernetesOperationsFactory` 的 API 版本分派直接读它（Gateway API v1/v1alpha2、autoscaling v1/v2）。组件清单里"API group 存在性"这一列读它、不另探 —— group 存在性因此仍只有一处判据 |
| 8 | **控制面组件（etcd / APIServer / Scheduler / ControllerManager）不在本批** | 台账把 T5「其余」标为仍待定；且数据源完全不同（healthz 端点、无版本、无命名空间），硬塞进来会让"组件"这个概念在两个模型间摇摆 |
| 9 | **本批不做安装/升级/卸载** | 台账裁决：执行复用 S3.3/S3.4。清单里给的是**指引**，不是安装动作 |

---

## 1. 交付边界

**做**：
1. 一份**组件目录**（platform-api）—— 哪些组件、凭什么判它装了、它支撑平台哪些功能；
2. 一次**活探测**（k8s-core + k8s-server 窄投影）—— 装在没装 / 版本 / 健康 / 命名空间；
3. 一个**受约束的租户可达探测端点**（§4）；
4. **门控收口**：CI / 服务网格 / 网络 / HPA 的判据改读同一份派生实现（§5）；
5. 集群详情页**新 tab「集群组件」**（§6）。

**不做**：安装/升级/卸载（S3.3/S3.4）· 控制面组件健康（决策 8）· 组件配置编辑 ·
"已装的一律只探测不接管"（台账裁决，本批不提供任何"交给平台管"的开关）。

---

## 2. 组件目录（唯一出处）

落点：`platform-api`。**为什么不在 k8s-server**：目录里是业务判断（哪个组件、影响哪个功能），
而本仓的立场是"适配器不读 DB 列做业务判断"（`MeshController` 类注释，2026-10-10）。

```java
/** 一条组件的判据。两个判据至少有一个；都写就都验。 */
public record ComponentDef(
    String code,                 // 稳定 id，如 "tekton"（前端与门控都按它引用）
    String name,                 // 展示名
    String apiGroup,             // API group 判据（读 capability 列）；可空
    List<Probe> probes,          // 工作负载判据；可空
    List<String> features        // 它支撑的平台功能 code（**依赖关系只写一遍**，反查是派生的）
) {}

/** 工作负载判据：在候选命名空间里找 kind 类资源，按镜像仓库片段（主）或名字（快路径）认定 */
public record Probe(String namespace, WorkloadKind kind, String imageRepo, String name) {}
```

**依赖关系只写一遍**：目录里每条组件写"我支撑哪些功能"，`feature → 组件` 的反查由代码算，
不手写第二张表 —— 手写两张表迟早对不上。

### 2.1 首批目录（11 条）

判据取自**真集群实测**（§9），不是照文档猜的：

| code | 展示名 | API group 判据 | 工作负载判据（ns / kind / 镜像仓库片段） | 支撑的功能 |
|---|---|---|---|---|
| `metrics-server` | metrics-server | `metrics.k8s.io` | `kube-system` Deploy `metrics-server` | HPA 资源指标 |
| `prometheus-operator` | Prometheus Operator | `monitoring.coreos.com` | `monitor` Deploy `prometheus-operator` | 监控曲线 · ServiceMonitor / PodMonitor 页 |
| `gateway-api` | Gateway API | `gateway.networking.k8s.io` | —（纯 CRD，无工作负载） | 服务网格 · 路由 |
| `istio` | Istio | `networking.istio.io`（`security.` / `telemetry.` 任一亦可） | `istio-system` Deploy `pilot` | 服务网格 |
| `istio-ambient` | Istio ambient 数据面 | — | `istio-system` DaemonSet `ztunnel` | ambient 流量接管 |
| `tekton` | Tekton Pipelines | `tekton.dev` | `tekton-pipelines` Deploy `pipeline/controller-` | CI 流水线 |
| `tekton-operator` | Tekton Operator | `operator.tekton.dev` | `tekton-operator` Deploy `operator` | CI（安装来源） |
| `calico` | Calico | `crd.projectcalico.org` | `calico-system` DaemonSet `calico/node` | 网络 · IPAM |
| `tigera-operator` | Tigera Operator | `operator.tigera.io` | `tigera-operator` Deploy `tigera/operator` | 网络（安装来源） |
| `cert-manager` | cert-manager | `cert-manager.io` | `cert-manager` Deploy `cert-manager-controller` | 证书（网关 TLS） |
| `otel-operator` | OpenTelemetry Operator | `opentelemetry.io` | `opentelemetry-operator-system` Deploy `opentelemetry-operator` | OTel 采集（S2.3 的地基） |

**不纳入的四条（用户 2026-10-17 裁定）**：`eck-operator`、`local-path-provisioner`、
`nfs-subdir-external-provisioner`、`snapshot-controller`。前三条**不作为集群组件展示**；
`snapshot-controller` 是因为**功能还没做**（S4.3），届时再决定它的门控读哪里。

**有意不列的**（与上面四条不同，这是设计判断）：Grafana / Thanos 各件 / Alertmanager /
node-exporter / kube-state-metrics 都是 **Prometheus Operator 撑起的后代**（操作器装了 = 这些在跑），
minio-operator / milvus-operator 是**用户业务**，coredns / kube-proxy / CNI 是**集群自带的基础设施**
——都不直接对应平台功能。

⚠️ 因此 **日志存储（ES）在本清单里没有对应行**：S2.3（日志采集）那批的门控得自己处理，
不要以为"组件清单里没有 = 平台不关心"。

---

## 3. 探测

### 3.1 k8s-core：`WorkloadProbeOperations`

对每个候选执行 **list（按命名空间 + kind）→ 内存过滤**，过滤条件 = 镜像仓库片段命中，
或名字精确命中（快路径）。

- **成本固定**：候选写死在目录里，**与集群的命名空间数量无关** —— 这条约束照
  `ClusterAggregationOperations` 的"固定 9 次 list"同形，**有单测盯着**。
  11 条目录里 `gateway-api` 只有 CRD 判据（无工作负载），`istio-ambient` 只有工作负载判据，
  其余 9 条两者都有 → **10 次 list**（`istio` 与 `istio-ambient` 同在 `istio-system`
  但 kind 不同，各自一次）。同一 `(namespace, kind)` 上若出现多个候选**合并成一次 list**。
- **健康按 kind 分支**（真集群实测踩到的）：Deployment / StatefulSet 用
  `status.readyReplicas / status.replicas`；**DaemonSet 用 `status.numberReady /
  status.desiredNumberScheduled`** —— `calico-node` / `ztunnel` / `kube-proxy` 的
  `readyReplicas` 是空的，用错字段会一律显示"0/0 不就绪"。
- **版本**：`app.kubernetes.io/version` 标签优先；否则取 image tag 原文
  （剥 `@sha256:…`；多容器取第一个带 tag 的）。**不做后缀猜测** —— `1.30.3-distroless`
  原样显示，不去猜 `-distroless` 能不能丢。标签只在 Helm 装的上面有（实测：istiod 有、
  coredns / otel-operator / prometheus-operator 都没有），所以 tag 是主来源。
- 匹配到**多个**工作负载时取第一个（按名字排序保证稳定），并在 `detail` 里写明命中数 ——
  静默挑一个比报错更难查。

返回：

```java
public record WorkloadProbeResult(
    String code,
    boolean found,
    String namespace, String version,
    Integer ready, Integer desired,        // DaemonSet 与 Deploy 各自映射进来
    String detail                          // "3/3 就绪" / "命中 2 个，取 calico-node"
) {}
```

### 3.2 组件状态 = 两个判据的合成

`ComponentStatus` 有**四态**，不是布尔：

| 状态 | 判据 |
|---|---|
| `INSTALLED_HEALTHY` | 有判据命中，且（无工作负载判据 或 ready == desired） |
| `INSTALLED_UNHEALTHY` | 有判据命中，但 ready < desired（或期望副本为 0） |
| `NOT_INSTALLED` | 探测**成功**且所有判据都没命中 |
| `UNKNOWN` | 探测**失败**（集群不可达 / 无权限），或 capability 列为空（未探测） |

**`NOT_INSTALLED` 与 `UNKNOWN` 必须分开**：这与既有口径一致（`MeshStatusDTO` 的
"未探测"注释、`CapabilitySummaryDTO` 的"全 false = 未探测"）。把探测失败说成"没装"
是本仓已经踩过并修掉的坑。

---

## 4. k8s-server 那条特殊接口（租户可达）

### 4.1 为什么必须是租户可达

门控收口后，**租户**进 CI 页 / 网格页就会触发探测。若该端点是 `PLATFORM` 边界
（要求 `PLATFORM_SCOPE`），租户只能吃缓存 —— 冷缓存时对租户答"不知道"，
而"能不能跑 CI"正是他要的答案。用户 2026-10-17 裁定：**开一个特殊的接口**。

### 4.2 形状：`WorkloadProbeController`（新类，UPSTREAM 边界）

```java
@RestController
@RequestMapping("/workload-probes")
public class WorkloadProbeController implements AccessBoundaryAware {
    @Override public AccessBoundary accessBoundary() { return AccessBoundary.UPSTREAM; }
    @PostMapping  // POST /workload-probes  body: { clusterId, probes: [{namespace, kind, imageRepo?, name?}] }
} 
```

- **必须单开一个类**：`BoundaryAuthorizationManager` 读的是 handler **所属类**的声明，
  往 `ClusterAdminController` 那种 PLATFORM 类上加方法，新方法一样要求 `PLATFORM_SCOPE`。
  与 `StorageClassProvisionerController` / `NodeAddressController` 完全同形。
- **用 POST 而非 GET**：非资源派生查询走 POST 是本仓惯例（`CalicoFormOptionController`、
  `MeshController`），且结果不进任何 HTTP 缓存 —— 缓存策略只应有一处（§5.1）。
- **admin 凭据**：租户 SA 的 K8s RBAC 模板里没有 `kube-system` / `istio-system` 的读权限，
  也不该为一次探测开。

### 4.3 约束：探测目标只允许落在 k8s-server 侧的清单内（这条是重点）

**先记一个反直觉的实测事实**（§9）：这套集群上**几乎所有基础设施命名空间都已经分配给租户了** ——
`kube-system` / `istio-system` / `tekton-pipelines` / `tekton-operator` / `calico-system` /
`calico-apiserver` / `tigera-operator` / `cert-manager` / `monitor` / `operators` /
`opentelemetry-operator-system` / `platform-system` 全在同一个租户名下，只有 `milvus` 属于另一个。
**所以"基础设施命名空间"在这套集群上不是一条安全边界** —— 这些命名空间里的工作负载，
该租户今天用 `/workloads/list` 本来就读得到。

真正要防的是另一件事：**把探测指向清单外的命名空间**。裸着放开的后果很具体：租户拿自己的
token 直连 k8s-server，传 `namespace=milvus` 就能问出"那个命名空间里有没有叫 X 的 Deployment" ——
**跨租户的对象存在性泄漏**，比 StorageClass / PV / 节点 IP 那三条残留严重一个量级
（那三条回的是与租户无关的集群级事实）。

因此：

- 配置项 `platform.k8s.probe-namespaces`，值为**允许被探测的命名空间清单**。
  默认值**只放目录真正要探的那 9 个**：`kube-system`、`istio-system`、`monitor`、
  `tekton-pipelines`、`tekton-operator`、`cert-manager`、`calico-system`、`tigera-operator`、
  `opentelemetry-operator-system` —— 清单越小越好，这是唯一一条能按命名空间读工作负载的路径。
- 请求里**任何**不在清单内的候选 → **整个请求 400**（不是静默跳过 —— 静默跳过会让平台侧
  把"被拒"当成"没装"，与 §3.2「不要把失败说成没装」同一条原则）。
- **清单的语义要写进配置注释**：*把一个命名空间加进这个清单，等于允许任何已认证用户探测
  该命名空间内工作负载的名称、镜像与副本数*。这是一条可审计的规则，不是随口给的默认值。
- **为什么清单在 k8s-server 而不是 platform-api**：它就是这条边界本身。放上游等于"靠调用方自觉"，
  而边界判定按本仓立场归适配器（`k8s-server-boundary-and-prefix`）。
- 代价：目录与清单要一致。**用单测盯住**（platform-api 侧断言目录里每个候选命名空间都在清单内），
  而不是靠人记得改两处。

---

## 5. 派生与门控收口

### 5.1 `ClusterComponentService`（platform-api，唯一的派生实现）

```
ClusterComponentService.status(clusterId)
  ├─ 读 k8s_cluster.capability（API group 那一半，走既有 ClusterCapabilityService）
  ├─ 调 k8s-server /workload-probes（工作负载那一半）
  ├─ 合成 → List<ComponentStatus>
  └─ Redis 短 TTL 缓存：key `cluster:components:<clusterId>`，TTL 45s
```

缓存照 B4 `cluster:aggregate:<clusterId>` 的形状（`RedisJsonCache`，2026-10-10）。

**为什么必须缓存**：门控收口后，**每个租户进 CI 页/网格页都会触发一次**。不挡的话
一个 20 人的租户团队进进出出就是几百次集群调用。TTL 45s 是"多新算够新"的取舍：
组件是分钟级变化的东西，45s 足够；装完组件看不到最新状态时，页面有「重新探测」（清缓存重探）。

**缓存不随租户变**：探测结果里没有任何按租户切分的内容，管理员与租户共用同一份。

### 5.2 两个投影（保住租户侧的两层降级）

今天的 `useMeshStatus` 有两层降级（有 admin 权 → `/mesh/status`；只有 capability 读权 →
从 capability 派生；两者都无权 → 「未探测」）。收口后**派生实现只有一份**，投影仍是两个：

| 投影 | 消费方 | 内容 |
|---|---|---|
| `full(clusterId)` | 平台侧展示 / 门控（管理员） | 完整：状态 + 版本 + 命名空间 + detail + 功能映射 |
| `flags(clusterId)` | 租户侧门控 | 只回「功能 code → 能不能用 + 一句话原因」，**不含版本/命名空间/detail** |

`flags` 有意砍掉版本与命名空间：租户不需要知道 istiod 装在哪个命名空间，
而"能少给就少给"是这条边界的默认立场。

### 5.3 四个消费方的改法

| 消费方 | 今天 | 改后 | 注意 |
|---|---|---|---|
| CI（`PipelineCapabilityService.available`） | capability 列（Tekton）+ 活探 StorageClass | Tekton 那条改读 `flags`；**StorageClass 那条不动** | ⚠️ CI 有**两个**前提，StorageClass 不是组件（它是集群存储配置），不该硬塞进组件清单。即 CI 的 available 仍是 join，但 Tekton 那一半有了单一出处 |
| 网格（`/mesh/status`） | 活探 ztunnel + capability 列 | 三条全从 `full`/`flags` 派生（istio 组件 / gateway-api 组件 / istio-ambient 组件） | 对外的 `MeshStatusDTO` **形状不变**，前端零改动（只换数据来源） |
| 网络页 | capability 列的 `crd.projectcalico.org` | `flags` 的 `calico` | 前端零改动 |
| HPA | capability 列的 `metrics.k8s.io` | `flags` 的 `metrics-server` | 前端提示文案改成"缺 metrics-server → HPA 的资源指标不可用"（今天是干巴巴一个 tag） |

**PVC 扩容（S4.1 的 `expansionOf`）不改**：它判的是**每个 StorageClass** 能不能扩，
粒度是 SC 不是组件，收进来只会把"组件"这个概念搞浑。

---

## 6. 前端

### 6.1 新 tab「集群组件」（`ClusterDetailView` 第 5 个 tab）

表格列：**组件 / 状态 / 版本 / 命名空间 / 健康 / 影响的功能**。

- 状态用四态徽标：已装（绿）/ 已装但不健康（橙）/ 未安装（灰）/ 未探测（虚线灰）——
  `UNKNOWN` 不能显示成"未安装"（§3.2）。
- **影响的功能**是这批的重点：缺 `tekton` 的行显示「CI 流水线不可用」，缺 `metrics-server`
  显示「HPA 资源指标不可用」。这是台账说的"新的那一层"。
- 缺组件的行给**指引**（一句话 + 需要 `cluster-admin` 的说明），**不给安装按钮**
  （决策 9）。安装入口留给 S3.3/S3.4，届时这里换成"去安装"的跳转。
- 顶部：一句摘要（形如 `11 个组件中 10 个已装，缺 1 个会影响 1 项平台功能`）+「重新探测」按钮
  （清缓存 + 重探，@audit 记录）。
  ⚠️ 这不是编的例子：按 §9 的勘查，本集群此刻的实际状态就是 **10 装 1 缺**，
  缺的那个是 `metrics-server` → 影响「HPA 资源指标」。这也是这批功能最该兑现的样子。
- 探测失败（`UNKNOWN`）时顶部给一句"集群当前探测不到，以下状态可能不准确" —— 不静默。

### 6.2 概览 tab 的收敛

现有「组件版本与 API 能力」那块 7 个 tag 与 `CapabilitySummaryDTO` **收敛**为：
一行摘要（`N 个组件，M 个缺失 · 查看详情 →`）+ 跳转到新 tab。
**两个地方各说一套是这类页面的经典腐化路径**（同一个事实两个渲染点，迟早不一致）。

### 6.3 租户侧的能力提示

各功能页把"不可用"的原因从"一个灰 tag"改成**一句话 + 缺哪个组件**：
CI 页顶部、网格横幅、HPA 编辑器的提示。数据来自 `flags`。

---

## 7. 权限与迁移

- 新页面码 + API 码：`platform:cluster:component:view`（页面）与 `platform:cluster:component:read`（接口）。
- **新权限行必须显式绑内置 admin 角色**（本仓踩过的坑：权限行不按 code 前缀继承），
  手写迁移 SQL、人工重放。命名照既有：`V2026_10_17_1__cluster_component_permission.sql`。
- **租户侧不新增权限行**：`flags` 走各功能页既有码（`tenant:pipeline:read` 等），
  CI 的 `/pipeline/capability` 已经是那个码。
- 探测端点（k8s-server）**不加权限点**：它是 UPSTREAM 边界，收窄在上游（§4.2）。

---

## 8. 测试

**k8s-core（`WorkloadProbeOperationsTest`）**
- DaemonSet 走 `numberReady/desiredNumberScheduled`、Deployment 走 `readyReplicas/replicas`
  （**两个都写**：这是真集群上踩到的字段差异，只用一种会显示"0/0 不就绪"）。
- 版本提取四种形态：`v3.30.3` / `1.30.3-distroless`（后缀不剥）/ `v1.15.3@sha256:…`（剥 digest）/
  无 tag（回 null）。
- 多容器取第一个带 tag 的；命中多个工作负载时取名字序第一个且 `detail` 写明命中数。
- **「list 次数与命名空间数量无关」**：照 `ClusterAggregationOperationsTest` 的形式钉住。

**k8s-server（`WorkloadProbeControllerTest`）**
- 白名单外的命名空间 → 400（不是静默跳过）；混合请求（一个越界）→ 整请求拒。
- 边界声明是 `UPSTREAM`（反射断言，照 `PodLogEndpointsAcceptPreviousTest` 的形式）。

**platform-api**
- `ClusterComponentCatalogTest`：每个组件至少一个判据（group 或 probe）；`feature → 组件`
  反查唯一；**目录里每个候选命名空间都在 k8s-server 白名单内**（§4.3 那条一致性）。
- `ClusterComponentServiceTest`：四态各自可达（装了健康 / 装了不健康 / 探测成功但没命中 /
  探测失败 → `UNKNOWN`）；缓存命中不重复调 k8s-server；TTL 过期后重探。
- `ClusterComponentGateTest`：四个消费方各自的派生（CI / 网格 / 网络 / HPA），
  含"缺组件 → 不可用 + 原因文案"。
- `/mesh/status` 的**对外形状不变**（回归：前端零改动的依据）。

**前端**：浏览器冒烟（真组件 + 桩）—— 新 tab 的四态渲染、缺组件行的"影响的功能"、
「重新探测」触发的请求、概览 tab 摘要跳转。**前端没有单测框架**，冒烟是当前唯一手段。

---

## 9. 已实测的前提（2026-10-17 在 `coding-cluster` 上只读勘查，**不是推断**）

| 事实 | 影响 |
|---|---|
| 集群上相当一部分基础设施工作负载的名字带 Helm release 前缀：`ps-kube-prometheus-stack-operator`（在目录内）、`th-thanos-*`、`nfs-client-nfs-subdir-external-provisioner`、`local-client-local-path-provisioner` | 决策 4：**按镜像仓库匹配**，不按名字。若按名字写死，换个 release 名就探测不到 |
| DaemonSet 的 `status.readyReplicas` 为空（`calico-node`/`ztunnel`/`kube-proxy` 实测） | §3.1 的 kind 分支 |
| `app.kubernetes.io/version` 只在部分组件上有（istiod 有；coredns / otel-operator / prometheus-operator 没有） | 版本主来源是 image tag |
| `metrics.k8s.io` **不在** apiservice 列表 | metrics-server 真没装 → 这正是"缺关键组件"的真实样本，也是 HPA 资源指标在这套集群上不可用的原因 |
| `snapshot.storage.k8s.io` **不在** | S4.3（卷快照）那批会被整块隐藏。⚠️ 本清单**不含** `snapshot-controller`（§2.1），届时它的门控自己探 |
| 库里的 capability 快照与集群实况**一致**（`monitoring.coreos.com` 两处都有、`metrics.k8s.io` 两处都无） | 决策 7：capability 列可信，继续当 group 判据 |
| `istio.io` 组不存在，`networking.istio.io` 存在 | 目录里 istio 的 group 判据必须覆盖 Istio 的多组形态（既有 `ISTIO_GROUPS` 常量） |
| **分配表里几乎所有基础设施命名空间都已分配给租户**（`kube-system` / `istio-system` / `tekton-pipelines` / `calico-system` / `cert-manager` / `monitor` / `operators` / … 同属一个租户；`milvus` 属另一个） | §4.3：「基础设施命名空间」不是安全边界。要防的是**把探测指向清单外**，不是"别读基础设施" |

---

## 10. 有意不做 / 已知缺口（**显式记录，勿当新问题上报**）

1. **控制面组件健康**（etcd / APIServer / Scheduler / ControllerManager）—— 决策 8。
2. **安装 / 升级 / 卸载** —— 决策 9，留给 S3.3 / S3.4。
3. **"交给平台管"开关** —— 台账裁决"已装的一律只探测不接管"。
4. **`StorageClass 能不能动态供应` 不进组件清单** —— 它是集群存储配置、粒度是 SC（§5.3）。
5. **`eck-operator` / `local-path-provisioner` / `nfs-subdir-external-provisioner` /
   `snapshot-controller` 不展示** —— 用户 2026-10-17 裁定（§2.1）。前三条是"不作为集群组件展示"，
   末条是"功能还没做"。**别把这几条当成漏纳**：日志存储（ES）因此在本清单里没有对应行，
   S2.3 那批的门控得自己处理。
6. **残留（显式接受）**：持任意已认证 token 直连 k8s-server 的 `/workload-probes`，
   可读到**清单内**命名空间的工作负载名称、镜像与副本数。实际新增面很窄 ——
   这套集群上那些命名空间本来就分配给租户（§9），该租户今天用 `/workloads/list` 就能读到同样内容。
   闭合它需要"只接受 platform-api 调用"的内部凭证 —— 与 PV / 节点 IP / StorageClass 三条
   一并留给下一步。**清单外的命名空间已被挡住**（§4.3），这是本条与那三条的关键差别。
7. **版本号的语义化比较**（"这个 Tekton 太旧了"）—— 本批只展示，不做版本门槛判断。

---

## 11. 部署

- 重建 **k8s-server + platform-api + platform-web**。
- 迁移：`V2026_10_17_1__cluster_component_permission.sql`（1 条权限行 + 显式绑 admin），**人工重放**。
- 配置：k8s-server 新增 `platform.k8s.probe-namespaces`（有默认值，不配也能跑）。
- 无新外部依赖、无集群侧前提（**探测失败要说 UNKNOWN，不是报错** —— 集群断开时页面照常打开）。
