# otel-operator（OpenTelemetry 采集栈）

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `otel-operator` |
| 显示名 | OpenTelemetry Operator（实际包含整条采集栈） |
| 画像 | BUSINESS / MANAGEMENT |
| 依赖组件 | **cert-manager**（webhook 证书），未就绪直接拒绝安装 |
| 探测判据 | `opentelemetry-operator-system` Deployment `opentelemetry-operator/opentelemetry-operator` |
| 平台功能 | `otel-collection`：链路追踪（Tempo）、日志查询中心（ES）、collector 指标 |
| API group | `opentelemetry.io` |

包含的 chart：

| chart | 版本 | 用途 |
|---|---|---|
| `opentelemetry-operator` | **0.126.0**（app 0.161.0） | operator + Instrumentation / collector CR |
| `tempo` | **3.2.0**（app 3.1.0，仅 MANAGEMENT） | traces 后端 |

chart 目录：`charts/components/otel-operator/0.126.0/chart/`、`charts/components/tempo/3.2.0/chart/`。

## 2. release 顺序

### MANAGEMENT（管理集群）

| 序 | release | namespace | 内容 |
|---|---|---|---|
| 1 | `platform-otel-operator` | `opentelemetry-operator-system` | **第一遍**：CRD + operator |
| 2 | `platform-tempo` | `monitor` | Tempo（S3/MinIO） |
| 3 | `platform-otel-operator` | `opentelemetry-operator-system` | **第二遍**：`extraObjects` 下发 Instrumentation + gateway + sidecar |

Tempo 夹在两遍 operator 之间：先有 CRD，Tempo 就绪后再下发 collector CR。

### BUSINESS（业务集群）

| 序 | release | namespace | 内容 |
|---|---|---|---|
| 1 | `platform-otel-operator` | `opentelemetry-operator-system` | 第一遍：CRD + operator |
| 2 | `platform-otel-operator` | `opentelemetry-operator-system` | 第二遍：collector CR |

业务集群**不装 Tempo**，`tempoEndpoint` 必须指向外部（通常是管理集群的 `platform-tempo.monitor.svc:4317` 或其它可达地址）。

## 3. 安装表单

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `tempoEndpoint` | 是 | MANAGEMENT：`platform-tempo.monitor.svc:4317`；BUSINESS：空 | collector 的 traces 导出目标，**gRPC `host:port`，不带 scheme** |
| `samplingPercentage` | 否 | `10` | 1..100；写入 Instrumentation 的 ratio 与 ingress `Telemetry` |
| `gatewayReplicas` | 否 | `1` | collector gateway Deployment 副本数（整数） |
| `logsEnabled` | 否 | `false` | 打开后 gateway 增加 logs 管道（ES exporter） |
| `tempoRetention` | 否 | `168h` | Tempo 保留期（Go duration；chart 默认只有 24h） |
| `tempoTlsMode` | 否 | `plaintext` | collector → Tempo：`plaintext` / `tls` / `tls-skip-verify` |
| `tempoStorage.endpoint` | MANAGEMENT 必填 | — | MinIO `host:port`（scheme 会被剥掉） |
| `tempoStorage.bucket` | MANAGEMENT 必填 | `tempo` | **独立桶**，需先在 MinIO 建好；不设前缀 |
| `tempoStorage.credentialSecret` | MANAGEMENT 必填 | — | 必须在 `monitor` 命名空间；支持 Thanos 同款 key（`access-key`/`secret-key` 等） |
| `tempoStorage.plaintext` | 否 | 按 endpoint scheme 推断 | 显式选择 HTTP（明文）/ HTTPS；表单总会下发 |
| `tempoStorage.insecureSkipVerify` | 否 | `true` | HTTPS + 自签证书 |
| `es.endpoint` | 日志开启时必填 | — | 完整 URL，**必须带 `http(s)://`** |
| `es.credentialSecret` | 日志开启时必填 | — | 必须在 `monitor`，且 key **固定**为 `username` / `password` |
| `es.insecureSkipVerify` | 否 | `true` | ECK 自签证书 |

## 4. 契约 values（🔒 不可改）

### operator

```yaml
admissionWebhooks:
  certManager: { enabled: true }        # webhook 证书由 cert-manager 签发
manager:
  collectorImage:
    repository: ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib
    tag: 0.161.0                        # operator 默认的 -k8s 发行版没有 elasticsearch exporter
```

### Tempo

```yaml
tempo:
  storage.trace:
    backend: s3
    s3:
      bucket: ${tempo.minio.bucket}
      endpoint: ${tempo.minio.endpoint}
      access_key: ${MINIO_ACCESS_KEY}     # 由 -config.expand-env 在容器内展开
      secret_key: ${MINIO_SECRET_KEY}
    wal: { path: /var/tempo/wal }
  extraArgs: { config.expand-env: "true" }
  extraEnvFrom: [{ secretRef: { name: platform-tempo-objstore } }]
  reportingEnabled: false                 # 内网部署不发 usage report
  retention: 168h
  resources: { requests: {cpu: 200m, memory: 512Mi}, limits: {cpu: "1", memory: 2Gi} }
persistence: { enabled: true, storageClassName: nfs-client, size: 20Gi }
```

- `${MINIO_ACCESS_KEY}` / `${MINIO_SECRET_KEY}` 是**运行时**变量，平台用 `preserveUnknownPlaceholders=true`
  放过它们；`${tempo.minio.*}` 才是平台占位符；
- ⚠️ `persistence.storageClassName` 目前固定 `nfs-client`（已知限制：集群没有该 StorageClass 时 PVC 会 Pending）；
- S3 的 HTTPS/HTTP 布尔值（`insecure` / `tls_insecure_skip_verify`）由 `ComponentInstallService` 在占位符解析后
  以**布尔**写入 —— ValueTemplate 只能产出字符串，CRD 会拒。

### 平台在 helm 之前/之后做的事

| 时机 | 动作 |
|---|---|
| helm 前（MANAGEMENT） | 读来源 Secret → 归一化成 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` → 写 `monitor/platform-tempo-objstore` |
| helm 前（日志开启） | 读 `monitor/<credentialSecret>` → 写 `monitor/platform-otel-es`（key 仍是 `username` / `password`） |
| helm 后 | 下发 collector 的 ServiceMonitor（没有 CRD 则跳过，不影响操作结果） |
| helm 后 | 下发 ingress gateway 的 `Telemetry`（Gateway/CRD 缺失则跳过；`samplingPercentage` 取表单值） |
| 第二遍 | `extraObjects`：`platform-instrumentation` / `-logs` / `-all`、`platform-otel-gateway`、`platform-otel-sidecar` |

## 5. 采集栈细节

### 5.1 Instrumentation（三份）

| 名字 | tracing | logs | 用于 |
|---|---|---|---|
| `platform-instrumentation` | ✓ | — | 只开追踪 |
| `platform-instrumentation-logs` | — | ✓ | 只开应用日志（sampler `always_off`） |
| `platform-instrumentation-all` | ✓ | ✓ | 两者都开 |

工作负载接入时按「追踪 / 日志开关 + 语言」选其中一份写注入注解。

### 5.2 gateway collector

- Deployment，端口 4317（gRPC）/ 4318（HTTP）/ 8889（prometheus），镜像固定 contrib 0.161.0；
- `resource` processor 统一 upsert `cluster.name`；
- traces → `otlp/tempo`；logs → `elasticsearch`（仅 `logsEnabled=true`）。

### 5.3 ES 日志文档（mapping mode = none）

0.161.0 的 `mapping.mode` 已被忽略，默认是 `otel`（data stream）；平台显式配：

```yaml
mapping: { allowed_modes: [none] }
logs_index: otel-logs
logstash_format: { enabled: true, prefix_separator: "-", date_format: "%Y.%m.%d" }
```

结果：普通索引 `otel-logs-YYYY.MM.DD`（**按天**，不是 Fluentd 的 `%{+yyyy.MM.dd}`），文档是扁平点号 key：
`@timestamp` / `SeverityText` / `Body` / `TraceId` / `TraceFlags` / `Resource.service.name` /
`Resource.cluster.name` / `Resource.k8s.namespace.name` / `Resource.k8s.pod.name` / `Attributes.*`。

### 5.4 sidecar（文件日志）

- 只有工作负载显式开启「文件日志」时才注入（注解 `sidecar.opentelemetry.io/inject`）；
- 卷名固定 `platform-otel-logs`（emptyDir）：应用容器挂日志文件**父目录**，sidecar 挂 `/var/log/app`；
- 采哪个文件由每个 Pod 的注解 `platform.coding/otel-log-file` 经 downward API → `OTEL_FILELOG_INCLUDE` 决定；
- sidecar **不跑** k8sattributes（Pod SA 未必有 list pods 权限）；gateway 收到同 Pod IP 的 OTLP 后补齐 deployment 等字段。

## 6. 已知坑（每个都踩过）

1. **`no matches for kind Instrumentation/OpenTelemetryCollector`** —— 两阶段没拆，或第一遍里带了 `extraObjects`。
2. **operator 起不来 / webhook 证书错误** —— cert-manager 未安装或未健康；recipe 会前置校验。
3. **Tempo CrashLoop：`Connection closed by foreign host`** —— MinIO 是 HTTPS 而 Tempo 配了 HTTP。
   `endpoint` 只写 `host:port`，传输方式由「传输方式」显式选择；自签证书选 `tls-skip-verify`。
4. **Tempo 启动报 `bucket does not exist`** —— 独立桶 `tempo` 要**先**在 MinIO 建好；平台不建桶。
5. **Tempo 与 Thanos 混数据** —— 不要复用 `thanos` 桶、不要设 prefix；Tempo 的 block 直接落桶根。
6. **Tempo 凭证 Secret 选错命名空间** —— 必须在 `monitor`；平台只在该 ns 读。
7. **ES 没有索引** —— `mapping.mode` 在 0.161.0 被忽略，必须用 `mapping.allowed_modes: [none]`；
   否则走 data stream / `require_data_stream`。
8. **索引没有按天分** —— 用了 Fluentd 语法 `%{+yyyy.MM.dd}`；应配 `logstash_format.date_format: "%Y.%m.%d"`。
9. **ES 凭证 401** —— Secret 的 key 必须是 `username` / `password`，且放在 `monitor`。
10. **日志里的 traceId 点进去 404** —— 采样率 < 100% 时未采样的请求只有日志没有 trace；
    日志页按 `TraceFlags` bit0 判断，未采样不给链路入口（bit1 是 random-trace-id，不能拿来判断）。
11. **gateway replicas 不生效 / CRD 拒绝** —— replicas 必须是整数；ValueTemplate 只能产出字符串，
    平台在解析后按 int 写入。
12. **文件日志没有数据** —— 注解里的文件路径必须是容器内**绝对路径且是具体文件**（不是目录）；
    include 为空时 sidecar 不会采任何东西。
13. **日志开关置灰** —— OTel 组件没开「日志采集」时，工作负载的日志开关不可用（这是有意的）。
14. **组件操作卡在 RUNNING** —— 进程重启留下的记录 15 分钟后标 UNKNOWN；管理员可「中止操作」，
    它只改平台记录、不杀 helm。修好配置后直接重试。

## 7. 使用

- **链路追踪页**：数据源是中心 Tempo；平台管理员看全量，租户按 `platform_tenant_namespace` 收窄
  （列表拼 TraceQL，详情按 span 的 `resource.cluster.name` + `resource.k8s.namespace.name` 过滤）。
- **日志查询中心**：ES 里 `otel-logs-*`；范围取顶栏租户/集群/命名空间，支持工作负载/Pod/关键词/级别/时间。
- **采样**：默认 10%；调高会增加 Tempo 存储与 collector 负载。日志侧始终全量。
- **验证**：

```bash
kubectl -n opentelemetry-operator-system get deploy
kubectl get instrumentation -A
kubectl -n monitor get opentelemetrycollector
kubectl -n monitor get statefulset,svc | grep tempo
kubectl -n monitor logs deploy/platform-otel-gateway-collector --tail=100
```

## 8. 新增版本

operator 与 tempo 是**两个独立 chart**，版本分别维护；但 operator 的两遍必须同版本。

1. 拉新 chart（operator 从 `open-telemetry/opentelemetry-helm-charts`，tempo 从 `grafana-community/helm-charts`）；
2. 入库到各自的 `<version>/chart/` + `provenance.yaml`；
3. `ComponentRecipeCatalog`：
   - `OTEL_OPERATOR_PASS1` 与 `OTEL_OPERATOR_PASS2` **一起**换版本；
   - `TEMPO_RELEASE` 单独换版本；
   - 契约 values 原样保留，按新版本实际情况核对（尤其 collector image tag、CRD API 版本、
     exporter 配置字段）；
4. 检查 `Instrumentation`（v1alpha1）与 `OpenTelemetryCollector`（v1beta1）CRD 的版本与字段是否变化；
5. 检查 collector contrib 镜像 tag 与 `elasticsearch` / `otlp` exporter 配置的兼容性（升级 contrib 时
   配置字段可能改名）；
6. 检查 Tempo chart 的 S3 values 路径与 `persistence` 默认值；
7. 跑 `OtelObjectsTest` 等单测；非生产集群真装一次（operator + Tempo + 一条 trace + 一条日志）；
8. 重建 k8s-server（charts）+ platform-api（recipe）；collector CR 由第二遍重新下发。

> 升级 operator 时 CRD 会先更新（第一遍），第二遍才重新 apply collector CR。如果新版 operator
> 对 CR schema 有破坏性变更，第二遍会失败 —— 此时先回滚 operator chart 版本，再按新版要求改 `OtelObjects`。

## 9. 卸载

平台不提供卸载。手工卸载时注意：

- CRD（`instrumentations.opentelemetry.io`、`opentelemetrycollectors.opentelemetry.io`）**不会**被 Helm 删除；
- 先删 collector / instrumentation CR，再 `helm uninstall platform-otel-operator`；
- Tempo 的 StatefulSet/PVC 与 `monitor/platform-tempo-objstore`、`platform-otel-es` 需要单独清理；
- 卸载后组件探测随 Deployment 消失自动变为未安装。
