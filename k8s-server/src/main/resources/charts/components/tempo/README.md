# Tempo（集群组件 `otel-operator` 的 MANAGEMENT 部分）

> **这是组件级 README（跨版本）**：平台怎么装、约定是什么、踩过哪些坑、加新版本要改哪里。
> 本 chart 是 **`otel-operator` 组件在 MANAGEMENT 画像下的第二个 release**，夹在 operator 两遍之间。
> collector / Instrumentation 的文档见 [otel-operator/README.md](../otel-operator/README.md)。
> 本次安装的 chart 版本与来源见版本目录：`<version>/README.md`（当前 `3.2.0/`）。

## 1. 概览

| 项 | 值 |
|---|---|
| 所属组件 | `otel-operator`（MANAGEMENT only） |
| release / namespace | `platform-tempo` / `monitor` |
| 部署形态 | 单二进制、1 副本 StatefulSet |
| 存储 | S3/MinIO，**独立桶 `tempo`**，无 prefix |
| 查询端点 | `http://platform-tempo.monitor.svc:3200`（`/api/search`、`/api/traces/{id}`） |
| OTLP 入库 | `platform-tempo.monitor.svc:4317`（gRPC） |

## 2. 实锤（来自历史对话）

### 2.1 桶必须独立、不要 prefix（用户原话）

> 「tempo 存储对象的 bucket 你为什么给的默认值是 thanos，不要和 thanos 混用，自己独立一个 bucket
> 就叫 tempo。不用前缀吧」

落地：默认桶 `tempo`（MinIO 里**先建好**），不写 `prefix`，block 直接落桶根；不与 Thanos 的
`thanos` 桶/前缀混用。

### 2.2 HTTP 打 HTTPS MinIO → CrashLoop（用户贴的日志）

```
failed to create store: unexpected error from ListObjects on tempo:
Get "http://192.168.31.80:9002/tempo/?location=": Connection closed by foreign host
```

集群只读证据：

- `platform-tempo-0` CrashLoopBackOff；
- 同端口实测 `https://192.168.31.80:9002` → 403（正常 TLS 响应），`http://` → 连接被断开；
- 对照 Thanos objstore（一直正常）：`insecure: false` + `insecure_skip_verify: true`；
- 平台渲染给 Tempo 的 values 是写死的 `insecure: true`。

后果：Tempo 起不来 → helm `--wait` 等满 10 分钟 → 操作停在 RUNNING（用户：「状态还是进行中无法编辑」）。
修法：`s3.endpoint` 只收 `host:port`，传输方式在表单显式选，解析后注入**布尔** `insecure` /
`tls_insecure_skip_verify`。

### 2.3 保留期进表单

chart 默认 `tempo.retention` 只有 24h；平台批次三把保留期做成表单字段（默认 `168h`），
按表单值覆盖 `tempo.retention`。

## 3. 契约 values（🔒 不可改）

```yaml
tempo:
  storage.trace:
    backend: s3
    s3:
      bucket: ${tempo.minio.bucket}
      endpoint: ${tempo.minio.endpoint}
      access_key: ${MINIO_ACCESS_KEY}
      secret_key: ${MINIO_SECRET_KEY}
    wal: { path: /var/tempo/wal }
  extraArgs: { config.expand-env: "true" }
  extraEnvFrom: [{ secretRef: { name: platform-tempo-objstore } }]
  reportingEnabled: false
  retention: 168h
  resources: { requests: {cpu: 200m, memory: 512Mi}, limits: {cpu: "1", memory: 2Gi} }
persistence: { enabled: true, storageClassName: nfs-client, size: 20Gi }
```

| 值 | 为什么 / 来源 |
|---|---|
| `bucket` / `endpoint` | 平台占位符，由表单解析；`endpoint` 只收 `host:port`（§2.2） |
| `${MINIO_ACCESS_KEY}` / `${MINIO_SECRET_KEY}` | **运行时**变量，靠 `-config.expand-env=true` 展开；平台 ValueTemplate 通过 `preserveUnknownPlaceholders=true` 放过它们 |
| `extraEnvFrom` | 平台在 helm 前把来源 Secret 归一化成 `monitor/platform-tempo-objstore`（key 为这两个） |
| `reportingEnabled: false` | 内网部署不发 usage report |
| `retention: 168h` | 表单覆盖；chart 默认只有 24h |
| `persistence.storageClassName: nfs-client` | ⚠️ **已知限制**：集群没有该 StorageClass 时 PVC Pending；后续应做成表单字段 |
| S3 传输布尔值 | 占位符解析后由平台以布尔注入（ValueTemplate 只能产出字符串，Go 不认字符串布尔） |

## 4. 安装表单（MANAGEMENT 的 OTel 组件）

| 字段 | 必填 | 说明 |
|---|---|---|
| MinIO 地址 | 是 | 只填 `host:port`；scheme 会被剥掉，不参与判断 |
| 传输方式 | 是 | HTTPS / HTTP（明文）显式选择；**不靠地址 scheme 隐式决定** |
| 桶 | 是 | 默认 `tempo`；不设前缀；需先在 MinIO 建好 |
| 凭证 Secret | 是 | 必须在 `monitor`；支持 `access-key/secret-key`、`accessKey/secretKey`、`ACCESS_KEY/SECRET_KEY`、`MINIO_ACCESS_KEY/MINIO_SECRET_KEY`、`minio-access-key/minio-secret-key`、`AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY` |
| 跳过 TLS 校验 | 否 | 仅 HTTPS + 自签证书；选 HTTP 时置灰 |
| 保留期 | 否 | Go duration，默认 `168h` |

Secret 命名空间不是 `monitor` 时安装前直接拒绝（跨 ns 读 Secret 无产品必要）。

## 5. 验证

```bash
kubectl -n monitor get statefulset platform-tempo
kubectl -n monitor logs statefulset/platform-tempo --tail=100
kubectl -n monitor get svc platform-tempo
curl -s http://platform-tempo.monitor.svc:3200/ready
```

平台侧：链路追踪页能查到 trace；collector 日志无 `failed to create store`。

## 6. 已知坑

1. **CrashLoop `Connection closed by foreign host`** —— HTTP 打 HTTPS MinIO（§2.2）。
2. **`bucket does not exist`** —— 桶要**先**在 MinIO 建好，平台不建桶。
3. **与 Thanos 混数据** —— 不要复用 `thanos` 桶、不要设 prefix（§2.1）。
4. **凭证 Secret 选错 ns** —— 必须 `monitor`。
5. **PVC Pending** —— `persistence.storageClassName` 固定 `nfs-client`（已知限制）。
6. **保留期不生效** —— 表单值没覆盖 `tempo.retention`，或组件没有重新 EDIT 重放。
7. **Tempo 查询 `durationMs` 缺失** —— `/api/search` 部分版本不返回顶层 `durationMs`，平台退回根 span 的
   `durationNanos`（这是 trace 客户端的事，不是 Tempo 装错）。

## 7. 新增版本

1. `helm pull tempo --repo https://grafana-community.github.io/helm-charts --version <x.y.z> --untar`；
2. 入库 `tempo/<x.y.z>/chart/` + `provenance.yaml` + 复制本 README；
3. `ComponentRecipeCatalog.TEMPO_RELEASE` 换版本（release 名/namespace/契约 values 不变）；
4. 核对 S3 values 路径、`persistence`、`retention` key；
5. 非生产集群验证：写一条 trace、查 Tempo、重启后数据仍在（S3）；
6. 重建 k8s-server + platform-api。

> 升级 Tempo 时 operator 的第一遍会先更新 CRD，第二遍再重下发 collector；Tempo 自身是 StatefulSet，
> 升级会滚动重启。跨大版本前先看上游 breaking changes（S3 配置、组件参数）。

## 8. 卸载

平台不提供卸载。手工：先缩容/删除 collector 的 Tempo exporter 配置，再
`helm -n monitor uninstall platform-tempo`；PVC、`platform-tempo-objstore` Secret 与桶内数据需单独处理。
