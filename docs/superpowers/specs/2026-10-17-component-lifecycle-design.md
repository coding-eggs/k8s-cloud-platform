# 组件生命周期（安装 / 升级）设计

> 需求出处：2026-10-17 用户提出「组件管理：对集群概览中的组件如未探活的可以进行安装部署、升级」。
> 与 S1.2 的关系：S1.2 解决"看得见"（目录 + 四态探测），本批解决"装得上、升得动"。
> 与 S3.3 / S3.4 的边界：**只服务 S1.2 目录里的 11 条组件**；通用 Helm 商店是另一件事，与本批无关。
> 台账 2026-10-14「不新开组件管理模块、执行复用 S3.3/S3.4」的裁定在本批被**部分推翻**：
> 执行能力提前到本批，但仍**只有一条写入实现**（不复制到 S3.3/S3.4 各写一遍）。

---

## 已定决策（brainstorming 结论，2026-10-17）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **只装/升目录内 11 条组件**，不开放任意 chart | 用户选定。通用 Helm 商店与本批无关 |
| 2 | **只做安装 + 升级**，不做卸载/回滚按钮 | 用户选定。卸载比安装危险一个量级（Calico 断网 / Istio 断流） |
| 3 | **统一 Helm 执行器**（chart 一律用官方 chart，原样入库） | Prometheus 这类富生态组件必须有 values；为简单组件保留第二套 apply 会带来两套版本/回滚/账本语义。2026-10-17 补充裁定：不写 thin chart，官方版本对齐 |
| 4 | **chart 内置进平台**（`platform-api` 资源目录），不引用外部仓库 | 用户选定。内网可复现、版本矩阵可审计；11 个组件几十 MB |
| 5 | **cluster profile（画像）**：同一组件在管理集群与业务集群的 recipe 可以不同 | 管理集群要额外部署 Thanos；业务集群只装 chart |
| 6 | **新增 `cluster_role`**（MANAGEMENT / BUSINESS，默认 BUSINESS） | 用户选定。不用安装时人工选画像 |
| 7 | **Thanos 挂在 `prometheus-operator` 的 management recipe 里**，不新增目录组件 | 用户选定。目录语义是"平台功能依赖"，Thanos 是管理集群的观测底座 |
| 8 | **异步操作记录 + 前端轮询** | helm 安装可能要几分钟，同步 HTTP 撑不住 |
| 9 | **release 名与命名空间由 recipe 固定**，用户不能改 | 否则装完 S1.2 探测还是找不到它 |
| 10 | **只有平台自己装的 release 可升级**；手工装的一律只读 | 与台账"已装的一律只探测不接管"一致 |
| 11 | **成功判定以 S1.2 探测为准**，不以 helm exit 0 为准 | operator 类组件的实际落地是异步的 |

---

## 1. 交付边界

**做**：
1. `cluster_role` 字段与集群列表/表单的展示；
2. 组件 recipe 目录（chart 内置 + 版本 + values 契约 + 可编辑字段 + 依赖）；
3. helm 执行链（预检 → 执行 → 收尾）与异步操作记录；
4. 组件 tab 的「安装 / 升级」与进度视图；
5. 每个组件的**配置契约**与**功能级验收**（§5）。

**不做**：卸载 / 回滚按钮 · 任意 chart 商店 · 租户安装 · 接管已有 release · 全量 values 编辑 · 组件间版本编排（如先升控制面再升数据面）。

---

## 2. 集群画像与 recipe 模型

### 2.1 `cluster_role`

`k8s_cluster` 新增 `cluster_role`（`MANAGEMENT` / `BUSINESS`，默认 `BUSINESS`）。
约束：**全局至多一个 MANAGEMENT**（平台自身所在集群），在创建/编辑集群时由后端校验（不是只写在文档里）。
注册/编辑集群时可改；改动只影响后续安装取哪套 recipe，**不改已装的东西**，也不自动重装已装组件。

### 2.2 recipe

目录里每条组件从「一个探测判据」扩展为「探测判据 + 按画像的 recipe」：

```java
public record ComponentRecipe(
    String profile,              // management / business
    List<ReleaseSpec> releases,  // 该画像下要装的 release（通常 1 个，Prometheus 管理画像是 2 个）
    List<String> requires        // 前置组件 code（如 istio-ambient requires istio）
) {}

public record ReleaseSpec(
    String releaseName,          // 平台固定，如 platform-kps
    String namespace,            // 平台固定，与探测命名空间一致
    String chart,                // 内置 chart 的目录名
    String chartVersion,         // 目标 chart 版本
    Map<String, Object> values,  // 🔒 平台契约值（见 §5）
    Map<String, Object> editableValues  // ⚙️ 少量可由用户改的默认值
) {}
```

- **一个组件在一个集群最多一个平台 release**（幂等：`helm upgrade --install`）。
- **同一组件可有多个 release**（Prometheus 管理画像 = `platform-kps` + `platform-thanos`）。
- `requires` 在安装前校验：前置组件必须已 `INSTALLED_HEALTHY`，否则按钮禁用并说明原因。

### 2.3 chart 打包与归属

**chart 放在执行侧（k8s-server）**，不放在 platform-api：

```
k8s-server/src/main/resources/charts/components/<code>/<chartVersion>/
    provenance.yaml         # 上游仓库/版本、拉取命令、拉取日期（完整性由 git / 镜像保证）
    chart/                  # 上游 chart 原样解包，**不修改任何模板**
      Chart.yaml
      templates/…
```

> 放在 `resources` 下是为了**按 classpath 解析**：IDE 的进程工作目录不一定是模块目录，
> 只按相对路径 `charts` 会读不到。`ChartBundle` 先找 `platform.components.charts-dir`
> （默认 `charts`），找不到再回退 classpath；镜像里由 Dockerfile 复制到 `/app/charts`
>（helm 需要真实目录，jar 内资源不是文件系统路径）。

**chart 规则（2026-10-17 用户裁定）**：

- **尽量用官方 chart，版本对齐，不自定义**。目录名 = 上游 chart 版本（如 `metrics-server/3.14.0`），
  不另起"自己的版本号"（最初的 thin chart 用 `1.0.0` 是错误示范）。
- **不修改 chart 模板**。平台需要的一切定制都通过 recipe 的 values（只使用 chart 支持的键）。
  例：metrics-server 的 `fullnameOverride: metrics-server`（否则官方 chart 用 release 名做 fullname，
  与组件探测认的 `deployment/metrics-server` 对不上）、`args: [--kubelet-insecure-tls]`。
- 升级组件版本 = 重新 `helm pull` 同一官方来源的新版本入库 + 改 recipe 的 `chartVersion`（平台发版）。
- **手写 chart 的代价已被实测证明**：metrics-server 的手写 thin chart 漏了 `system:auth-delegator` 绑定，
  `helm template` 与单测全绿，只有真集群调用 metrics API 时才 500。批② 起禁止手写。

理由：helm 在 k8s-server 里执行；若 chart 放 platform-api，就得把几 MB 的包经 HTTP 传过去。
platform-api 只传 `code + chartVersion + releaseName + values`，**业务判断与二进制产物分离**。

- 运行时 chart 以**目录**形态直接交给 helm（不打包 tgz）；临时 kubeconfig 0600，用完删除。
- **请求时校验** `(code, version)` 存在于内置包中且 `Chart.yaml` 存在，缺了就明确报错
  （两端版本漂移的症状是"装了个不存在的版本"，必须一眼可辨）。
- **不另做 sha256**（实现时修订）：chart 以文本目录进 git、随镜像发布，完整性由版本控制与镜像保证；
  真正的防漂移靠"版本找不到就报错"。
- **values 的契约值只在 platform-api 的 recipe 里写一遍**（实现时修订，删掉了最初的 `values-default.yaml`）——
  那份文件没有任何代码读取，两处重复会在漂移时毫无信号。
- **升级组件版本 = 平台发版**（用户已接受）。

---

## 3. 执行链

```
组件 tab ──POST /cluster/components/apply──▶ platform-api
                                            ├─ ComponentInstallService（recipe 解析 / 前置校验）
                                            ├─ platform_component_operation（PENDING→RUNNING→…）
                                            └─ K8sComponentClient（长超时 10min）
                                                 │ POST /components/apply（PLATFORM 边界）
                                                 ▼
                                            k8s-server → HelmOperations
                                                 ├─ 解包 chart / 写临时 kubeconfig(0600)
                                                 ├─ helm upgrade --install --dry-run=server（预检）
                                                 ├─ helm upgrade --install --atomic --timeout 10m
                                                 └─ helm status + 清理
```

### 3.1 helm 命令（两种画像同一形状）

```bash
helm upgrade --install <release> <chart.tgz> \
  -n <namespace> --create-namespace \
  --values <merged-values.yaml> \
  --rollback-on-failure --timeout 10m \
  --labels platform.coding/managed=true,platform.coding/component=<code>,platform.coding/cluster=<clusterId>
```

- **Helm 4 把 `--atomic` 改名为 `--rollback-on-failure`**（本平台钉死 helm 4.2.0；照旧写 `--atomic` 会直接报未知参数）。
  `--rollback-on-failure` 会把 `--wait` 默认成 `watcher`：失败自动回滚到上一版本；首次安装失败则自动清理。
- **预检分两级**（重要）：
  - `helm template`（客户端渲染）**必须通过**，失败即硬失败 —— 这一级能抓 values 路径写错、模板错误；
  - `helm upgrade --install --dry-run=server` **失败只记警告**：chart 同时安装 CRD 与 CR 时，
    dry-run 会因"CRD 尚不存在"报假阴性（kube-prometheus-stack 正是这种 chart）。
    把它当硬门禁会**永远装不上**。
- helm 输出（stderr 尾部）脱敏后写入操作记录。

### 3.2 状态机

```
PENDING → RUNNING → SUCCESS
                  ├→ FAILED     （helm 非 0；--atomic 已回滚）
                  └→ UNKNOWN    （进程重启/超时中断；提示查看 release 状态）
```

**`pending-install` / `pending-upgrade` 单独识别**：说明上一次操作被中断（Helm 进程被杀死时常见），页面给「先回滚或重试」的处置建议，而不是笼统的"失败"。进程重启后，把超过 15 分钟仍 `RUNNING` 的记录标为 `UNKNOWN`。

### 3.3 升级语义

- **比较的是 chart 版本**：读目标 release 的 chart version（`helm list` / release secret），与 recipe 里的 `chartVersion` 比。
- **不要拿探测到的应用版本跟 chart 版本比**：`prometheus-operator v0.93.1` 与 `kube-prometheus-stack 88.6.2` 是两套编号。UI 两个都显示并注明。
- 升级**复用上次成功操作的最终 values**（见 §4），用户修改可编辑字段则覆盖对应路径。

---

## 4. 数据模型与权限

### 4.1 新表 `platform_component_operation`

| 列 | 说明 |
|---|---|
| `id` | 主键 |
| `cluster_id` / `component_code` | 目标 |
| `action` | INSTALL / UPGRADE |
| `profile` | 本次使用的画像（留档，便于排障） |
| `from_version` / `to_version` | chart 版本的 from→to（安装时 from 为空） |
| `release_names` | 本次涉及的 release 列表（JSON） |
| `values_snapshot` | **最终合并后的 values**（升级时复用；体积小，可存 TEXT） |
| `status` / `message` / `log_tail` | 状态与失败原因（脱敏） |
| `operator` / `started_at` / `finished_at` | 审计 |

**不建"组件安装状态表"**：装没装仍由 S1.2 的探测回答。

### 4.2 端点与权限

| 端点 | 权限码 | 说明 |
|---|---|---|
| `POST /cluster/components/apply` | `platform:cluster:manage` | body: clusterId / componentCode / action / targetVersion / editableValues |
| `POST /cluster/components/operations/get` | `platform:cluster:manage` | body: operationId（或 clusterId+componentCode 取最近一条） |

k8s-server 侧：`POST /components/apply`，`AccessBoundary.PLATFORM`（平台管理员专属）。

迁移 `V2026_10_18_1__component_operations.sql`：建表 + 两行权限 + 显式绑 `builtin_role_admin`。

### 4.3 归属与安全

- **release 标签**：`platform.coding/managed=true` / `component` / `cluster`（打在 release secret 上）。
- **资源归属**：用 Helm 自带的 `app.kubernetes.io/managed-by=Helm` + `meta.helm.sh/release-name` + `meta.helm.sh/release-namespace` 反查，**不自己给资源补打标签**。
- **临时 kubeconfig**：`createTempFile` + 0600 + `finally` 删除；日志绝不打印内容。
- **values 里不出现密码类字段**（如 Grafana admin password）：v1 用平台默认或不启用该子组件。

---

## 5. 组件配置契约（本批重点）

> 原则：**平台依赖的那些 values 是契约值（🔒），由 recipe 固定、不在 UI 暴露**；用户只能改 ⚙️ 少量字段。
> 契约值写错的后果不是"装不上"，而是"装上了但平台功能用不了" —— 所以每条都要有**功能级验收**。
> ⚠️ 具体 values 路径以**所采用 chart 版本**的 `values.yaml` 为准；下表已对 kube-prometheus-stack 当前主线核实，其余在实施时逐条核对。

### 5.1 `prometheus-operator`（kube-prometheus-stack）

平台对它的依赖：监控曲线（Thanos 查询，**全部按 `cluster_name` 过滤**）、ServiceMonitor / PodMonitor 页、告警规则（PrometheusRule）、租户的 AlertmanagerConfig。

| 值 | 类型 | 说明 |
|---|---|---|
| `prometheus.prometheusSpec.externalLabels.cluster_name=<集群名>` | 🔒 | Thanos 里区分集群的唯一依据；缺了全部曲线串集群 |
| `prometheus.prometheusSpec.ruleSelectorNilUsesHelmValues=false` | 🔒 | **默认 true** ⇒ ruleSelector 为空时只选带 release 标签的规则；平台在租户 ns 创建的 PrometheusRule 会被**静默忽略** |
| `prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false` | 🔒 | 同上，否则 ServiceMonitor 页形同虚设 |
| `prometheus.prometheusSpec.podMonitorSelectorNilUsesHelmValues=false` | 🔒 | 同上 |
| `prometheus.prometheusSpec.probeSelectorNilUsesHelmValues=false` | 🔒 | 同上 |
| `prometheus.prometheusSpec.ruleNamespaceSelector={}` / `serviceMonitorNamespaceSelector={}` / `podMonitorNamespaceSelector={}` / `probeNamespaceSelector={}` | 🔒 | 显式全命名空间（业务 ns 里的对象才能被发现） |
| `alertmanager.alertmanagerSpec.alertmanagerConfigMatcherStrategy.type=OnNamespace` | 🔒 | 否则租户 ns 的 AlertmanagerConfig 不生效（默认 `None` 只匹配同 ns） |
| `alertmanager.alertmanagerSpec.alertmanagerConfigSelector={}` / `alertmanagerConfigNamespaceSelector={}` | 🔒 | 同上 |
| `prometheus.prometheusSpec.remoteWrite[0].url=<管理集群 Thanos receive>` | 🔒（business） | 业务集群上报入口。**平台现有配置只有 `thanos.query.url`，本批需新增 `thanos.receive.url`**（同一 Thanos 的 receive 端点） |
| 额外 release `platform-thanos`（query / receive / store + 对象存储） | 🔒（management） | 管理集群的观测底座 |

可编辑（⚙️）：`prometheus.prometheusSpec.retention`、`prometheus.prometheusSpec.storageSpec`（StorageClass + 容量）、`prometheus.prometheusSpec.replicas`、`grafana.enabled`。

**功能级验收**（安装/升级后自动执行，失败则该操作标为「已下发但功能未就绪」）。
验收会**临时写入**少量对象（打 `platform.coding/verify=true` 标签，测完立即删除）——这是一次写操作，
必须在操作记录里如实记录，并保证 `finally` 清理：
1. 探测 `prometheus-operator` = `INSTALLED_HEALTHY`；
2. 在监控命名空间建一条临时 PrometheusRule（打平台标签），30s 内能出现在 Prometheus 的规则列表中（验证 selector 契约）；
3. 查管理集群 Thanos：`count(up{cluster_name="<本集群>"}) > 0`（验证 externalLabels + remoteWrite）；
4. Alertmanager：建一条临时 AlertmanagerConfig，确认其路由被加载（验证 matcherStrategy）。

### 5.2 `gateway-api`

平台对它的依赖：路由页（含 **TCPRoute / TLSRoute / UDPRoute**）。

| 值 | 类型 | 说明 |
|---|---|---|
| **channel = experimental** | 🔒 | v1.6 里 TCP/UDP/TLS Route 只在 experimental channel；standard channel 装完这三个页面直接不可用 |
| CRD 版本 = 平台路由页支持的版本 | 🔒 | 与 `KubernetesOperationsFactory` 的版本分派对齐 |

可编辑：无。**功能级验收**：三个实验类路由各创建一条 dry-run。

### 5.3 `istio` / `istio-ambient`

| 值 | 类型 | 说明 |
|---|---|---|
| 控制面命名空间固定 `istio-system` | 🔒 | S1.2 探测与平台网格页都按它找 |
| **对外入口 gateway 名固定 `external-gateway`** | 🔒 | 集群对外入口拓扑依赖它（2026-10-16 实测：端口级 listener ×15、路由跨 ns 挂） |
| `meshID` 全局一致（多集群时） | 🔒 | 跨集群网格 |
| ambient：`istio-cni` + `ztunnel` 两个子 chart | 🔒 | `istio-ambient` recipe **`requires: [istio]`** |
| ambient：`dataplane-mode` / `use-waypoint` 标签由平台写 | 🔒 | 组件本身不管 |

可编辑：`replicas`、资源限制。**功能级验收**：`istioctl` 不在平台内 → 用探测 + 一次 Gateway 创建/删除验证控制面存活。

### 5.4 `tekton` / `tekton-operator`

平台对它的依赖：CI 全流程、构建缓存。

| 值 | 类型 | 说明 |
|---|---|---|
| `TektonConfig.spec.pipeline.coschedule=pipelineruns` | 🔒 | 否则一个 TaskRun 挂不了两块 PVC workspace ⇒ **构建缓存不启用**（集群前提文档已记） |
| `TektonConfig.spec.pipeline.default-container-resource-requirements` | 🔒 | 命名空间配额下 init 容器需要默认资源 |
| 命名空间固定 `tekton-pipelines` / `tekton-operator` | 🔒 | 探测位置 |

可编辑：无。**功能级验收**：跑一条最小 PipelineRun（平台已有该能力），确认成功。

### 5.5 `calico` / `tigera-operator`

| 值 | 类型 | 说明 |
|---|---|---|
| `Installation.spec.calicoNetwork.ipPools[].cidr` | 🔒 | **装错断网**；必须与集群现有 Pod CIDR 一致 |
| `Installation.spec.calicoNetwork.encapsulation` | 🔒 | 与集群网络方案一致 |
| tigera-operator 命名空间 `tigera-operator`；CNI 命名空间 `calico-system` | 🔒 | 探测位置 |

- `tigera-operator` 单独安装**不改变网络**，只有 `Installation` CR 才接管 CNI —— 目录里 `calico` **`requires: [tigera-operator]`**。
- 对**已运行**的集群，本组件的安装/升级属最高风险档：UI 需二次确认 + 明示"可能中断集群网络"。
- 可编辑：无（新建集群时的 CIDR 由集群登记流程决定，不在本批）。
- **功能级验收**：探测 healthy + 节点间连通性抽样（平台无此能力 → 手工检查项）。

### 5.6 `cert-manager` / `otel-operator`

| 值 | 类型 | 说明 |
|---|---|---|
| `crds.enabled=true` + `crds.keep=true` | 🔒 | 否则卸载/升级时 CRD 被删，证书对象全丢 |
| otel-operator `admissionWebhooks.certManager.enabled=true` | 🔒 | 它的 webhook 证书依赖 cert-manager ⇒ `otel-operator` **`requires: [cert-manager]`** |

可编辑：无。

### 5.7 `metrics-server`

| 值 | 类型 | 说明 |
|---|---|---|
| `args: --kubelet-insecure-tls` | 🔒 | 自签 kubelet 证书的集群（本集群同类）否则永远 NotReady |
| `args: --kubelet-preferred-address-types=InternalIP` | 🔒 | 内网地址可达性 |
| 命名空间固定 `kube-system` | 🔒 | 探测位置 |

可编辑：副本数、资源限制。

---

## 6. 与 S1.2 的联动

- 安装/升级成功后**自动触发一次组件探测刷新**，页面状态随之更新。
- 探测结果里的"版本"是**应用版本**；操作记录里的是**chart 版本**。两者都在 UI 显示并注明，避免看起来矛盾。
- 组件的 `detail` 里补充 release 信息（`release: platform-kps (chart 88.6.2)`），让"装了什么"可见。

---

## 7. 测试

**后端**
- recipe 解析：画像选择、`requires` 校验、values 合并（默认 < 用户可编辑 < 平台契约，契约不可被覆盖）。
- 升级版本比较：chart 版本 vs chart 版本（不是探测版本）。
- 操作状态机：成功/失败/中断（`pending-*`）。
- helm 命令拼装：标签、原子、超时、临时文件清理（用假的 helm 可执行脚本验证参数）。

**前端**
- 只有 `NOT_INSTALLED` 给安装；`UNKNOWN` 不给；已装但非平台管理的只读。
- 进度视图轮询与失败原因展示。

**真集群**
- 在一个**业务测试集群**上装 `metrics-server`（风险最低）走完整链路；
- Prometheus 的 4 条功能级验收（§5.1）在管理集群上手工跑一次。

---

## 8. 已知前提与风险

| 事实 | 影响 |
|---|---|
| 现有 `ps` / `th` 是手工 Helm 装的，平台无 values 记录 | 首次上线时这些组件只读；想交给平台管需先卸载重装（本批不提供） |
| 平台已有 `thanos.query.url`（全局单例） | 业务集群 remoteWrite 指向同一个管理集群 Thanos |
| `kube-prometheus-stack` 的 selector 默认只认 release 标签 | §5.1 的四个 `*NilUsesHelmValues=false` 是**必须**的，否则告警/监控静默失效 |
| 平台无 `istioctl` / `kubectl` 执行环境 | 组件的功能级验收能用 API/探测做的自动做，做不了的列为手工检查项 |

---

## 9. 有意不做 / 已知缺口

1. 卸载、回滚按钮（Helm 侧有能力，产品侧本批不开放）。
2. 租户安装；组件安装始终是平台管理员动作。
3. 接管已有 release（包括提供"导入现有 release"的能力）。
4. 组件版本编排（Istio 控制面→数据面的升级顺序、Calico 的滚动升级策略）。
5. 任意 chart / values 全量编辑。
6. 组件安装到非固定命名空间。

---

## 10. 部署

- 重建 **k8s-server + platform-api + platform-web**。
- 迁移 `V2026_10_18_1__component_operations.sql`（建表 + 两行权限 + 显式绑 admin）**先重放**。
- k8s-server 镜像**新增 helm 二进制**（固定 **4.2.0**，随镜像发布；注意 Helm 4 的 `--rollback-on-failure` 取代了 3.x 的 `--atomic`）。
- 新增配置：`platform.components.helm-binary`（默认 `helm`，指向镜像内置的固定版本）、
  `thanos.receive.url`（业务集群 Prometheus 的 remoteWrite 目标；管理集群 recipe 引用同一地址）。
  chart 已内置，运行时**不需要外网或代理**。
- `cluster_role` 迁移：`V2026_10_18_1` 同文件加列（`ALTER TABLE k8s_cluster ADD COLUMN cluster_role ... DEFAULT 'BUSINESS'`），并把现有唯一集群手工改为 `MANAGEMENT`。
