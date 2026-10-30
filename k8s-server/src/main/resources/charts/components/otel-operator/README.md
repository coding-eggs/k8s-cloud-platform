# OpenTelemetry Operator（集群组件）· OTel 采集栈

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 本组件 = operator chart（两遍）+ **Tempo chart（仅 MANAGEMENT）**。Tempo 的细节见
> [tempo/README.md](../tempo/README.md)；设计全文见
> `docs/superpowers/specs/2026-10-18-otel-observability-design.md`。
> 本次安装的 chart 版本、collector 镜像 tag 与来源见版本目录：`<version>/README.md`（当前 `0.126.0/`）。

## 1. 概览

| 项 | 值 |
|---|---|
| 组件 code | `otel-operator` |
| 画像 | BUSINESS / MANAGEMENT |
| 依赖组件 | **cert-manager**（未健康直接拒绝安装） |
| 探测判据 | `opentelemetry-operator-system` Deployment `opentelemetry-operator/opentelemetry-operator` |
| 平台功能 | `otel-collection`：链路追踪（Tempo）、日志查询中心（ES）、collector 指标 |

## 2. release 顺序

### MANAGEMENT

| 序 | release | namespace | 内容 |
|---|---|---|---|
| 1 | `platform-otel-operator` | `opentelemetry-operator-system` | 第一遍：CRD + operator |
| 2 | `platform-tempo` | `monitor` | Tempo（S3/MinIO） |
| 3 | `platform-otel-operator` | `opentelemetry-operator-system` | 第二遍：`extraObjects` 下发 Instrumentation + gateway + sidecar |

### BUSINESS

| 序 | release | namespace | 内容 |
|---|---|---|---|
| 1 | `platform-otel-operator` | `opentelemetry-operator-system` | 第一遍：CRD + operator |
| 2 | `platform-otel-operator` | `opentelemetry-operator-system` | 第二遍：collector CR |

业务集群不装 Tempo，`tempoEndpoint` 必须指向外部可达地址。

## 3. 实锤（来自历史对话）

### 3.1 用户问 cert-manager 前置校验

> 「这个 chart 的前置组件 certmanager，安装的时候校验了吗」

结论：`ComponentRecipe.requires = ["cert-manager"]`，安装在 `requireDependencies` 里校验
`INSTALLED_HEALTHY`，未健康直接拒绝。webhook 证书由 cert-manager 签发。

### 3.2 Tempo 用 HTTP 打 HTTPS MinIO → CrashLoop → 操作卡 RUNNING

用户贴的日志（2026-10-18）：

```
failed to create store: unexpected error from ListObjects on tempo:
Get "http://192.168.31.80:9002/tempo/?location=": Connection closed by foreign host
```

从集群只读拿到的证据：

- `platform-tempo-0` CrashLoopBackOff；
- 同一台 MinIO 9002 端口实测：`https://` 返回 403（正常 TLS 响应），`http://` 直接被断开；
- 对照 Thanos 的 objstore（一直正常）：`insecure: false` + `insecure_skip_verify: true`；
- 平台渲染给 Tempo 的 values 是写死的 `insecure: true`（明文）。

后果：Tempo 起不来 → helm `--wait` 等满 10 分钟 → 操作一直 RUNNING，期间后端拒绝新提交
（用户：「状态还是进行中无法编辑」）。修法：endpoint 只保留 `host:port`，传输方式由表单显式选，
占位符解析后注入布尔值（`insecure` / `tls_insecure_skip_verify`）。

### 3.3 「该组件不是平台安装的」→ 失败可重试 + 中止

用户报 `「该组件不是平台安装的，平台不接管（只升级平台自己装的 release）」` 后补了
`countAttempted`：`FAILED` / `UNKNOWN` 也算平台装过，可以改配置重试；另加管理员「中止操作」
（只把记录置 `CANCELED`，不 kill helm）。

### 3.4 ES 没建索引

用户：「我配置了 es 并给 movie 服务开启日志采集，但是我现在在 es 没看到索引建立」。

collector 日志：

```
failed to index document ... index=otel-logs-2026.10.19
error.type=resource_not_found_exception  http.response.status_code=404
```

ES 本身正常（`action.auto_create_index=true`，同一凭证直接写文档能自动建索引）。
根因：elasticsearchexporter **0.161.0 起 `mapping.mode` 被忽略**，默认 `otel` 模式要求
data stream；写普通索引就 404。修法见 §6.2。

### 3.5 ES 凭证与索引前缀（用户两条明确要求）

> 「chart 部署时 es 的 secret 只支持 monitor ns 的，key 也固定 username, password。所以表单只保留
> secret 名称下拉就好。es 的索引会自动按天分割吗？」

> 「索引前缀现在是可配置的，那如果组件地方改了，平台还得改，组件处就不让改了，固定吧」

落地：Secret 必须 `monitor` + 固定 key；索引前缀固定 `otel-logs`，按天写 `otel-logs-YYYY.MM.DD`。

### 3.6 日志 → 链路 404（采样）

用户：「日志中心跳到链路追踪很多时候会 404，提示不存在」。

实测：ES 里 `TraceFlags` 分布 2（1274 条）/ 3（213 条）；`TraceFlags=3` 的 traceId 查 Tempo 返回 200，
`2` 返回 404。`TraceFlags` bit0 = W3C sampled（bit1 是 random-trace-id，不能拿来判断）。
采样率 10% 时约 86% 的日志没有对应 trace。日志页只对已采样的行给「查看链路」。

## 4. 安装表单

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `tempoEndpoint` | 是 | MANAGEMENT：`platform-tempo.monitor.svc:4317`；BUSINESS：空 | collector 的 traces 目标，gRPC `host:port`，不带 scheme |
| `samplingPercentage` | 否 | `10` | 1..100 |
| `gatewayReplicas` | 否 | `1` | gateway 副本数（整数） |
| `logsEnabled` | 否 | `false` | 打开后 gateway 增加 logs 管道 |
| `tempoRetention` | 否 | `168h` | Tempo 保留期（chart 默认 24h） |
| `tempoTlsMode` | 否 | `plaintext` | `plaintext` / `tls` / `tls-skip-verify` |
| `tempoStorage.*` | MANAGEMENT 必填 | — | 见 Tempo README |
| `es.endpoint` | 日志开启必填 | — | 完整 URL，必须带 `http(s)://` |
| `es.credentialSecret` | 日志开启必填 | — | `monitor` 内，key 固定 `username` / `password` |
| `es.insecureSkipVerify` | 否 | `true` | ECK 自签证书 |

## 5. 契约 values（operator）

```yaml
admissionWebhooks:
  certManager: { enabled: true }
manager:
  collectorImage:
    repository: ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib
    tag: <随版本更新，见版本目录>
```

contrib 镜像不能换回默认 `-k8s` 发行版：**没有 elasticsearch exporter**（讨论阶段确认）。
两遍 operator 用同一份 values；`extraObjects` 只在第二遍。

## 6. 采集栈

### 6.1 三种 Instrumentation

| 名字 | tracing | logs | 用于 |
|---|---|---|---|
| `platform-instrumentation` | ✓ | — | 只开追踪 |
| `platform-instrumentation-logs` | — | ✓ | 只开应用日志（sampler `always_off`） |
| `platform-instrumentation-all` | ✓ | ✓ | 两者都开 |

### 6.2 ES exporter（mapping mode = none）

```yaml
mapping:
  allowed_modes: [none]   # 唯一能指定默认模式的方式（exporter 0.161.0 起 mapping.mode 被忽略）
logs_index: otel-logs
logstash_format: { enabled: true, prefix_separator: "-", date_format: "%Y.%m.%d" }
```

- `mapping.mode` 已被忽略（默认 `otel` → data stream，`require_data_stream=true`）；
- `allowed_modes` 不含 `otel` 时取第一项 `none` → 普通索引，ES 自动创建；
- 按天索引用 `logstash_format`，不是 Fluentd 语法 `%{+yyyy.MM.dd}`；
- 文档是扁平点号 key：`@timestamp` / `SeverityText` / `Body` / `TraceId` / `TraceFlags` /
  `Resource.service.name` / `Resource.cluster.name` / `Resource.k8s.namespace.name` / `Attributes.*`。

### 6.3 gateway / sidecar

- gateway：Deployment，4317（gRPC）/4318（HTTP），`resource` processor 统一
  upsert `cluster.name`；traces→Tempo，logs→ES（仅日志开关打开时）；
- **指标不走 OTel**（2026-10-30）：原来有一条 `otlp → prometheus(:8889)` 管道，但没有任何
  ServiceMonitor 抓 8889，应用推上来的指标点收进来就丢。已连同端口一起移除，并在三个
  `Instrumentation` 上设 `OTEL_METRICS_EXPORTER=none`（不然 agent 还会白推、gateway 刷
  "no pipeline" 错误）。应用指标统一由 Prometheus + ServiceMonitor 采集；
- sidecar：文件日志用，只有工作负载显式开启时注入；卷 `platform-otel-logs`（emptyDir）；
  采哪个文件由 Pod 注解 `platform.coding/otel-log-file` 经 downward API 注入 `OTEL_FILELOG_INCLUDE`；
  sidecar 不跑 k8sattributes（Pod SA 权限不够），由 gateway 按 Pod IP 补齐。

### 6.4 安装后平台做的动作

| 时机 | 动作 |
|---|---|
| helm 前 | Tempo 凭证 Secret → `monitor/platform-tempo-objstore`；ES Secret → `monitor/platform-otel-es` |
| helm 后 | collector ServiceMonitor（无 CRD 则跳过） |
| helm 后 | ingress gateway 的 `Telemetry`（Gateway/CRD 缺失则跳过） |

## 7. 已知坑

1. **`no matches for kind Instrumentation/OpenTelemetryCollector`** —— 两阶段没拆。
2. **operator 起不来 / webhook 证书错误** —— cert-manager 未装或未健康。
3. **Tempo CrashLoop `Connection closed`** —— HTTP 打 HTTPS MinIO（§3.2）；endpoint 只写 `host:port`。
4. **ES 没索引 / 404 resource_not_found** —— `mapping.mode` 失效，必须 `allowed_modes: [none]`（§3.4）。
5. **索引没按天分** —— 用了 Fluentd 语法；应配 `logstash_format`（§6.2）。
6. **ES 401** —— Secret key 必须是 `username` / `password` 且在 `monitor`（§3.5）。
7. **日志里的 traceId 点进去 404** —— 未采样，日志页按 `TraceFlags` bit0 判断（§3.6）。
8. **gateway replicas 被 CRD 拒** —— 必须写整数；ValueTemplate 只能产出字符串。
9. **文件日志没数据** —— 注解里的路径必须是容器内绝对路径且是具体文件，不能是目录。
10. **日志开关置灰** —— OTel 组件没开日志采集时，工作负载日志开关不可用（有意）。
11. **操作卡 RUNNING** —— 15 分钟标 UNKNOWN；管理员可中止（只改记录，不杀 helm），修好重试。
12. **换了 ES 凭证但 collector 还在用旧凭证（401）** —— 2026-10-26 一并修了：collector 的 Pod 是
    operator 从 `OpenTelemetryCollector` CR 生成的，**给那个 Deployment 打注解会被 reconcile 改回去**，
    所以平台改的是 CR 的 `spec.podAnnotations["platform.coding/config-hash"]`，由 operator 自己滚动。
    幂等（哈希一致就不动）；没装 operator / 没开日志时找不到 CR 就静默跳过。
    ES 地址走 values（`${otel.es.endpoint}`），那条路 helm 会自己滚，本来就没问题。


## 8. 使用

- 链路追踪页：中心 Tempo；平台管理员全量，租户按分配表收窄（列表 TraceQL、详情按 span 过滤）；
- 日志查询中心：`otel-logs-*`，范围取顶栏三级，支持工作负载/Pod/关键词/级别/时间；
- 采样默认 10%；调高会增加 Tempo 存储与 collector 负载，日志侧始终全量。

## 9. 新增版本

operator 与 Tempo 是**两个独立 chart**，版本分别维护；operator 的两遍必须同版本。

1. 拉新 chart（operator / tempo）；
2. 入库 + `provenance.yaml` + 复制 README；
3. `OTEL_OPERATOR_PASS1` 与 `PASS2` **一起**换版本；Tempo 单独换；
4. 检查 `Instrumentation`（v1alpha1）/ `OpenTelemetryCollector`（v1beta1）CRD 版本与字段；
5. 检查 collector contrib tag 与 `elasticsearch` / `otlp` exporter 配置的兼容性；
6. 检查 Tempo chart 的 S3 values 路径与 persistence；
7. 跑 `OtelObjectsTest`；非生产集群真装一次（operator + Tempo + 一条 trace + 一条日志）；
8. 重建 k8s-server + platform-api。

> 升级时第一遍先更新 CRD，第二遍才重新 apply collector CR。若新版对 CR schema 有破坏性变更，
> 第二遍会失败 —— 先回滚 operator 版本，再按新版要求改 `OtelObjects`。

## 10. 卸载

平台不提供卸载。手工卸载顺序：先删 collector/instrumentation CR，再
`helm uninstall platform-otel-operator`；CRD 不会被删除；Tempo 与两个 Secret 单独清理。
