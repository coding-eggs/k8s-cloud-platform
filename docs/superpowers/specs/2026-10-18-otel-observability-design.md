# OpenTelemetry 可观测接入设计（traces / 应用指标 / 应用日志）

> 需求出处：2026-10-18 用户提出「opentelemetry 现在只是部署 op，它的目的是自动采集集群中工作负载数据，
> 所以要建立 OpenTelemetryCollector、暴露 receivers、配置 exporters（Tempo / Prometheus / ES），
> 还有自动注入的 Instrumentation」。
>
> 与 S2.4.3（链路追踪）的关系：本文就是 S2.4.3 的落地方案，追踪后端定为 **Tempo**。
> 与 S3.6（组件生命周期）的关系：完全复用组件安装引擎；`otel-operator` 组件扩展为「采集栈」，
> Tempo 挂在它的 MANAGEMENT recipe 里，不新增目录行（与 Prometheus + Thanos 同构）。
>
> 本文是设计稿。实施计划（任务拆分、TDD 步骤）在本稿评审通过后另出。

---

## 已定决策（2026-10-18 brainstorming 结论）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **traces → Tempo** | OTLP 原生；MinIO 对象存储现成；Grafana / 平台自研视图都能接 |
| 2 | **应用指标 → collector 的 `prometheus` exporter，由 Prometheus 拉取** | 保留 Prometheus 的记录/告警规则和 `cluster_name` 外部标签；不直写 Thanos |
| 3 | **日志 → 同一个 gateway 的 logs 管道 → 现有 ES 9.2.1** | ES 已就绪；不引入 Loki/Kafka/Logstash |
| 4 | **日志是应用级、按工作负载开关**，不做全容器采集 | 用户明确否决节点级 DaemonSet 全量采集 |
| 5 | **第一批运行时：Java + Python**（traces + 日志）；**Node 第一批不做** | Node 的 auto-instrumentation 只初始化 traces/metrics，没有日志处理器 |
| 6 | **Nginx 等文件日志留到第二批**：per-Pod collector sidecar，**用户必须提供日志路径** | sidecar 读不到别的容器 stdout；平台无法推断日志写在哪 |
| 7 | **网格 tracing 只配 ingress gateway + waypoint**；**ztunnel 不动** | ztunnel 是 L4，不产生 span |
| 8 | **Tempo 并入 `otel-operator` 组件的 MANAGEMENT recipe** | 一次安装得到完整追踪栈；BUSINESS 只填外部 Tempo 地址 |
| 9 | **不采集集群/节点指标** | kps 的 node-exporter / kube-state-metrics / kubelet 已覆盖 |
| 10 | trace 查看界面（Grafana datasource 或平台自研）**不在本批** | 先把采集链路打通；S2.4.3 的 UI 另做 |
| 11 | 采样率默认 **10%**，安装/编辑表单可改 | 成本与可用性的折中 |
| 12 | collector 统一用 **contrib 镜像** | operator 默认的 `opentelemetry-collector-k8s` 发行版不含 `elasticsearch` exporter |

---

## 1. 现状与目标

### 1.1 实测现状（`coding-cluster`，2026-10-18）

| 事实 | 证据 |
|---|---|
| 集群里**没有任何 OpenTelemetry CRD** | `kubectl get crd \| grep opentelemetry` 为空 |
| 平台 recipe 里的 `otel-operator` 只装 operator | `ComponentRecipeCatalog.OTEL_OPERATOR_RELEASES` 只有一个 ReleaseSpec |
| ES 9.2.1 + Kibana 已就绪 | `default/es-es-default`（ECK）、`default/kibana-kb` |
| MinIO 已就绪，桶 `thanos` 在用 | Thanos 的 `objstore.yml` 指向 `192.168.31.80:9002` |
| Prometheus + Thanos 在 `monitor` | `platform-kps-*`、`platform-thanos-*` |
| 没有 Tempo / Loki / SkyWalking / Jaeger / Grafana | `kubectl get deploy -A \| grep -Ei 'tempo\|loki\|jaeger\|grafana'` 为空 |
| 入口 = `istio-system/external-gateway`；waypoint = `default/waypoint`、`default/waypoint-all` | `kubectl get gateways -A` |
| `Telemetry` CRD 已有 v1 | `telemetries.telemetry.istio.io` |

### 1.2 目标数据流

```
                    ┌──────────────────────── 应用层（OpenTelemetry） ───────────────────────┐
                    │  Instrumentation（注入 agent/SDK）                                      │
Java / Python 应用 ──┤    traces / metrics / logs  ──OTLP──▶ platform-otel-gateway          │
                    │                                          │                             │
                    └──────────────────────────────────────────┼─────────────────────────────┘
                                                               │
          ┌──────────────────── 代理层（Istio） ────────────────┤
          │ ingress gateway / waypoint ── Telemetry ──OTLP─────┤
          │ ztunnel（L4）：不产生 span，不配置                    │
          └────────────────────────────────────────────────────┤
                                                               ▼
                                              platform-otel-gateway（collector）
                                                ├─ traces  ──▶ Tempo（S3/MinIO）
                                                ├─ metrics ──▶ :8889（Prometheus 抓取）
                                                └─ logs    ──▶ ES（otel-logs-*）
```

---

## 2. 信号分工

| 信号 | 产生方 | 采集方式 | 落地 |
|---|---|---|---|
| **Traces** | 应用 SDK（Instrumentation）/ Envoy（ingress、waypoint） | OTLP → gateway | Tempo |
| **应用指标** | 应用 OTel SDK | OTLP → gateway 的 `prometheus` exporter（`:8889`）→ Prometheus 抓取 | Prometheus → Thanos |
| **应用日志** | 应用日志框架（Java Logback/Log4j2、Python logging） | OTel logs → gateway | ES |
| **文件日志**（第二批） | 应用写文件（Nginx 等） | per-Pod sidecar `filelog` → gateway | ES |
| **集群/节点指标** | kubelet / node-exporter / kube-state-metrics | **不经过 OTel** | kps 已覆盖 |

**为什么应用指标不直写 Thanos**：Thanos Receive 只负责长期存储；直写会绕过 Prometheus 的记录规则、
告警规则和 `cluster_name` 外部标签，平台指标页与告警都会缺数据。

**为什么日志不采全部容器 stdout**：用户明确否决；且全量采集会把平台自身的日志也灌进 ES。

---

## 3. 组件形状

### 3.1 `otel-operator` 组件扩展

组件 code 保持 `otel-operator`，显示名可改为「OpenTelemetry 采集栈」。recipe 的 release 顺序：

| 顺序 | release | namespace | 内容 |
|---|---|---|---|
| 1 | `platform-otel-operator` | `opentelemetry-operator-system` | **第一遍**：CRD + operator（`crds.create: true` 默认） |
| 2 | `platform-tempo`（仅 MANAGEMENT） | `monitor` | Tempo 3.2.0 |
| 3 | `platform-otel-operator` | `opentelemetry-operator-system` | **第二遍**：`extraObjects` 下发 Instrumentation + collector gateway |

两个硬约束：

1. **CRD 两阶段**：otel-operator chart 的 CRD 是 **templates**（不是 Helm 的 `crds/` 目录），
   与 Tekton 完全同一个坑 —— CR 和 CRD 在同一份 manifest 里会在 Helm Build 阶段报
   `no matches for kind ... ensure CRDs are installed first`。必须用**同名 ReleaseSpec 跑两遍**，
   第二遍是新 helm 进程，discovery 里已经有 CRD。
2. **版本语义**：`availableVersions()` / `resolveTargetVersion()` 只认 `releases().get(0)`，
   所以 operator 必须是第一个 ReleaseSpec；Tempo 不能排第一，否则组件版本会变成 Tempo 的 3.2.0。

**探针**：沿用现有 operator Deployment 判据，不把 gateway / Tempo 塞进 `probes`。
原因：`ComponentProbe` 之间是「任一命中即算」（istio 用两个镜像片段判同一个 Deployment），
把 gateway 加进去会变成 OR，gateway 在而 operator/CRD 不在也显示已安装。
多 release 的组件状态以「主 release 活判据」为准是既有惯例（istio 五个 release 也只探 istiod）；
Tempo / gateway 的失败通过**操作记录**（`log_tail`、FAILED）呈现。

### 3.2 Tempo 部署形态

- chart：`grafana-community/tempo` **3.2.0**（app 3.1.0，单二进制、1 副本 StatefulSet）；
  `grafana/helm-charts` 自 2026-01 起迁移到 `grafana-community/helm-charts`，旧 `grafana/tempo`
  1.24.4 已标记 deprecated，故采用社区仓库版本；
  需要 `helm pull` 官方 chart 入库，目录
  `k8s-server/src/main/resources/charts/components/tempo/3.2.0/chart/`。
- 命名：release `platform-tempo`（release 名包含 chart 名 → fullname = `platform-tempo`），
  namespace `monitor`。
- 地址：
  - OTLP gRPC 入库：`platform-tempo.monitor.svc:4317`
  - 查询 HTTP：`http://platform-tempo.monitor.svc:3200`（`/api/search`、`/api/traces/{id}`）
- 存储：S3/MinIO。**MinIO 地址、桶、凭证 Secret、TLS 校验开关全部是安装/编辑表单字段**
  （默认 `192.168.31.80:9002` / `tempo` / 复用 Thanos 的凭证 Secret / 跳过校验；
  见 §6.2），平台只做预填，不硬编码。Tempo 用**独立桶**（默认 `tempo`，需先在 MinIO 建好），
  **不与 Thanos 的 `thanos` 桶共用，也不设前缀** —— block 直接写在桶根。
- 凭证：平台在 helm 之前于 `monitor` 生成 `platform-tempo-objstore` Secret
  （`MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`，从表单选定的来源 Secret 转换），
  Tempo 通过 `extraEnvFrom` + `-config.expand-env=true` 引用；**凭证不写进 values 快照**
  （与 Thanos 的 `objstore.yml` 同一原则）。
- WAL/本地缓存：`persistence.enabled=true`，`storageClassName: nfs-client`，20Gi。

values 形状：

```yaml
tempo:
  storage:
    trace:
      backend: s3
      s3:
        bucket: ${tempo.minio.bucket}          # 平台占位符（表单值，独立桶 tempo）
        endpoint: ${tempo.minio.endpoint}
        access_key: ${MINIO_ACCESS_KEY}        # 留给 Tempo 的 -config.expand-env
        secret_key: ${MINIO_SECRET_KEY}
        insecure: true
  extraArgs:
    config.expand-env: "true"
  extraEnvFrom:
    - secretRef: {name: platform-tempo-objstore}
  reportingEnabled: false      # 内网部署，不发 usage report
  resources:
    requests: {cpu: 200m, memory: 512Mi}
    limits:   {cpu: "1",  memory: 2Gi}
persistence:
  enabled: true
  storageClassName: nfs-client
  size: 20Gi
```

> **占位符冲突（实现时必须处理）**：平台的 `ValueTemplate` 对未知 `${...}` 是**硬失败**的，
> 而 Tempo 需要 `${MINIO_ACCESS_KEY}` 这类占位符留到**运行时**由 `-config.expand-env=true` 展开。
> 所以 Tempo 的 `ReleaseSpec` 要带一个 `preserveUnknownPlaceholders=true`（默认 false）：
> 平台只解析已知的 `${tempo.minio.*}`，把 `${MINIO_*}` 原样交给 helm；
> 快照里存的是模板串，**不含凭证**。collector 里的 `${env:...}` 因为含冒号，本来就不会命中平台占位符正则。
> 凭证 Secret 的 key 必须恰好是 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`（预检时校验，避免拼错后 Tempo 静默拿到空串）。

> Tempo 自身的 `serviceMonitor` 第一批关闭：它依赖 `monitoring.coreos.com` CRD，
> 而 OTel 组件不强制 `requires: prometheus-operator`。需要监控 Tempo 时由后置步骤按 CRD 是否存在决定。

### 3.3 业务集群

BUSINESS 画像的 recipe **没有** `platform-tempo` release；collector 的 Tempo exporter 指向表单里的外部地址：

- 管理集群默认：`platform-tempo.monitor.svc:4317`（collector 的 `otlp` exporter 是 gRPC，端点用 `host:port`，不带 scheme）
- 业务集群：用户在安装/编辑表单里填平台侧可达的 Tempo 地址（必填，空则拒绝安装）
- 存储进操作快照 `__platform.tempoEndpoint`；编辑表单预填**上次成功操作**的值
  （与 Thanos Receive 地址同一套：`selectLatestSuccesses` → `ComponentStatusDTO`）。

业务集群要回传管理集群，需要 Tempo 的 4317 对外入口（external-gateway / NodePort / LB），
属于第三批，不在本稿范围。

---

## 4. OpenTelemetry 配置

### 4.1 Instrumentation（三个 CR）

注入对象放在 `monitor`。为了支持「只追踪 / 只日志 / 两者都要」三种组合，建三个 Instrumentation，
工作负载的注解只选其中一个（一个 Pod 只能被一个 Instrumentation 注入）：

| 名称 | sampler | 日志 env | 用途 |
|---|---|---|---|
| `platform-instrumentation` | `parentbased_traceidratio` / 0.1 | 无 | 只开追踪 |
| `platform-instrumentation-logs` | `always_off` | `OTEL_LOGS_EXPORTER=otlp` + Java appender 开关 | 只开日志 |
| `platform-instrumentation-all` | `parentbased_traceidratio` / 0.1 | 同上 | 追踪 + 日志 |

公共部分：

```yaml
apiVersion: opentelemetry.io/v1alpha1
kind: Instrumentation
metadata:
  name: platform-instrumentation
  namespace: monitor
spec:
  exporter:
    endpoint: http://platform-otel-gateway-collector.monitor.svc:4318
  propagators: [tracecontext, baggage]
  env:
    # 统一走 OTLP/HTTP（4318）：operator 的 Python 注入镜像只带 http/protobuf exporter
    # （gRPC 依赖 OS/Python 版本），Java agent 也支持 http/protobuf，一个端点覆盖两种语言
    - name: OTEL_EXPORTER_OTLP_PROTOCOL
      value: http/protobuf
  sampler:
    type: parentbased_traceidratio
    argument: "${otel.sampling}"          # 表单值，默认 "0.1"
  resource:
    resourceAttributes:
      cluster.name: ${cluster.name}
  java: {}
  python: {}
```

日志版额外带：

```yaml
  env:
    - name: OTEL_EXPORTER_OTLP_PROTOCOL
      value: http/protobuf
    - name: OTEL_LOGS_EXPORTER
      value: otlp
  java:
    env:
      - name: OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED
        value: "true"
      - name: OTEL_INSTRUMENTATION_LOG4J_APPENDER_ENABLED
        value: "true"
```

> 端点用 **4318** 而不是 4317：`Instrumentation` 的 `exporter.endpoint` 对所有语言是同一个值，
> 而 Python 的自动注入镜像固定使用 `http/protobuf`（4318）；写成 4317 会让 Python 的 trace 全部发不出去。
> collector 的两个 receiver 都开，collector 之间、Istio、sidecar 仍用 gRPC 4317。

注入注解（写在 **PodTemplate** 上，由平台按语言/组合选择）：

```yaml
instrumentation.opentelemetry.io/inject-java:   "monitor/platform-instrumentation"
instrumentation.opentelemetry.io/inject-python: "monitor/platform-instrumentation"
```

**operator 不自动识别语言**：注解写什么就注入什么。语言选错不会崩，但不会产生数据 ——
所以平台必须让用户确认语言，并给出镜像名启发式预选（`jdk`/`jre`/`spring` → Java，
`python`/`uvicorn`/`gunicorn` → Python），**不提供 Node 选项**。

### 4.2 Collector Gateway

```yaml
apiVersion: opentelemetry.io/v1beta1
kind: OpenTelemetryCollector
metadata:
  name: platform-otel-gateway
  namespace: monitor
spec:
  mode: deployment
  replicas: ${otel.gatewayReplicas}          # 默认 1
  image: ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.161.0
  ports:
    - {name: otlp-grpc, port: 4317, protocol: TCP}
    - {name: otlp-http, port: 4318, protocol: TCP}
    - {name: prometheus, port: 8889, protocol: TCP}
  env:
    - name: TEMPO_ENDPOINT
      value: ${tempo.endpoint}
    - name: ES_ENDPOINT
      value: ${otel.es.endpoint}
    - name: ES_USERNAME
      valueFrom:
        secretKeyRef: {name: platform-otel-es, key: username}
    - name: ES_PASSWORD
      valueFrom:
        secretKeyRef: {name: platform-otel-es, key: password}
  resources:
    requests: {cpu: 200m, memory: 256Mi}
    limits:   {cpu: "1",  memory: 1Gi}
  config:
    receivers:
      otlp:
        protocols:
          grpc: {endpoint: 0.0.0.0:4317}
          http: {endpoint: 0.0.0.0:4318}
    processors:
      memory_limiter:
        check_interval: 1s
        limit_percentage: 80
        spike_limit_percentage: 20
      k8sattributes:
        auth_type: serviceAccount
        extract:
          metadata: [k8s.namespace.name, k8s.pod.name, k8s.deployment.name, k8s.node.name]
        pod_association:
          - sources: [{from: resource_attribute, name: k8s.pod.ip}]
          - sources: [{from: connection}]
      batch: {timeout: 5s, send_batch_size: 8192}
    exporters:
      otlp/tempo:
        endpoint: ${env:TEMPO_ENDPOINT}
        tls: {insecure: true}
      prometheus:
        endpoint: 0.0.0.0:8889
        resource_to_telemetry_conversion: {enabled: true}
      elasticsearch:
        endpoints: ["${env:ES_ENDPOINT}"]
        user: ${env:ES_USERNAME}
        password: ${env:ES_PASSWORD}
        tls: {insecure_skip_verify: true}
        logs_index: otel-logs-%{+yyyy.MM.dd}
        mapping: {mode: none}
    service:
      pipelines:
        traces:
          receivers: [otlp]
          processors: [memory_limiter, k8sattributes, batch]
          exporters: [otlp/tempo]
        metrics:
          receivers: [otlp]
          processors: [memory_limiter, k8sattributes, batch]
          exporters: [prometheus]
        logs:
          receivers: [otlp]
          processors: [memory_limiter, k8sattributes, batch]
          exporters: [elasticsearch]
```

要点：

- **镜像必须是 contrib**。operator 默认的 `opentelemetry-collector-k8s:0.161.0` 不含
  `elasticsearch` exporter；contrib 同时覆盖 traces/metrics/logs。
- ES 凭证：表单从 `monitor` 选择来源 Secret（默认名字 `es-es-elastic-user`，key `elastic`；
  ECK 的原始 Secret 在 `default`，需先复制到 `monitor`；**命名空间固定，不提供切换**），
  平台校验后复制成 `monitor/platform-otel-es`（`username` / `password`）。
  ES 地址、用户名、TLS 校验开关、日志索引同样来自表单（见 §6.2）。
- `prometheus` exporter 打开 `resource_to_telemetry_conversion`，把 `service.name`、
  `cluster.name` 等 resource 属性转成指标 label。
- `k8sattributes` 需要 collector ServiceAccount 有 pods/namespaces/replicasets 的
  get/list/watch 权限（operator 0.161 会按配置自动生成；实测不生效时补一条 ClusterRole）。

**ServiceMonitor**：不放进 Helm values（`servicemonitors.monitoring.coreos.com` 的 CRD 可能不存在），
由 platform-api 在 helm 成功后做**后置 SSA**：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata: {name: platform-otel-gateway, namespace: monitor}
spec:
  selector:
    # 必须匹配 operator 生成的 collector Service；首次实施时按实际 labels 固定
    # （通常是 app.kubernetes.io/name: platform-otel-gateway-collector）
    matchLabels: {app.kubernetes.io/name: platform-otel-gateway-collector}
  endpoints: [{port: prometheus, interval: 30s}]
```

CRD 不存在时**跳过并记一条提示**，不算安装失败（traces 不依赖 Prometheus）。

### 4.3 应用日志（第一批）

- Java：Logback / Log4j2 的 OTel appender 随 agent 带入，`OTEL_LOGS_EXPORTER=otlp` + appender 开关即采；
- Python：注入镜像自带 `opentelemetry-instrumentation-logging`（默认开启），
  `OTEL_LOGS_EXPORTER=otlp` 后 `logging` / `structlog` 的记录会导出；
- **Node：第一批不提供**（`auto-instrumentations-node` 的 `NodeSDK` 没有日志处理器；
  且 Node 追踪也一并延后）；
- 能力边界：应用**只写 stdout、不用日志框架**时采不到（这是第二批 sidecar 的适用场景，见 §4.4）。
- 日志级别由应用自己的 logger 配置决定；需要过滤 DEBUG 时在 gateway 加 `filter` processor。
- ES 侧需要 ILM（推荐 `otel-logs-*` 保留 7 天）；本批在文档里给运维步骤，不做自动化。

### 4.4 文件日志 sidecar（第二批）

适用 Nginx 等「写文件、非日志框架」的应用。**开关由用户显式打开，且必须提供日志位置**：

| 表单项 | 必填 | 说明 |
|---|---|---|
| 日志目录 | 是 | 容器内的绝对路径，如 `/var/log/nginx`（sidecar 采集该目录下的 `*.log`；共享 CR 固定路径，不能用 per-workload glob） |
| 确认写文件 | 是 | sidecar 读不到别的容器 stdout；不勾选不允许提交 |
| 日志格式 | 否 | combined / json / plain，决定 parser；先原样采也可 |
| 多行合并 | 否 | Java 堆栈类；本批不做（后续再加） |

平台在打开开关时改 PodTemplate：

1. 加 `emptyDir` 卷，并挂到应用容器的日志目录（默认 `/var/log/nginx`）；
2. 加注解 `sidecar.opentelemetry.io/inject: "monitor/platform-otel-sidecar"`；
3. 固定 sidecar collector CR：

```yaml
apiVersion: opentelemetry.io/v1beta1
kind: OpenTelemetryCollector
metadata: {name: platform-otel-sidecar, namespace: monitor}
spec:
  mode: sidecar
  image: ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.161.0
  env:
    - {name: K8S_POD_NAME, valueFrom: {fieldRef: {fieldPath: metadata.name}}}
    - {name: K8S_NAMESPACE, valueFrom: {fieldRef: {fieldPath: metadata.namespace}}}
    - {name: K8S_POD_UID, valueFrom: {fieldRef: {fieldPath: metadata.uid}}}
  volumeMounts:
    # 卷 platform-otel-logs 由工作负载的 PodTemplate 定义（emptyDir），这里只挂载
    - {name: platform-otel-logs, mountPath: /var/log/app, readOnly: true}
  config:
    receivers:
      filelog:
        include: [/var/log/app/*.log]
        start_at: end
        include_file_path: true
    processors:
      resource:
        attributes:
          - {key: k8s.pod.name, value: "${env:K8S_POD_NAME}", action: upsert}
          - {key: k8s.namespace.name, value: "${env:K8S_NAMESPACE}", action: upsert}
          - {key: k8s.pod.uid, value: "${env:K8S_POD_UID}", action: upsert}
      batch: {timeout: 5s}
    exporters:
      otlp:
        endpoint: platform-otel-gateway-collector.monitor.svc:4317
        tls: {insecure: true}
    service:
      pipelines:
        logs:
          receivers: [filelog]
          processors: [resource, batch]
          exporters: [otlp]
```

> sidecar 不再跑 `k8sattributes`：它用 Pod 的 ServiceAccount，未必有 list pods 权限；
> gateway 收到同 Pod IP 的 OTLP 后会用 `from: connection` 补齐 deployment 等元数据。

限制：

- sidecar **只能读到挂进同一个卷的文件**，读不到 stdout；
- 自定义路径的 `filelog` 不会像 `/var/log/pods/...` 那样自动带 `k8s.pod.uid`，所以 sidecar CR 用
  downward API 注入 `K8S_POD_NAME` / `K8S_NAMESPACE` / `K8S_POD_UID`，再由 `resource` processor 写成
  resource 属性，`k8sattributes` 才能关联出 deployment（见上面的 CR）；
- 每个开启的 Pod 多一个 collector 容器（约 50–100Mi）；
- nginx 官方镜像的 `/var/log/nginx/*.log` 是 `/dev/stdout` 的软链，挂 emptyDir 后会变成真实文件；
  若配置里直接写 `/dev/stdout`，需要先改配置；
- 仍由 gateway 统一写 ES，sidecar 不持有 ES 凭证。

---

## 5. 网格侧

### 5.1 provider 注册（istiod）

在 istio recipe 的 istiod values 里固定声明（服务名固定，collector 不存在时 Envoy 只丢 span，不报错）：

```yaml
meshConfig:
  enableTracing: true
  extensionProviders:
    - name: platform-otel
      opentelemetry:
        service: platform-otel-gateway-collector.monitor.svc.cluster.local
        port: 4317
```

改这段需要重放一次 istio 组件（控制面滚动，不动数据面）。

### 5.2 ingress gateway 与 waypoint

```yaml
# ingress gateway
apiVersion: telemetry.istio.io/v1
kind: Telemetry
metadata: {name: platform-tracing-ingress, namespace: istio-system}
spec:
  targetRefs:
    - {group: gateway.networking.k8s.io, kind: Gateway, name: external-gateway}
  tracing:
    - providers: [{name: platform-otel}]
      randomSamplingPercentage: ${otel.samplingPercent}
```

```yaml
# 每个 waypoint 一条；由平台的 waypoint 生命周期创建/更新
apiVersion: telemetry.istio.io/v1
kind: Telemetry
metadata: {name: platform-tracing-waypoint, namespace: <namespace>}
spec:
  targetRefs:
    - {group: gateway.networking.k8s.io, kind: Gateway, name: <waypoint-name>}
  tracing:
    - providers: [{name: platform-otel}]
      randomSamplingPercentage: ${otel.samplingPercent}
```

- waypoint 创建/编辑 UI 增加「开启链路追踪」开关（默认关，按需勾选），平台随之创建/删除 Telemetry；
- ingress 这条在「OTel 与 istio 都已安装」时创建一次；谁后装由谁的后置步骤补建（幂等 SSA），
  缺一侧时跳过并提示。

### 5.3 为什么不配 ztunnel

ztunnel 工作在 L4，不产生 L7 span；它的指标（`ztunnel-stats`）是另一条 Prometheus 采集链路，
与 tracing 无关。**本方案不对 ztunnel 做任何 tracing 配置**。

### 5.4 能力边界

- 只有经过 **waypoint / ingress gateway 的 L7 流量**有 span；
- 与应用 span 连成一条链，需要应用透传 `traceparent`（Instrumentation 注入后自动完成）；
- 未接 SDK 且不经过 waypoint 的 L4 流量不会出现在调用链里。

---

## 6. 平台侧改动

### 6.1 后端

1. **`ComponentRecipeCatalog`**
   - `OTEL_OPERATOR_RELEASES` 拆成三段（operator CRD、Tempo、operator CR），
     recipe 目录按画像静态定义：MANAGEMENT = 第一遍 + Tempo + 第二遍；BUSINESS = 两遍 operator；
   - `manager.collectorImage` 固定 contrib 0.161.0；
   - 新增占位符 `${tempo.endpoint}`、`${otel.sampling}`、`${cluster.name}`；
   - 新增 recipe 标志：`tempoRemoteWrite`（暴露 Tempo 地址）、`tempoEndpointDefault`、
     `tempoInstall`（MANAGEMENT 的 Tempo 安装参数表单与快照；第一批固定安装，不提供跳过开关）、
     `otelInstrumentation`（暴露语言/日志表单）；
   - `ReleaseSpec` 新增 `preserveUnknownPlaceholders`（默认 false），仅 Tempo release 为 true：
     平台的 `ValueTemplate` 对未知占位符默认硬失败，而 Tempo 需要 `${MINIO_*}` 留到运行时展开（见 §3.2）。

2. **`ComponentRecipe` / `ComponentApplyRequest` / `ComponentStatusDTO`**
   - Tempo：`tempoEndpoint`；Tempo 存储：`tempoMinioEndpoint`、`tempoMinioBucket`、`tempoMinioPrefix`、
     `tempoCredentialSecret`（namespace/name）、`tempoMinioInsecure`；
   - ES：`otelEsEndpoint`、`otelEsUsername`、`otelEsCredentialSecret`（namespace/name）、
     `otelEsInsecure`、`otelEsLogsIndex`；
   - 采集：`otelSampling`、`otelGatewayReplicas`、`otelLogsEnabled`；
   - 所有字段都进快照 `__platform`；编辑表单预填上次成功操作的值（复用 `selectLatestSuccesses`）；
   - **地址、桶、用户名、索引名、TLS 开关都不硬编码**，只作为表单默认值。

3. **OTel CR 在请求期组装**：Instrumentation / gateway 的 `extraObjects` 里包含采样率、日志开关等
   用户值，**不能**走 `editableValues` 的路径机制（该机制只遍历 Map，不支持 list 下标）。
   由 `ComponentInstallService`（或独立的 `OtelRecipeBuilder`）在 `resolveValues` 时生成。

4. **前置 Secret 生成**（k8s-server 新端点，复用 Thanos 的做法）：
   - `platform-tempo-objstore`（MANAGEMENT，取自安装表单的 MinIO 凭证 Secret）；
   - `platform-otel-es`（从表单指定的 `monitor` 内 Secret 复制）。

5. **后置步骤**（helm 成功之后）：
   - `ServiceMonitor`（CRD 存在才建）；
   - ingress 的 `Telemetry`（Telemetry CRD + external-gateway 都存在才建）；
   - 失败记 WARN、不把操作整体标 FAILED（与告警联动不同：这些不是安装成功的前提）。

6. **无数据库迁移**：组件 code 不变、无新权限行；`platform_component_operation` 表已有。

### 6.2 前端

1. 组件安装/编辑弹窗（`ClusterComponentsPanel.vue`）新增以下表单，**ES 与 MinIO 的地址、桶、凭证、TLS 全部可改**，
   不做硬编码；默认值只做预填：

   **Tempo 地址（两个画像都显示）**

   | 字段 | 默认值 | 说明 |
   |---|---|---|
   | Tempo 地址 | MANAGEMENT：`platform-tempo.monitor.svc:4317`；BUSINESS：空 | collector 的 gRPC 端点（`host:port`）；业务集群必填 |

   **Tempo 存储 / MinIO（仅 MANAGEMENT）**

   | 字段 | 默认值 | 说明 |
   |---|---|---|
   | MinIO 地址 | 平台配置或 `192.168.31.80:9002` | S3 endpoint，`host:port` |
   | 桶 | `tempo` | **独立桶**（需先在 MinIO 建好），不与 Thanos 的 `thanos` 桶共用；不设前缀 |
   | 凭证 Secret | 选 `monitor` 内的 Secret（同名 Thanos 凭证可直接复用） | 支持与 Thanos 相同的 key 组合（`access-key`/`secret-key` 等），平台渲染成 `platform-tempo-objstore` |
   | 跳过 TLS 校验 | `true` | MinIO 是 HTTP 时保持 true |

   **ES 日志（两个画像都显示，日志关闭时置灰）**

   | 字段 | 默认值 | 说明 |
   |---|---|---|
   | ES 地址 | `https://es-es-http.default.svc:9200` | 完整 URL（含 scheme） |
   | ES 用户名 | `elastic` | 使用 ECK 默认用户时可不动 |
   | 凭证 Secret | 从 `monitor` 选（默认名字 `es-es-elastic-user`，key `elastic`） | **命名空间固定 monitor，表单不提供切换**；ECK 的原始 Secret 在 `default`，需先复制到 `monitor` |
   | 跳过 HTTPS 校验 | `true` | ECK 自签证书 |
   | 日志索引 | `otel-logs` | exporter 实际写成 `otel-logs-%{+yyyy.MM.dd}` |

   **采集参数（两个画像都显示）**

   | 字段 | 默认值 | 说明 |
   |---|---|---|
   | 采样率 | `10`（%） | 写入 Instrumentation 的 `sampler.argument`（0.1）与 `Telemetry.randomSamplingPercentage` |
   | Gateway 副本数 | `1` | collector Deployment 副本数 |
   | 日志采集 | 关 | 打开后才渲染 gateway 的 logs 管道（ES exporter） |

2. 工作负载页新增「可观测接入」动作（Deployment / StatefulSet / DaemonSet）：
   - 语言：Java / Python（Node 置灰并说明「第一批不支持」）；
   - 追踪开关、日志开关（日志开关对 Java/Python 可用）；
   - 服务名（默认工作负载名，写入 `OTEL_SERVICE_NAME`）；
   - 提交时按组合选 §4.1 的 Instrumentation，SSA patch PodTemplate 的注解/env；
   - 打开弹窗时读现有注解/env 回显状态；
   - 说明：切换会触发滚动更新；回滚到旧 revision 可能把接入配置一起回滚。
3. waypoint 创建/编辑弹窗新增「开启链路追踪」开关（第二批）。
4. 组件状态接口返回 §6.2 表格里的**所有当前值**（Tempo 地址、MinIO 地址/桶、ES 地址/用户名、采样率、副本数、日志开关），编辑表单按上次成功操作预填。

### 6.3 权限

- 组件安装/编辑复用 `platform:cluster:component:manage`（既有）；
- 工作负载接入复用工作负载更新权限（既有），**不新增权限码**；
- 租户是否允许给自己的命名空间开注入：本批沿用工作负载编辑权限，不额外收紧。

### 6.4 探针与状态

- `otel-operator` 仍只探 operator Deployment（理由见 §3.1）；
- Tempo / gateway 的存在性通过操作记录与后续「组件详情」扩展呈现，不改四态模型。

---

## 7. 失败语义与降级

| 场景 | 行为 |
|---|---|
| Tempo 未就绪 / 重启 | collector `otlp` exporter 重试队列缓冲；超出队列丢最旧数据；不阻塞应用 |
| ES 不可用 | `elasticsearch` exporter 重试；日志在队列中缓冲；不阻塞 traces/metrics |
| Tempo 地址为空 | **拒绝安装**（与告警地址同一原则，不留到 helm 成功后失败） |
| ServiceMonitor CRD 不存在（没装 prometheus-operator） | 跳过 ServiceMonitor，操作记 WARN，不 FAILED |
| 语言选错 | 注入的 agent 不产生数据；UI 文案提示「确认运行时」；不视为安装失败 |
| 应用只写 stdout | 第一批不支持；第二批 sidecar 表单要求勾选「确认写文件」 |
| Node 被选中 | 前端不提供该选项；后端收到也拒绝 |

---

## 8. 验收

### 8.1 第一批

1. 已有 cert-manager 的集群安装 `otel-operator`：operator、3 个 Instrumentation、gateway 就绪；
   MANAGEMENT 额外有 `platform-tempo` StatefulSet 就绪。
2. Prometheus 能抓到 gateway `:8889`（ServiceMonitor 存在且 target 无报错）。
3. Java 应用打开追踪后，Tempo `/api/search` 能按 `service.name` 查到 trace，
   resource 里有正确的 `cluster.name`。
4. Python 应用同上。
5. Java / Python 打开日志后，ES 出现 `otel-logs-*` 索引，文档带 namespace / pod / deployment。
6. Node 语言选项不可选；日志开关对 Node 不出现。
7. Tempo 停止时应用正常、collector 有重试日志、恢复后新 trace 继续写入。

### 8.2 第二批

8. ingress gateway 的请求产生 span；waypoint 的 L7 请求产生 span；ztunnel 无任何 tracing 配置。
9. sidecar 文件日志：用户填 `/var/log/nginx/*.log` 后，只采集该 Pod 的日志；
   不勾选「确认写文件」不能提交；纯 stdout 应用给出明确拒绝提示。
10. 关闭日志开关后，sidecar 注解/env/卷被移除，Pod 滚动更新后不再产生日志。

---

## 9. 非目标

- 全容器 stdout 采集 / 节点级日志 DaemonSet；
- Node.js 的 traces / logs（第一批）；
- Kafka / Logstash 缓冲层；
- trace 查看 UI（Grafana 或平台自研）；
- 业务集群回传管理集群 Tempo 的对外入口；
- 替换 Istio 的访问日志或 Kiali 类产品；
- 自动创建 MinIO 桶、自动配置 ES ILM。

## 10. 待定

1. ES 日志 ILM 的保留期（7 天是建议值，需按磁盘容量确认）。
2. 业务集群回传 Tempo 的入口方式由管理员负责（平台不实现暴露/TLS 终止）。
3. 采样率是否要支持按工作负载覆盖（当前是组件级的一个全局值，对应三个 Instrumentation）。
4. 租户级 trace 过滤（按 `k8s.namespace.name` 收窄）——当前 trace 页只给平台管理员。

---

## 11. 实现备注（批次一/二，2026-10-19）

- Tempo 实际用 **grafana-community/tempo 3.2.0（app 3.1.0）**：旧 `grafana/tempo` 1.24.4 已 deprecated。
- Instrumentation 的 exporter 端点用 **4318 + http/protobuf**（Python 注入镜像只带 HTTP exporter）。
- `OtelObjects.build(logsEnabled, gatewayReplicas)` 生成 **5** 个对象：3 个 Instrumentation +
  gateway + **sidecar collector**（sidecar 常驻，只有工作负载开启文件日志时才被注入）。
- 文件日志的共享 sidecar CR 固定采集 `/var/log/app/*.log`；**用户提供的是目录**（不是 glob），
  平台把目录挂成 emptyDir 卷 `platform-otel-logs`（应用容器与 sidecar 共享）。
- 网格 tracing：新增 `/mesh/waypoints/tracing`（POST/GET，权限码复用 `tenant:gateway:update/get`）；
  ingress 的 Telemetry 由 OTel 或 istio 组件安装成功后的后置步骤补建（谁后装谁补）。
  **部署前必须先重放 `V2026_10_19_1__mesh_waypoint_tracing.sql`**（缺失权限行会让
  `PermissionCrossCheckRunner` 在启动时直接 brick）。

## 12. 批次三实现备注（2026-10-19）

- gateway 增加 `resource` processor：所有信号统一 upsert `cluster.name`（应用 span 由 Instrumentation
  已带；网格 span 没有，必须靠这一层补）。
- Tempo 保留期进表单（`tempoRetention`，Go duration，默认 `168h`），注入 `tempo.retention`。
- Tempo exporter 三种传输模式：`plaintext`（`insecure: true`）/ `tls`（校验证书）/ `tls-skip-verify`
  （自签）；业务集群按管理员给的入口选。
- 平台自研 trace 查看：
  - 后端 `TraceController`：`POST /traces/search`（TraceQL，按 `resource.cluster.name` /
    `resource.service.name`）与 `GET /traces/{traceId}`；数据源 `platform.tempo.query-url`
    （默认 `http://platform-tempo.monitor.svc:3200`）；trace-by-ID 同时接受 `batches`（Tempo 2.x）
    与 `resourceSpans`（OTLP 兼容）两种 JSON。
  - 前端 `/mesh/traces` 页面：集群/服务/时间范围/条数 + 列表 + 瀑布图 + span 属性。
  - 权限：`platform:trace:search` / `platform:trace:get` / `platform:page:mesh.trace`，
    **只绑 admin**（中心 Tempo 可能跨租户）；**部署前重放 `V2026_10_19_2__trace_view_permissions.sql`**。
- 业务集群 Tempo 地址的**可达性/TLS 终止由管理员负责**；平台只提供 collector 侧的地址与传输模式。
